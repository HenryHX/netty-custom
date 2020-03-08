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

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import sun.nio.ch.SelectorImpl;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;

import java.nio.channels.spi.AbstractSelectionKey;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 * <p>NioEventLoop是一个SingleThreadEventLoop的实现, 将每个Channel注册到NIO Selector并执行multiplexing(多路复用).</p>
 *
 * <p>NioEventLoop时用单线程来处理NIO任务的!</p>
 *
 * <p>NioEventLoop是属于netty的基于NIO的event loop实现类</p>
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEY_SET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    // 标识Selector空轮询的阈值，当超过这个阈值的话则需要重构Selector。
    // 如果有设置系统属性”io.netty.selectorAutoRebuildThreshold”，
    //      并且该属性值大于MIN_PREMATURE_SELECTOR_RETURNS(即，3)，那么该属性值就为阈值；
    //      如果该属性值小于MIN_PREMATURE_SELECTOR_RETURNS(即，3)，那么阈值为0；
    //      如果没有设置系统属性”io.netty.selectorAutoRebuildThreshold”，那么阈值为512，即，默认情况下阈值为512。
    //          也就是当Selector连续执行了512次空轮询后，Netty就会进行Selector的重建操作，即rebuildSelector()操作。
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    /**
     * 在事件循环里用于选择策略(selectStrategy)
     * 获取选择操作返回值，用于判断注册到当前选择器的选择通道是否有IO事件就绪
     */
    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    // 解决在java6 中 NIO Selector.open()可能抛出NPE异常的问题。
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String bugLevel = SystemPropertyUtil.get(key);
        if (bugLevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        // 在事件循环时解决JDK NIO类库的epoll bug，先设置好SELECTOR_AUTO_REBUILD_THRESHOLD，即selector空轮询的阈值
        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEY_SET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    private Selector selector;
    private Selector unwrappedSelector;

    /**
     * 和selector的{@link SelectedSelectionKeySetSelector#selectionKeys}、
     * unwrappedSelector的{@link SelectorImpl#publicSelectedKeys}、
     * unwrappedSelector的{@link SelectorImpl#publicKeys}是一个对象
     */
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     * <p>
     * 一个原子类的Boolean标识，用于控制决定一个阻塞着的Selector.select是否应该结束它的select()选择操作。
     * 在我们的例子中，我们为select方法使用了超时，而select方法将在此期间阻塞，除非唤醒。
     */
    private final AtomicBoolean wakenUp = new AtomicBoolean();
    private volatile long nextWakeupTime = Long.MAX_VALUE;

    private final SelectStrategy selectStrategy;

    /**
     * 在事件循环中期待用于处理I/O操作时间的百分比。默认为50%。
     * 也就是说，在事件循环中默认情况下用于处理I/O操作的时间和用于处理任务的时间百分比都为50%，
     * 即，用于处理I/O操作的时间和用于处理任务的时间时一样的。用户可以根据实际情况来修改这个比率。
     */
    private volatile int ioRatio = 50;
    private int cancelledKeys;
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler,
                 EventLoopTaskQueueFactory queueFactory) {
        super(parent, executor, false, newTaskQueue(queueFactory), newTaskQueue(queueFactory),
                rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        provider = selectorProvider;
        // 开启Selector，构造SelectorTuple实例，SelectorTuple是一个封装了原始selector对象和封装后selector对象
        // (即，SelectedSelectionKeySetSelector对象)的类
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    private static Queue<Runnable> newTaskQueue(
            EventLoopTaskQueueFactory queueFactory) {
        if (queueFactory == null) {
            return newTaskQueue0(DEFAULT_MAX_PENDING_TASKS);
        }
        return queueFactory.newTaskQueue(DEFAULT_MAX_PENDING_TASKS);
    }

    private static final class SelectorTuple {
        final Selector unwrappedSelector;
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        if (DISABLE_KEY_SET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        if (!(maybeSelectorImplClass instanceof Class) ||
            // ensure the current selector implementation is what we can instrument.
            !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        // 通过反射机制将程序构建的SelectedSelectionKeySet对象给设置到了Selector内部的selectedKeys、publicSelectedKeys属性。
        // 这使Selector中所有对selectedKeys、publicSelectedKeys的操作实际上就是对SelectedSelectionKeySet的操作。
        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;
        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    if (PlatformDependent.javaVersion() >= 9 && PlatformDependent.hasUnsafe()) {
                        // Let us try to use sun.misc.Unsafe to replace the SelectionKeySet.
                        // This allows us to also do this in Java9+ without any extra flags.
                        long selectedKeysFieldOffset = PlatformDependent.objectFieldOffset(selectedKeysField);
                        long publicSelectedKeysFieldOffset =
                                PlatformDependent.objectFieldOffset(publicSelectedKeysField);

                        if (selectedKeysFieldOffset != -1 && publicSelectedKeysFieldOffset != -1) {
                            PlatformDependent.putObject(
                                    unwrappedSelector, selectedKeysFieldOffset, selectedKeySet);
                            PlatformDependent.putObject(
                                    unwrappedSelector, publicSelectedKeysFieldOffset, selectedKeySet);
                            return null;
                        }
                        // We could not retrieve the offset, lets try reflection as last-resort.
                    }

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        return newTaskQueue0(maxPendingTasks);
    }

    private static Queue<Runnable> newTaskQueue0(int maxPendingTasks) {
        // This event loop never calls takeTask()
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        if (inEventLoop()) {
            register0(ch, interestOps, task);
        } else {
            try {
                // Offload to the EventLoop as otherwise java.nio.channels.spi.AbstractSelectableChannel.register
                // may block for a long time while trying to obtain an internal lock that may be hold while selecting.
                submit(new Runnable() {
                    @Override
                    public void run() {
                        register0(ch, interestOps, task);
                    }
                }).sync();
            } catch (InterruptedException ignore) {
                // Even if interrupted we did schedule it so just mark the Thread as interrupted.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void register0(SelectableChannel ch, int interestOps, NioTask<?> task) {
        try {
            ch.register(unwrappedSelector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop. Value range from 1-100.
     * The default value is {@code 50}, which means the event loop will try to spend the same amount of time for I/O
     * as for non-I/O tasks. The lower the number the more time can be spent on non-I/O tasks. If value set to
     * {@code 100}, this feature will be disabled and event loop will not attempt to balance I/O and non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    @Override
    public int registeredChannels() {
        return selector.keys().size() - cancelledKeys;
    }

    /**
     * 如果经过检测，我们已经确认发生了“epoll-bug”，这时我们就需要进行Selector的重构操作;
     *
     * 重构操作主要的流程：
     * 首先，通过openSelector()先构造一个新的SelectorTuple。
     * 然后，遍历oldSelector中的所有SelectionKey，依次判断其有效性，如果有效则将其重新注册到新的Selector上，
     *      并将旧的SelectionKey执行cancel操作，进行相关的数据清理，以便最后oldSelector好进行关闭。
     *      在将所有的SelectionKey数据移至新的Selector后，将newSelectorTuple的selector和unwrappedSelector赋值给相应的成员属性。
     * 最后，调用oldSelector.close()关闭旧的Selector以进行资源的释放。
     */
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        for (SelectionKey key: oldSelector.keys()) {
            Object a = key.attachment();
            try {
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                key.cancel();
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
        }
    }

    /**
     * NioEventLoop的事件循环主要完成下面几件事：
     * ① 根据当前NioEventLoop中是否有待完成的任务得出select策略，进行相应的select操作
     * ② 处理select操作得到的已经准备好处理的I/O事件，以及处理提交到当前EventLoop的任务(包括定时和周期任务)。
     * ③ 如果NioEventLoop所在线程执行了关闭操作
     */
    @Override
    protected void run() {
        for (;;) {
            try {
                try {
                    // ① 根据当前NioEventLoop中是否有待完成的任务得出select策略，进行相应的select操作
                    // 如果当前的EventLoop中有待处理的任务，那么会调用selectSupplier.get()方法，
                    //      也就是最终会调用Selector.selectNow()方法，并清空selectionKeys。Selector.selectNow()方法不会发生阻塞，
                    //      如果没有一个channel(即，该channel注册的事件发生了)被选择也会立即返回0，否则返回就绪I/O事件的个数。
                    // 如果当前的EventLoop中没有待处理的任务，那么返回’SelectStrategy.SELECT(即，-1)’,堵塞select。

                    switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    case SelectStrategy.CONTINUE:
                        continue;

                    case SelectStrategy.BUSY_WAIT:
                        // fall-through to SELECT since the busy-wait is not supported with NIO

                    case SelectStrategy.SELECT:
                        // 如果‘selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())’操作返回的是一个>0的值，
                        //      则说明有就绪的的I/O事件待处理，则直接进入流程②。
                        // 否则，如果返回的是’SelectStrategy.SELECT’则进行select(wakenUp.getAndSet(false))操作：
                        //      首先先通过自旋锁(自旋 + CAS)方式获得wakenUp当前的标识，并再将wakenUp标识设置为false。
                        //      将wakenUp作为参数传入select(boolean oldWakenUp)方法中，注意这个select方法不是JDK NIO的Selector.select方法，
                        //      是NioEventLoop类自己实现的一个方法，只是方法名一样而已。
                        //      NioEventLoop的这个select方法还做了一件很重要的时，就是解决“JDK NIO类库的epoll bug”问题。
                        select(wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)
                        //
                        // However, there is a race condition in this approach.
                        // The race condition is triggered when 'wakenUp' is set to
                        // true too early.
                        //
                        // 'wakenUp' is set to true too early if:
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        //
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).

                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:
                    }
                } catch (IOException e) {
                    // If we receive an IOException here its because the Selector is messed up. Let's rebuild
                    // the selector and retry. https://github.com/netty/netty/issues/8566
                    rebuildSelector0();
                    handleLoopException(e);
                    continue;
                }

                // ② 处理select操作得到的已经准备好处理的I/O事件，以及处理提交到当前EventLoop的任务(包括定时和周期任务)。

                // 首先先将成员变量cancelledKeys和needsToSelectAgain重置，即，cancelledKeys置为0，needsToSelectAgain置为false
                cancelledKeys = 0;
                needsToSelectAgain = false;
                // ioRatio在事件循环中期待用于处理I/O操作时间的百分比。默认为50%。
                // 也就是说，在事件循环中默认情况下用于处理I/O操作的时间和用于处理任务的时间百分比都为50%，即，用于处理I/O操作的时间和用于处理任务的时间时一样的。
                final int ioRatio = this.ioRatio;
                if (ioRatio == 100) {
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        runAllTasks();
                    }
                } else {
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        // 当ioRatio不为100%时，我们假设在事件循环中用于处理任务时间的百分比为taskRatio，I/O操作的时间为ioTime，处理任务的时间为taskTime，求taskTime：
                        // ioTime/taskTime = ioRatio/taskRatio; 并且 ioRatio + taskRatio = 100;
                        // 带入，ioTime/taskTime = ioRatio/(100-ioRatio); ==> taskTime = ioTime*(100 - ioRatio) / ioRatio;
                        // 所以runAllTasks(ioTime * (100 - ioRatio) / ioRatio);传入的参数就为可用于运行任务的时间。
                        final long ioTime = System.nanoTime() - ioStartTime;
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            // ③ 如果NioEventLoop所在线程执行了关闭操作
            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        if (selectedKeys != null) {
            processSelectedKeysOptimized();
        } else {
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    /**
     * SelectionKey 取消逻辑
     * <p>
     *      设置{@link AbstractSelectionKey#valid}为false
     *      <p>
     *      {@link AbstractSelectionKey#cancel()} 触发 ((AbstractSelector)selector()).cancel(this);
     *      <p>
     *      {@link AbstractSelector#cancel(java.nio.channels.SelectionKey)} Selector的cancelledKeys.add(k);
     *      <p>
     *
     * needsToSelectAgain = true时调用{@link SelectorImpl#selectNow()}selector.selectNow()清除无效的key
     * <p>
     * 具体的清除逻辑如下：
     * <p>
     * {@link sun.nio.ch.WindowsSelectorImpl#doSelect}
     * <p>
     * {@link sun.nio.ch.SelectorImpl#processDeregisterQueue} 处理this.cancelledKeys()，每个key执行以下方法
     * <p>
     * {@link sun.nio.ch.WindowsSelectorImpl#implDereg} implDereg方法首选判断反注册的key是不是在通道key尾部，不在交换，
     *      并将交换信息更新到pollWrapper，从fdMap，keys，selectedKeys集合移除选择key，并将key从通道Channel中移除。
     */
    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    /**
     * 从selectedKeys中依次取出准备好被处理的SelectionKey，并对相应的待处理的I/O事件进行处理。
     */
    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            selectedKeys.keys[i] = null;

            // 将ServerSocketChannel注册到Selector的时候，是会将其对应的NioServerSocketChannel作为附加属性设置到SelectionKey中。
            // 所有这里从k.attachment()获取到的Object对象实际就是NioServerSocketChannel，而NioServerSocketChannel就是一个AbstractNioChannel的实现类。
            final Object a = k.attachment();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                selectedKeys.reset(i + 1);

                selectAgain();
                i = -1;
            }
        }
    }

    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        // 获取 Channel 的 NioUnsafe，所有的读写等操作都在 Channel 的 unsafe 类中操作
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        // 首先检查当前的SelectionKey是否有效(仅当SelectionKey从Selector上注销的时候，该SelectionKey会为无效状态)
        if (!k.isValid()) {
            // 如果无效的话
            // a) 获取该SelectionKey所关联的Channel所注册的EventLoop，如果获取Channel的EventLoop失败，则忽略错误直接返回。
            //      因为我们只处理注册到EventLoop上的Channel且有权去关闭这个Channel；
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            //
            // b) 如果获取到的EventLoop不是当前的执行线程所绑定的EventLoop，或者获取到的EventLoop为null，则直接返回。
            //       因为我们只关注依然注册在当前执行线程所绑定的EventLoop上的Channel。Channel可能已经从当前的EventLoop上注销了，
            //       并且它的SelectionKey可能已经被取消了，
            //       作为在注销处理流程的一部分。当然，如果Channel仍然健康的被注册在当前的EventLoop上，则需要去关闭它；
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            // c) 当能正确获取到EventLoop，且该EventLoop非空并为当前执行线程所绑定的EventLoop，
            //      则说明Channel依旧注册去当前的EventLoop上，那么执行关闭操作，来关闭相应的连接，释放相应的资源。
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924

                // 当SelectionKey.OP_CONNECT(连接事件)准备就绪时，我们执行如下操作：
                // 将SelectionKey.OP_CONNECT事件从SelectionKey所感兴趣的事件中移除，
                // 这样Selector就不会再去监听该连接的SelectionKey.OP_CONNECT事件了。
                // 而SelectionKey.OP_CONNECT连接事件是只需要处理一次的事件，一旦连接建立完成，就可以进行读、写操作了。
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                // unsafe.finishConnect()：该方法会调用SocketChannel.finishConnect()来标识连接的完成，
                // 如果我们不调用该方法，就去调用read/write方法，则会抛出一个NotYetConnectedException异常。
                // 在此之后，触发ChannelActive事件，该事件会在该Channel的ChannelPipeline中传播处理。
                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            // 首先处理OP_WRITE，因为我们可以写一些队列中的缓冲区，从而释放内存。
            // 如果是 OP_WRITE 事件，说明可以继续向 Channel 中写入数据，当写完数据后把 OP_WRITE 事件取消掉。
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                // 调用forceFlush，它也会在没有需要写的时候清除OP_WRITE
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop 还要检查readOps为0，以解决可能导致自旋循环的JDK bug
            // 如果是 OP_READ 或 OP_ACCEPT 事件，则调用 unsafe.read() 进行读取数据。
            // 当 NioEventLoop 读取数据的时候会委托给 Channel 中的 unsafe 对象进行读取数据。
            // Unsafe中真正读取数据是交由 ChannelPipeline 来处理。
            // ChannelPipeline 中是注册的我们自定义的 Handler，然后由 ChannelPipeline中的 Handler 一个接一个的处理请求的数据。
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    /**
     * 当一个非EventLoop线程提交了一个任务到EventLoop的taskQueue后，在什么情况下会去触发Selector.wakeup()。
     * 当满足下面4个条件时，在有任务提交至EventLoop后会触发Selector的wakeup()方法：
     * a) 成员变量addTaskWakesUp为false。
     * b）当提交上来的任务不是一个NonWakeupRunnable任务
     * c) 执行提交任务的线程不是EventLoop所在线程
     * d) 当wakenUp成员变量当前的值为false
     *
     * 只有同时满足上面4个条件的情况下，Selector的wakeup()方法才会的以调用。
     */
    @Override
    protected void wakeup(boolean inEventLoop) {
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    @Override
    protected boolean beforeScheduledTaskSubmitted(long deadlineNanos) {
        return deadlineNanos < nextWakeupTime;
    }

    @Override
    protected boolean afterScheduledTaskSubmitted(long deadlineNanos) {
        return deadlineNanos < nextWakeupTime;
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    /**
     * selector包装类{@link SelectedSelectionKeySetSelector}
     * <p>
     * {@link SelectedSelectionKeySetSelector#selectNow()}委托真实的selector完成selectoNow()操作外，还会将selectionKeys清空。
     * <p>
     * {@link SelectedSelectionKeySetSelector#wakeup()}
     */
    int selectNow() throws IOException {
        try {
            // 直接委托个Nio事件循环选择器
            /**
             * select方法的3种操作形式，实际上委托给为{@link SelectorImpl#lockAndDoSelect(long)}方法，方法实际上是同步的，
             * 可安全访问，获取key集合代理publicKeys和就绪key代理集合publicSelectedKeys，然后交给
             * doSelect(long l)方法，这个方法为抽象方法，待子类扩展。
             */
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            // 如果选择操作后，需要唤醒等待选择操作的线程，则唤醒
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    private void select(boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
            int selectCnt = 0;
            long currentTimeNanos = System.nanoTime();
            // delayNanos方法会返回最近一个待执行的定时/周期性任务还差多少纳秒就可以执行的时间差
            // (若，scheduledTaskQueue为空，也就是没有任务的定时/周期性任务，则返回1秒)。
            // 因此selectDeadLineNanos就表示最近一个待执行的定时/周期性任务的可执行时间。
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);

            long normalizedDeadlineNanos = selectDeadLineNanos - initialNanoTime();
            if (nextWakeupTime != normalizedDeadlineNanos) {
                nextWakeupTime = normalizedDeadlineNanos;
            }

            for (;;) {
                // 当有定时/周期性任务即将到达执行时间(<0.5ms)，
                // 或者NioEventLoop的线程收到了新提交的任务上来等待着被处理，
                // 或者有定时/周期性任务到达了可处理状态等待被处理，
                //      那么则退出select方法转而去执行任务。
                // 这也说明Netty总是会尽最大努力去保证任务队列中的任务以及定时/周期性任务能得到及时的处理。

                // 该段代码会计算scheduledTaskQueue中是否有即将要执行的任务，即在0.5ms内就可执行的scheduledTask，如果有则退出select方法转而去执行任务。
                // ‘selectDeadLineNanos - currentTimeNanos’就表示：最近一个待执行的定时/周期性任务还差多少纳秒就可以执行的时间差。
                // 如果该结果大于0，则说明‘selectDeadLineNanos - currentTimeNanos’ >= 0.5ms，否则‘selectDeadLineNanos - currentTimeNanos’ < 0.5ms。
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                if (timeoutMillis <= 0) {
                    if (selectCnt == 0) {
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                // 如果一个任务在wakenUp值为true的情况下被提交上来，那么这个任务将没有机会去调用Selector.wakeup()
                // (即，此时)wakenUp成员变量当前的值为false’条件不满足)。
                // 所以我们需要去再次检测任务队列中是否有待执行的任务，在执行Selector.select操作之前。
                // 如果我们不这么做，那么任务队列中的任务将等待直到Selector.select操作超时。
                // 如果ChannelPipeline中存在IdleStateHandler，那么IdleStateHandler处理器可能会被挂起直到空闲超时。
                //
                // 解释：
                //
                // 首先，这段代码是在每次要执行Selector.select(long timeout)之前我们会进行一个判断。
                // 我们能够确定的事，如果hasTasks()为true，即发现当前有任务待处理时。wakenUp.compareAndSet(false, true)会返回true，
                //      因为在每次调用当前这个select方法时，都会将wakenUp标识设置为false(即，‘wakenUp.getAndSet(false)’这句代码)。
                // 而此时，wakenUp已经被置位true了，在此之后有任务提交至EventLoop，那么是无法触发Selector.wakeup()的。
                // 所以如果当前有待处理的任务，就不会进行下面的Selector.select(long timeout)操作，而是退出select方法，继而去处理任务。
                //
                // 因为如果不这么做的话，如果当前NioEventLoop线程上已经有任务提交上来，
                //      这会使得这些任务可能会需要等待Selector.select(long timeout)操作超时后才能得以执行。
                // 再者，假设我们的ChannelPipeline中存在一个IdleStateHandler，
                //      那么就可能导致因为『Selector.select(long timeout)』操作的timeout比IdleStateHandler设置
                //      的idle timeout长，而导致IdleStateHandler不能对空闲超时做出即使的处理。
                //
                // 同时，我们注意，在执行‘break’退出select方法前，会执行‘selector.selectNow()’，该方法不会阻塞，它会立即返回，
                //      同时它会抵消Selector.wakeup()操作带来的影响
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    // 因为提交任务的线程是非NioEventLoop线程，所以也可能是由NioEventLoop线程成功执行了『if (hasTasks() && wakenUp.compareAndSet(false, true))』，
                    // 退出了select方法转而去执行任务队列中的任务。注意，这是提交任务的非NioEventLoop线程就不会执行『selector.wakeup()』。
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                // 如有有非NioEventLoop线程提交了一个任务上来，那么这个线程会执行『selector.wakeup()』方法，
                // 那么NioEventLoop在『if (hasTasks() && wakenUp.compareAndSet(false, true))』的后半个条件会返回false，
                // 程序会执行到这里，但是此时『selector.select()』不会阻塞，而是直接返回，因为前面已经先执行了『selector.wakeup()』
                int selectedKeys = selector.select(timeoutMillis);
                selectCnt ++;

                // 除了在每次Selector.select(long timeout)操作前进行任务队列的检测外，
                // 在每次Selector.select(long timeout)操作后也会检测任务队列是否已经有提交上来的任务待处理，以及是由有定时或周期性任务准备好被执行。
                // 如果有，也不会继续“epoll-bug”的检测，转而去执行待处理的任务。
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                //『if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos)』返回false，
                // 即『time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) < currentTimeNanos』 的意思是：
                //      int selectedKeys = selector.select(timeoutMillis)在timeoutMillis时间到期前就返回了，并且selectedKeys==0，
                //      则说明selector进行了一次空轮询，这违反了Javadoc中对Selector.select(timeout)方法的描述。
                // epoll-bug会导致无效的状态选择和100%的CPU利用率。也就是Selector不管有无感兴趣的事件发生，select总是不阻塞就返回。
                // 这会导致select方法总是无效的被调用然后立即返回，依次不断的进行空轮询，导致CPU的利用率达到了100%。
                long time = System.nanoTime();
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // The code exists in an extra method to ensure the method is not too big to inline as this
                    // branch is not very likely to get hit very frequently.
                    selector = selectRebuildSelector(selectCnt);
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    private Selector selectRebuildSelector(int selectCnt) throws IOException {
        // The selector returned prematurely many times in a row.
        // Rebuild the selector to work around the problem.
        logger.warn(
                "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                selectCnt, selector);

        rebuildSelector();
        Selector selector = this.selector;

        // Select again to populate selectedKeys.
        selector.selectNow();
        return selector;
    }

    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
