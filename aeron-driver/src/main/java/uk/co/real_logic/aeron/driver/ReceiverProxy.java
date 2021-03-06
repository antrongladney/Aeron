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

import uk.co.real_logic.agrona.concurrent.AtomicCounter;
import uk.co.real_logic.aeron.driver.cmd.*;

import java.util.Queue;

import static uk.co.real_logic.aeron.driver.ThreadingMode.SHARED;

/**
 * Proxy for offering into the Receiver Thread's command queue.
 */
public class ReceiverProxy
{
    private final ThreadingMode threadingMode;
    private final Queue<ReceiverCmd> commandQueue;
    private final AtomicCounter failCount;

    private Receiver receiver;

    public ReceiverProxy(final ThreadingMode threadingMode, final Queue<ReceiverCmd> commandQueue, final AtomicCounter failCount)
    {
        this.threadingMode = threadingMode;
        this.commandQueue = commandQueue;
        this.failCount = failCount;
    }

    public void receiver(final Receiver receiver)
    {
        this.receiver = receiver;
    }

    public void addSubscription(final ReceiveChannelEndpoint mediaEndpoint, final int streamId)
    {
        if (isShared())
        {
            receiver.onAddSubscription(mediaEndpoint, streamId);
        }
        else
        {
            offer(new AddSubscriptionCmd(mediaEndpoint, streamId));
        }
    }

    public void removeSubscription(final ReceiveChannelEndpoint mediaEndpoint, final int streamId)
    {
        if (isShared())
        {
            receiver.onRemoveSubscription(mediaEndpoint, streamId);
        }
        else
        {
            offer(new RemoveSubscriptionCmd(mediaEndpoint, streamId));
        }
    }

    public void newConnection(final ReceiveChannelEndpoint channelEndpoint, final DriverConnection connection)
    {
        if (isShared())
        {
            receiver.onNewConnection(channelEndpoint, connection);
        }
        else
        {
            offer(new NewConnectionCmd(channelEndpoint, connection));
        }
    }

    public void removeConnection(final DriverConnection connection)
    {
        if (isShared())
        {
            receiver.onRemoveConnection(connection);
        }
        else
        {
            offer(new RemoveConnectionCmd(connection));
        }
    }

    public void registerMediaEndpoint(final ReceiveChannelEndpoint channelEndpoint)
    {
        if (isShared())
        {
            receiver.onRegisterMediaChannelEndpoint(channelEndpoint);
        }
        else
        {
            offer(new RegisterReceiveChannelEndpointCmd(channelEndpoint));
        }
    }

    public void closeReceiveChannelEndpoint(final ReceiveChannelEndpoint channelEndpoint)
    {
        if (isShared())
        {
            receiver.onCloseReceiveChannelEndpoint(channelEndpoint);
        }
        else
        {
            offer(new CloseReceiveChannelEndpointCmd(channelEndpoint));
        }
    }

    public void removePendingSetup(final ReceiveChannelEndpoint channelEndpoint, final int sessionId, final int streamId)
    {
        if (isShared())
        {
            receiver.onRemovePendingSetup(channelEndpoint, sessionId, streamId);
        }
        else
        {
            offer(new RemovePendingSetupCmd(channelEndpoint, sessionId, streamId));
        }
    }

    public void closeSubscription(final DriverSubscription subscription)
    {
        if (isShared())
        {
            receiver.onCloseSubscription(subscription);
        }
        else
        {
            offer(new CloseSubscriptionCmd(subscription));
        }
    }

    private boolean isShared()
    {
        return threadingMode == SHARED;
    }

    private void offer(final ReceiverCmd cmd)
    {
        while (!commandQueue.offer(cmd))
        {
            failCount.orderedIncrement();
            Thread.yield();
        }
    }
}
