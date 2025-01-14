/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.MutableInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.Header;

public class ClusterNodeTest
{
    private static final long MAX_CATALOG_ENTRIES = 1024;

    private ClusteredMediaDriver clusteredMediaDriver;
    private ClusteredServiceContainer container;
    private AeronCluster aeronCluster;

    @Before
    public void before()
    {
        clusteredMediaDriver = ClusteredMediaDriver.launch(
            new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .errorHandler(TestUtil.errorHandler(0))
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .deleteArchiveOnStart(true),
            new ConsensusModule.Context()
                .errorHandler(TestUtil.errorHandler(0))
                .terminationHook(TestUtil.TERMINATION_HOOK)
                .deleteDirOnStart(true));
    }

    @After
    public void after()
    {
        CloseHelper.close(aeronCluster);
        CloseHelper.close(container);
        CloseHelper.close(clusteredMediaDriver);

        if (null != clusteredMediaDriver)
        {
            clusteredMediaDriver.consensusModule().context().deleteDirectory();
            clusteredMediaDriver.archive().context().deleteArchiveDirectory();
            clusteredMediaDriver.mediaDriver().context().deleteAeronDirectory();
        }
    }

    @Test
    public void shouldConnectAndSendKeepAlive()
    {
        container = launchEchoService();
        aeronCluster = connectToCluster(null);

        assertTrue(aeronCluster.sendKeepAlive());
    }

    @Test(timeout = 10_000)
    public void shouldEchoMessageViaServiceUsingDirectOffer()
    {
        final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
        final String msg = "Hello World!";
        msgBuffer.putStringWithoutLengthAscii(0, msg);

        final MutableInteger messageCount = new MutableInteger();

        final EgressListener listener = (clusterSessionId, timestamp, buffer, offset, length, header) ->
        {
            assertThat(buffer.getStringWithoutLengthAscii(offset, length), is(msg));
            messageCount.value += 1;
        };

        container = launchEchoService();
        aeronCluster = connectToCluster(listener);

        while (aeronCluster.offer(msgBuffer, 0, msg.length()) < 0)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
        }

