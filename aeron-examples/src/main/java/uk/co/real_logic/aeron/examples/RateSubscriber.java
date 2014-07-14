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
package uk.co.real_logic.aeron.examples;

import uk.co.real_logic.aeron.Aeron;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.aeron.mediadriver.MediaDriver;
import uk.co.real_logic.aeron.util.RateReporter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Example that displays current rate while receiving data
 */
public class RateSubscriber
{
    public static final String DESTINATION = "udp://localhost:40123";
    public static final int CHANNEL_ID_1 = 10;
    public static final int FRAME_COUNT_LIMIT = 10;

    public static void main(final String[] args)
    {
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final Aeron.ClientContext aeronContext = new Aeron.ClientContext().errorHandler(ExampleUtil::printError);

        try (final MediaDriver driver = ExampleUtil.createEmbeddedMediaDriver();
             final Aeron aeron = ExampleUtil.createAeron(aeronContext))
        {
            final RateReporter reporter = new RateReporter(TimeUnit.SECONDS.toNanos(1), ExampleUtil::printRate);

            final Subscription subscription1 = aeron.addSubscription(DESTINATION, CHANNEL_ID_1,
                    ExampleUtil.rateReporterHandler(reporter));

            executor.execute(() -> ExampleUtil.consumerLoop(FRAME_COUNT_LIMIT).accept(subscription1));
            executor.execute(reporter);

            // run aeron client conductor thread from here
            aeron.run();
        }
        catch (final Exception ex)
        {
            ex.printStackTrace();
        }

        executor.shutdown();
    }
}