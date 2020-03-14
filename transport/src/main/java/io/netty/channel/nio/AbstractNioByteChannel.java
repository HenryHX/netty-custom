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
import io.netty.channel.*;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 * AbstractNioByteChannel 类主要定义了写入消息的 doWrite() 方法
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    // 定义每次读循环最大的次数为16
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    /**
     * 负责刷新发送缓存链表中的数据
     * <p>
     * 该类定义了一个 flushTask 变量，来负责刷新发送已经 write 到缓存中的数据。
     * write 的数据没有直接写到 socket 中，而是写入到 ChannelOutboundBuffer 缓存中，等 flush 的时候才会写到 Socket 中进行发送数据。
     */
    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            // 直接调用flush0，以确保不会尝试同时刷新 通过write(…)添加的 消息。
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    // 通道关闭读取，又错误读取的错误的标识
    // 若 inputClosedSeenErrorOnRead = true ，移除对 SelectionKey.OP_READ 事件的感兴趣。
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     * 关闭通道的输入端。
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    /**
     * 是否允许半封闭
     */
    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    /**
     * NioByteUnsafe 类继承了 AbstractNioChannel 的内部类 AbstractNioUnsafe，并重写了读取数据的方法。
     */
    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            // input关闭了吗？
            if (!isInputShutdown0()) {
                // 是否支持半关闭，如果是，关闭读，触发事件
                if (isAllowHalfClosure(config())) {
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            } else {
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        /**
         * 如果没有配置autoread，整个读取流程如下：
         *
         * <p>1、每次调用{@link io.netty.channel.DefaultChannelPipeline.HeadContext#read}手动读取socket数据时，
         * <p>2、{@link io.netty.channel.AbstractChannel.AbstractUnsafe#beginRead}
         * <p>3、{@link io.netty.channel.nio.AbstractNioChannel#doBeginRead}注册selectionKey.readInterestOp读事件，此时readPending = true
         * <p>4、{@link io.netty.channel.nio.NioEventLoop#processSelectedKey(java.nio.channels.SelectionKey, io.netty.channel.nio.AbstractNioChannel)}会捕获读事件，
         *      调用{@link io.netty.channel.nio.AbstractNioByteChannel.NioByteUnsafe#read}进行真实的数据读取
         * <p>5、读操作完毕，且没有配置自动读，则从选择key兴趣集中移除读操作事件{@link AbstractNioUnsafe#removeReadOp()}
         * <p>6、下一次读取过程回到步骤1
         */
        @Override
        public final void read() {
            // 获取到 Channel 的 config 对象，并从该对象中获取内存分配器ByteBufAllocator，还有计算内存分配器RecvByteBufAllocator.Handle
            final ChannelConfig config = config();
            // 若 inputClosedSeenErrorOnRead = true ，移除对 SelectionKey.OP_READ 事件的感兴趣。
            if (shouldBreakReadReady(config)) {
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            final ByteBufAllocator allocator = config.getAllocator();
            // 返回AdaptiveRecvByteBufAllocator.HandleImpl实例，每个Netty Channel只会有一个HandleImpl实例，
            // 第一次调用此方法时初始化，后面直接使用，调用reset方法进行统计值复位。
            /**
             * defaultMaxMessagesPerRead被设置为16，具体设置流程可参考：
             * {@link DefaultMaxMessagesRecvByteBufAllocator.MaxMessageHandle#continueReading(io.netty.util.UncheckedBooleanSupplier)}
             */
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            /**
             * 进入一个循环，循环体的作用是：使用内存分配器获取数据容器ByteBuf，调用 doReadBytes 方法将数据读取到容器中，
             * 如果这次读取什么都没有或远程连接关闭，则跳出循环。还有，如果满足了跳出条件，也要结束循环，不能无限循环，默认16 次，
             * 默认参数来自 AbstractNioByteChannel 的 属性 ChannelMetadata 类型的 METADATA 实例。
             *
             * 每读取一次就调用 pipeline 的 channelRead 方法，为什么呢？
             *
             * 因为由于 TCP 传输如果包过大的话，丢失的风险会更大，导致重传，所以，大的数据流会分成多次传输。而 channelRead 方法也会被调用多次，
             * 因此，使用 channelRead 方法的时候需要注意，如果数据量大，最好将数据放入到缓存中，读取完毕后，再进行处理。
             */
            try {
                do {
                    /**
                     * 根据预测的值或者初始值（第一次分配时）进行缓冲区分配，初始为1024，
                     * {@link AdaptiveRecvByteBufAllocator.HandleImpl#HandleImpl(int, int, int)}######nextReceiveBufferSize
                     */
                    byteBuf = allocHandle.allocate(allocator);
                    // 读取socketChannel数据到分配的byteBuf,对写入的大小进行一个累计叠加
                    /**
                     * 记录本次循环迭代读取的字节数，有可能会触发{@link AdaptiveRecvByteBufAllocator.HandleImpl#record(int)}方法进行扩大或缩小预测值
                     * 详见下面的{@link AdaptiveRecvByteBufAllocator.HandleImpl#lastBytesRead(int)}方法
                     */
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    // 正常关闭返回-1
                    // 读过程异常关闭跑出IOException
                    if (allocHandle.lastBytesRead() <= 0) {
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        // 如果此次读取没有读到任何数据，则关闭
                        // 判断接收的数据大小是否<0，如果是，说明是关闭连接事件，开始执行关闭
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break;
                    }

                    // 累加读取次数
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    // 触发pipeline的ChannelRead事件来对byteBuf进行后续处理
                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;
                    /**
                     * 下面判断是否继续读取，从上面的介绍可知，while循环最多只会迭代16次，
                     * 详见HandleImpl父类MaxMessageHandle.continueReading方法
                     * {@link DefaultMaxMessagesRecvByteBufAllocator.MaxMessageHandle#continueReading()}
                     */
                } while (allocHandle.continueReading());

                // 记录总共读取的大小
                // 跳出循环后，调用 allocHandle 的 readComplete 方法，表示读取已完成，并记录读取记录，用于下次分配合理内存。
                allocHandle.readComplete();
                // 调用 pipeline 的fireChannelReadComplete方法。
                pipeline.fireChannelReadComplete();

                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    // 读操作完毕，且没有配置自动读，则从选择key兴趣集中移除读操作事件
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        return doWriteInternal(in, in.current());
    }

    /**
     * @return 该方法的返回值
     * <p>
     * 1、如果从 ChannelOutboundBuffer 中获取的消息不可读，返回0，不计入循环发送的次数
     * <p>
     * 2、如果调用 doWriteBytes 发送消息，只要发送的消息字节数大于0，就计入一次循环发送次数
     * <p>
     * 3、如果调用 doWriteBytes 发送消息，发送的字节数为0，则返回一个WRITE_STATUS_SNDBUF_FULL = Integer.MAX_VALUE值。
     */
    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        // 发送消息可以支持两种类型 ByteBuf 和 FileRegion。这里只分析 ByteBuf。FileRegion 和 ByteBuf 发送类似。
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            // 1、首先判断 buf 是否可读，如果不可读，说明该消息不可用，直接丢弃，并且在 ChannelOutboundBuffer 的缓存链表中删除该消息 。
            //      然后在 doWrite 继续循环发送下一条消息。
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            // 2、如果 buf 可读，则调用 doWriteBytes() 方法发送消息，直接写到 Socket 中发送出去，并且返回发送的字节数。
            final int localFlushedAmount = doWriteBytes(buf);
            // 3、如果发送的字节数大于0，则调用 in.progress() 更新消息发送的进度。
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                // 4、判断当前的 buf 中的数据是否已经全部发送完成，如果完成则从 ChannelOutboundBuffer 缓存链表中删除该消息。
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        // 一般只有当前 Socket 缓冲区写满了，无法再继续发送数据的时候才会返回0（Socket 的Buffer已满）。
        // 如果继续循环发送也还是无法写入的，这时返回一个比较大值，会直接退出循环发送的，稍后再尝试写入。
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        // 首先获取循环发送的次数，默认为16次 private volatile int writeSpinCount = 16。
        // 当一次没有完成该消息的发送的时候（写半包），会继续循环发送。
        // 设置发送循环的最大次数原因是当循环发送的时候，I/O 线程会一直尝试进行写操作，
        // 此时I/O 线程无法处理其他的 I/O 操作，比如发送消息，而客户端接收数据比较慢，这时会一直不停的尝试给客户端发送数据。
        int writeSpinCount = config().getWriteSpinCount();
        do {
            // 从 ChannelOutboundBuffer 中获取待写入到 Socket 中的消息。
            // Netty 写数据的时候首先是把数据写入到 ChannelOutboundBuffer 缓存中。使用的链表保存写入的消息数据。
            // 当调用 flush 的时候会从 ChannelOutboundBuffer 缓存中获取数据写入到 Socket 中发送出去。

            // 获取写缓存链表中第一条要写入的数据
            Object msg = in.current();
            // 当获取消息为空，说明所有数据都已经发送出去。然后调用 clearOpWrite()，取消该 Channel 注册在 Selector 上的 OP_WRITE 事件。
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            // 写入消息
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        // 判断消息是否写入完成，然后做相关的操作。
        //      如果未发送完成则在 selector 上注册 OP_WRITE 事件。
        //      如果发送完成则在 selector 上取消 OP_WRITE 事件。
        incompleteWrite(writeSpinCount < 0);
    }

    /**
     * 过滤待发送的消息，只有ByteBuf（堆 or 非堆）以及 FileRegion可以进行最终的Socket网络传输，
     * 其他类型的数据是不支持的，会抛UnsupportedOperationException异常。并且会把堆ByteBuf转换为一个非堆的ByteBuf返回。
     * 也就说，最后会通过socket传输的对象时非堆的ByteBuf和FileRegion。
     */
    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        /**
         * boolean setOpWrite = writeSpinCount < 0； writeSpinCount 什么时候才会出现小于0 呢？
         * 上面已经分析过，如果调用{@link AbstractNioByteChannel#doWriteInternal(io.netty.channel.ChannelOutboundBuffer, java.lang.Object)}
         * 内doWriteBytes方法发送消息，发送的字节数为0，则返回一个 WRITE_STATUS_SNDBUF_FULL = Integer.MAX_VALUE 值。
         * 此时Socket 的 Buffer 已经写满，无法再继续发送数据。
         * 这说明该消息还未写完，然后调用 setOpWrite() 方法，在 Selector 上注册写标识。
         */
        if (setOpWrite) {
            setOpWrite();
        } else {
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.

            // 如果写完，则清除 Selector 上注册的写标识。稍后再刷新计划，以便同时处理其他任务。
            clearOpWrite();

            // Schedule flush again later so other tasks can be picked up in the meantime
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * <p>将给定的{@link ByteBuf}字节写到底层{@link java.nio.channels.Channel}。</p>
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes 返回发送的字节数。
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
