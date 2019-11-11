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
package io.netty.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.util.concurrent.BlockingOperationException;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.util.concurrent.TimeUnit;


/**
 * The result of an asynchronous {@link Channel} I/O operation.
 * ChannelFuture是netty中异步Future,作用是用来保存Channel异步操作的结果
 *
 * All I/O operations in Netty are asynchronous.  It means any I/O calls will
 * return immediately with no guarantee that the requested I/O operation has
 * been completed at the end of the call.  Instead, you will be returned with
 * a {@link ChannelFuture} instance which gives you the information about the
 * result or status of the I/O operation.
 * Netty所有的IO操作都是异步的。意味着所有IO操作请求会立刻返回，但不能保证在调用结束后，IO请求操作完成。
 * 然而，你可以返回一个异步结果实例，可以获取异步IO操作的结果或IO状态。
 *
 * <p>
 * A {@link ChannelFuture} is either <em>uncompleted</em> or <em>completed</em>.
 * When an I/O operation begins, a new future object is created.  The new future
 * is uncompleted initially - it is neither succeeded, failed, nor cancelled
 * because the I/O operation is not finished yet.  If the I/O operation is
 * finished either successfully, with failure, or by cancellation, the future is
 * marked as completed with more specific information, such as the cause of the
 * failure.  Please note that even failure and cancellation belong to the
 * completed state.
 * 当一个IO操作开始时，不管操作是否完成，一个新的异步操作结果将会被创建。
 * 如果因为IO操作没有完成（既没有成功，失败，也没有取消），新创建的异步结果future并没有完成初始化。
 * 如果IO操作完成，不论操作结果成功，失败或取消，异步结果future将会标记为完成，同时携带更多的精确信息，比如失败的原因。
 * 需要注意的是，失败或取消也属于完成状态。
 * <pre>
 *                                      +---------------------------+
 *                                      | Completed successfully    |
 *                                      +---------------------------+
 *                                 +---->      isDone() = true      |
 * +--------------------------+    |    |   isSuccess() = true      |
 * |        Uncompleted       |    |    +===========================+
 * +--------------------------+    |    | Completed with failure    |
 * |      isDone() = false    |    |    +---------------------------+
 * |   isSuccess() = false    |----+---->      isDone() = true      |
 * | isCancelled() = false    |    |    |       cause() = non-null  |
 * |       cause() = null     |    |    +===========================+
 * +--------------------------+    |    | Completed by cancellation |
 *                                 |    +---------------------------+
 *                                 +---->      isDone() = true      |
 *                                      | isCancelled() = true      |
 *                                      +---------------------------+
 * </pre>
 *
 * Various methods are provided to let you check if the I/O operation has been
 * completed, wait for the completion, and retrieve the result of the I/O
 * operation. It also allows you to add {@link ChannelFutureListener}s so you
 * can get notified when the I/O operation is completed.
 * 异步结果提供不同的方法，比如检查IO操作是否完成、等待操作完成、获取IO操作结果。
 * 你可以添加多个ChannelFuture监听器，以便可以在IO操作完成时获取通知。
 *
 * <h3>Prefer {@link #addListener(GenericFutureListener)} to {@link #await()}</h3>
 *
 * It is recommended to prefer {@link #addListener(GenericFutureListener)} to
 * {@link #await()} wherever possible to get notified when an I/O operation is
 * done and to do any follow-up tasks.
 * 强烈建议使用添加监听器的方式，而不是等待方式，等待IO操作完成，同时可以做后续处理任务。
 *
 * <p>
 * {@link #addListener(GenericFutureListener)} is non-blocking.  It simply adds
 * the specified {@link ChannelFutureListener} to the {@link ChannelFuture}, and
 * I/O thread will notify the listeners when the I/O operation associated with
 * the future is done.  {@link ChannelFutureListener} yields the best
 * performance and resource utilization because it does not block at all, but
 * it could be tricky to implement a sequential logic if you are not used to
 * event-driven programming.
 * 添加监听器是非阻塞的。仅仅简单地添加一个通道结果监听器到异步监听结果，当IO操作关联的 异步任务完成时，IO线程将会通知监听器。
 * 通道结果监听器因为是非阻塞的，所以有更好的性能和资源利用率，
 * 如果您不习惯事件驱动的编程，那么实现顺序逻辑可能会比较麻烦。
 *
 * <p>
 * By contrast, {@link #await()} is a blocking operation.  Once called, the
 * caller thread blocks until the operation is done.  It is easier to implement
 * a sequential logic with {@link #await()}, but the caller thread blocks
 * unnecessarily until the I/O operation is done and there's relatively
 * expensive cost of inter-thread notification.  Moreover, there's a chance of
 * dead lock in a particular circumstance, which is described below.
 * 相比之下，await方式是一个阻塞操作。一旦调用，调用线程将会阻塞到IO操作完成。
 * 使用await可以很容易实现一个时序的逻辑，但是调用线程不需要阻塞到IO操作完成，这种
 * 方式相对于内部线程通知，代价比较大。更进一步说，在特殊的循环下，有可能出现死锁情况，
 * 具体描述如下：
 *
 * <h3>Do not call {@link #await()} inside {@link ChannelHandler}</h3>
 * 不要在通道处理器中调用await方法
 *
 * <p>
 * The event handler methods in {@link ChannelHandler} are usually called by
 * an I/O thread.  If {@link #await()} is called by an event handler
 * method, which is called by the I/O thread, the I/O operation it is waiting
 * for might never complete because {@link #await()} can block the I/O
 * operation it is waiting for, which is a dead lock.
 * 通道处理器内的事件处理方法通常由IO线程调用。如果await方法被IO线程在调用事件处理方法的过程中调用，
 * 它正在等待的I/O操作可能永远不会完成，因为await()可以阻塞它正在等待的I/O操作，这是一个死锁。
 *
 * <pre>
 * // BAD - NEVER DO THIS 坚决不要用await方式
 * {@code @Override}
 * public void channelRead({@link ChannelHandlerContext} ctx, Object msg) {
 *     {@link ChannelFuture} future = ctx.channel().close();
 *     future.awaitUninterruptibly();
 *     // Perform post-closure operation
 *     // ...
 * }
 *
 * // GOOD
 * {@code @Override} 建议方式，添加通道结果监听器
 * public void channelRead({@link ChannelHandlerContext} ctx, Object msg) {
 *     {@link ChannelFuture} future = ctx.channel().close();
 *     future.addListener(new {@link ChannelFutureListener}() {
 *         public void operationComplete({@link ChannelFuture} future) {
 *             // Perform post-closure operation
 *             // ...
 *         }
 *     });
 * }
 * </pre>
 * <p>
 * In spite of the disadvantages mentioned above, there are certainly the cases
 * where it is more convenient to call {@link #await()}. In such a case, please
 * make sure you do not call {@link #await()} in an I/O thread.  Otherwise,
 * {@link BlockingOperationException} will be raised to prevent a dead lock.
 * 尽管await方法有诸多缺点，但在其他一些场景中，使用await方法非常便利。在这些场景中，
 * 要确保不在IO线程中调用await方法。另外，BlockingOperationException异常将会抛出以阻止死锁的产生。
 *
 * <h3>Do not confuse I/O timeout and await timeout</h3>
 * 不要混淆IO超时和await超时。
 *
 * The timeout value you specify with {@link #await(long)},
 * {@link #await(long, TimeUnit)}, {@link #awaitUninterruptibly(long)}, or
 * {@link #awaitUninterruptibly(long, TimeUnit)} are not related with I/O
 * timeout at all.  If an I/O operation times out, the future will be marked as
 * 'completed with failure,' as depicted in the diagram above.  For example,
 * connect timeout should be configured via a transport-specific option:
 * 在await*（*）方法中的超时时间与IO超时一点关系也没有。如果一个IO操作超时，异步结果
 * 将被标记为失败并完成，如果上图中的描述。比如，连接超时应该通过特定于传输的选项进行配置
 * （b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);），连接超时会设置future完成状态，await方法调用线程被唤醒。
 *
 * <pre>
 * // BAD - NEVER DO THIS 坚决不要使用这种方式
 * {@link Bootstrap} b = ...;
 * {@link ChannelFuture} f = b.connect(...);
 * f.awaitUninterruptibly(10, TimeUnit.SECONDS);
 * if (f.isCancelled()) {
 *     // Connection attempt cancelled by user
 * } else if (!f.isSuccess()) {
 *     // You might get a NullPointerException here because the future
 *     // might not be completed yet.
 *     f.cause().printStackTrace();
 * } else {
 *     // Connection established successfully
 * }
 *
 * // GOOD 建议方式
 * {@link Bootstrap} b = ...;
 * // Configure the connect timeout option.
 * <b>b.option({@link ChannelOption}.CONNECT_TIMEOUT_MILLIS, 10000);</b>
 * {@link ChannelFuture} f = b.connect(...);
 * f.awaitUninterruptibly();
 *
 * // Now we are sure the future is completed.
 * assert f.isDone();
 *
 * if (f.isCancelled()) {
 *     // Connection attempt cancelled by user
 * } else if (!f.isSuccess()) {
 *     f.cause().printStackTrace();
 * } else {
 *     // Connection established successfully
 * }
 * </pre>
 *
 * 管道任务，管道是netty的核心概念，他将每个请求都当做一个管道，用户可以使用管道进行通讯读取数据等操作，
 * 这里暂时将ChannelFuture当做一个定义，这里可以看出他继承与Future但是返回的类型是Void,
 * Void类仅仅是一个占位标志类无任何作用，暂且当做当前的Future并没有结果可以返回，再讲它实现的时候将会细讲。
 */
