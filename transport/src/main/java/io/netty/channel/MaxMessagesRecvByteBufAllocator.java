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

/**
 * {@link RecvByteBufAllocator} that limits the number of read operations that will be attempted when a read operation
 * is attempted by the event loop.
 * <p>
 *     {@link RecvByteBufAllocator}，event loop尝试读操作时限制尝试的读操作的数量。
 * </p>
 * <p>详细解释：</p>
 * MaxMessagesRecvByteBufAllocator接口主要功能可以见其注释，用于限制每次read函数被调用时从channel中读取的次数，
 * 因为预测的字节数可能较小，read函数使用循环从channel中读取数据，如果数据量较多的话则可能需要迭代多次，
 * 这里限制的读取次数就是迭代的次数，限制次数之后，可能进入read函数之后，并不会读取channel中的全部数据，剩下的数据会在下次进入read函数再次被读取。
 *
 * read函数是在Unsafe中实现的，就是read函数其实是通过循环读取channel中的数据的。
 */
public interface MaxMessagesRecvByteBufAllocator extends RecvByteBufAllocator {
    /**
     * Returns the maximum number of messages to read per read loop.
     * a {@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object) channelRead()} event.
     * If this value is greater than 1, an event loop might attempt to read multiple times to procure multiple messages.
     * <p></p>
     * {@link RecvByteBufAllocator}，它限制event loop尝试读操作时尝试的读操作的数量。返回每次要读取的最大消息数。
     * 一个{@link ChannelInboundHandler#channelRead(ChannelHandlerContext, Object) channelRead()事件。
     * 如果该值大于1，则event loop可能尝试多次读取以获取多个消息。
     */
    int maxMessagesPerRead();

    /**
     * Sets the maximum number of messages to read per read loop.
     * If this value is greater than 1, an event loop might attempt to read multiple times to procure multiple messages.
     */
    MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead);
}
