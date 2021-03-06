/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.driver;

import org.junit.Test;
import org.mockito.InOrder;
import uk.co.real_logic.aeron.common.StaticDelayGenerator;
import uk.co.real_logic.aeron.common.TimerWheel;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.agrona.concurrent.AtomicCounter;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.LogRebuilder;
import uk.co.real_logic.aeron.common.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.common.protocol.HeaderFlyweight;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.mockito.Mockito.*;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.LogBufferDescriptor.*;
import static uk.co.real_logic.agrona.BitUtil.align;

public class LossHandlerTest
{
    private static final int TERM_BUFFER_LENGTH = TERM_MIN_LENGTH;
    private static final int POSITION_BITS_TO_SHIFT = Integer.numberOfTrailingZeros(TERM_BUFFER_LENGTH);
    private static final int MASK = TERM_BUFFER_LENGTH - 1;

    private static final byte[] DATA = new byte[36];

    static
    {
        for (int i = 0; i < DATA.length; i++)
        {
            DATA[i] = (byte)i;
        }
    }
    private static final int MESSAGE_LENGTH = DataHeaderFlyweight.HEADER_LENGTH + DATA.length;
    private static final int ALIGNED_FRAME_LENGTH = align(MESSAGE_LENGTH, FrameDescriptor.FRAME_ALIGNMENT);
    private static final int SESSION_ID = 0x5E55101D;
    private static final int STREAM_ID = 0xC400E;
    private static final int TERM_ID = 0xEE81D;
    private static final long ACTIVE_TERM_POSITION = computePosition(TERM_ID, 0, POSITION_BITS_TO_SHIFT, TERM_ID);

    private static final StaticDelayGenerator DELAY_GENERATOR = new StaticDelayGenerator(
        TimeUnit.MILLISECONDS.toNanos(20), false);

    private static final StaticDelayGenerator DELAY_GENERATOR_WITH_IMMEDIATE = new StaticDelayGenerator(
        TimeUnit.MILLISECONDS.toNanos(20), true);

    private final LogRebuilder[] rebuilders = new LogRebuilder[PARTITION_COUNT];
    private final UnsafeBuffer[] termBuffers = new UnsafeBuffer[PARTITION_COUNT];

    private final UnsafeBuffer rcvBuffer = new UnsafeBuffer(new byte[MESSAGE_LENGTH]);
    private final DataHeaderFlyweight dataHeader = new DataHeaderFlyweight();

    private final TimerWheel wheel;
    private LossHandler handler;
    private NakMessageSender nakMessageSender;
    private long currentTime = 0;
    private int activeIndex = partitionIndex(TERM_ID, TERM_ID);
    private SystemCounters mockSystemCounters = mock(SystemCounters.class);

