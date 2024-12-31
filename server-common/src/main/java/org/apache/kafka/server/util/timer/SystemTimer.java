/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.server.util.timer;

import org.apache.kafka.common.utils.KafkaThread;
import org.apache.kafka.common.utils.ThreadUtils;
import org.apache.kafka.common.utils.Time;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SystemTimer implements Timer {
    public static final String SYSTEM_TIMER_THREAD_PREFIX = "executor-";

    // timeout timer
    private final ExecutorService taskExecutor;
    private final DelayQueue<TimerTaskList> delayQueue;
    private final AtomicInteger taskCounter;
    private final TimingWheel timingWheel;

    // Locks used to protect data structures while ticking
    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = readWriteLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = readWriteLock.writeLock();

    public SystemTimer(String executorName) {
        this(executorName, 1, 20, Time.SYSTEM.hiResClockMs());
    }

    public SystemTimer(
        String executorName,
        long tickMs,
        int wheelSize,
        long startMs
    ) {
        this.taskExecutor = Executors.newFixedThreadPool(1,
            runnable -> KafkaThread.nonDaemon(SYSTEM_TIMER_THREAD_PREFIX + executorName, runnable));
        this.delayQueue = new DelayQueue<>();
        this.taskCounter = new AtomicInteger(0);
        this.timingWheel = new TimingWheel(
            tickMs,
            wheelSize,
            startMs,
            taskCounter,
            delayQueue
        );
    }

    public void add(TimerTask timerTask) {
        readLock.lock();
        try {
            addTimerTaskEntry(new TimerTaskEntry(timerTask, timerTask.delayMs + Time.SYSTEM.hiResClockMs()));
        } finally {
            readLock.unlock();
        }
    }

    private void addTimerTaskEntry(TimerTaskEntry timerTaskEntry) {
        if (!timingWheel.add(timerTaskEntry)) {
            // Already expired or cancelled
            if (!timerTaskEntry.cancelled()) {
                taskExecutor.submit(timerTaskEntry.timerTask);
            }
        }
    }

    /**
     * Advances the clock if there is an expired bucket. If there isn't any expired bucket when called,
     * waits up to timeoutMs before giving up.
     * 在有到期任务的时候，时间轮才向前推进
     */
    public boolean advanceClock(long timeoutMs) throws InterruptedException {
        // 有到期的任务则取出，没有到期的任务则等待 timeoutMs
        TimerTaskList bucket = delayQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (bucket != null) {
            writeLock.lock();
            try {
                while (bucket != null) {
                    // 推动时间轮向前，每个 bucket 都有一定的时间范围 [startTime , endTime)
                    // 将时间轮的 currentTimeMs 向前推进到 bucket 的 startTime
                    timingWheel.advanceClock(bucket.getExpiration());
                    // 只要任务的过期时间在 bucket 的[startTime , endTime ) 之间的任务就要执行
                    // 所以 kafka 时间轮的精度为 1ms
                    bucket.flush(this::addTimerTaskEntry);
                    bucket = delayQueue.poll();
                }
            } finally {
                writeLock.unlock();
            }
            return true;
        } else {
            // 没有到期的任务
            return false;
        }
    }

    public int size() {
        return taskCounter.get();
    }

    @Override
    public void close() {
        ThreadUtils.shutdownExecutorServiceQuietly(taskExecutor, 5, TimeUnit.SECONDS);
    }

    // visible for testing
    boolean isTerminated() {
        return taskExecutor.isTerminated();
    }
}