public interface ChannelFuture extends Future<Void> {

    /**
     * Returns a channel where the I/O operation associated with this
     * future takes place.
     * 返回一个通道，在该通道中，将执行与此future关联的I/O操作。
     */
    Channel channel();

    // 下面@Override标记的方法都是对父类的重写替换，这里可能有人会有疑问为什么父类定义返回的是Future而这里则是ChannelFuture，
    // 这是因为java在1.5后支持方法重写返回类型可以是子类。这话可能比较绕，
    // 那么就按现在的代码来看在Future中返回的是Future，那么在重写的还是允许修改为Future的子类。
    @Override
    ChannelFuture addListener(GenericFutureListener<? extends Future<? super Void>> listener);

    @Override
    ChannelFuture addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    @Override
    ChannelFuture removeListener(GenericFutureListener<? extends Future<? super Void>> listener);

    @Override
    ChannelFuture removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    @Override
    ChannelFuture sync() throws InterruptedException;

    @Override
    ChannelFuture syncUninterruptibly();

    @Override
    ChannelFuture await() throws InterruptedException;

    @Override
    ChannelFuture awaitUninterruptibly();

    /**
     * Returns {@code true} if this {@link ChannelFuture} is a void future and so not allow to call any of the
     * following methods:
     * 如果通道异步结果为void，返回ture，因此不允许调用下面的任何方法：
     * <ul>
     *     <li>{@link #addListener(GenericFutureListener)}</li>
     *     <li>{@link #addListeners(GenericFutureListener[])}</li>
     *     <li>{@link #await()}</li>
     *     <li>{@link #await(long, TimeUnit)} ()}</li>
     *     <li>{@link #await(long)} ()}</li>
     *     <li>{@link #awaitUninterruptibly()}</li>
     *     <li>{@link #sync()}</li>
     *     <li>{@link #syncUninterruptibly()}</li>
     * </ul>
     */
    // 是否为无效的Future,
    // 如果是则不允许调用:addListener、addListeners、await、awaitUninterruptibly、sync、syncUninterruptibly这些方法。具体根据类的实现而定。
    boolean isVoid();
}
