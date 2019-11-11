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

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;


/**
 * Listens to the result of a {@link ChannelFuture}.  The result of the
 * asynchronous {@link Channel} I/O operation is notified once this listener
 * is added by calling {@link ChannelFuture#addListener(GenericFutureListener)}.
 * 通道结果监听器ChannelFutureListener，监听通道任务的结果。一旦监听器被添加到通道任务中，
 * 当通道的异步IO操作完成时，将会通知监听器。
 *
 * <h3>Return the control to the caller quickly 快速地将控制权交给调用者</h3>
 *
 * {@link #operationComplete(Future)} is directly called by an I/O
 * thread.  Therefore, performing a time consuming task or a blocking operation
 * in the handler method can cause an unexpected pause during I/O.  If you need
 * to perform a blocking operation on I/O completion, try to execute the
 * operation in a different thread using a thread pool.
 *
 * operationComplete直接通过IO线程调用。因此在IO操作过程中，operationComplete处理方法执行一个耗时的任务或者阻塞操作，可能引起一个不期望的异常抛出。
 * 如果你需要在在I/O完成时operationComplete执行阻塞操作，尝试使用线程池在不同的线程中执行该耗时操作。
 */
public interface ChannelFutureListener extends GenericFutureListener<ChannelFuture> {

    /**
     * A {@link ChannelFutureListener} that closes the {@link Channel} which is
     * associated with the specified {@link ChannelFuture}.
     * 一个ChannelFutureListener，在IO操作完成时它关闭与指定的ChannelFuture关联的通道。
     */
    ChannelFutureListener CLOSE = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) {
            future.channel().close();
        }
    };

    /**
     * A {@link ChannelFutureListener} that closes the {@link Channel} when the
     * operation ended up with a failure or cancellation rather than a success.
     * 当IO操作失败时，关闭与指定的ChannelFuture关联的通道。
     */
    ChannelFutureListener CLOSE_ON_FAILURE = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) {
            if (!future.isSuccess()) {
                future.channel().close();
            }
        }
    };

    /**
     * A {@link ChannelFutureListener} that forwards the {@link Throwable} of the {@link ChannelFuture} into the
     * {@link ChannelPipeline}. This mimics the old behavior of Netty 3.
     * 转发通道任务异常到Channel管道。默认Netty3的行为。
     */
    ChannelFutureListener FIRE_EXCEPTION_ON_FAILURE = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) {
            if (!future.isSuccess()) {
                future.channel().pipeline().fireExceptionCaught(future.cause());
            }
        }
    };

    // Just a type alias
}
