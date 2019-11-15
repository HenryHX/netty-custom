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

/**
 * The {@link EventExecutor} is a special {@link EventExecutorGroup} which comes
 * with some handy methods to see if a {@link Thread} is executed in a event loop.
 * Besides this, it also extends the {@link EventExecutorGroup} to allow for a generic
 * way to access methods.
 *
 * <p>{@link EventExecutor}是一个特殊的{@link EventExecutorGroup}，
 * 它提供了一些方便的方法来查看{@link Thread}是否在event loop中执行。
 * 除此之外，它还扩展了{@link EventExecutorGroup}，允许以通用方式访问方法。</p>
 *
 */
public interface EventExecutor extends EventExecutorGroup {

    /**
     * Returns a reference to itself.
     * <p>EventExecutor中, 这里应该不会继续再管理其他EventExecutor了, 所以next()方法被覆盖为仅仅返回当前EventExecutor本身的引用</p>
     */
    @Override
    EventExecutor next();

    /**
     * Return the {@link EventExecutorGroup} which is the parent of this {@link EventExecutor},
     * <p>新增加的方法,返回当前EventExecutor所属的EventExecutorGroup</p>
     */
    EventExecutorGroup parent();

    /**
     * Calls {@link #inEventLoop(Thread)} with {@link Thread#currentThread()} as argument
     * <p>查询当前调用线程是否在EventExecutor所管理的线程中</p>
     */
    boolean inEventLoop();

    /**
     * Return {@code true} if the given {@link Thread} is executed in the event loop,
     * {@code false} otherwise.
     * <p>查询某个线程是否在EventExecutor所管理的线程中，在返回true，不在返回false</p>
     */
    boolean inEventLoop(Thread thread);

    /**
     * Return a new {@link Promise}.
     */
    <V> Promise<V> newPromise();

    /**
     * Create a new {@link ProgressivePromise}.
     */
    <V> ProgressivePromise<V> newProgressivePromise();

    /**
     * Create a new {@link Future} which is marked as succeeded already. So {@link Future#isSuccess()}
     * will return {@code true}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    <V> Future<V> newSucceededFuture(V result);

    /**
     * Create a new {@link Future} which is marked as failed already. So {@link Future#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    <V> Future<V> newFailedFuture(Throwable cause);
}
