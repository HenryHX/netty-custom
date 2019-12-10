/*
 * Copyright 2013 The Netty Project
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
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static java.lang.Math.min;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 * <p>
 * 每个 ChannelSocket 的 Unsafe 都有一个绑定的 ChannelOutboundBuffer ， Netty 向站外输出数据的过程统一通过 ChannelOutboundBuffer 类进行封装，
 * 目的是为了提高网络的吞吐量，在外面调用 write 的时候，数据并没有写到 Socket，而是写到了 ChannelOutboundBuffer 这里，当调用 flush 的时候，才真正的向 Socket 写出。
 * <p>
 * All methods must be called by a transport implementation from an I/O thread, except the following ones:
 * <ul>
 * <li>{@link #size()} and {@link #isEmpty()}</li>
 * <li>{@link #isWritable()}</li>
 * <li>{@link #getUserDefinedWritability(int)} and {@link #setUserDefinedWritability(int, boolean)}</li>
 * </ul>
 * </p>
 *
 * 由{@link WriteBufferWaterMark}可知：默认的情况下，ChannelOutboundBuffer 缓存区的大小最大是 64 kb，最小是 32 kb
 */
public final class ChannelOutboundBuffer {
    // Assuming a 64-bit JVM:
    //  - 16 bytes object header 对象头16
    //  - 8 reference fields 6*8（这个地方计算有问题？？事实上只有6个）
    //  - 2 long fields 2*8
    //  - 2 int fields  2*4
    //  - 1 boolean field 1
    //  - padding
    // 16 + 48 + 16 + 8 + 1 = 89 + 7bytes的padding = 96
    // 假设的是64位操作系统下，且没有使用各种压缩选项的情况。
    // 对象头的长度占16字节；引用属性占8字节；long类型占8字节；int类型占4字节；boolean类型占1字节。
    // 同时，由于HotSpot VM的自动内存管理系统要求对象起始地址必须是8字节的整数倍，也就是说对象的大小必须是8字节的整数倍，
    // 如果最终字节数不为8的倍数，则padding会补足至8的倍数。
    static final int CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD =
            SystemPropertyUtil.getInt("io.netty.transport.outboundBufferEntrySizeOverhead", 96);

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final FastThreadLocal<ByteBuffer[]> NIO_BUFFERS = new FastThreadLocal<ByteBuffer[]>() {
        @Override
        protected ByteBuffer[] initialValue() throws Exception {
            return new ByteBuffer[1024];
        }
    };

    private final Channel channel;

    // Entry(flushedEntry) --> ... Entry(unflushedEntry) --> ... Entry(tailEntry)
    //
    // The Entry that is the first in the linked-list structure that was flushed
    // 缓存链表中被刷新的第一个元素
    private Entry flushedEntry;
    // The Entry which is the first unflushed in the linked-list structure
    // 缓存链表中中第一个未刷新的元素
    private Entry unflushedEntry;
    // The Entry which represents the tail of the buffer
    // 缓存链表中的尾元素
    private Entry tailEntry;
    // The number of flushed entries that are not written yet
    // 刷新但还没有写入到 socket 中的数量
    private int flushed;

    private int nioBufferCount;
    private long nioBufferSize;

