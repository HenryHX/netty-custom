/*
 * Copyright 2015 The Netty Project
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

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.nio.AbstractNioByteChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.UncheckedBooleanSupplier;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    private volatile int maxMessagesPerRead;
    private volatile boolean respectMaybeMoreData = true;

    /**
     * 子类AdaptiveRecvByteBufAllocator调用的就是DefaultMaxMessagesRecvByteBufAllocator的默认构造函数，
     * 所以每次进入'Unsafe.read'函数while循环只会进行一次，也就是只会根据分配的ByteBuf从channel中读取一次数据。
     */
    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        maxMessagesPerRead(maxMessagesPerRead);
    }

    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        checkPositive(maxMessagesPerRead, "maxMessagesPerRead");
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    /**
     * Determine if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * <p>确定如果我们认为没有更多的数据，{@link #newHandle()}的future实例是否会停止读取。</p>
     * @param respectMaybeMoreData
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.
     *          <p>
     *          如果我们认为没有更多的数据，{@code true}停止读取。这可能会保存从套接字读取的系统调用，
     *          但是如果数据以一种不受控制的方式到达，我们可能会放弃我们的{@link #maxMessagesPerRead()}量，
     *          并不得不等待选择器通知我们更多的数据。</li></li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.
     *          <p>{@code false}保持读取(一直读到{@link #maxMessagesPerRead()})，或者直到尝试读取时没有数据为止。</li>
     * </ul>
     * @return {@code this}.
     */
    public DefaultMaxMessagesRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        this.respectMaybeMoreData = respectMaybeMoreData;
        return this;
    }

    /**
     * Get if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     * @return
     * <ul>
     *     <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *          the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *          quantum and have to wait for the selector to notify us of more data.</li>
     *     <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *          attempt to read.</li>
     * </ul>
     */
    public final boolean respectMaybeMoreData() {
        return respectMaybeMoreData;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     * <p></p>
     * 重点是为{@link #continueReading()}强制执行每次读取的最大消息条件。
     */
    public abstract class MaxMessageHandle implements ExtendedHandle {
        private ChannelConfig config;
        // 每次调用Unsafe.read从channel中进行读取的次数
        // 这个字段默认为1，在reset方法中会调用外部类
        // DefaultMaxMessagesRecvByteBufAllocator.maxMessagesPerRead
        // 方法进行初始化，DefaultMaxMessagesRecvByteBufAllocator上面
        // 介绍过，该字段默认为1.
        private int maxMessagePerRead;
        // Unsafe.read方法中，循环读取的次数
        // 每次进入Unsafe.read进行循环读取前会调用reset进行置0操作。
        private int totalMessages;
        // Unsafe.read方法中，进行循环读取的总字节数，同样
        // 会在读取前调用reset函数被置0
        private int totalBytesRead;
        // 在进行实际读取时，会调用如下方法
        // allocHandle.attemptedBytesRead(byteBuf.writableBytes());
        // 获取该ByteBuf可以读取的字节数，并使用该数值对attemptedBytesRead
        // 进行赋值，也就是此次循环迭代ByteBuf剩下可用于读取字节的数量
        private int attemptedBytesRead;
        // 记录上次迭代或者最后一次迭代实际读取的字节数
        private int lastBytesRead;
        // 这个字段看了源码没有实际使用，不介绍
        private final boolean respectMaybeMoreData = DefaultMaxMessagesRecvByteBufAllocator.this.respectMaybeMoreData;
        private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
            @Override
            public boolean get() {
                return attemptedBytesRead == lastBytesRead;
            }
        };

        /**
         * Only {@link ChannelConfig#getMaxMessagesPerRead()} is used.
         * <p>
         * 每次进入Unsafe.read函数进行循环读取前，会调用reset函数，对
         * Handle中的一些统计数值进行复位重置
         */
        @Override
        public void reset(ChannelConfig config) {
            this.config = config;
            maxMessagePerRead = maxMessagesPerRead();
            totalMessages = totalBytesRead = 0;
        }

        /**
         * 分配根据统计数据预测大小的ByteBuf
         * {@link AdaptiveRecvByteBufAllocator.HandleImpl#guess()}
         */
        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return alloc.ioBuffer(guess());
        }

        /**
         * 在Unsafe.read函数的每次循环迭代中，每读取一次
         * 都会调用incMessagesRead(1)增加已经读取的次数
         */
        @Override
        public final void incMessagesRead(int amt) {
            totalMessages += amt;
        }

        /**
         * 在Unsafe.read函数的每次循环迭代从channel进行读取之后，都会记录本次读取的字节数并累计总共读取的字节数
         *
         * @param bytes The number of bytes from the previous read operation. This may be negative if an read error
         * occurs. If a negative value is seen it is expected to be return on the next call to
         * {@link #lastBytesRead()}. A negative value will signal a termination condition enforced externally
         * to this class and is not required to be enforced in {@link #continueReading()}.
         *              <p></p>
         *              前一个读操作的字节数。如果发生读取错误，则为负。如果看到一个负值，那么它将在下一次调用{@link #lastBytesRead()}时返回。
         */
        @Override
        public void lastBytesRead(int bytes) {
            lastBytesRead = bytes;
            if (bytes > 0) {
                totalBytesRead += bytes;
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        /**
         * 判断是否继续读取，这里主要是根据配置限制每次Unsafe.read函数中循环迭代的次数。
         */
        @Override
        public boolean continueReading() {
            return continueReading(defaultMaybeMoreSupplier);
        }

        /**
         * 判断是否需要继续读取，带入如下:
         * <p>
         * 几个条件：
         *
         * 1、首先是否自动读取。
         * <p>
         * 2、且猜测是否还有更多数据，如果实际读取的和预估的一致，说明可能还有数据没读，需要再次循环。
         * <p>
         * 3、如果读取次数未达到 16 次，继续读取。
         *     <p>  a、初始化channel时在{@link AbstractNioByteChannel#METADATA}设置
         *              {@link ChannelMetadata#ChannelMetadata(boolean, int)}.defaultMaxMessagesPerRead = 16
         *     <p>  b、而{@link DefaultChannelConfig#setRecvByteBufAllocator(io.netty.channel.RecvByteBufAllocator, io.netty.channel.ChannelMetadata)}
         *              通过{@link AbstractNioByteChannel#METADATA}设置DefaultChannelConfig配置的RecvByteBufAllocator（即this对象）的defaultMaxMessagesPerRead为16
         *     <p>  c、之后{@link AbstractNioByteChannel.NioByteUnsafe#read()}读循环过程中
         *              使用的RecvByteBufAllocator正是上述DefaultChannelConfig中的{@link AbstractChannel.AbstractUnsafe#recvBufAllocHandle()}</p>
         * <p>
         * 4、如果读取到的总数大于0，说明有数据，继续读取。
         * <p>
         * 这里的循环的主要原因就像我们刚刚说的，TCP 传输过大数据容易丢包（带宽限制），因此会将大包分好几次传输，
         * 还有就是可能预估的缓冲区不够大，没有充分读取 Channel 的内容。
         */
        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            // 默认情况下 config.isAutoRead() 为true
            // respectMaybeMoreData 默认为 true
            // maybeMoreDataSupplier.get() 为false
            // totalMessages第一次循环则为1
            // maxMessagePerRead为16
            // 结果返回false
            return config.isAutoRead() &&
                   (!respectMaybeMoreData || maybeMoreDataSupplier.get()) &&
                   totalMessages < maxMessagePerRead &&
                   totalBytesRead > 0;
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        /**
         * read loop 此次查询的时候都会赋值:
         * <p>
         * {@link AbstractNioByteChannel.NioByteUnsafe#read()}方法每次while循环时都会执行
         * {@link NioSocketChannel#doReadBytes(io.netty.buffer.ByteBuf)},doReadBytes会赋值
         */
        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }

        protected final int totalBytesRead() {
            return totalBytesRead < 0 ? Integer.MAX_VALUE : totalBytesRead;
        }
    }
}
