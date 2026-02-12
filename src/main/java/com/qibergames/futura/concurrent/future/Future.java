package com.qibergames.futura.concurrent.future;

import com.qibergames.futura.concurrent.threading.Threading;
import com.qibergames.futura.function.ThrowableConsumer;
import com.qibergames.futura.function.ThrowableFunction;
import com.qibergames.futura.function.ThrowableRunnable;
import com.qibergames.futura.function.ThrowableSupplier;
import com.qibergames.futura.verification.Validator;
import com.google.common.collect.MapMaker;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.*;

/**
 * A Future represents a callback, which can be completed or failed either synchronously or asynchronously.
 * A Future can be completed with the creation of A new I object, and can be failed by an exception
 * happening whilst executing a Future task.
 * <p>
 * A Future chain can be used as a more modern way of handling child- and parent execution contexts. This is useful
 * when dealing with larger business logics. If any of the child parent fails, the error can be propagated to the
 * root parent element. This way, handling internal errors is much easier, as the execution stops at that internal
 * point, and the error is proxied back to the chain's entry point.
 * <p>
 * This class also contains useful methods to attach callbacks for completion/failure events,
 * and to create new Future objects based on this instance.
 * <p>
 * Error recovery is also possible using the {@link #fallback(Object)} and {@link #fallback(Function)} methods.
 * <p>
 * The syntax encourages chaining, therefore less code is needed to handle certain tasks/events.
 *
 * @param <T> the type of the returned value of the completed Future
 *
 * @author AdvancedAntiSkid
 * @author MrGazdag
 *
 * @since 1.0
 */
public class Future<T> implements Promise<T> {
    /**
     * The global executor to be used for performing asynchronous tasks, where the executor is not specified explicitly.
     */
    @Setter
    @Getter
    private static ExecutorService globalExecutor = Threading.createVirtualOrPool(
        Runtime.getRuntime().availableProcessors()
    );

    /**
     * The map of executors that should be used for the specified contexts.
     */
    private static final Map<Object, ExecutorService> contextExecutors = new MapMaker()
        .weakKeys()
        .weakValues()
        .concurrencyLevel(4)
        .makeMap();

    /**
     * The function that is used to determine what information should be used from the class to group
     * multiple classes together, and cache a shared executor for each.
     */
    @Setter
    private static @NotNull Function<Class<?>, Object> contextKeyMapper = Class::getClassLoader;

    /**
     * The function that resolves an executor for the specified key. The key is resolved from the class by the
     * {@link #contextKeyMapper} function.
     */
    @Setter
    private static @NotNull Function<Object, ExecutorService> contextExecutorMapper = key -> globalExecutor;

    /**
     * Represents the current state of the Future's lifecycle.
     */
    private enum State {
        PENDING, // Future is waiting for a completion request.
        COMPLETING, // Received a completion request, processing lifecycle change.
        FAILING, // Received a failure request, processing lifecycle change.
        COMPLETED, // Handled successful completion, invoking completion handlers.
        FAILED  // Handled failed completion, invoking failure handlers.
    }

    /**
     * The atomic reference of the current state of the future.
     */
    private final AtomicReference<State> stateRef = new AtomicReference<>(State.PENDING);

    /**
     * The read/write lock that allows efficient reads for completion and failure handlers.
     */
    private final ReadWriteLock handlersLock = new ReentrantReadWriteLock();

    /**
     * The special object that is used for coordinating waiting threads.
     */
    private final Object waitLock = new Object();

    /**
     * The list of future completion handlers.
     */
    private final List<Consumer<T>> completionHandlers = new ArrayList<>();

    /**
     * The list of future failure handlers.
     */
    private final List<Consumer<Throwable>> errorHandlers = new ArrayList<>();

    /**
     * The value of the completion result. Only valid when state is COMPLETED.
     */
    private volatile @Nullable T value;

    /**
     * The error that occurred whilst executing and caused a future failure.
     * Only valid when state is FAILED.
     */
    private volatile @Nullable Throwable error;

    /**
     * Creates a new, incomplete Future.
     */
    public Future() {
    }

    /**
     * Block the current thread and wait for the Future completion to happen.
     * After the completion happened, the completion result T object is returned.
     * <p>
     * If the future completes with an exception, a {@link FutureExecutionException} is thrown.
     * The actual exception that made the future fail can be obtained using {@link FutureExecutionException#getCause()}.
     * <p>
     * Note that if the future completes successfully with <code>null</code>, the method will also return <code>null</code>.
     *
     * @return the completion value or a default value
     *
     * @throws FutureExecutionException the completion failed and there was no default value to return
     *
     * @see #await()
     */
    @CheckReturnValue
    public T get() throws FutureExecutionException {
        try {
            // wait for the future completion without specifying a timeout
            return blockForValue(0, false, null);
        } catch (FutureTimeoutException e) {
            // this should not happen
            throw new IllegalStateException("Timeout should have been avoided", e);
        }
    }

    /**
     * Block the current thread and wait for the Future completion to happen.
     * After the completion happened, the completion result T object is returned wrapped with an {@link Optional}.
     * <p>
     * If the Future fails to complete, or the completion value is {@code null}, an {@link Optional#empty()} is
     * returned.
     * <p>
     * An {@link Optional#of(Object)} is returned if and only if the Future completes successfully, and the completion
     * value is not {@code null}.
     *
     * @return an optional of T holding the completion value, or an empty optional
     */
    @CheckReturnValue
    public @NotNull Optional<T> tryGet() {
        try {
            // note that future can complete with `null`, for instance when running `Future<Void>.completed()`
            // java optional api enforces optional values not to be null, so for completed null values, we
            // will return an empty optional as well
            T value = blockForValue(0, false, null);
            return value != null ? Optional.of(value) : Optional.empty();
        } catch (FutureExecutionException e) {
            return Optional.empty();
        } catch (FutureTimeoutException e) {
            // this should not happen
            throw new IllegalStateException("Timeout should have been avoided", e);
        }
    }

    /**
     * Block the current thread and wait for the Future completion to happen.
     * After the completion happened, the completion result T object is returned.
     * <p>
     * This is an alternative method for {@link #get()}. The purpose of this is to provide unsafe access via blocking
     * to the Future's completion value in contexts, where the parent context will handle the exception.
     * <p>
     * If the future completes with an exception, a {@link FutureExecutionException} is thrown.
     * The actual exception that made the future fail can be obtained using {@link FutureExecutionException#getCause()}.
     * <p>
     * If the request has a timeout and exceeds the given time interval, a {@link FutureTimeoutException} is thrown.
     * If the timeout is 0, the method will block indefinitely.
     *
     * @return the completion value or a default value
     *
     * @see #get()
     */
    @SneakyThrows
    @CanIgnoreReturnValue
    public T await() {
        return get();
    }

    /**
     * Block the current thread and wait for the Future completion to happen.
     * After the completion happened, the completion result T object is returned.
     * <p>
     * If the future completes with an exception, a {@link FutureExecutionException} is thrown.
     * The actual exception that made the future fail can be obtained using {@link FutureExecutionException#getCause()}.
     * <p>
     * If the request has a timeout and exceeds the given time interval, a {@link FutureTimeoutException} is thrown.
     * If the timeout is 0, the method will block indefinitely.
     * <p>
     * Note that if the future completes successfully with <code>null</code>, the method will also return <code>null</code>.
     *
     * @param timeout the maximum time interval to wait for the value, if this is exceeded, then a {@link FutureTimeoutException} is thrown.
     * @return the completion value or a default value
     *
     * @throws FutureTimeoutException the timeout interval has exceeded
     * @throws FutureExecutionException the completion failed and there was no default value to return
     */
    @CheckReturnValue
    public T get(long timeout) throws FutureTimeoutException, FutureExecutionException {
        // wait for the future completion with a specified timeout
        return blockForValue(timeout, false, null);
    }

    /**
     * Block the current thread and wait for the Future completion to happen.
     * After the completion happened, the completion result T object is returned.
     * <p>
     * If the future completes with an exception, the <code>defaultValue</code> is returned.
     * <p>
     * Note that if the future completes successfully with <code>null</code>, the method will also return <code>null</code>.
     *
     * @param defaultValue the default value which is returned on a completion failure
     * @return the completion value or a default value
     */
    @CheckReturnValue
    public T getOrDefault(@Nullable T defaultValue) {
        try {
            // wait for the future completion with a specified default value
            return blockForValue(0, true, defaultValue);
        } catch (FutureExecutionException | FutureTimeoutException e) {
            // this should not happen
            throw new IllegalStateException("Timeout should have been avoided", e);
        }
    }

