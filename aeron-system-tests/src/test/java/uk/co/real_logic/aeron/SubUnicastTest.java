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
package uk.co.real_logic.aeron;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.aeron.mediadriver.MediaDriver;
import uk.co.real_logic.aeron.util.ConductorShmBuffers;
import uk.co.real_logic.aeron.util.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.util.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.util.protocol.HeaderFlyweight;
import uk.co.real_logic.aeron.util.protocol.StatusMessageFlyweight;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static uk.co.real_logic.aeron.util.CommonConfiguration.ADMIN_DIR_NAME;
import static uk.co.real_logic.aeron.util.CommonConfiguration.DIRS_DELETE_ON_EXIT_PROP_NAME;

/**
 * Test that has a consumer and single media driver for unicast cases. Uses socket as sender/publisher endpoint.
 */
public class SubUnicastTest
{
    private static final String HOST = "localhost";
    private static final int PORT = 54321;
    private static final int SRC_PORT = 54322;
    private static final Destination DESTINATION = new Destination("udp://" + HOST + ":" + PORT);
    private static final long CHANNEL_ID = 1L;
    private static final long SESSION_ID = 2L;
    private static final long TERM_ID = 3L;
    private static final byte[] PAYLOAD = "Payload goes here!".getBytes();
    private static final byte[] NO_PAYLOAD = {};

    private final AtomicBuffer payload = new AtomicBuffer(ByteBuffer.allocate(PAYLOAD.length));

    private final InetSocketAddress rcvAddr = new InetSocketAddress(HOST, PORT);
    private final InetSocketAddress srcAddr = new InetSocketAddress(HOST, SRC_PORT);

    private Aeron consumingClient;
    private MediaDriver driver;
    private Subscriber subscriber;
    private DatagramChannel senderChannel;

    private final Queue<byte[]> receivedFrames = new ArrayDeque<>();
    private final Subscriber.DataHandler saveFrames =
        (buffer, offset, length, sessionId) ->
        {
//            System.out.println("saveFrame " + sessionId + " " + length + "@" + offset);
            final byte[] data = new byte[length];
            buffer.getBytes(offset, data);
            receivedFrames.add(data);
        };
    private final Subscriber.NewSourceEventHandler newSource =
        (channelId, sessionId) ->
        {
            System.out.println("newSource " + sessionId + " " + channelId);
        };
    private final Subscriber.InactiveSourceEventHandler inactiveSource =
        (channelId, sessionId) ->
        {
            System.out.println("inactiveSource " + sessionId + " " + channelId);
        };

    private final DataHeaderFlyweight dataHeader = new DataHeaderFlyweight();
    private final StatusMessageFlyweight statusMessage = new StatusMessageFlyweight();

    private ExecutorService executorService;

    @Before
    public void setupClientAndMediaDriver() throws Exception
    {
        System.setProperty(DIRS_DELETE_ON_EXIT_PROP_NAME, "true");

        senderChannel = DatagramChannel.open();
        senderChannel.configureBlocking(false);
        senderChannel.bind(srcAddr);

        driver = new MediaDriver();

        consumingClient = Aeron.newSingleMediaDriver(newAeronContext());

        subscriber = consumingClient.newSubscriber(new Subscriber.Context()
                .destination(DESTINATION)
                .channel(CHANNEL_ID, saveFrames)
                .newSourceEvent(newSource)
                .inactiveSourceEvent(inactiveSource));

        payload.putBytes(0, PAYLOAD);

        executorService = Executors.newFixedThreadPool(4);

        executorService.execute(driver.conductor());
        executorService.execute(driver.sender());
        executorService.execute(driver.receiver());
        executorService.execute(consumingClient.conductor());
    }

    private Aeron.Context newAeronContext()
    {
        return new Aeron.Context().conductorShmBuffers(new ConductorShmBuffers(ADMIN_DIR_NAME));
    }

    @After
    public void closeEverything() throws Exception
    {
        senderChannel.close();
        subscriber.close();
        consumingClient.close();
        driver.close();
        driver.conductor().nioSelector().selectNowWithNoProcessing();
        executorService.shutdown();
    }

    @Test
    public void shouldReceiveCorrectlyFormedSingleDataFrames() throws Exception
    {
        // let buffers get connected and media driver set things up
        Thread.sleep(100);

        // send some 0 length data frame
        sendDataFrame(0, NO_PAYLOAD);

        // sleep so we are sure some 0 length data has been sent
        Thread.sleep(100);

        final ByteBuffer buffer = ByteBuffer.allocate(StatusMessageFlyweight.HEADER_LENGTH);
        buffer.clear();
        final AtomicBuffer atomicBuffer = new AtomicBuffer(buffer);
        int smsSeen = 0;

        // should receive SM from consumer
        InetSocketAddress addr;
        while((addr = (InetSocketAddress) senderChannel.receive(buffer)) != null)
        {
            statusMessage.wrap(atomicBuffer, 0);
            assertThat(statusMessage.headerType(), is(HeaderFlyweight.HDR_TYPE_SM));
            assertThat(statusMessage.frameLength(), is(StatusMessageFlyweight.HEADER_LENGTH));
            assertThat(statusMessage.channelId(), is(CHANNEL_ID));
            assertThat(statusMessage.sessionId(), is(SESSION_ID));
            assertThat(statusMessage.termId(), is(TERM_ID));
            assertThat(buffer.position(), is(StatusMessageFlyweight.HEADER_LENGTH));
            assertThat(addr, is(rcvAddr));
            buffer.clear();
            smsSeen++;
        }

        assertThat(smsSeen, greaterThanOrEqualTo(1));

        // send single Data Frame
        sendDataFrame(0, PAYLOAD);

        // sleep to make sure that the receiver thread in the media driver has a chance to receive data
        Thread.sleep(100);

        // now receive data into app
        subscriber.read();

        // assert the received Data Frames are correct
        assertThat(receivedFrames.size(), is(1));
        assertThat(receivedFrames.remove(), is(PAYLOAD));
    }

    private void sendDataFrame(final long termOffset, final byte[] payload) throws Exception
    {
        final ByteBuffer dataBuffer = ByteBuffer.allocate(DataHeaderFlyweight.HEADER_LENGTH + payload.length);
        final AtomicBuffer dataAtomicBuffer = new AtomicBuffer(dataBuffer);
        dataHeader.wrap(dataAtomicBuffer, 0);

        dataHeader.termId(TERM_ID)
                  .channelId(CHANNEL_ID)
                  .sessionId(SESSION_ID)
                  .termOffset(termOffset)
                  .frameLength(DataHeaderFlyweight.HEADER_LENGTH + payload.length)
                  .headerType(HeaderFlyweight.HDR_TYPE_DATA)
                  .flags(DataHeaderFlyweight.BEGIN_AND_END_FLAGS)
                  .version(HeaderFlyweight.CURRENT_VERSION);

        if (payload.length > 0)
        {
            dataAtomicBuffer.putBytes(dataHeader.dataOffset(), payload);
        }

        dataBuffer.position(0);
        dataBuffer.limit(DataHeaderFlyweight.HEADER_LENGTH + payload.length);
        final int byteSent = senderChannel.send(dataBuffer, rcvAddr);

        assertThat(byteSent, is(DataHeaderFlyweight.HEADER_LENGTH + payload.length));
    }}