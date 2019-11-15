/*
 * Copyright 2012 The Netty Project
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

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The {@link EventExecutorGroup} is responsible for providing the {@link EventExecutor}'s to use
 * via its {@link #next()} method. Besides this, it is also responsible for handling their
 * life-cycle and allows shutting them down in a global fashion.
 *
 * <p>{@link EventExecutorGroup}负责通过其{@link #next()}方法提供{@link EventExecutor}的使用。
 * 除此之外，它还负责处理它们的生命周期，并允许在全球范围内关闭它们。</p>
 *
 * <p>{@link EventExecutorGroup}和{@link EventExecutor}的区别在于，
 * 前者管理后者，当调用前者的相关方法，其实先通过{@link #next()}方法选择一个后者，然后执行后者同样的方法，
 * 本质上讲前者就是一个对后者的调度</p>
 *
 */
public interface EventExecutorGroup extends ScheduledExecutorService, Iterable<EventExecutor> {

    /**
     * Returns {@code true} if and only if all {@link EventExecutor}s managed by this {@link EventExecutorGroup}
     * are being {@linkplain #shutdownGracefully() shut down gracefully} or was {@linkplain #isShutdown() shut down}.
     *
     * <p>当且仅当由这个{@link EventExecutorGroup}管理的所有{@link EventExecutor}正在shutdownGracefully或者已经是shutdown状态，返回{@code true}。</p>
     */
    boolean isShuttingDown();

    /**
     * Shortcut method for {@link #shutdownGracefully(long, long, TimeUnit)} with sensible default values.
     * 使用合理的默认值调用shutdownGracefully
     * @return the {@link #terminationFuture()}
     */
    Future<?> shutdownGracefully();

    /**
     * Signals this executor that the caller wants the executor to be shut down.  Once this method is called,
     * {@link #isShuttingDown()} starts to return {@code true}, and the executor prepares to shut itself down.
     * Unlike {@link #shutdown()}, graceful shutdown ensures that no tasks are submitted for <i>'the quiet period'</i>
     * (usually a couple seconds) before it shuts itself down.  If a task is submitted during the quiet period,
     * it is guaranteed to be accepted and the quiet period will start over.
     *
     * <p>向该executor发出信号，表明调用者希望关闭该executor。调用此方法后，{@link #isShuttingDown()}开始返回{@code true}，executor准备关闭自身。
     * 与{@link #shutdown()}不同，优雅的关闭自己之前需要确保在<i>'the quiet period'</i>(通常是几秒钟)内不会提交任何任务。
     * 如果一个任务是在安静期提交的，它将被保证被接受，安静期将重新开始计算。</p>
     *
     * @param quietPeriod the quiet period as described in the documentation
     * @param timeout     the maximum amount of time to wait until the executor is {@linkplain #shutdown()}
     *                    regardless if a task was submitted during the quiet period
     *                    <p>等待executor{@linkplain #shutdown()}的最大时间是，不管是否在安静期间提交了任务</p>
     * @param unit        the unit of {@code quietPeriod} and {@code timeout}
     *
     * @return the {@link #terminationFuture()}
     */
    Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit);

    /**
     * Returns the {@link Future} which is notified when all {@link EventExecutor}s managed by this
     * {@link EventExecutorGroup} have been terminated.
     * <p>当由这个{@link EventExecutorGroup}管理的所有{@link EventExecutor}被终止时，该{@link Future}将收到通知。</p>
     */
    Future<?> terminationFuture();

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    void shutdown();

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    List<Runnable> shutdownNow();

    /**
     * Returns one of the {@link EventExecutor}s managed by this {@link EventExecutorGroup}.
     * <p>返回由这个{@link EventExecutorGroup}管理的{@link EventExecutor}中的一个。</p>
     */
    EventExecutor next();

    @Override
    Iterator<EventExecutor> iterator();

    @Override
    Future<?> submit(Runnable task);

    @Override
    <T> Future<T> submit(Runnable task, T result);

    @Override
    <T> Future<T> submit(Callable<T> task);

    @Override
    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    @Override
    <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    @Override
    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}