    public LossHandlerTest()
    {
        when(mockSystemCounters.naksSent()).thenReturn(mock(AtomicCounter.class));

        for (int i = 0; i < PARTITION_COUNT; i++)
        {
            final UnsafeBuffer termBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(TERM_BUFFER_LENGTH));
            final UnsafeBuffer metaDataBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(TERM_META_DATA_LENGTH));
            rebuilders[i] = new LogRebuilder(termBuffer, metaDataBuffer);
            termBuffers[i] = termBuffer;
        }

        wheel = new TimerWheel(
            () -> currentTime,
            Configuration.CONDUCTOR_TICK_DURATION_US,
            TimeUnit.MICROSECONDS,
            Configuration.CONDUCTOR_TICKS_PER_WHEEL);

        nakMessageSender = mock(NakMessageSender.class);

        handler = new LossHandler(wheel, DELAY_GENERATOR, nakMessageSender, mockSystemCounters);
        dataHeader.wrap(rcvBuffer, 0);
    }

    @Test
    public void shouldNotSendNakWhenBufferIsEmpty()
    {
        final long completedPosition = ACTIVE_TERM_POSITION;
        final long hwmPosition = ACTIVE_TERM_POSITION;

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);

        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(100));
        verifyZeroInteractions(nakMessageSender);
    }

    @Test
    public void shouldNakMissingData()
    {
        final long completedPosition = ACTIVE_TERM_POSITION;
        final long hwmPosition = ACTIVE_TERM_POSITION + (ALIGNED_FRAME_LENGTH * 3);

        insertDataFrame(offsetOfMessage(0));
        insertDataFrame(offsetOfMessage(2));

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(40));

        verify(nakMessageSender).send(TERM_ID, offsetOfMessage(1), gapLength());
    }

    @Test
    public void shouldRetransmitNakForMissingData()
    {
        final long completedPosition = ACTIVE_TERM_POSITION;
        final long hwmPosition = ACTIVE_TERM_POSITION + (ALIGNED_FRAME_LENGTH * 3);

        insertDataFrame(offsetOfMessage(0));
        insertDataFrame(offsetOfMessage(2));

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(60));

        verify(nakMessageSender, atLeast(2)).send(TERM_ID, offsetOfMessage(1), gapLength());
    }

    @Test
    public void shouldSuppressNakOnReceivingNak()
    {
        final long completedPosition = ACTIVE_TERM_POSITION;
        final long hwmPosition = ACTIVE_TERM_POSITION + (ALIGNED_FRAME_LENGTH * 3);

        insertDataFrame(offsetOfMessage(0));
        insertDataFrame(offsetOfMessage(2));

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(20));
        handler.onNak(TERM_ID, offsetOfMessage(1));
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(20));

        verifyZeroInteractions(nakMessageSender);
    }

    @Test
    public void shouldStopNakOnReceivingData()
    {
        final long completedPosition = ACTIVE_TERM_POSITION;
        final long hwmPosition = ACTIVE_TERM_POSITION + (ALIGNED_FRAME_LENGTH * 3);

        insertDataFrame(offsetOfMessage(0));
        insertDataFrame(offsetOfMessage(2));

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(20));
        insertDataFrame(offsetOfMessage(1));
        handler.scan(termBuffers[0], hwmPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(100));

        verifyZeroInteractions(nakMessageSender);
    }

    @Test
    public void shouldHandleMoreThan2Gaps()
    {
        final long completedPosition = ACTIVE_TERM_POSITION;
        final long hwmPosition = ACTIVE_TERM_POSITION + (ALIGNED_FRAME_LENGTH * 7);

        insertDataFrame(offsetOfMessage(0));
        insertDataFrame(offsetOfMessage(2));
        insertDataFrame(offsetOfMessage(4));
        insertDataFrame(offsetOfMessage(6));

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(40));
        insertDataFrame(offsetOfMessage(1));
        handler.scan(termBuffers[0], completedPosition + (3 * ALIGNED_FRAME_LENGTH),
                     hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(80));

        final InOrder inOrder = inOrder(nakMessageSender);
        inOrder.verify(nakMessageSender, atLeast(1)).send(TERM_ID, offsetOfMessage(1), gapLength());
        inOrder.verify(nakMessageSender, atLeast(1)).send(TERM_ID, offsetOfMessage(3), gapLength());
        inOrder.verify(nakMessageSender, never()).send(TERM_ID, offsetOfMessage(5), gapLength());
    }

    @Test
    public void shouldReplaceOldNakWithNewNak()
    {
        long completedPosition = ACTIVE_TERM_POSITION;
        long hwmPosition = ACTIVE_TERM_POSITION + (ALIGNED_FRAME_LENGTH * 3);

        insertDataFrame(offsetOfMessage(0));
        insertDataFrame(offsetOfMessage(2));

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(20));

        insertDataFrame(offsetOfMessage(4));
        insertDataFrame(offsetOfMessage(1));
        completedPosition += (ALIGNED_FRAME_LENGTH * 3);
        hwmPosition = (ALIGNED_FRAME_LENGTH * 5);

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        processTimersUntil(() -> wheel.clock().time() >= TimeUnit.MILLISECONDS.toNanos(100));

        verify(nakMessageSender, atLeast(1)).send(TERM_ID, offsetOfMessage(3), gapLength());
    }

    @Test
    public void shouldHandleImmediateNak()
    {
        handler = getLossHandlerWithImmediate();

        long completedPosition = ACTIVE_TERM_POSITION;
        long hwmPosition = ACTIVE_TERM_POSITION + (ALIGNED_FRAME_LENGTH * 3);

        insertDataFrame(offsetOfMessage(0));
        insertDataFrame(offsetOfMessage(2));

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);

        verify(nakMessageSender).send(TERM_ID, offsetOfMessage(1), gapLength());
    }

    @Test
    public void shouldNotNakImmediatelyByDefault()
    {
        long completedPosition = ACTIVE_TERM_POSITION;
        long hwmPosition = ACTIVE_TERM_POSITION + (ALIGNED_FRAME_LENGTH * 3);

        insertDataFrame(offsetOfMessage(0));
        insertDataFrame(offsetOfMessage(2));

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);

        verifyZeroInteractions(nakMessageSender);
    }

    @Test
    public void shouldOnlySendNaksOnceOnMultipleScans()
    {
        handler = getLossHandlerWithImmediate();

        long completedPosition = ACTIVE_TERM_POSITION;
        long hwmPosition = ACTIVE_TERM_POSITION + (ALIGNED_FRAME_LENGTH * 3);

        insertDataFrame(offsetOfMessage(0));
        insertDataFrame(offsetOfMessage(2));

        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);
        handler.scan(termBuffers[0], completedPosition, hwmPosition, MASK, POSITION_BITS_TO_SHIFT, TERM_ID);

        verify(nakMessageSender).send(TERM_ID, offsetOfMessage(1), gapLength());
    }