    /**
     * Block the current thread and wait for the Future completion to happen.
     * After the completion happened, the completion result T object is returned.
     * <p>
     * If the future completes with an exception, the <code>defaultValue</code> is returned.
     * <p>
     * If the request has a timeout and exceeds the given time interval, a {@link FutureTimeoutException} is thrown.
     * If the timeout is 0, the method will block indefinitely.
     * <p>
     * Note that if the future completes successfully with <code>null</code>, the method will also return <code>null</code>.
     *
     * @param timeout the maximum time interval to wait for the value, if this is exceeded, then a {@link FutureTimeoutException} is thrown.
     * @param defaultValue the default value which is returned on a completion failure
     * @return the completion value or a default value
     *
     * @throws FutureTimeoutException the timeout interval has exceeded
     */
    @CheckReturnValue
    public T getOrDefault(long timeout, @Nullable T defaultValue) throws FutureTimeoutException {
        try {
            // wait for the future completion with a specified timeout and default value
            return blockForValue(timeout, true, defaultValue);
        } catch (FutureExecutionException e) {
            // this should not happen
            throw new IllegalStateException("Execution exception should have been avoided", e);
        }
    }

    /**
     * Block the current thread and wait for the Future completion to happen.
     * After the completion happened, the completion result T object is returned.
     * <p>
     * If the future completes with an exception, the specified <code>error</code> is thrown.
     * <p>
     * If the request has a timeout and exceeds the given time interval, the specified <code>error</code> is thrown.
     * If the timeout is 0, the method will block indefinitely.
     * <p>
     * Note that if the future completes successfully with <code>null</code>, the method will also return <code>null</code>.
     *
     *
     * @param timeout the maximum time interval to wait for the value, if this is exceeded, then a {@link FutureTimeoutException} is thrown
     * @param error the error to throw if the future fails
     * @return the completion value or the specified error
     *
     * @param <E> the type of the error to throw
     *
     * @throws E the error to throw if the future fails
     */
    @CheckReturnValue
    public <E extends Throwable> T getOrThrow(long timeout, @NotNull E error) throws E {
        try {
            return blockForValue(timeout, false, null);
        } catch (FutureExecutionException | FutureTimeoutException e) {
            throw error;
        }
    }

    /**
     * Block the current thread and wait for the Future completion to happen.
     * After the completion happened, the completion result T object is returned.
     * <p>
     * If the future completes with an exception, the specified <code>error</code> is thrown.
     * <p>
     * Note that if the future completes successfully with <code>null</code>, the method will also return <code>null</code>.
     *
     * @param error the error to throw if the future fails
     * @return the completion value or the specified error
     *
     * @param <E> the type of the error to throw
     *
     * @throws E the error to throw if the future fails
     */
    @CheckReturnValue
    public <E extends Throwable> T getOrThrow(@NotNull E error) throws E {
        try {
            return blockForValue(0, false, null);
        } catch (FutureExecutionException | FutureTimeoutException e) {
            throw error;
        }
    }

    /**
     * Block the current thread and wait for the Future completion to happen.
     * After the completion happened, the completion result T object is returned.
     * <p>
     * If the future completes with an exception, a {@link FutureExecutionException} is thrown,
     * or the <code>defaultValue</code> is returned if present.
     * The actual exception that made the future fail can be obtained using {@link FutureExecutionException#getCause()}.
     * <p>
     * If the request has a timeout and exceeds the given time interval, a {@link FutureTimeoutException} is thrown.
     * If the timeout is 0, the method will block indefinitely.
     * <p>
     * Note that if the future completes successfully with <code>null</code>, the method will also return <code>null</code>.
     * <p>
     * @param timeout the maximum time interval to wait for the value, if this is exceeded, then a {@link FutureTimeoutException} is thrown.
     * @param hasDefault indicates whether a default value should be returned on a completion failure
     * @param defaultValue the default value which is returned on a completion failure
     * @return the completion value or a default value
     *
     * @throws FutureTimeoutException the timeout interval has exceeded
     * @throws FutureExecutionException the completion failed and a default value was not specified
     *
     * @see #get()
     * @see #get(long)
     * @see #getOrDefault(Object)
     * @see #getOrDefault(long, Object)
     */
    @CheckReturnValue
    private T blockForValue(
        long timeout, boolean hasDefault, @Nullable T defaultValue
    ) throws FutureTimeoutException, FutureExecutionException {
        State currentState = getState();

        // check if the future is already completed
        if (!isPendingLike(currentState)) {
            // check if the completion was successful
            if (currentState == State.COMPLETED)
                return value;

            // completion was unsuccessful
            // return the default value if it is present
            if (hasDefault)
                return defaultValue;

            // no default value set, throw the completion error
            Throwable cause = error;
            assert cause != null : "Expected Future#cause to be not null";
            throw new FutureExecutionException(cause);
        }

        // the future is not yet completed
        // use shared waitLock for coordination
        long deadlineNanos = timeout > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout) : 0;
        synchronized (waitLock) {
            while (isPendingLike(currentState)) {
                try {
                    if (timeout == 0) {
                        // wait indefinitely
                        waitLock.wait(0);
                    } else {
                        long remainingNanos = deadlineNanos - System.nanoTime();
                        if (remainingNanos <= 0)
                            break;
                        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                        int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
                        waitLock.wait(millis, nanos);
                    }
                } catch (InterruptedException ignored) {
                    // ignore if the completion thread was interrupted
                }
                currentState = getState();
            }
        }

        // check final state after waiting
        if (timeout > 0 && isPendingLike(currentState))
            throw new FutureTimeoutException(timeout);

        currentState = getState();
        if (currentState == State.PENDING)
            throw new FutureTimeoutException(timeout);

        // the future has been completed
        // check if the completion was successful
        if (currentState == State.COMPLETED)
            return value;

        // the completion was unsuccessful
        // return the default value if it is present
        if (hasDefault)
            return defaultValue;

