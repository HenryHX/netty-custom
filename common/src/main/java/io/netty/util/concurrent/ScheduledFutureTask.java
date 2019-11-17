/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.concurrent;

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/**
 * 延迟任务管理，不仅支持延迟执行也可以根据周期一直运行。
 */
@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
    // 任务的创建时间，创建了当前任务则代表已经开始，因为如果是延迟任务那么就要从创建开始进行计时。
    private static final long START_TIME = System.nanoTime();

    /**
     * 获取当前时间减去创建时间的时间差
     */
    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    // 获取最后一次的执行时间，此方法一般用于循环任务
    static long deadlineNanos(long delay) {
        // 使用当前时间减去任务开始时间并且加上周期，不管怎么算都会是下一次执行时间和任务创建时间的间隔
        // 这里稍微有点绕，此处并不是使用具体的时间进行比较的而是使用时间段进行比较的，
        // 比如开始时间是00:00:00，而当前时间是00:00:01，他们的时间段就是1s，delay如果是2s，
        // 则下一次执行的时间应该是1+2=3s
        long deadlineNanos = nanoTime() + delay;
        // Guard against overflow
        // 这里防止计算错误导致程序错误所以做了对应的处理
        return deadlineNanos < 0 ? Long.MAX_VALUE : deadlineNanos;
    }

    static long initialNanoTime() {
        return START_TIME;
    }

    // set once when added to priority queue
    // 添加到优先队列时设置一次
    private long id;

    // 记录任务执行的周期叠加数，之前介绍了他的计算是计算的时间段而每个时间段执行都需要叠加上周期这样才能保证执行时间的准确
    // 这里提一下可能有人会发现START_TIME是static描述的，不管是哪个对象来使用都会是一样开始时间，
    // 而为了保证执行的准确性，再添加任务的时候会已过去的周期叠加到此字段，就是调用了deadlineNanos方法，
    // 这里提到可能会有些抽象，后面使用的时候自然就会清楚。
    private long deadlineNanos;

    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    // 周期时长，这里需要注意这个周期有三个状态
    // 等于0，不会循环执行
    // 小于0，则使用scheduleWithFixedDelay方法的算法，下一次执行时间是上次执行结束的时间加周期
    // 大于0，则使用scheduleAtFixedRate方法的算法，下一次执行时间是上一次执行时间加周期
    private final long periodNanos;

    // 存储当前PriorityQueueNode在队列中的那个下标
    private int queueIndex = INDEX_NOT_IN_QUEUE;

    /**
     * 此方法没有传入周期，执行一次即可结束。
     * @param nanoTime 执行开始时间，而这个时间的算法就是{@link ScheduledFutureTask#deadlineNanos}方法的调用
     */
    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Runnable runnable, long nanoTime) {

        super(executor, runnable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    /**
     * 此处只支持period大于0或者小于0，如果等于0则会抛出异常
     * 而period就是对periodNanos的赋值之前讲述过他的差异
     */
    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Runnable runnable, long nanoTime, long period) {

        super(executor, runnable);
        deadlineNanos = nanoTime;
        periodNanos = validatePeriod(period);
    }

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = validatePeriod(period);
    }

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    private static long validatePeriod(long period) {
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        return period;
    }

    ScheduledFutureTask<V> setId(long id) {
        this.id = id;
        return this;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * 获取当前时间还有多久到下一次执行时间
     * 如果当前时间已经超过下一次执行时间则返回0
     */
    public long delayNanos() {
        return deadlineToDelayNanos(deadlineNanos());
    }

    static long deadlineToDelayNanos(long deadlineNanos) {
        return Math.max(0, deadlineNanos - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    /**
     * 返回当前这个对象离执行的延迟值, 即还要过多长时间就要执行了(所谓定时任务).
     * <p>
     * 通过调用delayNanos()方法得到时间结果,然后转换为指定的时间单位.
     * 而delayNanos()方法是通过计算deadlineNanos()和nanoTime()的差值来计算delay时间的.
     */
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            // 如果两者下一个周期时间相等则代表d是0，则判断当前的id是否小于传入的id，如果小则代表当前任务优先于传入的任务则返回-1
            return -1;
        } else {
            assert id != that.id;
            return 1;
        }
    }

    @Override
    public void run() {
        // 如果当前线程不是传入的执行器线程，则会抛出断言异常，当然如果运行时没有开启断言关键字那么此代码无效
        assert executor().inEventLoop();
        try {
            // 检查是否周期为0，如果是0则只执行一次，不进行循环
            if (periodNanos == 0) {
                if (setUncancellableInternal()) {
                    V result = runTask();
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                // 检查当前的任务是否被取消了
                if (!isCancelled()) {
                    runTask();
                    if (!executor().isShutdown()) {
                        if (periodNanos > 0) {
                            deadlineNanos += periodNanos;
                        } else {
                            deadlineNanos = nanoTime() - periodNanos;
                        }
                        if (!isCancelled()) {
                            // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                            // 获取任务队列并且将当前的任务在丢进去，因为已经计算完下一次执行的时间了，
                            // 所以当前任务已经是一个新的任务，最起码执行时间改变了
                            Queue<ScheduledFutureTask<?>> scheduledTaskQueue =
                                    ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                            assert scheduledTaskQueue != null;
                            // 执行的时候已经从queue中poll，所以需要重新add以实现周期任务
                            scheduledTaskQueue.add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            setFailureInternal(cause);
        }
    }

    /**
     * 取消当前任务，需要从任务队列中移除当前任务
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        if (canceled) {
            ((AbstractScheduledEventExecutor) executor()).removeScheduled(this);
        }
        return canceled;
    }

    /**
     * 取消当前任务，不需要从任务队列中移除当前任务
     */
    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex;
    }

    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        queueIndex = i;
    }
}
