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
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    //netty内部对日志打印的实现
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    //打印异常的日志对象
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");

    // LISTENER最大的栈的深度, 限制递归避免出现StackOverflowError
    // SystemPropertyUtil是netty的自带的配置类可以再启动的时候进行配置, 最终使用的是System.getProperty方法。
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));

    // 原子更新操作，这里可以理解为在多线程下操作是线程安全的。
    // AtomicReferenceFieldUpdater.newUpdater使用了这个方法传入了三个参数
    // 1、DefaultPromise.class 持有字段的对象的类型
    // 2、Object.class 字段类型
    // 3、result 需要原子更新的字段的名称
    // 两个泛型则是AtomicReferenceFieldUpdater对应的类型T、V。
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");

    // 操作成功，用于result的设置
    private static final Object SUCCESS = new Object();
    // 不可取消，用于result的设置
    private static final Object UNCANCELLABLE = new Object();
    // 构造并存储取消异常的原因，cancel时cas设置result的值
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(ThrowableUtil.unknownStackTrace(
            new CancellationException(), DefaultPromise.class, "cancel(...)"));
    private static final StackTraceElement[] CANCELLATION_STACK = CANCELLATION_CAUSE_HOLDER.cause.getStackTrace();

    // 任务的执行结果, 根据执行情况可以设置为SUCCESS、UNCANCELLABLE、CANCELLATION_CAUSE_HOLDER
    private volatile Object result;

    // future是异步操作任务的所以需要执行器，因为执行器是多线程的。
    private final EventExecutor executor;
    /**
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     * 需要通知的监听器如果为null则会有两种情况1、没有监听器 2、监听器已经通知完毕
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     * 使用synchronized(this)进行线程同步
     */
    private Object listeners;
    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     * 计数，在当前类中有地方使用了this Object的wait和notifyAll用来计数wait的次数
     */
    private short waiters;

    /**
     * Threading - synchronized(this). We must prevent concurrent notification and FIFO listener notification if the
     * executor changes.
     * 如果执行程序发生变化，我们必须防止并发通知和FIFO监听器通知
     * true已经有线程进行通知了，false没有线程发送通知。
     */
    private boolean notifyingListeners;

    /**
     * Creates a new instance.
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     * 最好使用{@link EventExecutor#newPromise()}来创建新promise
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     */
    public DefaultPromise(EventExecutor executor) {
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     * 无参构造，如果子类的实现没有使用到执行器那么可以调用无参构造，因为executor是final的所以必须初始化这里默认给了null
     */
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    @Override
    public Promise<V> setSuccess(V result) {
        // 设置结果如果返回true代表设置成功则调用通知，否则代表已经完成了并且抛出异常
        if (setSuccess0(result)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    /**
     * 和上方方法并没有不同，仅仅是如果设置失败则返回false，而上方设置失败则抛出异常
     */
    @Override
    public boolean trySuccess(V result) {
        return setSuccess0(result);
    }

    /**
     * 设置当前的任务为失败并且传入一个异常信息, 返回true则通知监听器，否则抛出异常
     */
    @Override
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    /**
     * 尝试设置当前任务为失败并且传入一个异常信息，返回true则尝试成功并且通知监听器，否则返回false
     */
    @Override
    public boolean tryFailure(Throwable cause) {
        return setFailure0(cause);
    }

    /**
     * 设置当前任务为不可取消
     * @return
     */
    @Override
    public boolean setUncancellable() {
        // 在上方说原子操作的时候RESULT_UPDATER字段是用来设置结果的。
        // 这里便使用它来操作设置当前的result为UNCANCELLABLE对象，
        // 第一参数传入需要操作的对象，第二参数传入期望当前的值，第三个参数传入需要设置的对象。
        // 这里讲述第二个对象，此字段的操作叫做CAS就是下面方法名的缩写，翻译则是比较和设置，如果当前的值是传入的第二个参数那么就设置第三个参数为这个字段的值。
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }
        Object result = this.result;
        // 否则获取当前的结果并且判断是成功了还是被取消了
        // 如果是已经完成且是取消状态，则返回false，
        // 其他情况，!isDone0(result) 即result已经等于UNCANCELLABLE则设置失败返回true
        // 如果已经完成，同时最终不是cancel状态，返回true
        return !isDone0(result) || !isCancelled0(result);
    }

    @Override
    public boolean isSuccess() {
        Object result = this.result;
        // result不等于null是必须的因为初始值就是null说明并没有进行任何状态的设置
        // result不等于UNCANCELLABLE 代表是不可取消状态但是他是未完成的，因为最终的result并不会是他，从而代表正在运行并且在运行途中还设置了不可取消状态
        // result不是CauseHolder类型，代表结束运行但是设置了异常信息，如取消异常CancellationException
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    /**
     * 只有初始状态null可以cancel
     * @return
     */
    @Override
    public boolean isCancellable() {
        return result == null;
    }

    private static final class LeanCancellationException extends CancellationException {
        private static final long serialVersionUID = 2794674970981187807L;

        @Override
        public Throwable fillInStackTrace() {
            setStackTrace(CANCELLATION_STACK);
            return this;
        }

        @Override
        public String toString() {
            return CancellationException.class.getName();
        }
    }

    @Override
    public Throwable cause() {
        return cause0(result);
    }

    private Throwable cause0(Object result) {
        if (!(result instanceof CauseHolder)) {
            return null;
        }
        if (result == CANCELLATION_CAUSE_HOLDER) {
            CancellationException ce = new LeanCancellationException();
            if (RESULT_UPDATER.compareAndSet(this, CANCELLATION_CAUSE_HOLDER, new CauseHolder(ce))) {
                return ce;
            }
            result = this.result;
        }
        return ((CauseHolder) result).cause;
    }

    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
            addListener0(listener);
        }

        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                addListener0(listener);
            }
        }

        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
            removeListener0(listener);
        }

        return this;
    }

    @Override
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                removeListener0(listener);
            }
        }

        return this;
    }

    @Override
    public Promise<V> await() throws InterruptedException {
        if (isDone()) {
            return this;
        }

        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        // 如果当前调用方法的线程是执行器的线程，发生死锁
        checkDeadLock();

        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    // 如果被中断了抛出异常，退出await
                    wait();
                } finally {
                    decWaiters();
                }
            }
        }
        return this;
    }

    @Override
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        checkDeadLock();

        boolean interrupted = false;
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    // 如果被中断了不抛出异常，直到被唤醒再处理中断
                    wait();
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    /**
     * 获取当前结果，非阻塞，如果当前值是异常或者是SUCCESS或者UNCANCELLABLE则返回null，否则返回当前值
     * @return
     */
    @SuppressWarnings("unchecked")
    @Override
    public V getNow() {
        Object result = this.result;
        if (result instanceof CauseHolder || result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        return (V) result;
    }

    /**
     *
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @SuppressWarnings("unchecked")
    @Override
    public V get() throws InterruptedException, ExecutionException {
        Object result = this.result;
        if (!isDone0(result)) {
            await();
            result = this.result;
        }
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        Throwable cause = cause0(result);
        if (cause == null) {
            return (V) result;
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    @SuppressWarnings("unchecked")
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Object result = this.result;
        if (!isDone0(result)) {
            if (!await(timeout, unit)) {
                throw new TimeoutException();
            }
            result = this.result;
        }
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        Throwable cause = cause0(result);
        if (cause == null) {
            return (V) result;
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    /**
     * {@inheritDoc}
     * 取消当前任务执行，并且尝试中断，但是当前方法并没有尝试中断所以传参则无用。
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
            // 设置成功则检查并唤醒之前wait中等待的线程
            if (checkNotifyWaiters()) {
                // 通知所有的监听器
                notifyListeners();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    @Override
    public boolean isDone() {
        return isDone0(result);
    }

    /**
     * 同步等待调用了之前wait方法。如果失败则尝试抛出异常
     * @return
     * @throws InterruptedException
     */
    @Override
    public Promise<V> sync() throws InterruptedException {
        await();
        rethrowIfFailed();
        return this;
    }

    @Override
    public Promise<V> syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override
    public String toString() {
        return toStringBuilder().toString();
    }

    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    protected EventExecutor executor() {
        return executor;
    }

    /**
     * 检查当前调用方法的线程是不是执行器的线程，如果是则说明发生了死锁需要抛出异常停止死锁操作
     */
    protected void checkDeadLock() {
        // 获取执行器，如果执行器为null则不会发生死锁，如果不是null则判断当前线程是否是执行器线程
        EventExecutor e = executor();
        if (e != null && e.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    /**
     * Notify a listener that a future has completed.
     * <p>
     * This method has a fixed depth of {@link #MAX_LISTENER_STACK_DEPTH} that will limit recursion to prevent
     * {@link StackOverflowError} and will stop notifying listeners added after this threshold is exceeded.
     * 这个方法有一个固定的深度MAX_LISTENER_STACK_DEPTH来限制递归避免出现StackOverflowError，超过此阈值后将停止通知添加的侦听器。
     * @param eventExecutor the executor to use to notify the listener {@code listener}.
     * @param future the future that is complete.
     * @param listener the listener to notify.
     */
    protected static void notifyListener(
            EventExecutor eventExecutor, final Future<?> future, final GenericFutureListener<?> listener) {
        checkNotNull(eventExecutor, "eventExecutor");
        checkNotNull(future, "future");
        checkNotNull(listener, "listener");
        notifyListenerWithStackOverFlowProtection(eventExecutor, future, listener);
    }

    private void notifyListeners() {
        // 获取当前任务的的执行器
        EventExecutor executor = executor();
        // 如果调用这个方法的线程就是执行器的线程则进入该if
        if (executor.inEventLoop()) {
            // 获取当前线程的InternalThreadLocalMap对象,。
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            // 通过线程的数据对象获取到当前的任务监听器通知的层次，如果是第一次通知则为0
            final int stackDepth = threadLocals.futureListenerStackDepth();
            // 当前的线程数据中的层次与我们设置的最大层次相比，如果当前层次小于设置的最大层则进入if
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    // 并且立即通知
                    notifyListenersNow();
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        // 如果当前线程不是执行器或者当前的线程深度已经大于了设置的最大深度，则使用当前的执行器进行异步通知
        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListenersNow();
            }
        });
    }

    /**
     * The logic in this method should be identical to {@link #notifyListeners()} but
     * cannot share code because the listener(s) cannot be cached for an instance of {@link DefaultPromise} since the
     * listener(s) may be changed and is protected by a synchronized operation.
     *
     * 此方法中的逻辑应该与notifylistener()相同，但不能共享代码，
     * 因为监听器不能被缓存在DefaultPromise的实例中，不能缓存的原因是监听器可能会被更改，并且受到同步操作的保护。
     *
     * 此方法和上方的方法相同，但是上方的通知是使用当前任务的监听器，而此处使用的是传入的监听器，
     * 可能监听器会发生改变所以没有使用当前任务的字段做缓存，因为做了缓存上方代码是可以复用的。
     */
    private static void notifyListenerWithStackOverFlowProtection(final EventExecutor executor,
                                                                  final Future<?> future,
                                                                  final GenericFutureListener<?> listener) {
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListener0(future, listener);
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListener0(future, listener);
            }
        });
    }

    /**
     * 这里对此方法进行一个小结: 这里使用了两个地方用锁而且他们的锁是一样的所以会出现竞争问题，
     * 如果第一个线程进来并且设置为正在发送通知那么剩下的线程都不会再继续执行并且当前的监听器是null的
     * 如果通过别的途径再次添加了监听器并且当前的通知还是正在通知的状态那么其他的线程还是进不来，
     * 但是当前的线程执行完通知会发现当前的监听器又发生了变化，那么这个for的死循环再次执行，
     * 因为发现又有新的通知所以当前还是正在发送通知状态，所以其他线程还是进不来，最终还是由当前线程进行执行。
     * 而在讲述notifyListenerWithStackOverFlowProtection的时候说过监听器发生改变所以不能复用的问题，
     * 而这里就处理如果当前的监听器发送改变的处理。
     */
    private void notifyListenersNow() {
        Object listeners;
        synchronized (this) {
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            // 只有在有监听器要通知而我们还没有通知监听器时才继续。
            if (notifyingListeners || this.listeners == null) {
                return;
            }

            notifyingListeners = true;
            listeners = this.listeners;
            this.listeners = null;
        }
        for (;;) {
            if (listeners instanceof DefaultFutureListeners) {
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                notifyListener0(this, (GenericFutureListener<?>) listeners);
            }
            synchronized (this) {
                if (this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                    // 在这个方法中不能抛出任何东西，因此将notifyinglistener设置回false，而不需要在finally块中。
                    notifyingListeners = false;
                    return;
                }
                // 可能会在通知的时候又有新的监听器进来，所以这里再次设置了，for循环继续处理
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    /**
     * 通知数组类型的监听器
     */
    private void notifyListeners0(DefaultFutureListeners listeners) {
        GenericFutureListener<?>[] a = listeners.listeners();
        int size = listeners.size();
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
            l.operationComplete(future);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
            }
        }
    }

    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        // 如果是null则说明这是第一个监听器那么直接将其赋值给当前的全局变量
        if (listeners == null) {
            listeners = listener;
            // 否则说明不是第一个监听器那么就判断是不是数组类型的监听器如果是则add加进去就行了
        } else if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
            // 如果当监听器不是数组类型并且当前添加的不是第一次，所以修改当前局部变量为数组类型的监听器，并且传入两个已知的监听器
            listeners = new DefaultFutureListeners((GenericFutureListener<?>) listeners, listener);
        }
    }

    /**
     * 删除监听器，非常简单如果是数组类型那么直接从数组中移除如果不是数组类型那么就置为null
     */
    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            listeners = null;
        }
    }

    /**
     * 设置当前任务的结果为成功，如果传入的结果是是null则设置为SUCCESS，否则设置为传入的result
     */
    private boolean setSuccess0(V result) {
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setFailure0(Throwable cause) {
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }

    /**
     * 设置result的值，成功设置表示该promise已经处于done状态，需要通知其他wait的线程
     */
    private boolean setValue0(Object objResult) {
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
            // 对前面wait等待线程的通知，如果listeners != null，通知监听器
            if (checkNotifyWaiters()) {
                notifyListeners();
            }
            return true;
        }
        return false;
    }

    /**
     * Check if there are any waiters and if so notify these.
     * @return {@code true} if there are any listeners attached to the promise, {@code false} otherwise.
     */
    private synchronized boolean checkNotifyWaiters() {
        if (waiters > 0) {
            notifyAll();
        }
        return listeners != null;
    }

    private void incWaiters() {
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        ++waiters;
    }

    private void decWaiters() {
        --waiters;
    }

    private void rethrowIfFailed() {
        Throwable cause = cause();
        if (cause == null) {
            return;
        }

        PlatformDependent.throwException(cause);
    }

    /**
     * await等待异步任务完成
     * @param timeoutNanos 等待的纳秒时间
     * @param interruptable 是否中断抛出异常
     * @return
     * @throws InterruptedException
     */
    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        if (isDone()) {
            return true;
        }

        // 否则判断当前传入的时间是否小于等于0，如果是则返回当前执行结果是否为成功
        if (timeoutNanos <= 0) {
            return isDone();
        }

        // 判断是否允许抛出中断异常，并且判断当前线程是否被中断如果两者都成立则抛出中断异常
        // 所以{@link DefaultPromise#awaitUninterruptibly()}方法依然要捕捉该错误
        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        checkDeadLock();

        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        boolean interrupted = false;
        try {
            for (;;) {
                synchronized (this) {
                    if (isDone()) {
                        return true;
                    }
                    // 等待线程数+1
                    incWaiters();
                    try {
                        // 使用wait进行等待,因为wait传入参数是毫秒而这里是纳秒所以这里做了处理
                        // 1、获取纳秒数中的毫秒传入第一个参数
                        // 2、获取剩余的那纳秒数作为第二个参数
                        // wait 第一个参数是毫秒数 第二个参数是纳秒数，看起来比较精准其实jdk只是发现有纳秒数后对毫秒数进行了+1
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                        // 如果出现中断异常，那么判断传入的第二个参数是否抛出异常如果为true，此处则抛出异常否则修改前面声明的变量为true
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                        // 不管最终如何都会对waiters进行-1操作
                        decWaiters();
                    }
                }
                // 能到这里说明已经被唤醒则判断是否执行成功，执行成功则返回true
                if (isDone()) {
                    return true;
                } else {
                    // 否则判断当前睡眠时间是否超过设置时间，如果超过则返回当前的执行结果，否则继续循环
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                    if (waitTime <= 0) {
                        return isDone();
                    }
                }
            }
        } finally {
            // 当跳出循环后判断在等待过程中是否发生了中断异常，如果发生则将当前线程进行中断
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * 通知所有进度监听器。
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * 如果在原始调用完成之前对该方法进行了多次调用，则不会尝试确保通知顺序。
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * 这将对所有监听器进行一次迭代，以获得所有{@link GenericProgressiveFutureListener}的类型。
     * @param progress the new progress. 当前的进度
     * @param total the total progress. 总的进度
     */
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
        // 从当前的监听器中获取到进度监听器，如果没有则return，否则继续执行
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        // 对应进度监听器的自然是进度的任务管理，所以会将当前的this转为进度管理器self
        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                notifyProgressiveListeners0(
                        self, (GenericProgressiveFutureListener<?>[]) listeners, progress, total);
            } else {
                notifyProgressiveListener0(
                        self, (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners, progress, total);
            }
        } else {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                final GenericProgressiveFutureListener<?>[] array =
                        (GenericProgressiveFutureListener<?>[]) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListener0(self, l, progress, total);
                    }
                });
            }
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     * 获取进度监听器列表，因为任务中只有一个字段存储监听器所以需要从该字段中进行筛选，此方法就是对这个字段进行类的筛选
     */
    private synchronized Object progressiveListeners() {
        // 获取当前任务的监听器,这里之所以使用一个临时变量进行接收是害怕其他线程如果修改了监听器那么下面的处理会出现未知异常，
        // 所以为了保证不出错此处将监听器做了处理。
        Object listeners = this.listeners;
        if (listeners == null) {
            // No listeners added
            return null;
        }

        if (listeners instanceof DefaultFutureListeners) {
            // Copy DefaultFutureListeners into an array of listeners.
            DefaultFutureListeners dfl = (DefaultFutureListeners) listeners;
            int progressiveSize = dfl.progressiveSize();
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            GenericFutureListener<?>[] array = dfl.listeners();
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
            }
        }
    }

    private static boolean isCancelled0(Object result) {
        // 如果结果类型是CauseHolder并且结果还是取消异常那么则返回true
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    private static boolean isDone0(Object result) {
        // 传入结果不等于null 并且 不是不能取消（因为不能取消则说明正在运行，而不管是SUCCESS 还 CANCELLATION_CAUSE_HOLDER 都是已经有确切结果的）
        return result != null && result != UNCANCELLABLE;
    }

    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }
}