        while (messageCount.get() == 0)
        {
            if (aeronCluster.pollEgress() <= 0)
            {
                TestUtil.checkInterruptedStatus();
                Thread.yield();
            }
        }
    }

    @Test(timeout = 10_000)
    public void shouldEchoMessageViaServiceUsingTryClaim()
    {
        final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
        final String msg = "Hello World!";
        msgBuffer.putStringWithoutLengthAscii(0, msg);

        final MutableInteger messageCount = new MutableInteger();

        final EgressListener listener = (clusterSessionId, timestamp, buffer, offset, length, header) ->
        {
            assertThat(buffer.getStringWithoutLengthAscii(offset, length), is(msg));
            messageCount.value += 1;
        };

        container = launchEchoService();
        aeronCluster = connectToCluster(listener);

        final BufferClaim bufferClaim = new BufferClaim();
        long publicationResult;
        do
        {
            publicationResult = aeronCluster.tryClaim(msg.length(), bufferClaim);
            if (publicationResult > 0)
            {
                final int offset = bufferClaim.offset() + AeronCluster.SESSION_HEADER_LENGTH;
                bufferClaim.buffer().putBytes(offset, msgBuffer, 0, msg.length());
                bufferClaim.commit();
            }
        }
        while (publicationResult < 0);

        while (aeronCluster.offer(msgBuffer, 0, msg.length()) < 0)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
        }

        while (messageCount.get() == 0)
        {
            if (aeronCluster.pollEgress() <= 0)
            {
                TestUtil.checkInterruptedStatus();
                Thread.yield();
            }
        }
    }

    @Test(timeout = 10_000)
    public void shouldScheduleEventInService()
    {
        final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
        final String msg = "Hello World!";
        msgBuffer.putStringWithoutLengthAscii(0, msg);

        final MutableInteger messageCount = new MutableInteger();

        final EgressListener listener = (clusterSessionId, timestamp, buffer, offset, length, header) ->
        {
            assertThat(buffer.getStringWithoutLengthAscii(offset, length), is(msg + "-scheduled"));
            messageCount.value += 1;
        };

        container = launchTimedService();
        aeronCluster = connectToCluster(listener);

        while (aeronCluster.offer(msgBuffer, 0, msg.length()) < 0)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
        }

        while (messageCount.get() == 0)
        {
            if (aeronCluster.pollEgress() <= 0)
            {
                TestUtil.checkInterruptedStatus();
                Thread.yield();
            }
        }
    }

    @Test(timeout = 10_000)
    public void shouldSendResponseAfterServiceMessage()
    {
        final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
        final String msg = "Hello World!";
        msgBuffer.putStringWithoutLengthAscii(0, msg);

        final MutableInteger messageCount = new MutableInteger();

        final EgressListener listener = (clusterSessionId, timestamp, buffer, offset, length, header) ->
        {
            assertThat(buffer.getStringWithoutLengthAscii(offset, length), is(msg));
            messageCount.value += 1;
        };

        container = launchServiceMessageIngressService();
        aeronCluster = connectToCluster(listener);

        while (aeronCluster.offer(msgBuffer, 0, msg.length()) < 0)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
        }

        while (messageCount.get() == 0)
        {
            if (aeronCluster.pollEgress() <= 0)
            {
                TestUtil.checkInterruptedStatus();
                Thread.yield();
            }
        }
    }

    private ClusteredServiceContainer launchEchoService()
    {
        final ClusteredService clusteredService = new StubClusteredService()
        {
            public void onSessionMessage(
                final ClientSession session,
                final long timestamp,
                final DirectBuffer buffer,
                final int offset,
                final int length,
                final Header header)
            {
                while (session.offer(buffer, offset, length) < 0)
                {
                    cluster.idle();
                }
            }
        };

        return ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .clusteredService(clusteredService)
                .errorHandler(Throwable::printStackTrace));
    }

    private ClusteredServiceContainer launchTimedService()
    {
        final ClusteredService clusteredService = new StubClusteredService()
        {
            private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();
            private long clusterSessionId;
            private int nextCorrelationId;
            private String msg;

            public void onSessionMessage(
                final ClientSession session,
                final long timestamp,
                final DirectBuffer buffer,
                final int offset,
                final int length,
                @SuppressWarnings("unused") final Header header)
            {
                clusterSessionId = session.id();
                msg = buffer.getStringWithoutLengthAscii(offset, length);

                while (!cluster.scheduleTimer(serviceCorrelationId(nextCorrelationId++), timestamp + 100))
                {
                    cluster.idle();
                }
            }

            public void onTimerEvent(final long correlationId, final long timestamp)
            {
                final String responseMsg = msg + "-scheduled";
                buffer.putStringWithoutLengthAscii(0, responseMsg);
                final ClientSession clientSession = cluster.getClientSession(clusterSessionId);

                while (clientSession.offer(buffer, 0, responseMsg.length()) < 0)
                {
                    cluster.idle();
                }
            }
        };

        return ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .clusteredService(clusteredService)
                .terminationHook(TestUtil.TERMINATION_HOOK)
                .errorHandler(TestUtil.errorHandler(0)));
    }

    private ClusteredServiceContainer launchServiceMessageIngressService()
    {
        final ClusteredService clusteredService = new StubClusteredService()
        {
            public void onSessionMessage(
                final ClientSession session,
                final long timestamp,
                final DirectBuffer buffer,
                final int offset,
                final int length,
                @SuppressWarnings("unused") final Header header)
            {
                if (null != session)
                {
                    while (cluster.offer(buffer, offset, length) < 0)
                    {
                        cluster.idle();
                    }
                }
                else
                {
                    for (final ClientSession clientSession : cluster.clientSessions())
                    {
                        while (clientSession.offer(buffer, offset, length) < 0)
                        {
                            cluster.idle();
                        }
                    }
                }
            }
        };

        return ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .clusteredService(clusteredService)
                .terminationHook(TestUtil.TERMINATION_HOOK)
                .errorHandler(TestUtil.errorHandler(0)));
    }

    private AeronCluster connectToCluster(final EgressListener egressListener)
    {
        return AeronCluster.connect(
            new AeronCluster.Context()
                .egressListener(egressListener)
                .ingressChannel("aeron:udp")
                .clusterMemberEndpoints("0=localhost:9010,1=localhost:9011,2=localhost:9012"));
    }
}