/*
    @Test
    public void shouldRotateToNewTermIdCorrectlyOnNoGapsNoPadding()
    {
        // use immediate NAKs for simplicity
        handler = getLossHandlerWithImmediate();

        assertThat(handler.activeIndex(), is(activeIndex));

        // fill term buffer (making sure to be exact, i.e. no padding)
        int offset = 0, i = 0;
        while (TERM_BUFFER_LENGTH > offset)
        {
            insertDataFrame(offsetOfMessage(i++));
            offset += ALIGNED_FRAME_LENGTH;
        }

        assertThat(offset, is(TERM_BUFFER_LENGTH));  // sanity check the fill to make sure it doesn't need padding
        assertTrue(rebuilders[activeIndex].isComplete());
        assertThat(handler.scan(), is(1));

        activeIndex = nextPartitionIndex(activeIndex);
        assertThat(handler.activeIndex(), is(activeIndex));

        insertDataFrame(offsetOfMessage(0));

        assertThat(handler.scan(), is(0));
        verifyZeroInteractions(nakMessageSender);
    }

    @Test
    public void shouldRotateToNewTermIdCorrectlyOnNoGaps()
    {
        // use immediate NAKs for simplicity
        handler = getLossHandlerWithImmediate();

        assertThat(handler.activeIndex(), is(activeIndex));

        // fill term buffer except the padding frame
        int offset = 0, i = 0;
        while ((TERM_BUFFER_LENGTH - 1024) > offset)
        {
            insertDataFrame(offsetOfMessage(i++));
            offset += ALIGNED_FRAME_LENGTH;
        }
        insertPaddingFrame(offsetOfMessage(i));  // and ticks pad to end of buffer

        assertTrue(rebuilders[activeIndex].isComplete());
        assertThat(handler.scan(), is(1));

        activeIndex = nextPartitionIndex(activeIndex);
        assertThat(handler.activeIndex(), is(activeIndex));

        insertDataFrame(offsetOfMessage(0));

        assertThat(handler.scan(), is(0));
        verifyZeroInteractions(nakMessageSender);
    }

    @Test
    public void shouldRotateToNewTermIdCorrectlyOnReceivingDataAfterGap()
    {
        // use immediate NAKs for simplicity
        handler = getLossHandlerWithImmediate();

        assertThat(handler.activeIndex(), is(activeIndex));

        insertDataFrame(offsetOfMessage(0));
        // fill term buffer except the padding frame
        int offset = offsetOfMessage(2), i = 2;
        while (TERM_BUFFER_LENGTH > offset)
        {
            insertDataFrame(offsetOfMessage(i++));
            offset += ALIGNED_FRAME_LENGTH;
        }

        assertThat(offset, is(TERM_BUFFER_LENGTH));  // sanity check the fill to make sure it doesn't need padding
        assertFalse(rebuilders[activeIndex].isComplete());
        assertThat(handler.scan(), is(0));

        verify(nakMessageSender).send(TERM_ID, offsetOfMessage(1), gapLength());

        insertDataFrame(offsetOfMessage(1));

        assertTrue(rebuilders[activeIndex].isComplete());
        assertThat(handler.scan(), is(1));

        activeIndex = nextPartitionIndex(activeIndex);
        assertThat(handler.activeIndex(), is(activeIndex));

        insertDataFrame(offsetOfMessage(0));

        assertThat(handler.scan(), is(0));
        verifyNoMoreInteractions(nakMessageSender);
    }

    @Test
    public void shouldDetectGapOnPotentialHighPositionChange()
    {
        handler = getLossHandlerWithImmediate();

        assertThat(handler.activeIndex(), is(activeIndex));

        insertDataFrame(offsetOfMessage(0));

        final long highPosition = computePosition(TERM_ID, offsetOfMessage(2), POSITION_BITS_TO_SHIFT, TERM_ID);

        handler.hwmCandidate(highPosition);
        assertThat(handler.scan(), is(0));

        verify(nakMessageSender).send(TERM_ID, offsetOfMessage(1), gapLength());
    }

    @Test
    public void shouldDetectGapOnPotentialHighPositionChangeFromRollover()
    {
        handler = getLossHandlerWithImmediate();

        assertThat(handler.activeIndex(), is(activeIndex));

        // fill term buffer except the padding frame
        int offset = 0, i = 0;
        while ((TERM_BUFFER_LENGTH - 1024) > offset)
        {
            insertDataFrame(offsetOfMessage(i++));
            offset += ALIGNED_FRAME_LENGTH;
        }

        assertFalse(rebuilders[activeIndex].isComplete());
        assertThat(handler.scan(), is(0));

        activeIndex = nextPartitionIndex(activeIndex);
        assertThat(handler.activeIndex(), is(previousPartitionIndex(activeIndex)));

        insertDataFrame(offsetOfMessage(0));

        assertThat(handler.scan(), is(0));
        verifyZeroInteractions(nakMessageSender);

        final long highPosition = computePosition(TERM_ID + 1, offsetOfMessage(0), POSITION_BITS_TO_SHIFT, TERM_ID);

        handler.hwmCandidate(highPosition);
        assertThat(handler.scan(), is(0));

        verify(nakMessageSender).send(TERM_ID, offset, TERM_BUFFER_LENGTH - offset);
    }

    @Test
    public void shouldHandleNonZeroInitialTermOffset()
    {
        rebuilders[activeIndex].tail(offsetOfMessage(2));
        handler = getLossHandlerWithImmediateAndTermOffset(offsetOfMessage(2));

        insertDataFrame(offsetOfMessage(2));
        insertDataFrame(offsetOfMessage(4));

        handler.scan();

        verify(nakMessageSender).send(TERM_ID, offsetOfMessage(3), gapLength());
        verifyNoMoreInteractions(nakMessageSender);

        assertThat(handler.completedPosition(), is((long)offsetOfMessage(3)));
        assertThat(handler.hwmCandidate((long)offsetOfMessage(5)), is((long)offsetOfMessage(5)));
    }
*/
    private LossHandler getLossHandlerWithImmediate()
    {
        return new LossHandler(wheel, DELAY_GENERATOR_WITH_IMMEDIATE, nakMessageSender, mockSystemCounters);
    }

    private void insertDataFrame(final int offset)
    {
        insertDataFrame(offset, DATA);
    }

    private void insertDataFrame(final int offset, final byte[] payload)
    {
        dataHeader.termId(TERM_ID)
                  .streamId(STREAM_ID)
                  .sessionId(SESSION_ID)
                  .termOffset(offset)
                  .frameLength(payload.length + DataHeaderFlyweight.HEADER_LENGTH)
                  .headerType(HeaderFlyweight.HDR_TYPE_DATA)
                  .flags(DataHeaderFlyweight.BEGIN_AND_END_FLAGS)
                  .version(HeaderFlyweight.CURRENT_VERSION);

        dataHeader.buffer().putBytes(dataHeader.dataOffset(), payload);

        rebuilders[activeIndex].insert(offset, rcvBuffer, 0, payload.length + DataHeaderFlyweight.HEADER_LENGTH);
    }

    private int offsetOfMessage(final int index)
    {
        return index * ALIGNED_FRAME_LENGTH;
    }

    private int gapLength()
    {
        return ALIGNED_FRAME_LENGTH;
    }

    private long processTimersUntil(final BooleanSupplier condition)
    {
        final long startTime = wheel.clock().time();

        while (!condition.getAsBoolean())
        {
            if (wheel.computeDelayInMs() > 0)
            {
                currentTime += TimeUnit.MICROSECONDS.toNanos(Configuration.CONDUCTOR_TICK_DURATION_US);
            }

            wheel.expireTimers();
        }

        return wheel.clock().time() - startTime;
    }
}
