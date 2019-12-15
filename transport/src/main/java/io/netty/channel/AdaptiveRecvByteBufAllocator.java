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

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 * <p>
 * 根据每次实际读取的反馈动态的增大或缩小下次预测所需分配的缓冲区大小数值。
 * 如果读取的实际字节数正好等于本次预测的缓冲区可写入字节数，则扩大预测下次需要分配的缓冲区大小；
 * 如果连续两次实际读取的字节数没有填满本次预测的缓冲区可写入字节数，则缩小预测值。
 * 其他情况则不改变预测值。
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;	// 默认的最小缓冲区大小
    static final int DEFAULT_INITIAL = 1024;// 第一次分配的缓冲区大小
    static final int DEFAULT_MAXIMUM = 65536;// 最大缓冲区大小

    // 如果上次预测的缓冲区太小，造成实际读取字节数大于上次预测的缓冲区大小的话，这里用于指定应该增加的步进大小
    private static final int INDEX_INCREMENT = 4;
    // 这个和上面相反，如果上次预测的缓冲区太大，造成实际读取字节数小于上次预测的缓冲区大小的话，这里用于指定应该减少的步进大小
    private static final int INDEX_DECREMENT = 1;

    // 缓冲区大小序列，SIZE_TABLE数组从小到大保存了所有可以分配的缓冲区大小，上面说的步进其实就是SIZE_TABLE数组下标，下标越大对应的缓冲区越大
    private static final int[] SIZE_TABLE;

    // 下面静态初始化块对SIZE_TABLE进行初始化，
    // 初始化之后为：
    // [16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224,
    //  240, 256, 272, 288, 304, 320, 336, 352, 368, 384, 400, 416,
    //  432, 448, 464, 480, 496, 512, 1024, 2048, 4096, 8192, 16384,
    //  32768, 65536, 131072, 262144, 524288, 1048576, 2097152,
    //  4194304, 8388608, 16777216, 33554432, 67108864, 134217728,
    //  268435456, 536870912, 1073741824]
    // 小于512之前因为缓冲区容量较小，降低步进值，采用每次增加16字节进行分配，
    // 大于512之后则进行加倍分配每次分配缓冲区容量较大，为了减少动态扩张的频率
    // 采用加倍的快速步进分配。
    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    /**
     * AdaptiveRecvByteBufAllocator还实现了如下方法，根据预测的大小，
     * 使用二分查找法从SIZE_TABLE中获取预测缓冲区大小对应的最接近的一个值，用于缓冲区的实际分配
     */
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        // 构造函数的三个参数分别对应上面AdaptiveRecvByteBufAllocator
        // 最小缓冲区内存规范为SIZE_TABLE中数值的对应的下标、
        // 最大缓冲区内存规范为SIZE_TABLE
        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            // 获取默认缓冲大小规范为SIZE_TABLE中数值对应的小标, 初始为1024
            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        /**
         *  Unsafe.read函数每次循环迭代调用该方法，传入每次循环迭代实际读的字节数量
         *
         * @param bytes The number of bytes from the previous read operation. This may be negative if an read error
         * occurs. If a negative value is seen it is expected to be return on the next call to
         * {@link #lastBytesRead()}. A negative value will signal a termination condition enforced externally
         * to this class and is not required to be enforced in {@link #continueReading()}.
         *              <p></p>
         */
        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            // 如果读取字节数正好等于读取之前缓冲区可供写入的字节数的话，调用record方法调整下次分配的缓冲区大小
            if (bytes == attemptedBytesRead()) {
                record(bytes);
            }
            // 调用父类方法进行统计计数，即MaxMessageHandle.lastBytesRead
            super.lastBytesRead(bytes);
        }

        /**
         * 返回此次预测的下次需分配的缓冲区大小
         */
        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        /**
         * 根据实际读取的字节数，预测下次应该分配的缓冲区大小
         */
        private void record(int actualReadBytes) {
            // 尝试减小下次分配的缓冲区大小，如果此次实际读取的字节数小于减小之后的值，则减小下标，下次分配的缓冲区将减少
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                // decreaseNow则实现上面注释说明的，要求连续两次读取实际字节数恰好等于当前分配缓冲区剩余可用大小才进行所缩小调整
                if (decreaseNow) {
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                // 下面的else if则表明实际读取的大小大于上次预测的缓冲区大小，需要扩大预测的数值，扩大 不要求 满足连续两次都大于预测值。
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        /**
         * Unsafe.read方法所有循环迭代结束，调用测方法进行一次预测，为下次进入Unsafe.read方法分配缓冲区做准备
         * 下次进入Unsafe.read方法会对Handle的一些统计数值进行复位，但是不会修改预测的nextReceiveBufferSize的值。
         */
        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    // 三个字段
    // 最小缓冲区内存规范为SIZE_TABLE中数值的对应的下标
    private final int minIndex;
    // 最大缓冲区内存规范为SIZE_TABLE中数值的对应的下标
    private final int maxIndex;
    // 默认的分配的缓冲区大小
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        // 上面列出的默认值，分别为64 1024 65536
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
