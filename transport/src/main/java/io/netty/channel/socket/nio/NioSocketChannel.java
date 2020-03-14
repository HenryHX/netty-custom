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
package io.netty.channel.socket.nio;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.DefaultSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.SuppressJava6Requirement;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Map;
import java.util.concurrent.Executor;

import static io.netty.channel.internal.ChannelUtils.MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD;

/**
 * {@link io.netty.channel.socket.SocketChannel} which uses NIO selector based implementation.
 */
public class NioSocketChannel extends AbstractNioByteChannel implements io.netty.channel.socket.SocketChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioSocketChannel.class);
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();

    private static SocketChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each SocketChannel.open() otherwise.
             *  <p>
             *  使用{@link SelectorProvider}打开{@link SocketChannel}，从而删除{@link SelectorProvider#provider()}中的条件，
             *  否则每个SocketChannel.open()将调用该条件。
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
            return provider.openSocketChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    private final SocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioSocketChannel() {
        this(DEFAULT_SELECTOR_PROVIDER);
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    public NioSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link SocketChannel}.
     */
    public NioSocketChannel(SocketChannel socket) {
        this(null, socket);
    }

    /**
     * Create a new instance
     *
     * @param parent    the {@link Channel} which created this instance or {@code null} if it was created by the user
     * @param socket    the {@link SocketChannel} which will be used
     */
    public NioSocketChannel(Channel parent, SocketChannel socket) {
        super(parent, socket);
        config = new NioSocketChannelConfig(this, socket.socket());
    }

    /**
     * NioSocketChannel.parent() 是 NioServerSocketChannel。
     * NioServerSocketChannel.parent() 为 null。
     */
    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    public SocketChannelConfig config() {
        return config;
    }

    @Override
    protected SocketChannel javaChannel() {
        return (SocketChannel) super.javaChannel();
    }

    /**
     * 查看 SocketChannel 是否是open，并且是已经连接状态。
     */
    @Override
    public boolean isActive() {
        SocketChannel ch = javaChannel();
        return ch.isOpen() && ch.isConnected();
    }

    /**
     * 查看 SocketChannel 写是否关闭。
     * 调用 {@link NioSocketChannel#doShutdownOutput()} 可以关闭写操作。
     */
    @Override
    public boolean isOutputShutdown() {
        return javaChannel().socket().isOutputShutdown() || !isActive();
    }

    /**
     * 查看 SocketChannel 读是否关闭。
     * 调用 {@link NioSocketChannel#shutdownInput()} 可以关闭读操作。
     */
    @Override
    public boolean isInputShutdown() {
        return javaChannel().socket().isInputShutdown() || !isActive();
    }

    /**
     * 判断SocketChannel 是否是Shutdown 状态：true- 如果 SocketChannel 不支持读写 或者不是打开和连接状态,
     */
    @Override
    public boolean isShutdown() {
        Socket socket = javaChannel().socket();
        return socket.isInputShutdown() && socket.isOutputShutdown() || !isActive();
    }

    /**
     * 调用{@link NioSocketChannel#localAddress0()}
     */
    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    /**
     * 调用{@link NioSocketChannel#remoteAddress0()}
     */
    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    @UnstableApi
    @Override
    protected final void doShutdownOutput() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownOutput();
        } else {
            javaChannel().socket().shutdownOutput();
        }
    }

    @Override
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(final ChannelPromise promise) {
        final EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
                }
            });
        }
        return promise;
    }

    @Override
    public ChannelFuture shutdownInput() {
        return shutdownInput(newPromise());
    }

    @Override
    protected boolean isInputShutdown0() {
        return isInputShutdown();
    }

    @Override
    public ChannelFuture shutdownInput(final ChannelPromise promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            shutdownInput0(promise);
        } else {
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    shutdownInput0(promise);
                }
            });
        }
        return promise;
    }

    /**
     * 1、shutdownOutput()
     * 2、shutdownInput()
     * 3、1和2有一个发生error，则promise失败
     * @return
     */
    @Override
    public ChannelFuture shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public ChannelFuture shutdown(final ChannelPromise promise) {
        ChannelFuture shutdownOutputFuture = shutdownOutput();
        if (shutdownOutputFuture.isDone()) {
            shutdownOutputDone(shutdownOutputFuture, promise);
        } else {
            shutdownOutputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture shutdownOutputFuture) throws Exception {
                    shutdownOutputDone(shutdownOutputFuture, promise);
                }
            });
        }
        return promise;
    }

    private void shutdownOutputDone(final ChannelFuture shutdownOutputFuture, final ChannelPromise promise) {
        ChannelFuture shutdownInputFuture = shutdownInput();
        if (shutdownInputFuture.isDone()) {
            shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
        } else {
            shutdownInputFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture shutdownInputFuture) throws Exception {
                    shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
                }
            });
        }
    }

    private static void shutdownDone(ChannelFuture shutdownOutputFuture,
                                     ChannelFuture shutdownInputFuture,
                                     ChannelPromise promise) {
        Throwable shutdownOutputCause = shutdownOutputFuture.cause();
        Throwable shutdownInputCause = shutdownInputFuture.cause();
        if (shutdownOutputCause != null) {
            if (shutdownInputCause != null) {
                logger.debug("Exception suppressed because a previous exception occurred.",
                        shutdownInputCause);
            }
            promise.setFailure(shutdownOutputCause);
        } else if (shutdownInputCause != null) {
            promise.setFailure(shutdownInputCause);
        } else {
            promise.setSuccess();
        }
    }
    private void shutdownInput0(final ChannelPromise promise) {
        try {
            shutdownInput0();
            promise.setSuccess();
        } catch (Throwable t) {
            promise.setFailure(t);
        }
    }

    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    private void shutdownInput0() throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().shutdownInput();
        } else {
            javaChannel().socket().shutdownInput();
        }
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            SocketUtils.bind(javaChannel().socket(), localAddress);
        }
    }

    /**
     * 客户端向服务端发起连接操作。
     * 1、首先绑定 本地IP
     * 2、然后发起连接，返回连接的结果true、false。
     * 3、如果未连接成功，则向 Selector 上注册 OP_CONNECT 事件。
     * <p></p>
     * 连接结果有 3 种可能
     * 1、连接成功，返回true
     * 2、连接未成功，返回false，表示已经发送连接请求还未收到连接成功的状态。
     * 3、连接网络异常，在 finally 中关闭该连接。
     */
    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (localAddress != null) {
            doBind0(localAddress);
        }

        boolean success = false;
        try {
            boolean connected = SocketUtils.connect(javaChannel(), remoteAddress);
            if (!connected) {
                selectionKey().interestOps(SelectionKey.OP_CONNECT);
            }
            success = true;
            return connected;
        } finally {
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception {
        if (!javaChannel().finishConnect()) {
            throw new Error();
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    /**
     * 关闭 SocketChannel 连接。
     * @throws Exception
     */
    @Override
    protected void doClose() throws Exception {
        super.doClose();
        javaChannel().close();
    }

    @Override
    protected int doReadBytes(ByteBuf byteBuf) throws Exception {
        final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
        allocHandle.attemptedBytesRead(byteBuf.writableBytes());
        return byteBuf.writeBytes(javaChannel(), allocHandle.attemptedBytesRead());
    }

    @Override
    protected int doWriteBytes(ByteBuf buf) throws Exception {
        final int expectedWrittenBytes = buf.readableBytes();
        return buf.readBytes(javaChannel(), expectedWrittenBytes);
    }

    /**
     * FileRegion 是使用 FileChannel.transferTo() 直接写 Socket的，使用的零拷贝。
     * @param region        the {@link FileRegion} from which the bytes should be written
     */
    @Override
    protected long doWriteFileRegion(FileRegion region) throws Exception {
        final long position = region.transferred();
        return region.transferTo(javaChannel(), position);
    }

    private void adjustMaxBytesPerGatheringWrite(int attempted, int written, int oldMaxBytesPerGatheringWrite) {
        // By default we track the SO_SNDBUF when ever it is explicitly set. However some OSes may dynamically change
        // SO_SNDBUF (and other characteristics that determine how much data can be written at once) so we should try
        // make a best effort to adjust as OS behavior changes.
        if (attempted == written) {
            if (attempted << 1 > oldMaxBytesPerGatheringWrite) {
                ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted << 1);
            }
        } else if (attempted > MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD && written < attempted >>> 1) {
            ((NioSocketChannelConfig) config).setMaxBytesPerGatheringWrite(attempted >>> 1);
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        SocketChannel ch = javaChannel();
        int writeSpinCount = config().getWriteSpinCount(); // 写入自旋计数, default 16次
        // doWrite是一个写循环操作，当满足一定条件时会结束循环。每一次循环会完成的操作：
        do {
            // 判断当前ChannelOutboundBuffer中的数据都已经被传输完了，如果已经传输完了，并且发现NioSocketChannel还注册有SelectionKey.OP_WRITE事件，
            // 则将SelectionKey.OP_WRITE从感兴趣的事件中移除，即，Selector不再监听该NioSocketChannel的可写事件了。然后跳出循环，方法返回。
            if (in.isEmpty()) {
                // All written so clear OP_WRITE
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }

            // Ensure the pending writes are made of ByteBufs only.
            // 获取设置的每个 ByteBuf 的最大字节数，这个数字来自操作系统的 so_sndbuf 定义
            int maxBytesPerGatheringWrite = ((NioSocketChannelConfig) config).getMaxBytesPerGatheringWrite();
            // 获取所有待写出的ByteBuffer，它会将ChannelOutboundBuffer中所有待写出的ByteBuf转换成JDK Bytebuffer
            // （因为，底层依旧是基于JDK NIO的网络传输，所有最终传输的还是JDK 的ByteBuffer对象）。
            // 它依次出去每个待写的ByteBuf，然后根据ByteBuf的信息构建一个ByteBuffer（这里的ByteBuf是一个堆外ByteBuf，
            // 因此构建出来的ByteBuffer也是一个堆外的ByteBuffer），并设置该ByteBuffer的readerIndex、readableBytes的值为ByteBuf对应的值。
            // 然后返回构建好的ByteBuffer[]数组。
            ByteBuffer[] nioBuffers = in.nioBuffers(1024, maxBytesPerGatheringWrite);
            // 获取本次循环需要写出的ByteBuffer个数
            int nioBufferCnt = in.nioBufferCount();

            // Always us nioBuffers() to workaround data-corruption.
            // See https://github.com/netty/netty/issues/2761
            switch (nioBufferCnt) {
                case 0:
                    // We have something else beside ByteBuffers to write so fallback to normal writes.
                    // 除了ByteBuffers，我们还需要写些别的东西，这样就可以退回到正常的写操作。
                    /**
                     * nioBufferCnt == 0 ：对非ByteBuffer对象的数据进行普通的写操作。
                     * 上面说了in.nioBuffers()会将ChannelOutboundBuffer中所有待发送的ByteBuf转换成Bytebuffer后返回一个ByteBuffer[]数组，
                     * 以便后面进行ByteBuffer的传输，而nioBufferCnt则表示待发送ByteBuffer的个数，即ByteBuffer[]数组的长度。
                     * 注意，这里nioBuffers()仅仅是对ByteBuf对象进行了操作，但是我们从前面的流程可以得知，
                     * 除了ByteBuf外FileRegion对象也是可以进行底层的网络传输的。因此当待传输的对象是FileRegion时“nioBufferCnt == 0”，
                     * 那么这是就会调用{@link AbstractNioByteChannel#doWrite(ChannelOutboundBuffer in)}方法来完成数据的传输。
                     * 实际上底层就是依靠JDK NIO 的 FileChannel来实现零拷贝的数据传输。
                     */
                    writeSpinCount -= doWrite0(in);
                    break;
                case 1: {
                    // Only one ByteBuf so use non-gathering write
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    /**
                     *  nioBufferCnt == 1 ：说明只有一个ByteBuffer等待被传输，那么不使用gather的write操作来传输数据
                     *                      （JDK NIO 支持一次写单个ByteBuffer 以及 一次写多个ByteBuffer的聚集写模式）
                     */
                    ByteBuffer buffer = nioBuffers[0];
                    int attemptedBytes = buffer.remaining();
                    final int localWrittenBytes = ch.write(buffer);
                    /**
                     * ch.write操作会返回本次写操作写出的字节数，但该方法返回0时，即localWrittenBytes为0，则
                     * 说明底层的写缓冲区已经满了(这里应该指的是linux底层的写缓冲区满了)，这时就会将setOpWrite置为true，
                     * 那么这种情况下就会注册当前SocketChannel的写事件（SelectionKey.OP_WRITE）到对应的Selector为感兴趣的事件，
                     * 这样当写缓冲区有空间时，就会触发SelectionKey.OP_WRITE就绪事件，
                     * NioEventLoop的事件循环在处理SelectionKey.OP_WRITE事件时会执行forceFlush()以继续发送外发送完的数据。
                     */
                    if (localWrittenBytes <= 0) {
                        incompleteWrite(true);
                        return;
                    }
                    adjustMaxBytesPerGatheringWrite(attemptedBytes, localWrittenBytes, maxBytesPerGatheringWrite);
                    // 释放所有已经写出去的缓存对象，并修改部分写缓冲的索引。
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
                default: {
                    // Zero length buffers are not added to nioBuffers by ChannelOutboundBuffer, so there is no need
                    // to check if the total size of all the buffers is non-zero.
                    // We limit the max amount to int above so cast is safe
                    /**
                     * nioBufferCnt > 1 ：说明有多个ByteBuffer等待被传输，那么使用JDK NIO的聚集写操作，
                     *                      一次性传输多个ByteBuffer到NioSocketChannel中。
                     */
                    // 获取本次循环总共需要写出的数据的字节总数
                    long attemptedBytes = in.nioBufferSize();
                    /**
                     * NIO 知识点 Gather 和 Scatter
                     *
                     * 聚集（gather）写入Channel是指在写操作时将多个buffer的数据写入同一个Channel，因此，
                     *      Channel 将多个Buffer中的数据“聚集（gather）”后发送到Channel。
                     *
                     * 分散（scatter）从Channel中读取是指在读操作时将读取的数据写入多个buffer中。
                     *      因此，Channel将从Channel中读取的数据“分散（scatter）”到多个Buffer中。
                     */
                    final long localWrittenBytes = ch.write(nioBuffers, 0, nioBufferCnt);
                    if (localWrittenBytes <= 0) {
                        incompleteWrite(true);
                        return;
                    }
                    // Casting to int is safe because we limit the total amount of data in the nioBuffers to int above.
                    adjustMaxBytesPerGatheringWrite((int) attemptedBytes, (int) localWrittenBytes,
                            maxBytesPerGatheringWrite);
                    in.removeBytes(localWrittenBytes);
                    --writeSpinCount;
                    break;
                }
            }
        } while (writeSpinCount > 0);

        /**
         * done表示本次写操作是否完成，。有两种情况下done为false：
         * <p></p>
         * [1] 还有未写完的数据待发送，并且写缓冲区已经满了(这里指的是linux底层的写缓冲区满了)，无法再继续写出，那么此时setOpWrite标识为true。
         * 这种情况下就会注册当前SocketChannel的写事件（SelectionKey.OP_WRITE）到对应的Selector为感兴趣的事件，
         * 这样当写缓冲区有空间时，就会触发SelectionKey.OP_WRITE就绪事件， NioEventLoop的事件循环在处理SelectionKey.OP_WRITE事件时
         * 会执行{@link AbstractNioUnsafe#forceFlush()}以继续发送外发送完的数据。接着退出doWrite()循环写操作。
         * <p></p>
         * [2] 执行了config().getWriteSpinCount()次（默认16次）socket写操作后，数据仍旧未写完，
         * 那么此时会将flush()操作封装成一个task提交至NioEventLoop的taskQueue中，这样在NioEventLoop的下一次事件循环时会就会取出该任务并执行，
         * 也就会继续写出未写完的任务了。这也说明了，如果发送的是很大的数据包的话，可能一次写循环操作是无法将数据全部发送出去的，
         * 也不会为了发送该大数据包的数据而导致NioEventLoop线程的阻塞以至于影响NioEventLoop上其他Channel的操作和响应。
         * 接着退出doWrite()循环写操作。
         */
        incompleteWrite(writeSpinCount < 0);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioSocketChannelUnsafe();
    }

    private final class NioSocketChannelUnsafe extends NioByteUnsafe {
        @Override
        protected Executor prepareToClose() {
            try {
                if (javaChannel().isOpen() && config().getSoLinger() > 0) {
                    // We need to cancel this key of the channel so we may not end up in a eventloop spin
                    // because we try to read or write until the actual close happens which may be later due
                    // SO_LINGER handling.
                    // See https://github.com/netty/netty/issues/4449
                    // 需要逗留导数据收发完成或者设置的时间，所以提交到另外的Executor中执行
                    // 提前doDeregister,逗留期间不再接受新的数据（包含selection key的cancel的原因之一）
                    doDeregister();
                    return GlobalEventExecutor.INSTANCE;
                }
            } catch (Throwable ignore) {
                // Ignore the error as the underlying channel may be closed in the meantime and so
                // getSoLinger() may produce an exception. In this case we just return null.
                // See https://github.com/netty/netty/issues/4449
            }
            return null;
        }
    }

    /**
     * NioSocketChannelConfig 是 NioSocketChannel 的配置类。
     * 1、设置 Socket 参数
     * 2、设置gather 最大写入数据
     */
    private final class NioSocketChannelConfig extends DefaultSocketChannelConfig {
        private volatile int maxBytesPerGatheringWrite = Integer.MAX_VALUE;
        private NioSocketChannelConfig(NioSocketChannel channel, Socket javaSocket) {
            super(channel, javaSocket);
            calculateMaxBytesPerGatheringWrite();
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public NioSocketChannelConfig setSendBufferSize(int sendBufferSize) {
            super.setSendBufferSize(sendBufferSize);
            calculateMaxBytesPerGatheringWrite();
            return this;
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            if (PlatformDependent.javaVersion() >= 7) {
                return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
            }
            return super.getOptions();
        }

        void setMaxBytesPerGatheringWrite(int maxBytesPerGatheringWrite) {
            this.maxBytesPerGatheringWrite = maxBytesPerGatheringWrite;
        }

        int getMaxBytesPerGatheringWrite() {
            return maxBytesPerGatheringWrite;
        }

        private void calculateMaxBytesPerGatheringWrite() {
            // Multiply by 2 to give some extra space in case the OS can process write data faster than we can provide.
            // 乘以2以留出一些额外的空间，以防操作系统处理写数据的速度比我们提供的要快。
            int newSendBufferSize = getSendBufferSize() << 1;
            if (newSendBufferSize > 0) {
                setMaxBytesPerGatheringWrite(newSendBufferSize);
            }
        }

        private SocketChannel jdkChannel() {
            return ((NioSocketChannel) channel).javaChannel();
        }
    }
}
