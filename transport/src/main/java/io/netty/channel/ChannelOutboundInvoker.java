/*
 * Copyright 2016 The Netty Project
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

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FutureListener;

import java.net.ConnectException;
import java.net.SocketAddress;

public interface ChannelOutboundInvoker {

    /**
     * Request to bind to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#bind(ChannelHandlerContext, SocketAddress, ChannelPromise)} method
     * called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求绑定到给定的{@link SocketAddress}，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 这将导致{@link Channel}的{@link ChannelPipeline}中的下一个
     * {@link ChannelOutboundHandler}的{@link ChannelOutboundHandler#bind(ChannelHandlerContext, SocketAddress, ChannelPromise)}方法被调用。
     */
    ChannelFuture bind(SocketAddress localAddress);

    /**
     * Request to connect to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     * <p>
     * If the connection fails because of a connection timeout, the {@link ChannelFuture} will get failed with
     * a {@link ConnectTimeoutException}. If it fails because of connection refused a {@link ConnectException}
     * will be used.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求连接到给定的{@link SocketAddress}，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 如果连接超时导致连接失败，{@link ChannelFuture}将以{@link ConnectTimeoutException}失败。如果因为连接被拒绝而失败，
     * 将使用{@link ConnectException}。这将导致{@link Channel}的{@link ChannelPipeline}中的下一个
     * {@link ChannelOutboundHandler}的
     * {{@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}方法被调用。
     */
    ChannelFuture connect(SocketAddress remoteAddress);

    /**
     * Request to connect to the given {@link SocketAddress} while bind to the localAddress and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求连接到给定的{@link SocketAddress}，同时绑定到localAddress，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 这将导致{@link Channel}的{@link ChannelPipeline}中的下一个{@link ChannelOutboundHandler}的
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}方法被调用。
     */
    ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress);

    /**
     * Request to disconnect from the remote peer and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of an error.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#disconnect(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求断开与远程对等点的连接，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 这将导致{@link Channel}的{@link ChannelPipeline}中的下一个{@link ChannelOutboundHandler}的
     * {@link ChannelOutboundHandler#disconnect(ChannelHandlerContext, ChannelPromise)}方法被调用。
     */
    ChannelFuture disconnect();

    /**
     * Request to close the {@link Channel} and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of
     * an error.
     *
     * After it is closed it is not possible to reuse it again.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#close(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求关闭{@link Channel}，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 关闭后，就不可能再重用它了。这将导致{@link Channel}的{@link ChannelPipeline}中的下一个{@link ChannelOutboundHandler}的
     * {@link ChannelOutboundHandler#close(ChannelHandlerContext, ChannelPromise)}方法被调用。
     */
    ChannelFuture close();

    /**
     * Request to deregister from the previous assigned {@link EventExecutor} and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#deregister(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     *
     * <p></p>
     * 请求从之前分配的{@link EventExecutor}取消注册，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 这将导致{@link Channel}的{@link ChannelPipeline}中的下一个{@link ChannelOutboundHandler}的
     * {@link ChannelOutboundHandler#deregister(ChannelHandlerContext, ChannelPromise)}方法被调用。
     */
    ChannelFuture deregister();

    /**
     * Request to bind to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     *
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#bind(ChannelHandlerContext, SocketAddress, ChannelPromise)} method
     * called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求绑定到给定的{@link SocketAddress}，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 将通知给定的{@link ChannelPromise}。这将导致{@link Channel}的{@link ChannelPipeline}中的下一个{@link ChannelOutboundHandler}的
     * {@link ChannelOutboundHandler#bind(ChannelHandlerContext, SocketAddress, ChannelPromise)} 方法被调用。
     */
    ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise);

    /**
     * Request to connect to the given {@link SocketAddress} and notify the {@link ChannelFuture} once the operation
     * completes, either because the operation was successful or because of an error.
     *
     * The given {@link ChannelFuture} will be notified.
     *
     * <p>
     * If the connection fails because of a connection timeout, the {@link ChannelFuture} will get failed with
     * a {@link ConnectTimeoutException}. If it fails because of connection refused a {@link ConnectException}
     * will be used.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求连接到给定的{@link SocketAddress}，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 给定的{@link ChannelFuture}将被通知。如果连接超时导致连接失败，{@link ChannelFuture}将以{@link ConnectTimeoutException}失败。
     * 如果因为连接被拒绝而失败，将使用{@link ConnectException}。这将导致{@link Channel}的{@link ChannelPipeline}中的下一个
     * {@link ChannelOutboundHandler}的{@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)} 方法被调用。
     */
    ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise);

    /**
     * Request to connect to the given {@link SocketAddress} while bind to the localAddress and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     *
     * The given {@link ChannelPromise} will be notified and also returned.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求连接到给定的{@link SocketAddress}，同时绑定到localAddress，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 给定的{@link ChannelPromise}将被通知并返回。这将导致{@link Channel}的{@link ChannelPipeline}中的下一个{@link ChannelOutboundHandler}的
     * {@link ChannelOutboundHandler#connect(ChannelHandlerContext, SocketAddress, SocketAddress, ChannelPromise)} 方法被调用。
     */
    ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise);

    /**
     * Request to disconnect from the remote peer and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of an error.
     *
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#disconnect(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求断开与远程对等点的连接，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 将通知给定的{@link ChannelPromise}。这将导致{@link Channel}的{@link ChannelPipeline}中的下一个{@link ChannelOutboundHandler}的
     * {@link ChannelOutboundHandler#disconnect(ChannelHandlerContext, ChannelPromise)}方法被调用。
     */
    ChannelFuture disconnect(ChannelPromise promise);

    /**
     * Request to close the {@link Channel} and notify the {@link ChannelFuture} once the operation completes,
     * either because the operation was successful or because of
     * an error.
     *
     * After it is closed it is not possible to reuse it again.
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#close(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求关闭{@link Channel}，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 关闭后，就不可能再重用它了。将通知给定的{@link ChannelPromise}。这将导致{@link Channel}的{@link ChannelPipeline}中的下一个
     * {@link ChannelOutboundHandler}的{@link ChannelOutboundHandler#close(ChannelHandlerContext, ChannelPromise)}方法被调用。
     */
    ChannelFuture close(ChannelPromise promise);

    /**
     * Request to deregister from the previous assigned {@link EventExecutor} and notify the
     * {@link ChannelFuture} once the operation completes, either because the operation was successful or because of
     * an error.
     *
     * The given {@link ChannelPromise} will be notified.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#deregister(ChannelHandlerContext, ChannelPromise)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     *
     * 请求从之前分配的{@link EventExecutor}取消注册，并在操作完成后通知{@link ChannelFuture}，原因可能是操作成功，也可能是错误。
     * 将通知给定的{@link ChannelPromise}。这将导致{@link Channel}的{@link ChannelPipeline}中的下一个{@link ChannelOutboundHandler}的
     * {{@link ChannelOutboundHandler#deregister(ChannelHandlerContext, ChannelPromise)}方法被调用。
     */
    ChannelFuture deregister(ChannelPromise promise);

    /**
     * Request to Read data from the {@link Channel} into the first inbound buffer, triggers an
     * {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)} event if data was
     * read, and triggers a
     * {@link ChannelInboundHandler#channelReadComplete(ChannelHandlerContext) channelReadComplete} event so the
     * handler can decide to continue reading.  If there's a pending read operation already, this method does nothing.
     * <p>
     * This will result in having the
     * {@link ChannelOutboundHandler#read(ChannelHandlerContext)}
     * method called of the next {@link ChannelOutboundHandler} contained in the {@link ChannelPipeline} of the
     * {@link Channel}.
     * <p></p>
     * 请求从{@link Channel}读取数据到第一个入站缓冲区，如果读取数据，则触发{@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object)} 事件，
     * 并触发{@link ChannelInboundHandler#channelReadComplete(ChannelHandlerContext) channelReadComplete}事件，以便处理程序可以决定继续读取。
     * 如果已经有一个挂起的读操作，这个方法什么也不做。这将导致{@link Channel}的{@link ChannelPipeline}中的下一个{@link ChannelOutboundHandler}的
     * {@link ChannelOutboundHandler#read(ChannelHandlerContext)}方法被调用。
     */
    ChannelOutboundInvoker read();

    /**
     * Request to write a message via this {@link ChannelHandlerContext} through the {@link ChannelPipeline}.
     * This method will not request to actual flush, so be sure to call {@link #flush()}
     * once you want to request to flush all pending data to the actual transport.
     * <p></p>
     *
     * 请求通过这个{@link ChannelHandlerContext}通过{@link ChannelPipeline}写入消息。
     * 此方法不会请求实际刷新，因此请确保在希望请求将所有挂起数据刷新到实际传输时调用{@link #flush()}。
     */
    ChannelFuture write(Object msg);

    /**
     * Request to write a message via this {@link ChannelHandlerContext} through the {@link ChannelPipeline}.
     * This method will not request to actual flush, so be sure to call {@link #flush()}
     * once you want to request to flush all pending data to the actual transport.
     * <p></p>
     *
     * 请求通过这个{@link ChannelHandlerContext}通过{@link ChannelPipeline}写入消息。
     * 此方法不会请求实际刷新，因此请确保在希望请求将所有挂起数据刷新到实际传输时调用{@link #flush()}。
     */
    ChannelFuture write(Object msg, ChannelPromise promise);

    /**
     * Request to flush all pending messages via this ChannelOutboundInvoker.
     * <p></p>
     * 请求通过此ChannelOutboundInvoker刷新所有挂起的消息。
     */
    ChannelOutboundInvoker flush();

    /**
     * Shortcut for call {@link #write(Object, ChannelPromise)} and {@link #flush()}.
     * <p></p>
     * 调用{@link #write(Object, ChannelPromise)}和{@link #flush()}的快捷方式。
     */
    ChannelFuture writeAndFlush(Object msg, ChannelPromise promise);

    /**
     * Shortcut for call {@link #write(Object)} and {@link #flush()}.
     * <p></p>
     * 调用{@link #write(Object)} and {@link #flush()}的快捷方式。
     */
    ChannelFuture writeAndFlush(Object msg);

    /**
     * Return a new {@link ChannelPromise}.
     * <p></p>
     *返回一个新的{@link ChannelPromise}。
     */
    ChannelPromise newPromise();

    /**
     * Return an new {@link ChannelProgressivePromise}
     * <p></p>
     * 返回一个新的{@link ChannelProgressivePromise}
     */
    ChannelProgressivePromise newProgressivePromise();

    /**
     * Create a new {@link ChannelFuture} which is marked as succeeded already. So {@link ChannelFuture#isSuccess()}
     * will return {@code true}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     * <p></p>
     *
     * 创建一个新的{@link ChannelFuture}，标记为已成功。因此{@link ChannelFuture#isSuccess()}将返回{@code true}。
     * 所有添加到它的{@link FutureListener}将被直接通知。而且阻塞方法的每个调用都将返回，而不会阻塞。
     */
    ChannelFuture newSucceededFuture();

    /**
     * Create a new {@link ChannelFuture} which is marked as failed already. So {@link ChannelFuture#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     * <p></p>
     *
     * 创建一个新的{@link ChannelFuture}，它已经被标记为失败。因此{@link ChannelFuture#isSuccess()}将返回{@code false}。
     * 所有添加到它的{@link FutureListener}将被直接通知。而且阻塞方法的每个调用都将返回，而不会阻塞。
     */
    ChannelFuture newFailedFuture(Throwable cause);

    /**
     * Return a special ChannelPromise which can be reused for different operations.
     * <p>
     * It's only supported to use
     * it for {@link ChannelOutboundInvoker#write(Object, ChannelPromise)}.
     * </p>
     * <p>
     * Be aware that the returned {@link ChannelPromise} will not support most operations and should only be used
     * if you want to save an object allocation for every write operation. You will not be able to detect if the
     * operation  was complete, only if it failed as the implementation will call
     * {@link ChannelPipeline#fireExceptionCaught(Throwable)} in this case.
     * </p>
     * <strong>Be aware this is an expert feature and should be used with care!</strong>
     * <p></p>
     *
     * 返回可用于不同操作的特殊ChannelPromise。它只支持用于{@link ChannelOutboundInvoker#write(Object, ChannelPromise)}。
     * 注意，返回的{@link ChannelPromise}将不支持大多数操作，只有在您希望为每个写操作保存对象分配时才应该使用。
     * 您将无法检测操作是否完成，除非它失败，因为在这种情况下，实现将调用{@link ChannelPipeline#fireExceptionCaught(Throwable)}。
     * 请注意这是一个专家特性，应该小心使用!
     */
    ChannelPromise voidPromise();
}
