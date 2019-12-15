/*
 * Copyright 2017 The Netty Project
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
package io.netty.buffer;

public interface ByteBufAllocatorMetric {
    /**
     * Returns the number of bytes of heap memory used by a {@link ByteBufAllocator} or {@code -1} if unknown.
     * <p></p>
     * 返回{@link ByteBufAllocator}使用的堆内存字节数或{@code -1}(如果未知)。
     */
    long usedHeapMemory();

    /**
     * Returns the number of bytes of direct memory used by a {@link ByteBufAllocator} or {@code -1} if unknown.
     */
    long usedDirectMemory();
}
