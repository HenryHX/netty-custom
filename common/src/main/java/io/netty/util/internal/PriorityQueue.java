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
package io.netty.util.internal;

import java.util.Queue;

public interface PriorityQueue<T> extends Queue<T> {
    /**
     * Same as {@link #remove(Object)} but typed using generics.
     * <p>
     * 与{@link #remove(Object)}相同，但使用泛型类型。
     */
    boolean removeTyped(T node);

    /**
     * Same as {@link #contains(Object)} but typed using generics.
     */
    boolean containsTyped(T node);

    /**
     * Notify the queue that the priority for {@code node} has changed. The queue will adjust to ensure the priority
     * queue properties are maintained.
     * <p>
     * 通知队列{@code node}的优先级已更改。队列将进行调整，以确保优先队列属性得到维护。
     * @param node An object which is in this queue and the priority may have changed.
     */
    void priorityChanged(T node);

    /**
     * Removes all of the elements from this {@link PriorityQueue} without calling
     * {@link PriorityQueueNode#priorityQueueIndex(DefaultPriorityQueue)} or explicitly removing references to them to
     * allow them to be garbage collected. This should only be used when it is certain that the nodes will not be
     * re-inserted into this or any other {@link PriorityQueue} and it is known that the {@link PriorityQueue} itself
     * will be garbage collected after this call..
     * <p>
     * 删除{@link PriorityQueue}中的所有元素，而不调用{@link PriorityQueueNode#priorityQueueIndex(DefaultPriorityQueue)}
     * 或显式删除对它们的引用，以允许对它们进行垃圾收集。
     * <p>
     * 只有当确定节点不会被重新插入到这个或任何其他{@link PriorityQueue}中，
     * 且已知{@link PriorityQueue}本身在调用后将被垃圾收集时，才应该使用此方法。
     */
    void clearIgnoringIndexes();
}
