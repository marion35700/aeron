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

import org.agrona.DeadlineTimerWheel;
import org.agrona.collections.Long2LongHashMap;

import java.util.concurrent.TimeUnit;

class TimerService implements DeadlineTimerWheel.TimerHandler
{
    private static final int POLL_LIMIT = 20;

    private final ConsensusModuleAgent consensusModuleAgent;
    private final DeadlineTimerWheel timerWheel;
    private final Long2LongHashMap timerIdByCorrelationIdMap = new Long2LongHashMap(Long.MAX_VALUE);
    private final Long2LongHashMap correlationIdByTimerIdMap = new Long2LongHashMap(Long.MAX_VALUE);

    TimerService(
        final ConsensusModuleAgent consensusModuleAgent,
        final TimeUnit timeUnit,
        final long startTime,
        final long tickResolution,
        final int ticksPerWheel)
    {
        this.consensusModuleAgent = consensusModuleAgent;
        timerWheel = new DeadlineTimerWheel(timeUnit, startTime, tickResolution, ticksPerWheel);
    }

    int poll(final long now)
    {
        int expired = 0;

        do
        {
            expired += timerWheel.poll(now, this, POLL_LIMIT);
        }
        while (expired < POLL_LIMIT && timerWheel.currentTickTime() < now);

        return expired;
    }

    long currentTickTime()
    {
        return timerWheel.currentTickTime();
    }

    void currentTickTime(final long timestamp)
    {
        timerWheel.currentTickTime(timestamp);
    }

    public boolean onTimerExpiry(final TimeUnit timeUnit, final long now, final long timerId)
    {
        final long correlationId = correlationIdByTimerIdMap.get(timerId);

        if (consensusModuleAgent.onTimerEvent(correlationId))
        {
            correlationIdByTimerIdMap.remove(timerId);
            timerIdByCorrelationIdMap.remove(correlationId);

            return true;
        }

        return false;
    }

    void scheduleTimer(final long correlationId, final long deadline)
    {
        cancelTimer(correlationId);

        final long timerId = timerWheel.scheduleTimer(deadline);
        timerIdByCorrelationIdMap.put(correlationId, timerId);
        correlationIdByTimerIdMap.put(timerId, correlationId);
    }

    boolean cancelTimer(final long correlationId)
    {
        final long timerId = timerIdByCorrelationIdMap.remove(correlationId);
        if (Long.MAX_VALUE != timerId)
        {
            timerWheel.cancelTimer(timerId);
            correlationIdByTimerIdMap.remove(timerId);

            return true;
        }

        return false;
    }

    void snapshot(final ConsensusModuleSnapshotTaker snapshotTaker)
    {
        final Long2LongHashMap.EntryIterator iter = timerIdByCorrelationIdMap.entrySet().iterator();

        while (iter.hasNext())
        {
            iter.next();

            final long correlationId = iter.getLongKey();
            final long deadline = timerWheel.deadline(iter.getLongValue());

            snapshotTaker.snapshotTimer(correlationId, deadline);
        }
    }
}
