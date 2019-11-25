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

import io.netty.util.IntSupplier;

/**
 * Select strategy interface.
 *
 * Provides the ability to control the behavior of the select loop. For example a blocking select
 * operation can be delayed or skipped entirely if there are events to process immediately.
 *
 * <p>
 * 选择策略接口提供了控制select loop选择循环行为的方法。
 * 比如，如果有事件需要立刻处理，则可以阻塞选择操作或完全直接跳过。
 */
public interface SelectStrategy {

    /**
     * Indicates a blocking select should follow.
     * 阻塞选择操作
     */
    int SELECT = -1;
    /**
     * Indicates the IO loop should be retried, no blocking select to follow directly.
     * 如果没有选择操作阻塞，预示着应该重试IO事件循环，处理IO事件
     */
    int CONTINUE = -2;
    /**
     * Indicates the IO loop to poll for new events without blocking.
     * IO循环在不阻塞的情况下轮询新事件。
     */
    int BUSY_WAIT = -3;

    /**
     * The {@link SelectStrategy} can be used to steer the outcome of a potential select
     * call.
     * <p>选择策略可以用于控制潜在的选择操作结果</p>
     *
     * @param selectSupplier The supplier with the result of a select result.
     * @param hasTasks true if tasks are waiting to be processed.
     * @return {@link #SELECT} if the next step should be blocking select {@link #CONTINUE} if
     *         the next step should be to not select but rather jump back to the IO loop and try
     *         again. Any value >= 0 is treated as an indicator that work needs to be done.
     *         如果返回结果为-1，则下一步应该阻塞选择操作，如果返回结果为-2，则下一步应该调回IO事件循环，处理
     *          IO事件，而不是继续执行选择操作，返回值大于0，表示需要有工作要做，即注册到选择器的选择通道有IO事件
     *          就绪。
     */
    int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception;
}
