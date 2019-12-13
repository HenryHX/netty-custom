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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import sun.nio.ch.SelectorImpl;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectionKey;
import java.nio.channels.spi.AbstractSelector;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    // 抽象了NIO SocketChannel 和 ServerSocketChannel 的公共的父类
    // SelectableChannel 抽象了 java.nio.SocketChannel 和 java.nio.ServerSocketChannel 的公共方法。
    private final SelectableChannel ch;
    // SelectionKey.OP_READ 读事件
    protected final int readInterestOp;
    // 注册到 selector 上返回的 selectorKey, 默认是#SelectionKey.OP_READ=1
    volatile SelectionKey selectionKey;
    // 是否还有未读的数据
    boolean readPending;
    // 取消channel read注册事件
    private final Runnable clearReadPendingRunnable = new Runnable() {
        @Override
        public void run() {
            clearReadPending0();
        }
    };

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     * <p>当前连接尝试的结果。如果不为空，则后续的连接尝试将失败。</p>
     */
    private ChannelPromise connectPromise;
    // 连接超时定时任务
    private ScheduledFuture<?> connectTimeoutFuture;
    // 客户端地址
    private SocketAddress requestedRemoteAddress;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     *                          它操作的底层{@link SelectableChannel}
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent);
        this.ch = ch;
        this.readInterestOp = readInterestOp;
        try {
            /**
             * configureBlocking(),这个是设置channel的阻塞模式的,true代表block,false为non-block
             * 一般用non-block配合Selector多路复用
             * 可以参考具体的实现,{@link java.nio.channels.spi.AbstractSelectableChannel#configureBlocking}
             * <p></p>
             * SelectableChannel 在设计时,是可以处于"阻塞"或"非阻塞"两种模式下(configureBlocking方法设定).在"阻塞"模式下,每个I/O操作完成之前,都会阻塞其他的IO操作(参见 Channels.write/read,read使用readLock,write使用writeLock同步.).
             * <p>
             * 在"非阻塞"模式 下,永远不会阻塞IO操作,其将会使用Selector作为异步支持.即任何write和read将不阻塞,可能会立即返回.新创建的 SeletableChannel总是处于阻塞模式,
             * 如果需要使用selector多路复用,那么必须使用非阻塞模式(API级别控制).当向某个Selector注册时,此Channel必须处于noblocking模式,且此后模式不可改变,再修改为blocking模式会报错
             * 直到selectionKey销毁.
             */
            ch.configureBlocking(false);
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException e2) {
                logger.warn(
                            "Failed to close a partially initialized socket.", e2);
            }

            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    protected SelectableChannel javaChannel() {
        return ch;
    }

    /**
     * eventLoop是在{@link AbstractUnsafe#register(io.netty.channel.EventLoop, io.netty.channel.ChannelPromise)}时候注册的
     * @return
     */
    @Override
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    /**
     * @deprecated No longer supported.
     * No longer supported.
     */
    @Deprecated
    protected boolean isReadPending() {
        return readPending;
    }

    /**
     * @deprecated Use {@link #clearReadPending()} if appropriate instead.
     * No longer supported.
     */
    @Deprecated
    protected void setReadPending(final boolean readPending) {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                setReadPending0(readPending);
            } else {
                eventLoop.execute(new Runnable() {
                    @Override
                    public void run() {
                        setReadPending0(readPending);
                    }
                });
            }
        } else {
            // Best effort if we are not registered yet clear readPending.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            this.readPending = readPending;
        }
    }

    /**
     * Set read pending to {@code false}.
     */
    protected final void clearReadPending() {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                clearReadPending0();
            } else {
                eventLoop.execute(clearReadPendingRunnable);
            }
        } else {
            // Best effort if we are not registered yet clear readPending. This happens during channel initialization.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            readPending = false;
        }
    }

    private void setReadPending0(boolean readPending) {
        this.readPending = readPending;
        if (!readPending) {
            ((AbstractNioUnsafe) unsafe()).removeReadOp();
        }
    }

    private void clearReadPending0() {
        readPending = false;
        ((AbstractNioUnsafe) unsafe()).removeReadOp();
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     * <p></p>
     * 特殊的{@link Unsafe}子类型，允许访问底层{@link SelectableChannel}
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         */
        SelectableChannel ch();

        /**
         * Finish connect
         */
        void finishConnect();

        /**
         * Read from underlying {@link SelectableChannel}
         */
        void read();

        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {

        protected final void removeReadOp() {
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            if (!key.isValid()) {
                return;
            }
            int interestOps = key.interestOps();
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        @Override
        public final SelectableChannel ch() {
            return javaChannel();
        }

        @Override
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                // 之前连接成功后将connectPromise=null
                if (connectPromise != null) {
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }

                boolean wasActive = isActive();
                /**
                 * {@link NioSocketChannel#doConnect(java.net.SocketAddress, java.net.SocketAddress)}中
                 * boolean connected = javaChannel().connect(remoteAddress)此处就向服务端发起了connect请求，准备三次握手。
                 * 由于是非阻塞模式，所以该方法会立即返回。如果建立连接成功，则返回true，否则返回false,后续需要使用select来检测连接是否已建立成功。
                 * 如果返回false，此种情况就需要将ops设置为SelectionKey.OP_CONNECT，等待connect的select事件通知，然后调用finishConnect方法。
                 *
                 * 对于connect，会加一个超时调度任务，默认的超时时间是30s
                 */
                if (doConnect(remoteAddress, localAddress)) {
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    connectPromise = promise;
                    requestedRemoteAddress = remoteAddress;

                    // Schedule connect timeout.
                    /**
                     * 默认的超时时间是30s
                     * {@link DefaultChannelConfig#connectTimeoutMillis}
                     */
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    if (connectTimeoutMillis > 0) {
                        // 如果设置了connect连接超时时间，则在eventLoop()中放置一个delay task，延时connectTimeoutMillis后执行
                        //
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() {
                                /**
                                 * 如果延迟时间范围内，连接成功，则调用{@link AbstractNioUnsafe#finishConnect()}将connectPromise设置为null，此时不会关闭channel，否则关闭
                                 */
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                ConnectTimeoutException cause =
                                        new ConnectTimeoutException("connection timed out: " + remoteAddress);
                                if (connectPromise != null && connectPromise.tryFailure(cause)) {
                                    close(voidPromise());
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }


                    promise.addListener(new ChannelFutureListener() {
                        // future 和 promise是一个对象
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            if (future.isCancelled()) {
                                if (connectTimeoutFuture != null) {
                                    connectTimeoutFuture.cancel(false);
                                }
                                connectPromise = null;
                                close(voidPromise());
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            boolean active = isActive();

            // trySuccess() will return false if a user cancelled the connection attempt.
            // 如果用户取消了连接尝试，trySuccess()将返回false。
            boolean promiseSet = promise.trySuccess();

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) {
                pipeline().fireChannelActive();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            // 如果用户取消了连接尝试，那么关闭通道，后面跟着channelInactive()。
            if (!promiseSet) {
                close(voidPromise());
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(cause);
            closeIfClosed();
        }

        @Override
        public final void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.
            // 注意，只有在连接尝试既没有被取消也没有超时的情况下，事件循环才调用此方法。

            assert eventLoop().inEventLoop();

            try {
                boolean wasActive = isActive();
                doFinishConnect();
                fulfillConnectPromise(connectPromise, wasActive);
            } catch (Throwable t) {
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                // 连接成功或失败后，取消connectTimeoutFuture定时调度任务
                if (connectTimeoutFuture != null) {
                    connectTimeoutFuture.cancel(false);
                }
                connectPromise = null;
            }
        }

        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            // 只有在没有挂起的刷新时才会立即刷新。
            // 如果有一个挂起的刷新操作，事件循环将在稍后调用forceFlush()，
            // 因此，现在没有必要调用它。
            if (!isFlushPending()) {
                // 当socket写缓冲区未满，那么就执行flush0()
                super.flush0();
            }
        }

        @Override
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }

        /**
         * 判断flush操作是否需要被挂起
         * <p></p>
         * 首先会判断当前NioSocketChannel的SelectionKey.OP_WRITE事件是否有被注册到对应的Selector上，
         * 如果有，则说明当前写缓冲区已经满了(这里指是socket的写缓冲区满了，并且socket并没有被关闭，那么write操作将返回0。
         * <p></p>
         * 这是如果还有未写出的数据待被发送，那么就会注册SelectionKey.OP_WRITE事件)。
         * 等写缓冲区有空间时，SelectionKey.OP_WRITE事件就会被触发，
         * 到时NioEventLoop的事件循环就会调用forceFlush()方法来继续将为写出的数据写出，所以这里直接返回就好。
         */
        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    @Override
    protected void doRegister() throws Exception {
        boolean selected = false;
        for (;;) {
            try {
                // 这里就是JAVA NIO中的注册
                // javaChannel() ：是 Nio 中的 channel。
                // 这里的interestOps=0表示完成注册操作，不对任何事件感兴趣
                // 第三个参数this比较重要，用于java的channel和netty的channel间的绑定关系
                // 把当前的 Channel当做附件进行注册。如果注册成功则返回 selectionKey，通过 selectionKey 可用从 Selector 中获取 当前注册的 Channel。
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
                return;
            } catch (CancelledKeyException e) {
                /**
                 * 什么情况下才抛出 CancelledKeyException 异常呢？
                 *
                 * 由于尚未调用select.select（..）操作，因此可能仍存在缓存而未删除的“已取消”的selectionkey，因此强制调用 selector.selectNow() 方法
                 * 将已经取消的 selectionKey 从 selector {@link AbstractSelector#cancelledKeys}上删除。
                 *
                 * 只有第一次抛出此异常，才调用 selector.selectNow() 进行取消。 如果调用 selector.selectNow() 还有取消的缓存，可能是jdk的一个bug。
                 */
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    eventLoop().selectNow();
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }


    /**
     * SelectionKey 取消逻辑
     * <p>
     *      设置{@link AbstractSelectionKey#valid}为false
     *      <p>
     *      {@link AbstractSelectionKey#cancel()} 触发 ((AbstractSelector)selector()).cancel(this);
     *      <p>
     *      {@link AbstractSelector#cancel(java.nio.channels.SelectionKey)} Selector的cancelledKeys.add(k);
     *      <p>
     *
     * needsToSelectAgain = true时调用{@link SelectorImpl#selectNow()}selector.selectNow()清除无效的key
     * <p>
     * 具体的清除逻辑如下：
     * <p>
     * {@link sun.nio.ch.WindowsSelectorImpl#doSelect}
     * <p>
     * {@link sun.nio.ch.SelectorImpl#processDeregisterQueue} 处理this.cancelledKeys()，每个key执行以下方法
     * <p>
     * {@link sun.nio.ch.WindowsSelectorImpl#implDereg} implDereg方法首选判断反注册的key是不是在通道key尾部，不在交换，
     *      并将交换信息更新到pollWrapper，从fdMap，keys，selectedKeys集合移除选择key，并将key从通道Channel中移除。
     */
    @Override
    protected void doDeregister() throws Exception {
        eventLoop().cancel(selectionKey());
    }

    /**
     * 什么情况下会调用 doBeginRead() 方法？
     *
     * 当 Channel 处于 channelActive 状态后，对于设置了autoRead的Channel执行beginRead()
     * doBeginRead() 方法，在 selector 上注册读事件。
     */
    @Override
    protected void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
        final SelectionKey selectionKey = this.selectionKey;
        /**
         * 该方法首先判断 Channel 是否可用，不可用直接返回。
         * <p>mingza
         * 如果调用了{@link AbstractSelectionKey#cancel()}, 则selectionkey valid ==》 false
         */
        if (!selectionKey.isValid()) {
            return;
        }

        readPending = true;

        // 获取该 selectionKey 注册到 selector 的事件。
        // 如果注册的事件 位运算与 读事件 等于0，则说明该 Channel 没有在 selelctor 上注册读事件，在这里注册读事件。
        final int interestOps = selectionKey.interestOps();
        if ((interestOps & readInterestOp) == 0) {
            selectionKey.interestOps(interestOps | readInterestOp);
        }
    }

    /**
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     * Note that this method does not create an off-heap copy if the allocation / deallocation cost is too high,
     * but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        return buf;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.  Note that this method does not create an off-heap copy if the allocation / deallocation cost is
     * too high, but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ReferenceCounted holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        if (holder != buf) {
            // Ensure to call holder.release() to give the holder a chance to release other resources than its content.
            buf.retain();
            ReferenceCountUtil.safeRelease(holder);
        }

        return buf;
    }

    @Override
    protected void doClose() throws Exception {
        ChannelPromise promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(new ClosedChannelException());
            connectPromise = null;
        }

        ScheduledFuture<?> future = connectTimeoutFuture;
        if (future != null) {
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