    private boolean inFail;

    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    // 记录了该ChannelOutboundBuffer中所有待发送Entry对象占的总内存大小和所有待发送数据的大小。
    @SuppressWarnings("UnusedDeclaration")
    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> UNWRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "unwritable");

    /**
     * unwritable用来标示当前该Channel要发送的数据是否已经超过了设定 or 默认的WriteBufferWaterMark的high值。
     * 如果当前操作导致了待写出的数据（包括Entry对象大小以及真实需要传输数据的大小）超过了设置写缓冲区的高水位，
     * 那么将会触发fireChannelWritabilityChanged事件。
     */
    @SuppressWarnings("UnusedDeclaration")
    private volatile int unwritable;

    private volatile Runnable fireChannelWritabilityChangedTask;

    ChannelOutboundBuffer(AbstractChannel channel) {
        this.channel = channel;
    }

    /**
     * Add given message to this {@link ChannelOutboundBuffer}. The given {@link ChannelPromise} will be notified once
     * the message was written.
     * 将给定的消息添加到 ChannelOutboundBuffer，一旦消息被写入，就会通知 promise。
     */
    public void addMessage(Object msg, int size, ChannelPromise promise) {
        Entry entry = Entry.newInstance(msg, size, total(msg), promise);
        // 判断 tailEntry 是否为 null，如果为 null 说明链表为空。则把 flushedEntry 置为null。
        if (tailEntry == null) {
            flushedEntry = null;
        } else {
            // 如果 tailEntry 不为空，则把新添加的 Entry 添加到 tailEntry 后面 。
            Entry tail = tailEntry;
            tail.next = entry;
        }
        // 将新添加的 Entry 设置为 链表的 tailEntry。
        tailEntry = entry;
        // 如果 unflushedEntry 为null，说明没有未被刷新的元素。新添加的Entry 肯定是未被刷新的，则把当前 Entry 设置为 unflushedEntry 。
        if (unflushedEntry == null) {
            unflushedEntry = entry;
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        // 统计未被刷新的元素的总大小。
        incrementPendingOutboundBytes(entry.pendingSize, false);
    }

    /**
     * Add a flush to this {@link ChannelOutboundBuffer}. This means all previous added messages are marked as flushed
     * and so you will be able to handle them.
     * <p>
     * 当 addMessage 成功添加进 ChannelOutboundBuffer 后，就需要 flush 刷新到 Socket 中去。
     * 但是这个方法并不是做刷新到 Socket 的操作。而是将 unflushedEntry 的引用转移到 flushedEntry 引用中，表示即将刷新这个 flushedEntry，
     * 至于为什么这么做？
     * <p>
     * 答：因为 Netty 提供了 promise，这个对象可以做取消操作，例如，不发送这个 ByteBuf 了，所以，在 write 之后，flush 之前需要告诉 promise 不能做取消操作了。
     */
    public void addFlush() {
        // There is no need to process all entries if there was already a flush before and no new messages
        // where added in the meantime.
        //
        // See https://github.com/netty/netty/issues/2577
        // write操作最终会将包含有待发送消息的ByteBuf封装成Entry对象放入unflushedEntry单向链表的尾部。
        // 而这里就会先判断unflushedEntry是否为null，如果为null则说明所有的entries已经被flush了，
        // 并在此期间没有新的消息被添加进ChannelOutboundBuffer中。所以直接返回就好。
        Entry entry = unflushedEntry;

        // 如果unflushedEntry非空，则说明有待发送的entries等待被发送。
        // 那么将unflushedEntry赋值给flushedEntry（调用flush()操作时就是将该flushedEntry单向链表中的entries的数据发到网络），
        // 并将unflushedEntry置为null，表示没有待发送的entries了。并通过flushed成员属性记录待发送entries的个数。
        if (entry != null) {
            // 如果 flushedEntry == null 说明当前没有正在刷新的任务，则把 entry 设置为 flushedEntry 刷新的起点。
            if (flushedEntry == null) {
                // there is no flushedEntry yet, so start with the entry
                flushedEntry = entry;
            }
            // 循环设置 entry， 设置这些 entry 状态设置为非取消状态，如果设置失败，则把这些entry 节点取消并使 totalPendingSize 减去这个节点的字节大小。
            do {
                flushed ++;
                if (!entry.promise.setUncancellable()) {
                    // Was cancelled so make sure we free up memory and notify about the freed bytes
                    int pending = entry.cancel();
                    decrementPendingOutboundBytes(pending, false, true);
                }
                entry = entry.next;
            } while (entry != null);

            // All flushed so reset unflushedEntry
            unflushedEntry = null;
        }
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void incrementPendingOutboundBytes(long size) {
        incrementPendingOutboundBytes(size, true);
    }

    private void incrementPendingOutboundBytes(long size, boolean invokeLater) {
        if (size == 0) {
            return;
        }

        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, size);
        if (newWriteBufferSize > channel.config().getWriteBufferHighWaterMark()) {
            setUnwritable(invokeLater);
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(long size) {
        decrementPendingOutboundBytes(size, true, true);
    }

    private void decrementPendingOutboundBytes(long size, boolean invokeLater, boolean notifyWritability) {
        if (size == 0) {
            return;
        }

        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);
        if (notifyWritability && newWriteBufferSize < channel.config().getWriteBufferLowWaterMark()) {
            setWritable(invokeLater);
        }
    }

    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    /**
     * Return the current message to write or {@code null} if nothing was flushed before and so is ready to be written.
     */
    public Object current() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return null;
        }

        return entry.msg;
    }

    /**
     * Return the current message flush progress.
     * @return {@code 0} if nothing was flushed before for the current message or there is no current message
     */
    public long currentProgress() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return 0;
        }
        return entry.progress;
    }

    /**
     * Notify the {@link ChannelPromise} of the current message about writing progress.
     */
    public void progress(long amount) {
        Entry e = flushedEntry;
        assert e != null;
        ChannelPromise p = e.promise;
        long progress = e.progress + amount;
        e.progress = progress;
        if (p instanceof ChannelProgressivePromise) {
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as success and return {@code true}. If no
     * flushed message exists at the time this method is called it will return {@code false} to signal that no more
     * messages are ready to be handled.
     */
    public boolean remove() {
        // 获取 flushedEntry 节点，链表的头结点。如果获取不到清空 ByteBuf 缓存
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers();
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        // 在链表上移除该 Entry。如果之前没有取消，只释放消息、通知和递减。
        removeEntry(e);

        if (!e.cancelled) {
            // only release message, notify and decrement if it was not canceled before.
            // 如果之前没有取消，则只释放消息、通知和减量。
            ReferenceCountUtil.safeRelease(msg);
            safeSuccess(promise);
            decrementPendingOutboundBytes(size, false, true);
        }

        // recycle the entry
        // 当 Entry 从链表中移除的时候回调用 e.recycle() 方法。把 Entry 中成员变量全部初始化
        e.recycle();

        return true;
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as failure using the given {@link Throwable}
     * and return {@code true}. If no   flushed message exists at the time this method is called it will return
     * {@code false} to signal that no more messages are ready to be handled.
     * <p></p>
     * 将删除当前消息，使用给定的{@link Throwable}将其{@link ChannelPromise}标记为失败，并返回{@code true}。
     * 如果在调用此方法时不存在刷新消息，它将返回{@code false}，以表明不再准备处理任何消息。
     */
    public boolean remove(Throwable cause) {
        return remove0(cause, true);
    }

    private boolean remove0(Throwable cause, boolean notifyWritability) {
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers();
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        removeEntry(e);

        if (!e.cancelled) {
            // only release message, fail and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg);

            safeFail(promise, cause);
            decrementPendingOutboundBytes(size, false, notifyWritability);
        }

        // recycle the entry
        e.recycle();

        return true;
    }

    /**
     * 链表移除节点
     */
    private void removeEntry(Entry e) {
        // 如果 flushed ==0 说明，链表中所有 flush 的数据都已经发送到 Socket 中。
        // 把 flushedEntry 置位 null。此时链表可能还有 unflushedEntry 数据。
        // 如果此时 e == tailEntry 说明链表为空，则把 tailEntry 和 unflushedEntry 都置为空。
        if (-- flushed == 0) {
            // processed everything
            flushedEntry = null;
            if (e == tailEntry) {
                tailEntry = null;
                unflushedEntry = null;
            }
        } else {
            // 把 flushedEntry 置为下一个节点（flushedEntry 此时是头结点）。
            flushedEntry = e.next;
        }
    }

    /**
     * Removes the fully written entries and update the reader index of the partially written entry.
     * This operation assumes all messages in this buffer is {@link ByteBuf}.
     * <p></p>
     * 通过已经写出数据的字节数来清理或修改ByteBuf。也就是说writtenBytes的大小可能是包含了多个ByteBuf以及某个ByteBuf的部分数据
     * (因为一个ByteBuf可能只写出了部分数据，还未完成被写出到网络层中)。
     */
    public void removeBytes(long writtenBytes) {
        for (;;) {
            Object msg = current();
            if (!(msg instanceof ByteBuf)) {
                assert writtenBytes == 0;
                break;
            }

            final ByteBuf buf = (ByteBuf) msg;
            final int readerIndex = buf.readerIndex();
            final int readableBytes = buf.writerIndex() - readerIndex;

            /**
             * 这个if判断表示：本次socket的write操作(这里是真的是网络通信写操作了)已经写出去的字节数”大于"了当前ByteBuf包可读取的字节数。
             * 这说明，当前这个包中所有的可写的数据都已经写完了，既然当前这个ByteBuf的数据都写完了，那么久可以将其删除了。
             * 即，调用『remove()』操作，这个操作就会标识异步write操作为成功完成，
             * 并且会回调已经注册到ByteBuf的promise上的所有listeners。同时会原子的修改ChannelOutboundBuffer的totalPendingSize属性值，
             * 减少已经写出的数据大小（包括Entry对象内存大小和真实数据的大小），
             * 并且如果减少后totalPendingSize小于设置 or 默认的WriteBufferWaterMark的low值，
             * 并且在此之前totalPendingSize超过了WriteBufferWaterMark的high值，那么将触发fireChannelWritabilityChanged事件。
             * 『remove()』操作还会将当前的ByteBuf指向下一个待处理的ByteBuf，最后释放这个已经被写出去的ByteBuf对象资源。
             */
            if (readableBytes <= writtenBytes) {
                if (writtenBytes != 0) {
                    progress(readableBytes);
                    // writtenBytes > readableBytes 表示已经有多个entry被写到socket，此时要以此删除成功写完的entry
                    writtenBytes -= readableBytes;
                }
                remove();
            } else { // readableBytes > writtenBytes
                /**
                 * 大数据包走的是else流程。也就是说，本次真实写出去的数据 比 当前这个ByteBuf的可读取数据要小
                 * （也就说明，当前这个ByteBuf还没有被完全的写完。因此并不会通过调用『remove()』操作。
                 * 直到整个大数据包所有的内容都写出去了，那么这时if(readableBytes <= writtenBytes)才会为真执行『remove()』完成相关后续的操作）。
                 * 那么此时，会根据已经写出的字节数大小修改该ByteBuf的readerIndex索引值。
                 * 并且，如果该异步写操作的ChannelPromise是ChannelProgressivePromise对象并且注册了相应的progressiveListeners事件，
                 * 则该listener会得到回调。你可以通过该listener来观察到大数据包写出去的进度。
                 */
                if (writtenBytes != 0) {
                    buf.readerIndex(readerIndex + (int) writtenBytes);
                    progress(writtenBytes);
                }
                break;
            }
        }
        clearNioBuffers();
    }

    // Clear all ByteBuffer from the array so these can be GC'ed.
    // See https://github.com/netty/netty/issues/3837
    private void clearNioBuffers() {
        int count = nioBufferCount;
        if (count > 0) {
            nioBufferCount = 0;
            Arrays.fill(NIO_BUFFERS.get(), 0, count, null);
        }
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>将刷新链中的写请求对象消息放到nio buf数组中。#nioBufferCount和#nioBufferSize，将返回当前nio buf数组的长度
     *  和可读字节数
     * </p>
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     * <p>返回的nio buf将会被 {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)}方法重用 </p>
     * @param maxCount The maximum amount of buffers that will be added to the return value.
     *                 将添加到返回值的缓冲区的最大数量。
     * @param maxBytes A hint toward the maximum number of bytes to include as part of the return value. Note that this
     *                 value maybe exceeded because we make a best effort to include at least 1 {@link ByteBuffer}
     *                 in the return value to ensure write progress is made.
     *                 指向返回值中包含的最大字节数的提示。注意，这个返回值可能超过了这个值，
     *                 因为我们尽了最大的努力在返回值中包含至少1个{@link ByteBuffer}，以确保进行写操作。
     */
    public ByteBuffer[] nioBuffers(int maxCount, long maxBytes) {
        assert maxCount > 0;
        assert maxBytes > 0;
        long nioBufferSize = 0;
        int nioBufferCount = 0;
        // 获取通道Outbound缓存区线程本地的niobuf数组
        final InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        ByteBuffer[] nioBuffers = NIO_BUFFERS.get(threadLocalMap);
        Entry entry = flushedEntry;
        // 遍历刷新链，链上的写请求Entry的消息必须为ByteBuf
        while (isFlushedEntry(entry) && entry.msg instanceof ByteBuf) {
            if (!entry.cancelled) {
                // 在写请求没有取消的情况下，获取写请求消息buf，及buf的读索引，和可读字节数
                ByteBuf buf = (ByteBuf) entry.msg;
                final int readerIndex = buf.readerIndex();
                final int readableBytes = buf.writerIndex() - readerIndex;

                if (readableBytes > 0) {
                    if (maxBytes - readableBytes < nioBufferSize && nioBufferCount != 0) {
                        // 如果消息buf可读字节数+nioBufferSize大于整数的最大值，则跳出循环
                        // If the nioBufferSize + readableBytes will overflow maxBytes, and there is at least one entry
                        // we stop populate the ByteBuffer array. This is done for 2 reasons:
                        // 1. bsd/osx don't allow to write more bytes then Integer.MAX_VALUE with one writev(...) call
                        // and so will return 'EINVAL', which will raise an IOException. On Linux it may work depending
                        // on the architecture and kernel but to be safe we also enforce the limit here.
                        // 2. There is no sense in putting more data in the array than is likely to be accepted by the
                        // OS.
                        //
                        // See also:
                        // - https://www.freebsd.org/cgi/man.cgi?query=write&sektion=2
                        // - http://linux.die.net/man/2/writev
                        break;
                    }
                    nioBufferSize += readableBytes;
                    int count = entry.count;
                    if (count == -1) {
                        //noinspection ConstantValueVariableUse
                        entry.count = count = buf.nioBufferCount();
                    }
                    int neededSpace = min(maxCount, nioBufferCount + count);
                    if (neededSpace > nioBuffers.length) {
                        // 如果buf需求数量大于当前nio buf数组，则扩容nio数组为原来的2的指数级容量增长，直至大于neededSpace
                        nioBuffers = expandNioBufferArray(nioBuffers, neededSpace, nioBufferCount);
                        // 更新nio buf数组
                        NIO_BUFFERS.set(threadLocalMap, nioBuffers);
                    }
                    if (count == 1) {
                        ByteBuffer nioBuf = entry.buf;
                        if (nioBuf == null) {
                            // cache ByteBuffer as it may need to create a new ByteBuffer instance if its a derived buffer
                            // 缓存ByteBuffer，因为它可能需要创建一个新的ByteBuffer实例，如果它是一个派生的缓冲区
                            entry.buf = nioBuf = buf.internalNioBuffer(readerIndex, readableBytes);
                        }
                        nioBuffers[nioBufferCount++] = nioBuf;
                    } else {
                        // The code exists in an extra method to ensure the method is not too big to inline as this
                        // branch is not very likely to get hit very frequently.
                        // 代码存在于一个额外的方法中，以确保该方法不会太大而不能内联，因为这个分支不太可能经常被攻击。
                        nioBufferCount = nioBuffers(entry, buf, nioBuffers, nioBufferCount, maxCount);
                    }
                    if (nioBufferCount == maxCount) {
                        break;
                    }
                }
            }
            entry = entry.next;
        }
        this.nioBufferCount = nioBufferCount;
        this.nioBufferSize = nioBufferSize;

        return nioBuffers;
    }

    private static int nioBuffers(Entry entry, ByteBuf buf, ByteBuffer[] nioBuffers, int nioBufferCount, int maxCount) {
        ByteBuffer[] nioBufs = entry.bufs;
        if (nioBufs == null) {
            // cached ByteBuffers as they may be expensive to create in terms
            // of Object allocation
            entry.bufs = nioBufs = buf.nioBuffers();
        }
        for (int i = 0; i < nioBufs.length && nioBufferCount < maxCount; ++i) {
            ByteBuffer nioBuf = nioBufs[i];
            if (nioBuf == null) {
                break;
            } else if (!nioBuf.hasRemaining()) {
                continue;
            }
            nioBuffers[nioBufferCount++] = nioBuf;
        }
        return nioBufferCount;
    }

    /**
     * ByteBuffer数组扩容
     * @param array 原数组
     * @param neededSpace 期望的目标容量
     * @param size 原数组大小
     * @return
     */
    private static ByteBuffer[] expandNioBufferArray(ByteBuffer[] array, int neededSpace, int size) {
        int newCapacity = array.length;
        do {
            // double capacity until it is big enough
            // See https://github.com/netty/netty/issues/1890
            newCapacity <<= 1;

            if (newCapacity < 0) {
                throw new IllegalStateException();
            }

        } while (neededSpace > newCapacity);

        ByteBuffer[] newArray = new ByteBuffer[newCapacity];
        System.arraycopy(array, 0, newArray, 0, size);

        return newArray;
    }

    /**
     * Returns the number of {@link ByteBuffer} that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public int nioBufferCount() {
        return nioBufferCount;
    }

    /**
     * Returns the number of bytes that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     */
    public long nioBufferSize() {
        return nioBufferSize;
    }

    /**
     * Returns {@code true} if and only if {@linkplain #totalPendingWriteBytes() the total number of pending bytes} did
     * not exceed the write watermark of the {@link Channel} and
     * no {@linkplain #setUserDefinedWritability(int, boolean) user-defined writability flag} has been set to
     * {@code false}.
     * <p></p>
     * 缓冲区是否可写
     * true:当且仅当totalPendingWriteBytes方法的通道可写字节数，不超过通道的写watermark，同时
     * 用户没有使用#setUserDefinedWritability，设置可写标志为false
     */
    public boolean isWritable() {
        return unwritable == 0;
    }

    /**
     * Returns {@code true} if and only if the user-defined writability flag at the specified index is set to
     * {@code true}.
     */
    public boolean getUserDefinedWritability(int index) {
        return (unwritable & writabilityMask(index)) == 0;
    }

    /**
     * Sets a user-defined writability flag at the specified index.
     * 根据可写状态，设置用户定义的可写标志
     */
    public void setUserDefinedWritability(int index, boolean writable) {
        if (writable) {
            setUserDefinedWritability(index);
        } else {
            clearUserDefinedWritability(index);
        }
    }

    private void setUserDefinedWritability(int index) {
        final int mask = ~writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private void clearUserDefinedWritability(int index) {
        final int mask = writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private static int writabilityMask(int index) {
        if (index < 1 || index > 31) {
            throw new IllegalArgumentException("index: " + index + " (expected: 1~31)");
        }
        return 1 << index;
    }

    private void setWritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & ~1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    private void setUnwritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | 1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    private void fireChannelWritabilityChanged(boolean invokeLater) {
        final ChannelPipeline pipeline = channel.pipeline();
        if (invokeLater) {
            Runnable task = fireChannelWritabilityChangedTask;
            if (task == null) {
                fireChannelWritabilityChangedTask = task = new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelWritabilityChanged();
                    }
                };
            }
            channel.eventLoop().execute(task);
        } else {
            pipeline.fireChannelWritabilityChanged();
        }
    }

    /**
     * Returns the number of flushed messages in this {@link ChannelOutboundBuffer}.
     */
    public int size() {
        return flushed;
    }

    /**
     * Returns {@code true} if there are flushed messages in this {@link ChannelOutboundBuffer} or {@code false}
     * otherwise.
     */
    public boolean isEmpty() {
        return flushed == 0;
    }

    /**
     *
     * @param cause
     * @param notify false表示channel已经关闭，没必要在进行Writability notify了
     */
    void failFlushed(Throwable cause, boolean notify) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            inFail = true;
            for (;;) {
                if (!remove0(cause, notify)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

    /**
     * 关闭Outbound缓存区
     */
    void close(final Throwable cause, final boolean allowChannelOpen) {
        if (inFail) {
            // 已经刷新失败，则创建关闭任务线程，委托给事件循环执行
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause, allowChannelOpen);
                }
            });
            return;
        }

        inFail = true;

        if (!allowChannelOpen && channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        // 遍历未刷新的写请求，更新写任务失败
        try {
            Entry e = unflushedEntry;
            while (e != null) {
                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                // 更新通道待发送的字节数
                TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);

                if (!e.cancelled) {
                    // 如果写任务没有取消，则释放消息，更新任务状态为失败
                    ReferenceCountUtil.safeRelease(e.msg);
                    safeFail(e.promise, cause);
                }
                e = e.recycleAndGetNext();
            }
        } finally {
            inFail = false;
        }
        clearNioBuffers();
    }

    void close(ClosedChannelException cause) {
        close(cause, false);
    }

    private static void safeSuccess(ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as trySuccess(...) is expected to return
        // false.
        PromiseNotificationUtil.trySuccess(promise, null, promise instanceof VoidChannelPromise ? null : logger);
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    @Deprecated
    public void recycle() {
        // NOOP
    }

    public long totalPendingWriteBytes() {
        return totalPendingSize;
    }

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     * <p></p>
     * 返回直到通道不可写，通道Outbound缓冲区还可以写多少字节的数。如果通道不可写，则返回0
     */
    public long bytesBeforeUnwritable() {
        // HighWaterMark() 默认值64*1024
        long bytes = channel.config().getWriteBufferHighWaterMark() - totalPendingSize;
        // If bytes is negative we know we are not writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? bytes : 0;
        }
        return 0;
    }

    /**
     * Get how many bytes must be drained from the underlying buffer until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     * <p></p>
     * 获取直到通道可写，通道底层buf有多少字节数据需要发送。如果可写返回0
     */
    public long bytesBeforeWritable() {
        // HighWaterMark() 默认值32*1024
        long bytes = totalPendingSize - channel.config().getWriteBufferLowWaterMark();
        // If bytes is negative we know we are writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? 0 : bytes;
        }
        return 0;
    }

    /**
     * Call {@link MessageProcessor#processMessage(Object)} for each flushed message
     * in this {@link ChannelOutboundBuffer} until {@link MessageProcessor#processMessage(Object)}
     * returns {@code false} or there are no more flushed messages to process.
     * <p></p>
     * 当刷新消息时，调用消息处理器的处理消息方法#processMessage，处理每个消息，直到#processMessage方法
     *  返回false，或没有消息需要刷新
     */
    public void forEachFlushedMessage(MessageProcessor processor) throws Exception {
        if (processor == null) {
            throw new NullPointerException("processor");
        }

        Entry entry = flushedEntry;
        if (entry == null) {
            return;
        }

        do {
            if (!entry.cancelled) {
                if (!processor.processMessage(entry.msg)) {
                    return;
                }
            }
            entry = entry.next;
        } while (isFlushedEntry(entry));
    }

    private boolean isFlushedEntry(Entry e) {
        return e != null && e != unflushedEntry;
    }

    public interface MessageProcessor {
        /**
         * Will be called for each flushed message until it either there are no more flushed messages or this
         * method returns {@code false}.
         */
        boolean processMessage(Object msg) throws Exception;
    }

    static final class Entry {
        /**
         * 由于 Entry 使用比较频繁，会频繁的创建和销毁，这里使用了 Entry 的对象池，创建的时候从缓存中获取，销毁时回收。
         */
        private static final ObjectPool<Entry> RECYCLER = ObjectPool.newPool(new ObjectCreator<Entry>() {
            @Override
            public Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        });

        private final Handle<Entry> handle;
        Entry next;
        // 原始消息对象的引用;
        Object msg;
        ByteBuffer[] bufs;
        ByteBuffer buf;
        // 该异步写操作的ChannelPromise（用于在完成真是的网络层write后去标识异步操作的完成以及回调已经注册到该promise上的listeners）;
        ChannelPromise promise;
        long progress;
        // 待发送数据包的总大小（该属性与pendingSize的区别在于，如果是待发送的是FileRegion数据对象，
        // 则pengdingSize中只有对象内存的大小，即真实的数据大小被记录为0；但total属性则是会记录FileRegion中数据大小，
        // 并且total属性是不包含对象内存大小，仅仅是对数据本身大小的记录）;
        long total;
        // 记录有该ByteBuf or ByteBufs 中待发送数据大小 和 对象本身内存大小 的累加和;
        // pendingSize属性记录的不单单是写请求数据的大小，记录的是这个写请求对象的大小。
        int pendingSize;
        // 写消息数据个数的记录（如果写消息数据是个数组的话，该值会大于1）
        int count = -1;
        boolean cancelled;

        private Entry(Handle<Entry> handle) {
            this.handle = handle;
        }

        static Entry newInstance(Object msg, int size, long total, ChannelPromise promise) {
            // 从 Recycler 中获取 Entry 对象，如果获取不到则使用 Recycler.newObject() 方法创建一个 Entry 对象，
            // 调用 newObject() 方法在 Recycler.get() 方法中
            // 从 Recycler 中可以看出，Entry 对象是存储在 Stack 中。如果 Stack 中没有可用的 Stack，则调用 newObject() 方法创建。
            Entry entry = RECYCLER.get();
            entry.msg = msg;
            entry.pendingSize = size + CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD;
            entry.total = total;
            entry.promise = promise;
            return entry;
        }

        int cancel() {
            if (!cancelled) {
                cancelled = true;
                int pSize = pendingSize;

                // release message and replace with an empty buffer
                ReferenceCountUtil.safeRelease(msg);
                msg = Unpooled.EMPTY_BUFFER;

                pendingSize = 0;
                total = 0;
                progress = 0;
                bufs = null;
                buf = null;
                return pSize;
            }
            return 0;
        }

        /**
         * 把 Entry 中成员变量全部初始化。然后在调用 handle.recycle() 方法
         * Recycler.recycle() 方法又把 Entry 对象压进 stack 中。
         */
        void recycle() {
            next = null;
            bufs = null;
            buf = null;
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
            count = -1;
            cancelled = false;
            handle.recycle(this);
        }

        Entry recycleAndGetNext() {
            Entry next = this.next;
            recycle();
            return next;
        }
    }
}
