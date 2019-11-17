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
package io.netty.util.internal;

import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;

import static io.netty.util.internal.PriorityQueueNode.INDEX_NOT_IN_QUEUE;

/**
 * A priority queue which uses natural ordering of elements. Elements are also required to be of type
 * {@link PriorityQueueNode} for the purpose of maintaining the index in the priority queue.
 *
 * <p>优先队列，使用元素的自然顺序。为了在优先级队列中维护索引，队列中的元素需要是{@link PriorityQueueNode}类型。
 * <p>具体实现是小顶堆，它是特殊的二叉树，要求父节点的值不能大于子节点
 *
 * @param <T> The object that is maintained in the queue.
 */
public final class DefaultPriorityQueue<T extends PriorityQueueNode> extends AbstractQueue<T>
                                                                     implements PriorityQueue<T> {
    private static final PriorityQueueNode[] EMPTY_ARRAY = new PriorityQueueNode[0];
    // 大小比较器
    private final Comparator<T> comparator;
    // 负责任务存储的数组–优先级队列实现的小顶堆的结构
    private T[] queue;
    // 小顶堆的大小
    private int size;

    @SuppressWarnings("unchecked")
    public DefaultPriorityQueue(Comparator<T> comparator, int initialSize) {
        this.comparator = ObjectUtil.checkNotNull(comparator, "comparator");
        queue = (T[]) (initialSize != 0 ? new PriorityQueueNode[initialSize] : EMPTY_ARRAY);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof PriorityQueueNode)) {
            return false;
        }
        PriorityQueueNode node = (PriorityQueueNode) o;
        return contains(node, node.priorityQueueIndex(this));
    }

    @Override
    public boolean containsTyped(T node) {
        return contains(node, node.priorityQueueIndex(this));
    }

    @Override
    public void clear() {
        for (int i = 0; i < size; ++i) {
            T node = queue[i];
            if (node != null) {
                node.priorityQueueIndex(this, INDEX_NOT_IN_QUEUE);
                queue[i] = null;
            }
        }
        size = 0;
    }

    /**
     * 只有当确定节点不会被重新插入到这个或任何其他{@link PriorityQueue}中，
     * 且已知{@link PriorityQueue}本身在调用后将被垃圾收集时，才应该使用此方法。
     */
    @Override
    public void clearIgnoringIndexes() {
        size = 0;
    }

    @Override
    public boolean offer(T e) {
        if (e.priorityQueueIndex(this) != INDEX_NOT_IN_QUEUE) {
            throw new IllegalArgumentException("e.priorityQueueIndex(): " + e.priorityQueueIndex(this) +
                    " (expected: " + INDEX_NOT_IN_QUEUE + ") + e: " + e);
        }

        // Check that the array capacity is enough to hold values by doubling capacity.
        // 通过加倍容量来检查数组容量是否足够容纳值。
        if (size >= queue.length) {
            // Use a policy which allows for a 0 initial capacity. Same policy as JDK's priority queue, double when
            // "small", then grow by 50% when "large".
            // 使用允许初始容量为0的策略。与JDK的优先队列相同的策略，在“小”时加倍，然后在“大”时增长50%。
            queue = Arrays.copyOf(queue, queue.length + ((queue.length < 64) ?
                                                         (queue.length + 2) :
                                                         (queue.length >>> 1)));
        }

        // 当插入节点大于父节点值的时候就停止交换.
        bubbleUp(size++, e);
        return true;
    }

    /**
     * 立即获取队列头元素
     */
    @Override
    public T poll() {
        if (size == 0) {
            return null;
        }
        T result = queue[0];
        result.priorityQueueIndex(this, INDEX_NOT_IN_QUEUE);

        T last = queue[--size];
        queue[size] = null;
        if (size != 0) { // Make sure we don't add the last element back.
            bubbleDown(0, last);
        }

        return result;
    }

    @Override
    public T peek() {
        return (size == 0) ? null : queue[0];
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean remove(Object o) {
        final T node;
        try {
            node = (T) o;
        } catch (ClassCastException e) {
            return false;
        }
        return removeTyped(node);
    }

    @Override
    public boolean removeTyped(T node) {
        int i = node.priorityQueueIndex(this);
        if (!contains(node, i)) {
            return false;
        }

        node.priorityQueueIndex(this, INDEX_NOT_IN_QUEUE);
        if (--size == 0 || size == i) {
            // If there are no node left, or this is the last node in the array just remove and return.
            // 如果没有节点了，或者这是数组中的最后一个节点，那么就删除并返回，不需要重新堆排序。
            queue[i] = null;
            return true;
        }

        // Move the last element where node currently lives in the array.
        // 将数组最后一个元素移动到node节点当前所在的位置。
        T moved = queue[i] = queue[size];
        queue[size] = null;
        // priorityQueueIndex will be updated below in bubbleUp or bubbleDown

        // Make sure the moved node still preserves the min-heap properties.
        // 如果尾节点大于node，则向下寻找位置
        if (comparator.compare(node, moved) < 0) {
            bubbleDown(i, moved);
        } else {
            // 如果尾节点小于node，则向上寻找位置
            bubbleUp(i, moved);
        }
        return true;
    }

    @Override
    public void priorityChanged(T node) {
        int i = node.priorityQueueIndex(this);
        if (!contains(node, i)) {
            return;
        }

        // Preserve the min-heap property by comparing the new priority with parents/children in the heap.
        // 通过比较新优先级与堆中的父/子优先级，保留min-heap属性。
        // 头节点优先级改变，直接向下重排序
        if (i == 0) {
            bubbleDown(i, node);
        } else {
            // Get the parent to see if min-heap properties are violated.
            // 查看node的父节点判断是否违反了min-heap属性。
            int iParent = (i - 1) >>> 1;
            T parent = queue[iParent];
            // 如果父节点大于node，说明node的优先级比原来减小了，则向上寻找位置
            if (comparator.compare(node, parent) < 0) {
                bubbleUp(i, node);
            } else {
                // 如果父节点小于node，说明node的优先级比原来增大了，则向下寻找位置
                bubbleDown(i, node);
            }
        }
    }

    @Override
    public Object[] toArray() {
        return Arrays.copyOf(queue, size);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <X> X[] toArray(X[] a) {
        if (a.length < size) {
            return (X[]) Arrays.copyOf(queue, size, a.getClass());
        }
        System.arraycopy(queue, 0, a, 0, size);
        if (a.length > size) {
            a[size] = null;
        }
        return a;
    }

    /**
     * This iterator does not return elements in any particular order.
     */
    @Override
    public Iterator<T> iterator() {
        return new PriorityQueueIterator();
    }

    private final class PriorityQueueIterator implements Iterator<T> {
        private int index;

        @Override
        public boolean hasNext() {
            return index < size;
        }

        @Override
        public T next() {
            if (index >= size) {
                throw new NoSuchElementException();
            }

            return queue[index++];
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }
    }

    private boolean contains(PriorityQueueNode node, int i) {
        return i >= 0 && i < size && node.equals(queue[i]);
    }

    /**
     * poll 或者 remove 一个节点时，需要将最后一个节点插入到被删除节点的位置
     * 然后从插入的位置开始重排序堆，保证满足父节点的值不能大于子节点
     * @param k 已经移除的节点的indedx
     * @param node 原来尾结点
     */
    private void bubbleDown(int k, T node) {
        final int half = size >>> 1;
        // 通过循环，保证父节点的值不能小于子节点。
        while (k < half) {
            // Compare node to the children of index k.
            // 左子节点, 相当于 (k * 2) + 1
            int iChild = (k << 1) + 1;
            T child = queue[iChild];

            // Make sure we get the smallest child to compare against.
            // 右子节点, 相当于 (k * 2) + 2
            int rightChild = iChild + 1;
            // 如果左子节点元素值大于右子节点元素值，那么右子节点才是较小值的子节点。
            // 就要将iChild与child值重新赋值
            if (rightChild < size && comparator.compare(child, queue[rightChild]) > 0) {
                child = queue[iChild = rightChild];
            }
            // If the bubbleDown node is less than or equal to the smallest child then we will preserve the min-heap
            // property by inserting the bubbleDown node here.
            // 如果父节点元素值小于较小的子节点元素值，那么就跳出循环
            if (comparator.compare(node, child) <= 0) {
                break;
            }

            // Bubble the child up.
            // 否则，父节点元素就要和子节点进行交换
            queue[k] = child;
            child.priorityQueueIndex(this, k);

            // Move down k down the tree for the next iteration.
            k = iChild;
        }

        // We have found where node should live and still satisfy the min-heap property, so put it in the queue.
        queue[k] = node;
        node.priorityQueueIndex(this, k);
    }

    /**
     * 1 当插入节点大于父节点值的时候就停止交换.
     * 2 当插入节点小于父节点的值的时候就进行交换,然后迭代检查,直到满足步骤1;
     * @param k 节点插入的index
     * @param node 插入的节点
     */
    private void bubbleUp(int k, T node) {
        // 当k==0时，就到了堆二叉树的根节点了，跳出循环
        while (k > 0) {
            // 父节点位置坐标, 相当于(k - 1) / 2
            int iParent = (k - 1) >>> 1;
            // 获取父节点位置元素
            T parent = queue[iParent];

            // If the bubbleUp node is less than the parent, then we have found a spot to insert and still maintain
            // min-heap properties.
            // 如果node元素大于父节点位置元素，满足条件，那么跳出循环
            // 因为是从小到大排序的。
            if (comparator.compare(node, parent) >= 0) {
                break;
            }

            // Bubble the parent down.
            // 否则就将父节点元素存放到k位置
            queue[k] = parent;
            parent.priorityQueueIndex(this, k);

            // Move k up the tree for the next iteration.
            // 重新赋值k，寻找元素node应该插入到堆二叉树的那个节点
            k = iParent;
        }

        // We have found where node should live and still satisfy the min-heap property, so put it in the queue.
        // 我们已经找到了节点应该存在的位置，并且仍然满足min-heap属性，因此将它放到队列中。
        queue[k] = node;
        node.priorityQueueIndex(this, k);
    }
}
