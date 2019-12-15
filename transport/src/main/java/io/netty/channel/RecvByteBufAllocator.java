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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.UncheckedBooleanSupplier;
import io.netty.util.internal.UnstableApi;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Allocates a new receive buffer whose capacity is probably large enough to read all inbound data and small enough
 * not to waste its space.
 * <p></p>
 * 分配一个新的接收缓冲区，其容量可能大到足以读取所有入站数据，并且小到不会浪费空间。
 */
public interface RecvByteBufAllocator {
    /**
     * Creates a new handle.  The handle provides the actual operations and keeps the internal information which is
     * required for predicting an optimal buffer capacity.
     * <p></p>
     * 创建一个新handle。该handle提供实际操作并保存预测最佳缓冲区容量所需的内部信息。
     */
    Handle newHandle();

    /**
     * @deprecated Use {@link ExtendedHandle}.
     */
    @Deprecated
    interface Handle {
        /**
         * Creates a new receive buffer whose capacity is probably large enough to read all inbound data and small
         * enough not to waste its space.
         */
        ByteBuf allocate(ByteBufAllocator alloc);

        /**
         * Similar to {@link #allocate(ByteBufAllocator)} except that it does not allocate anything but just tells the
         * capacity.
         */
        int guess();

        /**
         * Reset any counters that have accumulated and recommend how many messages/bytes should be read for the next
         * read loop.
         * <p>
         * 重置所有累积的计数器，并建议为下一个读循环应该读取多少消息/字节。
         * <p>
         * This may be used by {@link #continueReading()} to determine if the read operation should complete.
         * <p>
         * {@link #continueReading()}可以使用它来确定是否应该完成读取操作。
         * </p>
         * This is only ever a hint and may be ignored by the implementation.
         * 这只是一个提示，可能会被实现忽略。
         * @param config The channel configuration which may impact this object's behavior.
         */
        void reset(ChannelConfig config);

        /**
         * Increment the number of messages that have been read for the current read loop.
         * 为当前读循环增加已读取的消息数量。
         * @param numMessages The amount to increment by.
         */
        void incMessagesRead(int numMessages);

        /**
         * Set the bytes that have been read for the last read operation.
         * This may be used to increment the number of bytes that have been read.
         * <p>为最后一次读取操作设置已读取的字节数。这可以用来增加已读取的字节数。</p>
         * @param bytes The number of bytes from the previous read operation. This may be negative if an read error
         * occurs. If a negative value is seen it is expected to be return on the next call to
         * {@link #lastBytesRead()}. A negative value will signal a termination condition enforced externally
         * to this class and is not required to be enforced in {@link #continueReading()}.
         *              <p></p>
         *              前一个读操作的字节数。如果发生读取错误，则为负。如果看到一个负值，那么它将在下一次调用{@link #lastBytesRead()}时返回。
         *              如果为负值，则表示此类外部强制执行的终止条件，且不需要在{@link #continueReading()}中强制执行。
         */
        void lastBytesRead(int bytes);

        /**
         * Get the amount of bytes for the previous read operation.
         * @return The amount of bytes for the previous read operation.
         */
        int lastBytesRead();

        /**
         * Set how many bytes the read operation will (or did) attempt to read.
         * 设置读操作尝试读取的字节数
         * @param bytes How many bytes the read operation will (or did) attempt to read.
         */
        void attemptedBytesRead(int bytes);

        /**
         * Get how many bytes the read operation will (or did) attempt to read.
         * @return How many bytes the read operation will (or did) attempt to read.
         */
        int attemptedBytesRead();

        /**
         * Determine if the current read loop should continue.
         * @return {@code true} if the read loop should continue reading. {@code false} if the read loop is complete.
         */
        boolean continueReading();

        /**
         * The read has completed.
         */
        void readComplete();
    }

    @SuppressWarnings("deprecation")
    @UnstableApi
    interface ExtendedHandle extends Handle {
        /**
         * Same as {@link Handle#continueReading()} except "more data" is determined by the supplier parameter.
         * <p>与{@link Handle#continueReading()}相同，但“更多数据”由supplier参数决定。</p>
         * @param maybeMoreDataSupplier A supplier that determines if there maybe more data to read.
         *                              A supplier 确定是否可能有更多的数据读取。
         */
        boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier);
    }

    /**
     * A {@link Handle} which delegates all call to some other {@link Handle}.
     * <p>一个{@link Handle}将所有调用委托给其他{@link Handle}。</p>
     */
    class DelegatingHandle implements Handle {
        private final Handle delegate;

        public DelegatingHandle(Handle delegate) {
            this.delegate = checkNotNull(delegate, "delegate");
        }

        /**
         * Get the {@link Handle} which all methods will be delegated to.
         * @return the {@link Handle} which all methods will be delegated to.
         */
        protected final Handle delegate() {
            return delegate;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return delegate.allocate(alloc);
        }

        @Override
        public int guess() {
            return delegate.guess();
        }

        @Override
        public void reset(ChannelConfig config) {
            delegate.reset(config);
        }

        @Override
        public void incMessagesRead(int numMessages) {
            delegate.incMessagesRead(numMessages);
        }

        @Override
        public void lastBytesRead(int bytes) {
            delegate.lastBytesRead(bytes);
        }

        @Override
        public int lastBytesRead() {
            return delegate.lastBytesRead();
        }

        @Override
        public boolean continueReading() {
            return delegate.continueReading();
        }

        @Override
        public int attemptedBytesRead() {
            return delegate.attemptedBytesRead();
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            delegate.attemptedBytesRead(bytes);
        }

        @Override
        public void readComplete() {
            delegate.readComplete();
        }
    }
}