        // no default value set, throw the completion error
        Throwable cause = error;
        assert cause != null : "Expected Future#cause to be not null";
        throw new FutureExecutionException(cause);
    }

    /**
     * Get instantly the completion value or the default value if the Future hasn't been completed yet.
     *
     * @param defaultValue default value to return if the Future isn't completed
     * @return the completion value or the default value
     */
    @CheckReturnValue
    public T getNow(@Nullable T defaultValue) {
        return getState() == State.COMPLETED ? value : defaultValue;
    }

    /**
     * Attempt to instantly get the completion value of the Future.
     * <p>
     * If the Future hasn't been completed yet, an {@link Optional#empty()} is returned.
     * <p>
     * If the Future is already completed, but the completion values is {@code null}, an {@link Optional#empty()}
     * is returned.
     * <p>
     * An {@link Optional#of(Object)} is returned if and only if the Future is completed and the completion value
     * is not {@code null}.
     *
     * @return an optional of T holding the completion value, or an empty optional
     */
    @CheckReturnValue
    public @NotNull Optional<T> tryGetNow() {
        T value = this.value;
        // note that future can complete with `null`, for instance when running `Future<Void>.completed()`
        // java optional api enforces optional values not to be null, so for completed null values, we
        // will return an empty optional as well
        return getState() == State.COMPLETED && value != null ? Optional.of(value) : Optional.empty();
    }

    /**
     * Complete the Future successfully with the value given.
     * Call all the callbacks waiting on the completion of this Future.
     * <p>
     * If this Future was already completed (either successful or unsuccessful), this method does nothing.
     * Handler exceptions are collected and rethrown after all handlers run.
     *
     * @param value the completion value
     * @return <code>true</code> if the Future was completed with the value,
     * <code>false</code> otherwise
     */
    @CanIgnoreReturnValue
    public boolean complete(@Nullable T value) {
        List<Consumer<T>> handlers;

        // ignore failure if the future is not pending anymore
        if (!compareAndSetState(State.PENDING, State.COMPLETING))
            return false;

        // set value before publishing the final state
        this.value = value;
        setState(State.COMPLETED);

        // notify waiting threads
        synchronized (waitLock) {
            waitLock.notifyAll();
        }

        // capture handlers with read lock for efficiency
        handlersLock.readLock().lock();
        try {
            handlers = new ArrayList<>(completionHandlers);
        } finally {
            handlersLock.readLock().unlock();
        }

        // call the completion handlers outside synchronized block
        Throwable handlerError = null;
        for (Consumer<T> handler : handlers) {
            try {
                handler.accept(value);
            } catch (Throwable e) {
                if (handlerError == null)
                    handlerError = e;
            }
        }
        if (handlerError != null)
            throw new FutureRuntimeException("Unexpected exception caught in completion handler", handlerError);

        return true;
    }

    /**
     * Fail the Future completion with the given error.
     * Call all the callbacks waiting on the failure of this Future.
     * <p>
     * If this Future was already completed (either successful or unsuccessful), this method does nothing.
     * Handler exceptions are collected and rethrown after all handlers run.
     *
     * @param error the error occurred whilst completing
     * @return <code>true</code> if the Future was completed with an error, <code>false</code> otherwise
     */
    @CanIgnoreReturnValue
    public boolean fail(@NotNull Throwable error) {
        List<Consumer<Throwable>> handlers;

        // ignore failure if the future is not pending anymore
        if (!compareAndSetState(State.PENDING, State.FAILING))
            return false;

        // set error before publishing the final state
        this.error = error;
        setState(State.FAILED);

        // notify waiting threads
        synchronized (waitLock) {
            waitLock.notifyAll();
        }

        // capture handlers with read lock for efficiency
        handlersLock.readLock().lock();
        try {
            handlers = new ArrayList<>(errorHandlers);
        } finally {
            handlersLock.readLock().unlock();
        }

        // call the failure handlers outside synchronized block
        Throwable handlerError = null;
        for (Consumer<Throwable> handler : handlers) {
            try {
                handler.accept(error);
            } catch (Throwable e) {
                if (handlerError == null)
                    handlerError = e;
            }
        }
        if (handlerError != null)
            throw new FutureRuntimeException("Unexpected exception caught in failure handler", handlerError);
        return true;
    }

    /**
     * Register a completion handler to be called when the Future completes without an error.
     * <p>
     * If the Future completes with an exception, the specified <code>action</code> will not be called.
     * If you wish to handle exceptions as well,
     * use {@link #result(BiConsumer)} or {@link #except(Consumer)} methods.
     * <p>
     * If the Future is already completed successfully, the action will be called immediately with
     * the completion value. If the Future failed with an exception, the action will not be called.
     *
     * @param action the successful completion callback
     * @return this Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> then(@NotNull Consumer<T> action) {
        if (addCompletionHandlerIfPending(action))
            return this;

        if (getState() == State.COMPLETED)
            action.accept(value);
        return this;
    }

    /**
     * Register a completion handler to be called when the Future completes without an error.
     * <p>
     * If the Future completes with an exception, the specified <code>action</code> will not be called.
     * If you wish to handle exceptions as well,
     * use {@link #result(BiConsumer)} or {@link #except(Consumer)} methods.
     * <p>
     * If the Future is already completed successfully, the action will be called immediately with
     * the completion value. If the Future failed with an exception, the action will not be called.
     *
     * @param action the successful completion callback
     * @return this Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> tryThen(@NotNull ThrowableConsumer<T, Throwable> action) {
        Consumer<T> handler = value -> {
            try {
                action.accept(value);
            } catch (Throwable e) {
                fail(e);
            }
        };

        if (addCompletionHandlerIfPending(handler))
            return this;

        if (getState() == State.COMPLETED)
            handler.accept(value);
        return this;
    }

    /**
     * Register an asynchronous completion handler to be called when the Future completes without an error.
     * <p>
     * If the Future completes with an exception, the specified <code>action</code> will not be called.
     * If you wish to handle exceptions as well,
     * use {@link #result(BiConsumer)} or {@link #except(Consumer)} methods.
     * <p>
     * If the Future is already completed successfully, the action will be called immediately with
     * the completion value. If the Future failed with an exception, the action will not be called.
     *
     * @param action the successful completion callback
     * @return this Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> thenAsync(@NotNull Consumer<T> action) {
        Consumer<T> handler = value -> executeAsync(() -> action.accept(value));
        if (addCompletionHandlerIfPending(handler))
            return this;

        if (getState() == State.COMPLETED)
            handler.accept(value);
        return this;
    }

    /**
     * Create a new Future that will complete the specified task after this Future has been completed.
     * <p>
     * If the Future completes with an exception, the specified <code>action</code> will not be called.
     * If you wish to handle exceptions as well,
     * use {@link #result(BiConsumer)} or {@link #except(Consumer)} methods.
     * <p>
     * If the Future is already completed successfully, the action will be called immediately with
     * the completion value. If the Future failed with an exception, the action will not be called.
     * <p>
     * The new Future will be completed after this Future completes and the specified task is completed.
     *
     * @param task the task to complete after this Future is completed
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> thenInvoke(@NotNull Runnable task) {
        Future<T> future = new Future<>();
        State currentState = getState();

        if (currentState == State.PENDING) {
            boolean registered = addHandlersIfPending(
                value -> {
                    task.run();
                    future.complete(value);
                },
                future::fail
            );
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            future.fail(error);
        } else {
            task.run();
            future.complete(value);
        }

        return future;
    }

    /**
     * Create a new Future that will complete the specified task after this Future has been completed.
     * <p>
     * If the Future completes with an exception, the specified <code>action</code> will not be called.
     * If you wish to handle exceptions as well,
     * use {@link #result(BiConsumer)} or {@link #except(Consumer)} methods.
     * <p>
     * If the Future is already completed successfully, the action will be called immediately with
     * the completion value. If the Future failed with an exception, the action will not be called.
     * <p>
     * The new Future will be completed after this Future completes and the specified task is completed.
     * <p>
     * If the specified task throws an error, the new Future will be failed with the produced error.
     *
     * @param task the task to complete after this Future is completed
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> thenTryInvoke(@NotNull ThrowableRunnable<Throwable> task) {
        Future<T> future = new Future<>();
        State currentState = getState();

        if (currentState == State.PENDING) {
            boolean registered = addHandlersIfPending(
                value -> {
                    try {
                        task.run();
                        future.complete(value);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                },
                future::fail
            );
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            future.fail(error);
        }
        else {
            try {
                task.run();
                future.complete(value);
            } catch (Throwable e) {
                future.fail(e);
            }
        }

        return future;
    }

    /**
     * Create a new Future that will transform the value to a new Future using the given transformer.
     * <p>
     * After this Future will successfully complete, the result will be passed to the specified transformer.
     * The output of the transformer will be the input for the new Future.
     * <p>
     * If this Future completes with an exception, the new Future
     * will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, the transformer will be called
     * immediately, and a completed Future will be returned.
     * <p>
     *
     * @param transformer the function that transforms the value from T to U
     * @param <U> the new Future type
     * @return a new Future of type U
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> transform(@NotNull Function<T, U> transformer) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(value -> {
                try {
                    future.complete(transformer.apply(value));
                } catch (Exception e) {
                    future.fail(e);
                }
            }, future::fail);
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        try {
            return completed(transformer.apply(value));
        } catch (Exception e) {
            return failed(e);
        }
    }

    /**
     * Create a new Future that will transform the value to a new Future using the given transformer.
     * <p>
     * After this Future will successfully complete, the result will be passed to the specified transformer.
     * The output of the transformer will be the input for the new Future.
     * <p>
     * If this Future completes with an exception, the new Future
     * will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, the transformer will be called
     * immediately, and a completed Future will be returned.
     *
     * @param transformer the function that transforms the value from T to U
     * @param <U> the new Future type
     * @return a new Future of type U
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> tryTransform(@NotNull ThrowableFunction<T, U, Throwable> transformer) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(value -> {
                try {
                    future.complete(transformer.apply(value));
                } catch (Throwable e) {
                    future.fail(e);
                }
            }, future::fail);
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        try {
            return completed(transformer.apply(value));
        } catch (Throwable e) {
            return failed(e);
        }
    }

    /**
     * Create a new Future that will asynchronously transform the value to a new Future
     * using the given asynchronous transformer.
     * <p>
     * After this Future will successfully complete, the result will be passed to the specified transformer.
     * The output of the transformer will be the input for the new Future.
     * <p>
     * If this Future completes with an exception, the new Future
     * will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, the transformer will be called
     * immediately, and a completed Future will be returned.
     * <p>
     * If you want to get the completion value of this Future after the transformer Future is completed,
     * consider using {@link #chain(Future)} instead.
     * </p>
     *
     * @param transformer the function that transforms the value from T to U
     * @param <U> the new Future type
     * @return a new Future of type U
     *
     * @see #chain(Future)
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> transformAsync(@NotNull Function<T, Future<U>> transformer) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(value -> {
                try {
                    transformer.apply(value).then(future::complete);
                } catch (Exception e) {
                    future.fail(e);
                }
            }, future::fail);
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        try {
            return transformer.apply(value);
        } catch (Exception e) {
            return failed(e);
        }
    }

    /**
     * Create a new Future that will asynchronously transform the value to a new Future
     * using the given asynchronous transformer.
     * <p>
     * After this Future will successfully complete, the result will be passed to the specified transformer.
     * The output of the transformer will be the input for the new Future.
     * <p>
     * If this Future completes with an exception, the new Future
     * will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, the transformer will be called
     * immediately, and a completed Future will be returned.
     *
     * @param transformer the function that transforms the value from T to U
     * @param <U> the new Future type
     * @return a new Future of type U
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> tryTransformAsync(@NotNull ThrowableFunction<T, Future<U>, Throwable> transformer) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(value -> {
                try {
                    transformer.apply(value).then(future::complete);
                } catch (Throwable e) {
                    future.fail(e);
                }
            }, future::fail);
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        try {
            return transformer.apply(value);
        } catch (Throwable e) {
            return failed(e);
        }
    }

    /**
     * Create a new Future that will be completed with the given value, when this Future completes.
     * <p>
     * If this Future completes with an exception, the new Future will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, the new Future will be completed immediately.
     * <p>
     * @param value the value to complete the new Future with
     * @return a new Future of type U
     * @param <U> the new Future type
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> to(@Nullable U value) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(ignored -> future.complete(value), future::fail);
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        return completed(value);
    }

    /**
     * Create a new Future that will be completed with the given value, when this Future completes.
     * <p>
     * When this Future completes with a value, the supplier will be called synchronously to get the value
     * to complete the new Future with.
     * <p>
     * If this Future completes with an exception, the new Future will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, the new Future will be completed immediately.
     * <p>
     * @param supplier the value to complete the new Future with
     * @return a new Future of type U
     * @param <U> the new Future type
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> to(@NotNull Supplier<@Nullable U> supplier) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(value -> future.complete(supplier.get()), future::fail);
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        return completed(supplier.get());
    }

    /**
     * Create a new Future that will be completed with the given value, when this Future completes.
     * <p>
     * When this Future completes with a value, the supplier will be called synchronously to get the value
     * to complete the new Future with.
     * <p>
     * If this Future completes with an exception, the new Future will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, the new Future will be completed immediately.
     * <p>
     * @param supplier the value to complete the new Future with
     * @return a new Future of type U
     * @param <U> the new Future type
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> tryTo(@NotNull ThrowableSupplier<U, Throwable> supplier) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addCompletionHandlerIfPending(value -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable error) {
                    future.fail(error);
                }
            });
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        try {
            return completed(supplier.get());
        } catch (Throwable error) {
            return failed(error);
        }
    }

    /**
     * Create a new Future that will be completed with the given value, when this Future completes.
     * <p>
     * When this Future completes with a value, the supplier will be called asynchronously to get the value
     * to complete the new Future with.
     * <p>
     * If this Future completes with an exception, the new Future will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, the new Future will be completed immediately.
     * <p>
     * @param supplier the value to complete the new Future with
     * @return a new Future of type U
     * @param <U> the new Future type
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> toAsync(@NotNull Supplier<U> supplier) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(
                ignored -> Future.supplyAsync(supplier).then(future::complete),
                future::fail
            );
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        try {
            return completed(supplier.get());
        } catch (Throwable error) {
            return failed(error);
        }
    }

    /**
     * Create a new Future that will be completed with the given value, when this Future completes.
     * <p>
     * When this Future completes with a value, the supplier will be called asynchronously to get the value
     * to complete the new Future with.
     * <p>
     * If this Future completes with an exception, the new Future will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, the new Future will be completed immediately.
     * <p>
     * @param supplier the value to complete the new Future with
     * @return a new Future of type U
     * @param <U> the new Future type
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> tryToAsync(@NotNull ThrowableSupplier<U, Throwable> supplier) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(
                ignored -> Future.trySupplyAsync(supplier)
                    .then(future::complete)
                    .except(future::fail),
                future::fail
            );
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        try {
            return completed(supplier.get());
        } catch (Throwable error) {
            return failed(error);
        }
    }

    /**
     * Create a new Future that does not care about the completion value, it only checks for successful or
     * failed completion.
     * <p>
     * After this Future will successfully complete, a null be passed to the new Future.
     * <p>
     * If this Future completes with an exception, the new Future
     * will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, a completed Future will be returned with the value of null.
     *
     * @return a new Future of Void type
     */
    @CheckReturnValue
    public @NotNull Future<Void> callback() {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<Void> future = new Future<>();
            boolean registered = addHandlersIfPending(value -> future.complete(null), future::fail);
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        try {
            return completed();
        } catch (Exception e) {
            return failed(e);
        }
    }

    /**
     * Create a new Future that does not care about the completion value, it only checks for successful or
     * failed completion.
     * <p>
     * This is a special method, designed for some cases, when an external method implicitly returns a Future type,
     * but the parent context does not care about the completion.
     * <p>
     * After this Future will successfully complete, a null be passed to the new Future.
     * <p>
     * If this Future completes with an exception, the new Future
     * will be completed with the same exception.
     * <p>
     * If the current Future is already completed successfully, a completed Future will be returned with the value of null.
     *
     * @return a new Future of Void type
     */
    @CanIgnoreReturnValue
    public Future<Void> discard() {
        return callback();
    }

    /**
     * Create a new Future that does not care about the completion value, it only checks for successful or
     * failed completion.
     * <p>
     * The Future will be completed successfully in every case.
     * <p>
     * The Future will be completed with the value of <code>true</code> if the current Future completes successfully,
     * and with the value of <code>false</code> if the current Future fails with an exception.
     *
     * @return a new Future of Boolean type
     */
    @CheckReturnValue
    public @NotNull Future<Boolean> status() {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<Boolean> future = new Future<>();
            boolean registered = addHandlersIfPending(
                ignored -> future.complete(true),
                ignored -> future.complete(false)
            );
            if (registered)
                return future;
            currentState = getState();
        }

        return completed(currentState == State.COMPLETED);
    }

    /**
     * Register a failure handler to be called when the Future completes with an error.
     * <p>
     * If the Future completes successfully, the specified <code>action</code> will not be called.
     * If you wish to handle successful completions as well,
     * use {@link #result(BiConsumer)} or {@link #then(Consumer)} methods.
     * <p>
     * If the Future is already completed unsuccessfully, the action will be called immediately with
     * the completion error. If the Future has completed with a result, the action will not be called.
     *
     * @param action the failed completion handler
     * @return this Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> except(@NotNull Consumer<Throwable> action) {
        if (addErrorHandlerIfPending(action))
            return this;

        if (getState() == State.FAILED)
            action.accept(error);
        return this;
    }

    /**
     * Register a failure handler to be called when the Future completes with an error.
     * <p>
     * If the Future completes successfully, the specified <code>action</code> will not be called.
     * If you wish to handle successful completions as well,
     * use {@link #result(BiConsumer)} or {@link #then(Consumer)} methods.
     * <p>
     * If the Future is already completed unsuccessfully, the action will be called immediately with
     * the completion error. If the Future has completed with a result, the action will not be called.
     *
     * @param action the failed completion handler
     * @return this Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> tryExcept(@NotNull ThrowableConsumer<Throwable, Throwable> action) {
        Consumer<Throwable> handler = error -> {
            try {
                action.accept(error);
            } catch (Throwable ignored) {
                // future is already failed, do not fail again
            }
        };

        if (addErrorHandlerIfPending(handler))
            return this;

        if (getState() == State.FAILED)
            handler.accept(error);
        return this;
    }

    /**
     * Register a failure handler to be called when the Future completes with an error.
     * <p>
     * If the Future completes successfully, the specified <code>action</code> will not be called.
     * If you wish to handle successful completions as well,
     * use {@link #result(BiConsumer)} or {@link #then(Consumer)} methods.
     * <p>
     * If the Future is already completed unsuccessfully, the action will be called immediately with
     * the completion error. If the Future has completed with a result, the action will not be called.
     *
     * @param action the failed completion handler
     * @return this Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> exceptAsync(@NotNull Consumer<Throwable> action) {
        Consumer<Throwable> handler = error -> executeAsync(() -> action.accept(error));
        if (addErrorHandlerIfPending(handler))
            return this;

        if (getState() == State.FAILED)
            handler.accept(error);
        return this;
    }

    /**
     * Register a failure handler to be called when the Future completes with an error.
     * <p>
     * If the Future completes successfully, nothing will happen.
     * If you wish to handle successful completions as well,
     * use {@link #result(BiConsumer)} or {@link #then(Consumer)} methods.
     * <p>
     * If the Future is already completed unsuccessfully, the produced error is printed to the console.
     *
     * @return this Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> exceptPrint() {
        return except(Throwable::printStackTrace);
    }

    /**
     * Create a new Future that will transform the exception from the old Future to a value.
     * <p>
     * If this Future completes successfully, the new Future will be completed
     * with the same exact value.
     * <p>
     * If this Future fails with an exception, the transformer will be called to
     * try to transform the exception to a fallback value. Finally, the value will be the
     * completion value of the new Future.
     * <p>
     * If the transformer's result is a constant, consider using {@link #fallback(Object)} instead,
     * as it does not require allocating a Function.
     *
     * @param transformer the function that transforms the error to T
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> fallback(@NotNull Function<Throwable, T> transformer) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<T> future = new Future<>();
            boolean registered = addHandlersIfPending(
                future::complete,
                error -> {
                    try {
                        future.complete(transformer.apply(error));
                    } catch (Exception e) {
                        future.fail(e);
                    }
                }
            );
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.COMPLETED)
            return completed(value);

        try {
            return completed(transformer.apply(error));
        } catch (Exception e) {
            return failed(e);
        }
    }

    /**
     * Create a new Future that will complete with the fallback value if this Future fails.
     * <p>
     * If this Future completes successfully, the new Future will be completed
     * with the same exact value.
     * <p>
     * If this Future fails with an exception, the fallback value will be used to complete the new Future.
     * This can be used for error recovery, or to produce a fallback object,
     * that will be returned upon unsuccessful completion.
     * <p>
     * If the fallback object is not a constant, consider using {@link #fallback(Function)} instead,
     * to allow dynamic fallback object creation.
     *
     * @param fallbackValue the value used if an exception occurs
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> fallback(@Nullable T fallbackValue) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<T> future = new Future<>();
            boolean registered = addHandlersIfPending(
                future::complete,
                error -> future.complete(fallbackValue)
            );
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED)
            return completed(fallbackValue);

        return completed(value);
    }

    /**
     * Create a new Future that will statically cast the value of the completion value to the specified class type.
     * <p>
     * If this Future completes successfully, the new Future will be completed
     * with the completion value cast to the specified type.
     * <p>
     * If this Future fails with an exception, the new Future will be failed with the same exception.
     *
     * @param type the type of the class to cast the completion value to
     * @return a new Future of the type U
     * @param <U> the type of the class to cast to
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> cast(@NotNull Class<U> type) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(
                value -> {
                    if (value != null && !value.getClass().isAssignableFrom(type))
                        future.fail(new ClassCastException(value.getClass() + " cannot be casted to " + type));
                    else
                        future.complete(type.cast(value));
                },
                future::fail
            );
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        T value = this.value;
        if (value != null && !value.getClass().isAssignableFrom(type))
            return failed(new ClassCastException(value.getClass() + " cannot be casted to " + type));

        return completed(type.cast(value));
    }

    /**
     * Register a special handler, that listens to both successful and unsuccessful completions.
     * <p>
     * After a successful completion, the specified action will be called with the result value,
     * and the exception will be <code>null</code>.
     * <p>
     * If the Future is completed with an exception, the result will be null, and the exception will be given.
     * <p>
     * If you wish to determine if the completion was successful, consider checking if the exception is
     * <code>null</code>, as the completion might be successful with a <code>null</code> result.
     * <pre>
     * future.result((value, exception) -> {
     *     if (exception == null) {
     *         // successful completion, handle result
     *     } else {
     *         // unsuccessful completion, handle exception
     *     }
     * });
     * </pre>
     * If the Future is already completed, the action will be called immediately
     * with the completed value or exception.
     *
     * @param action the completion value and error handler
     * @return this Future
     */
    @CanIgnoreReturnValue
    public @NotNull Future<T> result(@NotNull BiConsumer<T, Throwable> action) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            boolean registered = addHandlersIfPending(
                value -> action.accept(value, null),
                error -> action.accept(null, error)
            );
            if (registered)
                return this;
            currentState = getState();
        }

        action.accept(value, error);
        return this;
    }

    /**
     * Register a special handler, that listens to both successful and unsuccessful completions.
     * Use the transformer to create a new Future using the completion value and error.
     * <p>
     * After a successful completion, the specified action will be called with the result value,
     * and the exception will be <code>null</code>.
     * <p>
     * If the Future is completed with an exception, the result will be null, and the exception will be given.
     * <p>
     * If you wish to determine if the completion was successful, consider checking if the exception is
     * <code>null</code>, as the completion might be successful with a <code>null</code> result.
     * <pre>
     * future.result((value, exception) -> {
     *     if (exception == null) {
     *         // successful completion, handle result
     *     } else {
     *         // unsuccessful completion, handle exception
     *     }
     *     return modifiedValue;
     * });
     * </pre>
     * If the Future is already completed, the action will be called immediately
     * with the completed value or exception.
     *
     * @param transformer the Future value transformer
     * @return a new Future of type U
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> result(@NotNull BiFunction<T, Throwable, U> transformer) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<U> future = new Future<>();
            boolean registered = addHandlersIfPending(
                value -> {
                    try {
                        future.complete(transformer.apply(value, null));
                    } catch (Exception e) {
                        future.fail(e);
                    }
                },
                error -> {
                    try {
                        future.complete(transformer.apply(null, error));
                    } catch (Exception e) {
                        future.fail(e);
                    }
                }
            );
            if (registered)
                return future;
            currentState = getState();
        }

        try {
            return completed(transformer.apply(value, error));
        } catch (Exception e) {
            return failed(e);
        }
    }

    /**
     * Create a new Future, that will fail if the predicate fails for a value of a completion.
     *
     * @param predicate the function to test the completion value
     * @param error the error to fail the future with if the predicate fails
     * @return a new future that will fail if the predicate fails
     */
    @CheckReturnValue
    public @NotNull Future<T> filter(@NotNull Predicate<T> predicate, @NotNull Supplier<Throwable> error) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<T> future = new Future<>();
            boolean registered = addHandlersIfPending(
                value -> {
                    if (predicate.test(value))
                        future.complete(value);
                    else
                        future.fail(error.get());
                },
                future::fail
            );
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable completedError = this.error;
            assert completedError != null;
            return failed(completedError);
        }

        if (!predicate.test(value))
            return failed(error.get());

        return completed(value);
    }

    /**
     * Create a new Future, that will fail if the predicate fails for a value of a completion.
     *
     * @param predicate the function to test the completion value
     * @param error the error to fail the future with if the predicate fails
     * @return a new future that will fail if the predicate fails
     */
    @CheckReturnValue
    public @NotNull Future<T> filter(@NotNull Predicate<T> predicate, @NotNull Throwable error) {
        return filter(predicate, () -> error);
    }

    /**
     * Create a new Future, that will fail if the predicate fails for a value of a completion.
     * @param predicate the function to test the completion value
     * @return a new future that will fail if the predicate fails
     */
    @CheckReturnValue
    public @NotNull Future<T> filter(Predicate<T> predicate) {
        return filter(predicate, () -> new FutureExecutionException("Predicate failed for value `" + value + "`"));
    }

    /*
     * Fail the future if the specified predicate outputs an error.
     * This is useful when trying to fail a future, if the completion value turned out
     * to be something else than expected.
     * <p>
     * If you would like to handle errors as well, use {@link #failIf(BiFunction)} instead.
     *
     * @param predicate the function that returns an error if the future should be failed
     * @return a new Future
     */
    @CheckReturnValue
    public @NotNull Future<T> failIf(Function<T, @Nullable Throwable> predicate) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<T> future = new Future<>();
            boolean registered = addCompletionHandlerIfPending(value -> {
                Throwable error = predicate.apply(value);
                if (error != null)
                    future.fail(error);
                future.complete(value);
            });
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        Throwable error = predicate.apply(value);
        if (error != null)
            return failed(error);

        return completed(value);
    }

    /**
     * Fail the future if the specified predicate outputs an error.
     * This is useful when trying to fail a future, if the completion value turned out
     * to be something else than expected.
     * <p>
     * If you would like to handle the completion value only, use {@link #failIf(Function)} instead.
     *
     * @param predicate the function that returns an error if the future should be failed
     * @return a new Future
     */
    @CheckReturnValue
    public @NotNull Future<T> failIf(BiFunction<T, Throwable, @Nullable Throwable> predicate) {
        State currentState = getState();
        if (currentState == State.PENDING) {
            Future<T> future = new Future<>();
            boolean registered = addCompletionHandlerIfPending(value -> {
                Throwable error = predicate.apply(value, this.error);
                if (error != null)
                    future.fail(error);
                future.complete(value);
            });
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            return failed(error);
        }

        Throwable error = predicate.apply(value, this.error);
        if (error != null)
            return failed(error);

        return completed(value);
    }

    /**
     * Create a new Future, that will be completed unsuccessfully using a {@link FutureTimeoutException}
     * if the specified time has elapsed without a response. If this Future completes before the
     * timeout has passed, the new Future will be completed with this Future's result value.
     * <p>
     * If this Future completes unsuccessfully, the new Future will be completed with the same exception.
     *
     * @param timeout the time to wait (in milliseconds) until a {@link FutureTimeoutException} is thrown.
     * @return a new Future
     */
    @CheckReturnValue
    public @NotNull Future<T> timeout(long timeout) {
        Future<T> future = new Future<>();
        State currentState = getState();
        if (currentState == State.PENDING) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            boolean registered = addHandlersIfPending(
                value -> {
                    future.complete(value);
                    executor.shutdownNow();
                },
                error -> {
                    future.fail(error);
                    executor.shutdownNow();
                }
            );
            if (registered) {
                executor.schedule(() -> {
                    future.fail(new FutureTimeoutException(timeout));
                    executor.shutdown();
                }, timeout, TimeUnit.MILLISECONDS);
                return future;
            }
            executor.shutdownNow();
            currentState = getState();
        }

        if (currentState == State.COMPLETED)
            return completed(value);

        Throwable error = this.error;
        assert error != null;
        return failed(error);
    }

    /**
     * Create a new Future, that will be completed unsuccessfully using a {@link FutureTimeoutException}
     * if the specified time has elapsed without a response. If this Future completes before the
     * timeout has passed, the new Future will be completed with this Future's result value.
     * <p>
     * If this Future completes unsuccessfully, the new Future will be completed with the same exception.
     *
     * @param timeout the time to wait until a {@link FutureTimeoutException} is thrown.
     * @param unit the type of the timeout (milliseconds, seconds, etc.)
     * @return a new Future
     */
    @CheckReturnValue
    public @NotNull Future<T> timeout(long timeout, @NotNull TimeUnit unit) {
        return timeout(TimeUnit.MILLISECONDS.convert(timeout, unit));
    }

    /**
     * Create a new Future which acts the same way this Future does.
     * @return a new Future
     */
    @CheckReturnValue
    public @NotNull Future<T> mock() {
        Future<T> future = new Future<>();
        State currentState = getState();
        if (currentState == State.PENDING) {
            boolean registered = addHandlersIfPending(future::complete, future::fail);
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            future.fail(error);
        }
        else {
            future.complete(value);
        }

        return future;
    }

    /**
     * Chain the execution of another Future to this Future.
     * <p>
     * Wait for this Future to complete successfully, then call the other Future.
     * The new Future will be completed when the other Future is completed.
     * <p>
     * If this Future fails, the other Future will not be called.
     * <p>
     * If this Future completes, and then the other Future fails, the new Future
     * will be failed with the other Future's exception.
     * <p>
     * The completion value of the other Future will not be returned here.
     * In case you want to access that value, consider using {@link #transformAsync(Function)} instead.
     *
     * @param other the Future to complete after this Future completes
     * @return a new Future that will be completed when this- and the other Future completes
     * @param <U> the type of the other future
     *
     * @see #transformAsync(Function)
     */
    @CheckReturnValue
    public <U> @NotNull Future<T> chain(@NotNull Future<U> other) {
        Future<T> future = new Future<>();
        State currentState = getState();
        if (currentState == State.PENDING) {
            boolean registered = addHandlersIfPending(
                value -> other
                    .then(ignored -> future.complete(value))
                    .except(future::fail),
                future::fail
            );
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED) {
            Throwable error = this.error;
            assert error != null;
            future.fail(error);
        }
        else other
            .then(ignored -> future.complete(this.value))
            .except(future::fail);

        return future;
    }

    /**
     * Indicates whether the future completion had been done (either successfully or unsuccessfully).
     * In order to determine if the completion was successful, use {@link #isFailed()}.
     *
     * @return <code>true</code> if this Future has already completed, <code>false</code> otherwise
     * @see #isFailed()
     */
    @CheckReturnValue
    public boolean isCompleted() {
        return getState() != State.PENDING;
    }

    /**
     * Indicates whether the future was completed unsuccessfully.
     * If the Future hasn't been completed yet, this method returns <code>false</code>.
     *
     * @return <code>true</code> if the completion was unsuccessful, <code>false</code> otherwise
     * @see #isCompleted()
     */
    @CheckReturnValue
    public boolean isFailed() {
        return getState() == State.FAILED;
    }

    /**
     * Convert this Future to a Java {@link CompletableFuture}.
     * <p>
     * The returned CompletableFuture will be completed when this Future is completed.
     * If this Future is already completed, the CompletableFuture will be completed immediately.
     * <p>
     * If this Future fails, the CompletableFuture will be completed exceptionally.
     *
     * @return a new CompletableFuture
     */
    public @NotNull CompletableFuture<T> toJavaFuture() {
        CompletableFuture<T> future = new CompletableFuture<>();
        State currentState = getState();
        if (currentState == State.PENDING) {
            boolean registered = addHandlersIfPending(future::complete, future::completeExceptionally);
            if (registered)
                return future;
            currentState = getState();
        }

        if (currentState == State.FAILED)
            future.completeExceptionally(error);
        else
            future.complete(value);
        return future;
    }

    /**
     * Perform a task asynchronously on the context executor.
     *
     * @param task the task to perform
     */
    private void executeAsync(@NotNull Runnable task) {
        // use the executor of the caller's context to run the task on
        getExecutor(Thread.currentThread().getStackTrace()).execute(task);
    }

    /**
     * Get the current future state atomically.
     */
    private State getState() {
        State state = stateRef.get();
        while (state == State.COMPLETING || state == State.FAILING) {
            Threading.onSpinWait();
            state = stateRef.get();
        }
        return state;
    }

    /**
     * Get the public status of this Future.
     *
     * @return the stable status (pending, completed, failed)
     */
    public @NotNull Status getStatus() {
        State state = getState();
        switch (state) {
            case COMPLETED:
                return Status.COMPLETED;
            case FAILED:
                return Status.FAILED;
            default:
                return Status.PENDING;
        }
    }

    /**
     * Compare and set the future state atomically.
     */
    private boolean compareAndSetState(State expected, State newState) {
        return stateRef.compareAndSet(expected, newState);
    }

    /**
     * Update the future state atomically.
     */
    private void setState(State newState) {
        stateRef.set(newState);
    }

    private boolean isPendingLike(State state) {
        return state == State.PENDING || state == State.COMPLETING || state == State.FAILING;
    }

    /**
     * Register a completion handler while blocking writes against handlers.
     * <p>
     * It will register the handler if and only if the Future is still pending.
     *
     * @param handler the handler to register
     * @return {@code true} if the handler was registered, {@code false} otherwise
     */
    private boolean addCompletionHandlerIfPending(@NotNull Consumer<T> handler) {
        handlersLock.writeLock().lock();
        try {
            if (getState() != State.PENDING)
                return false;
            completionHandlers.add(handler);
            return true;
        } finally {
            handlersLock.writeLock().unlock();
        }
    }

    /**
     * Register a failure handler while blocking writes against handlers.
     * <p>
     * It will register the handler if and only if the Future is still pending.
     *
     * @param handler the handler to register
     * @return {@code true} if the handler was registered, {@code false} otherwise
     */
    private boolean addErrorHandlerIfPending(@NotNull Consumer<Throwable> handler) {
        handlersLock.writeLock().lock();
        try {
            if (getState() != State.PENDING)
                return false;
            errorHandlers.add(handler);
            return true;
        } finally {
            handlersLock.writeLock().unlock();
        }
    }

    /**
     * Register a completion and a failure handler while blocking writes against handlers.
     * <p>
     * It will register the handler if and only if the Future is still pending.
     *
     * @param completionHandler the associated completion handler
     * @param errorHandler the associated failure handler
     * @return {@code true} if the handler was registered, {@code false} otherwise
     */
    private boolean addHandlersIfPending(
        @Nullable Consumer<T> completionHandler,
        @Nullable Consumer<Throwable> errorHandler
    ) {
        handlersLock.writeLock().lock();
        try {
            if (getState() != State.PENDING)
                return false;
            if (completionHandler != null)
                completionHandlers.add(completionHandler);
            if (errorHandler != null)
                errorHandlers.add(errorHandler);
            return true;
        } finally {
            handlersLock.writeLock().unlock();
        }
    }

    /**
     * Create a new Future, that is completed initially using the specified value.
     *
     * @param value the completion result
     * @param <T> the type of the Future
     *
     * @return a new, completed Future
     */
    @CheckReturnValue
    public static <T> @NotNull Future<T> completed(@Nullable T value) {
        // create a new empty Future
        Future<T> future = new Future<>();

        // set the future state
        future.value = value;
        future.setState(State.COMPLETED);

        return future;
    }

    /**
     * Create a new Future, that is completed without a specified value.
     *
     * @return a new, completed Future
     */
    @CheckReturnValue
    public static @NotNull Future<Void> completed() {
        // create a new empty Future
        Future<Void> future = new Future<>();

        // set the future state
        future.setState(State.COMPLETED);

        return future;
    }

    /**
     * Create a new Future, that is failed initially using the specified error.
     *
     * @param error the completion error
     * @param <T> the type of the Future
     * @return a new, failed Future
     */
    @CheckReturnValue
    public static <T> @NotNull Future<T> failed(@NotNull Throwable error) {
        // create a new empty Future
        Future<T> future = new Future<>();

        // set the future state
        future.error = error;
        future.setState(State.FAILED);

        return future;
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread using the specified value.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is not a constant, consider using {@link #supplyAsync(Supplier, Executor)} instead,
     * as it does allow dynamic object creation.
     *
     * @param result the value that is used to complete the Future with
     * @param executor the executor used to complete the Future on
     * @param <T> the type of the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> completeAsync(@Nullable T result, @NotNull Executor executor) {
        // create an empty future
        Future<T> future = new Future<>();

        // complete the future on the executor thread
        executor.execute(() -> {
            try {
                future.complete(result);
            } catch (Exception e) {
                future.fail(e);
            }
        });
        return future;
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread using the specified value.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is not a constant, consider using {@link #supplyAsync(Supplier)} instead,
     * as it does allow dynamic object creation.
     *
     * @param result the value that is used to complete the Future with
     * @param <T> the type of the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> completeAsync(@Nullable T result) {
        return completeAsync(result, getExecutor(Thread.currentThread().getStackTrace()));
    }

    /**
     * Complete the Future successfully with the value given.
     * Call all the callbacks waiting on the completion of this Future.
     * <p>
     * If the supplier throws an exception, the Future will be completed with the exception.
     *
     * @param result the completion value supplier
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> supply(@NotNull Supplier<T> result) {
        return trySupply(result::get);
    }

    /**
     * Try to complete the Future successfully with the value given.
     * Call all the callbacks waiting on the completion of this Future.
     * <p>
     * If the supplier throws an exception, the Future will be completed with the exception.
     *
     * @param result the completion value supplier
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> trySupply(@NotNull ThrowableSupplier<T, Throwable> result) {
        Future<T> future = new Future<>();

        try {
            future.complete(result.get());
        } catch (Throwable e) {
            future.fail(e);
        }

        return future;
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread using the specified value.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is a constant, consider using {@link #completeAsync(Object, Executor)} instead,
     * as it does not require allocating a supplier.
     *
     * @param result the value that is used to complete the Future with
     * @param executor the executor used to complete the Future on
     * @param <T> the type of the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> supplyAsync(@NotNull Supplier<T> result, @NotNull Executor executor) {
        // create an empty future
        Future<T> future = new Future<>();

        // complete the future on the executor thread
        executor.execute(() -> {
            try {
                future.complete(result.get());
            } catch (Exception e) {
                future.fail(e);
            }
        });

        return future;
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread using the specified value.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is a constant, consider using {@link #completeAsync(Object)} instead,
     * as it does not require allocating a supplier.
     *
     * @param result the value that is used to complete the Future with
     * @param <T> the type of the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> supplyAsync(@NotNull Supplier<T> result) {
        return supplyAsync(result, getExecutor(Thread.currentThread().getStackTrace()));
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread using the specified value.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is a constant, consider using {@link #completeAsync(Object, Executor)} instead,
     * as it does not require allocating a supplier.
     *
     * @param result the value that is used to complete the Future with
     * @param executor the executor used to complete the Future on
     * @param <T> the type of the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> trySupplyAsync(
        @NotNull ThrowableSupplier<T, Throwable> result, @NotNull Executor executor
    ) {
        // create an empty future
        Future<T> future = new Future<>();

        // complete the future on the executor thread
        executor.execute(() -> {
            try {
                future.complete(result.get());
            } catch (Throwable e) {
                future.fail(e);
            }
        });

        return future;
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread using the specified value.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is a constant, consider using {@link #completeAsync(Object)} instead,
     * as it does not require allocating a supplier.
     *
     * @param result the value that is used to complete the Future with
     * @param <T> the type of the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> trySupplyAsync(@NotNull ThrowableSupplier<T, Throwable> result) {
        return Future.trySupplyAsync(result, getExecutor(Thread.currentThread().getStackTrace()));
    }

    /**
     * Complete the Future successfully by completing the specified action.
     * Call all the callbacks waiting on the completion of this Future.
     * <p>
     * If the action throws an exception, the Future will be completed with the exception.
     * <p>
     * If the action completes successfully, the Future will be completed with a <code>null</code> value.
     *
     * @param task the task to try to complete
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static @NotNull Future<Void> invoke(@NotNull Runnable task) {
        return tryInvoke(task::run);
    }

    /**
     * Try to complete the Future successfully by completing the specified action.
     * Call all the callbacks waiting on the completion of this Future.
     * <p>
     * If the action throws an exception, the Future will be completed with the exception.
     * <p>
     * If the action completes successfully, the Future will be completed with a <code>null</code> value.
     *
     * @param task the task to try to complete
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static @NotNull Future<Void> tryInvoke(@NotNull ThrowableRunnable<Throwable> task) {
        Future<Void> future = new Future<>();

        try {
            task.run();
            future.complete(null);
        } catch (Throwable e) {
            future.fail(e);
        }

        return future;
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread, after running the specified task.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is a constant, consider using {@link #completeAsync(Object, Executor)} instead,
     * as it does not require allocating a supplier.
     *
     * @param task the task to run to complete the future
     * @param executor the executor used to complete the Future on
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static @NotNull Future<Void> invokeAsync(@NotNull Runnable task, @NotNull Executor executor) {
        // create an empty future
        Future<Void> future = new Future<>();

        executor.execute(() -> {
            try {
                task.run();
                future.complete(null);
            } catch (Exception e) {
                future.fail(e);
            }
        });

        return future;
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread, after running the specified task.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is a constant, consider using {@link #completeAsync(Object)} instead,
     * as it does not require allocating a supplier.
     *
     * @param task the task to run to complete the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static @NotNull Future<Void> invokeAsync(@NotNull Runnable task) {
        return invokeAsync(task, getExecutor(Thread.currentThread().getStackTrace()));
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread, after running the specified task.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is a constant, consider using {@link #completeAsync(Object, Executor)} instead,
     * as it does not require allocating a supplier.
     *
     * @param task the task to run to complete the future
     * @param executor the executor used to complete the Future on
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static @NotNull Future<Void> tryInvokeAsync(
        @NotNull ThrowableRunnable<Throwable> task, @NotNull Executor executor
    ) {
        // create an empty future
        Future<Void> future = new Future<>();

        executor.execute(() -> {
            try {
                task.run();
                future.complete(null);
            } catch (Throwable e) {
                future.fail(e);
            }
        });

        return future;
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread, after running the specified task.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is a constant, consider using {@link #completeAsync(Object)} instead,
     * as it does not require allocating a supplier.
     *
     * @param task the task to run to complete the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static @NotNull Future<Void> tryInvokeAsync(@NotNull ThrowableRunnable<Throwable> task) {
        return tryInvokeAsync(task, getExecutor(Thread.currentThread().getStackTrace()));
    }

    /**
     * Create a new Future, will be completed using the specified future completer.
     * <p>
     * This can be used to complete a Future from an external context, such as a callback.
     * <p>
     * The callback is called immediately with a new {@link FutureResolver} value.
     *
     * @param callback the callback to pass the Future completer to
     * @return a new Future
     *
     * @param <T> the type of the Future
     */
    public static <T> @NotNull Future<T> resolve(
        @NotNull Consumer<FutureResolver<T>> callback
    ) {
        Future<T> future = new Future<>();

        FutureResolver<T> completer = new FutureResolver<T>() {
            @Override
            public boolean onComplete(@Nullable T result) {
                return future.complete(result);
            }

            @Override
            public boolean onFail(@NotNull Throwable error) {
                return future.fail(error);
            }
        };

        try {
            callback.accept(completer);
        } catch (Throwable e) {
            future.fail(e);
        }

        return future;
    }

    /**
     * Create a new Future, will be completed using the specified future completer.
     * <p>
     * This can be used to complete a Future from an external context, such as a callback.
     * <p>
     * The callback is called immediately with a new {@link FutureResolver} value.
     * <p>
     * If the callback throws an exception, the Future will be completed with the exception.
     *
     * @param callback the callback to pass the Future completer to
     * @return a new Future
     *
     * @param <T> the type of the Future
     */
    public static <T> @NotNull Future<T> tryResolve(
        @NotNull ThrowableConsumer<FutureResolver<T>, Throwable> callback
    ) {
        Future<T> future = new Future<>();

        FutureResolver<T> completer = new FutureResolver<T>() {
            @Override
            public boolean onComplete(@Nullable T result) {
                return future.complete(result);
            }

            @Override
            public boolean onFail(@NotNull Throwable error) {
                return future.fail(error);
            }
        };

        try {
            callback.accept(completer);
        } catch (Throwable e) {
            future.fail(e);
        }

        return future;
    }

    /**
     * Create a new Future, will be asynchronously completed using the specified future completer.
     * <p>
     * This can be used to complete a Future from an external context, such as a callback.
     * <p>
     * The callback is called immediately with a new {@link FutureResolver} value.
     * <p>
     * If the callback throws an exception, the Future will be completed with the exception.
     * <p>
     * The executor is used to run the callback on.
     *
     * @param callback the callback to pass the Future completer to
     * @param executor the executor used to complete the Future on
     * @return a new Future
     *
     * @param <T> the type of the Future
     */
    public static <T> @NotNull Future<T> resolveAsync(
        @NotNull Consumer<FutureResolver<T>> callback, @NotNull Executor executor
    ) {
        Future<T> future = new Future<>();

        FutureResolver<T> completer = new FutureResolver<T>() {
            @Override
            public boolean onComplete(@Nullable T result) {
                return future.complete(result);
            }

            @Override
            public boolean onFail(@NotNull Throwable error) {
                return future.fail(error);
            }
        };

        executor.execute(() -> {
            try {
                callback.accept(completer);
            } catch (Exception e) {
                future.fail(e);
            }
        });

        return future;
    }

    /**
     * Create a new Future, will be asynchronously completed using the specified future completer.
     * <p>
     * This can be used to complete a Future from an external context, such as a callback.
     * <p>
     * The callback is called immediately with a new {@link FutureResolver} value.
     * <p>
     * If the callback throws an exception, the Future will be completed with the exception.
     *
     * @param callback the callback to pass the Future completer to
     * @return a new Future
     * @param <T> the type of the Future
     */
    public static <T> @NotNull Future<T> resolveAsync(@NotNull Consumer<FutureResolver<T>> callback) {
        return resolveAsync(callback, getExecutor(Thread.currentThread().getStackTrace()));
    }

    /**
     * Create a new Future, will be asynchronously completed using the specified future completer.
     * <p>
     * This can be used to complete a Future from an external context, such as a callback.
     * <p>
     * The callback is called immediately with a new {@link FutureResolver} value.
     * <p>
     * If the callback throws an exception, the Future will be completed with the exception.
     * <p>
     * The executor is used to run the callback on.
     *
     * @param callback the callback to pass the Future completer to
     * @param executor the executor used to complete the Future on
     * @return a new Future
     *
     * @param <T> the type of the Future
     */
    public static <T> @NotNull Future<T> tryResolveAsync(
        @NotNull ThrowableConsumer<FutureResolver<T>, Throwable> callback, @NotNull Executor executor
    ) {
        Future<T> future = new Future<>();

        FutureResolver<T> completer = new FutureResolver<T>() {
            @Override
            public boolean onComplete(@Nullable T result) {
                return future.complete(result);
            }

            @Override
            public boolean onFail(@NotNull Throwable error) {
                return future.fail(error);
            }
        };

        executor.execute(() -> {
            try {
                callback.accept(completer);
            } catch (Throwable e) {
                future.fail(e);
            }
        });

        return future;
    }

    /**
     * Create a new Future, will be asynchronously completed using the specified future completer.
     * <p>
     * This can be used to complete a Future from an external context, such as a callback.
     * <p>
     * The callback is called immediately with a new {@link FutureResolver} value.
     * <p>
     * If the callback throws an exception, the Future will be completed with the exception.
     *
     * @param callback the callback to pass the Future completer to
     * @return a new Future
     * @param <T> the type of the Future
     */
    public static <T> @NotNull Future<T> tryResolveAsync(
        @NotNull ThrowableConsumer<FutureResolver<T>, Throwable> callback
    ) {
        return tryResolveAsync(callback, getExecutor(Thread.currentThread().getStackTrace()));
    }

    /**
     * Create a new Future, that will be completed when each of the specified futures are completed.
     * <p>
     * If any of the specified futures fail, the new Future will be failed with the exception.
     * <p>
     * The futures completion callbacks are executed parallel.
     *
     * @param futures the futures to wait for
     * @return a new Future
     */
    public static @NotNull Future<Void> all(@NotNull Future<?>... futures) {
        Future<Void> future = new Future<>();
        AtomicInteger counter = new AtomicInteger(futures.length);

        for (Future<?> f : futures) {
            f.then(val -> {
                if (counter.decrementAndGet() == 0)
                    future.complete(null);
            }).except(future::fail);
        }

        return future;
    }

    /**
     * Create a new Future, that will be completed when each of the specified futures are completed.
     * <p>
     * If any of the specified futures fail, the new Future will be failed with the exception.
     * <p>
     * The futures completion callbacks are executed parallel.
     *
     * @param futures the futures to wait for
     * @return a new Future
     */
    public static @NotNull Future<Void> all(@NotNull Collection<Future<?>> futures) {
        return all(futures.toArray(new Future[0]));
    }

    /**
     * Resolve the executor for the specified stack trace.
     *
     * @param stackTrace the stack trace of the method to be checked
     * @return the executor for the stack trace or the global executor
     */
    @CheckReturnValue
    private static @NotNull ExecutorService getExecutor(@NotNull StackTraceElement @NotNull [] stackTrace) {
        // validate that the class key and executor resolver functions are not set to null
        Validator.notNull(contextKeyMapper, "context key mapper");
        Validator.notNull(contextExecutorMapper, "context executor mapper");

        // retrieve the global executor if the stack trace may not contain the caller class
        if (stackTrace.length <= 2)
            return globalExecutor;

        // resolve the class type of the method's caller
        Class<?> type;
        try {
            type = Class.forName(stackTrace[2].getClassName());
        } catch (ClassNotFoundException ignored) {
            return globalExecutor;
        }

        // resolve the key to cache the class executor with
        Object key = contextKeyMapper.apply(type);
        Validator.notNull(key, "context key");

        // check if an executor is already cached for the key
        ExecutorService executor = contextExecutors.get(key);
        if (executor != null)
            return executor;

        // apply the function to resolve the executor with
        executor = contextExecutorMapper.apply(key);

        // cache the executor if the function was able to resolve it
        if (executor != null && executor != globalExecutor) {
            contextExecutors.put(key, executor);
            return executor;
        }

        // unable to resolve the executor, return the global executor instead
        return globalExecutor;
    }

    /**
     * Convert a Java {@link CompletableFuture} to a Future.
     * <p>
     * The returned Future will be completed when the CompletableFuture is completed.
     * If the CompletableFuture is already completed, the Future will be completed immediately.
     * <p>
     * If the CompletableFuture fails, the Future will be completed with the same exception.
     *
     * @param future the CompletableFuture to convert
     * @return a new Future
     * @param <T> the type of the Future
     */
    public static <T> @NotNull Future<T> fromJavaFuture(@NotNull CompletableFuture<T> future) {
        Future<T> newFuture = new Future<>();
        future.thenAccept(newFuture::complete);
        future.exceptionally(throwable -> {
            newFuture.fail(throwable);
            return null;
        });
        return newFuture;
    }

    /**
     * Attempt to shut down all pending tasks submitted to Futures for this context.
     * <p>
     * If the context's executor has been already shut down, an empty list is returned.
     * <p>
     * If the executor of the specified context could not be resolved, the shut down request is ignored.
     *
     * @param stackTrace the stack trace of the context to shut down at
     * @param force whether to force terminate running tasks
     *
     * @return the list of pending tasks if force is {@code true}, an empty list otherwise
     */
    public static @NotNull List<Runnable> shutdownContext(
        @NotNull StackTraceElement @NotNull [] stackTrace, boolean force
    ) {
        synchronized (contextExecutors) {
            ExecutorService executor = getExecutor(stackTrace);
            if (executor == globalExecutor)
                return Collections.emptyList();

            if (executor.isShutdown())
                return Collections.emptyList();

            if (force)
                return executor.shutdownNow();

            executor.shutdown();
            return Collections.emptyList();
        }
    }

    /**
     * Attempt to shut down all pending tasks submitted to Futures.
     * <p>
     * If any executor is already shut down, it is ignored.
     *
     * @param force whether to force terminate running tasks
     * @return the list of pending tasks if force is {@code true}, an empty list otherwise
     */
    public static @NotNull List<Runnable> shutdown(boolean force) {
        synchronized (contextExecutors) {
            Set<ExecutorService> executors = new HashSet<>();
            executors.add(globalExecutor);
            executors.addAll(contextExecutors.values());

            List<Runnable> tasks = new ArrayList<>();

            for (ExecutorService executor : executors) {
                if (executor.isShutdown())
                    continue;

                if (force)
                    tasks.addAll(executor.shutdownNow());
                else
                    executor.shutdown();
            }

            contextExecutors.clear();
            return tasks;
        }
    }
}
