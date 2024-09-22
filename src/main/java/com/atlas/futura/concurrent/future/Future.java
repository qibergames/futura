package com.atlas.futura.concurrent.future;

import com.atlas.futura.concurrent.threading.Threading;
import com.atlas.futura.function.ThrowableConsumer;
import com.atlas.futura.function.ThrowableFunction;
import com.atlas.futura.function.ThrowableRunnable;
import com.atlas.futura.function.ThrowableSupplier;
import com.atlas.futura.util.Validator;
import com.google.common.collect.MapMaker;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import lombok.Setter;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static @NotNull Executor globalExecutor = Threading.createVirtualOrPool(
        Runtime.getRuntime().availableProcessors()
    );

    /**
     * The map of executors that should be used for the specified contexts.
     */
    private static final @NotNull Map<@NotNull Object, @Nullable Executor> contextExecutors = new MapMaker()
        .weakKeys()
        .weakValues()
        .concurrencyLevel(4)
        .makeMap();

    /**
     * The function that is used to determine what information should be used from the class to group
     * multiple classes together, and cache a shared executor for each.
     */
    @Setter
    private static @NotNull Function<@NotNull Class<?>, @NotNull Object> contextKeyMapper = Class::getClassLoader;

    /**
     * The function that resolves an executor for the specified key. The key is resolved from the class by the
     * {@link #contextKeyMapper} function.
     */
    @Setter
    private static @NotNull Function<@NotNull Object, @Nullable Executor> contextExecutorMapper = key -> globalExecutor;

    /**
     * The object used for thread locking for unsafe value modifications.
     */
    private final @NotNull Object lock = new Object();

    /**
     * The list of the future completion handlers.
     */
    private final @NotNull List<@NotNull Consumer<@Nullable T>> completionHandlers = new CopyOnWriteArrayList<>();

    /**
     * The list of the future failure handlers.
     */
    private final @NotNull List<@NotNull Consumer<@NotNull Throwable>> errorHandlers = new CopyOnWriteArrayList<>();

    /**
     * The value of the completion result. Initially <code>null</code>, it is set to the completion object
     * after the completion is finished (which might still be <code>null</code>).
     */
    private volatile @Nullable T value;

    /**
     * The error that occurred whilst executing and caused a future failure.
     * Initially <code>null</code>, after a failure, it is guaranteed to be non-null.
     */
    private volatile @Nullable Throwable error;

    /**
     * Indicates whether the future completion had been done (either successfully or unsuccessfully).
     */
    private volatile boolean completed;

    /**
     * Indicates whether the future completion was failed.
     */
    private volatile boolean failed;

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
    private synchronized T blockForValue(
        long timeout, boolean hasDefault, @Nullable T defaultValue
    ) throws FutureTimeoutException, FutureExecutionException {
        // check if the future is already completed
        if (completed) {
            // check if the completion was successful
            if (!failed)
                return value;

            // completion was unsuccessful
            // return the default value if it is present
            if (hasDefault)
                return defaultValue;

            // no default value set, throw the completion error
            throw new FutureExecutionException(error);
        }

        // the future is not yet completed
        // ensure the lock is not used externally
        synchronized (lock) {
            try {
                // freeze the current thread until the future completion occurs
                // wait for the completion notification
                lock.wait(timeout);
            } catch (InterruptedException ignored) {
                // ignore if the completion thread was interrupted
            }
        }

        // check if the timeout has been exceeded, but the future hasn't been completed yet
        if (!completed)
            throw new FutureTimeoutException(timeout);

        // the future has been completed
        // check if the completion was successful
        if (!failed)
            return value;

        // the completion was unsuccessful
        // return the default value if it is present
        if (hasDefault)
            return defaultValue;

        // no default value set, throw the completion error
        throw new FutureExecutionException(error);
    }

    /**
     * Get instantly the completion value or the default value if the Future hasn't been completed yet.
     * @param defaultValue default value to return if the Future isn't completed
     * @return the completion value or the default value
     */
    @CheckReturnValue
    public T getNow(@Nullable T defaultValue) {
        return completed ? value : defaultValue;
    }

    /**
     * Complete the Future successfully with the value given.
     * Call all the callbacks waiting on the completion of this Future.
     * <p>
     * If this Future was already completed (either successful or unsuccessful), this method does nothing.
     *
     * @param value the completion value
     * @return <code>true</code> if the Future was completed with the value,
     * <code>false</code> otherwise
     */
    @CanIgnoreReturnValue
    public boolean complete(@Nullable T value) {
        // check if the future is already completed
        if (completed)
            return false;

        // set the completion value and unlock the waiting thread
        synchronized (lock) {
            this.value = value;
            completed = true;
            lock.notify();
        }

        // call the completion handlers
        handleCompleted(value);
        return true;
    }

    /**
     * Try to call the completion handlers.
     * @param value the completion value
     */
    private void handleCompleted(@Nullable T value) {
        // call the completion handlers
        for (Consumer<T> handler : completionHandlers) {
            try {
                // try call the completion handler
                handler.accept(value);
            } catch (Throwable e) {
                // fail the completion
                fail(e);
                // TODO should the loop break here?
            }
        }
    }

    /**
     * Fail the Future completion with the given error.
     * Call all the callbacks waiting on the failure of this Future.
     * <p>
     * If this Future was already completed (either successful or unsuccessful), this method does nothing.
     *
     * @param error the error occurred whilst completing
     * @return <code>true</code> if the Future was completed with an error, <code>false</code> otherwise
     */
    @CanIgnoreReturnValue
    public boolean fail(@NotNull Throwable error) {
        // check if the future is already completed
        if (completed)
            return false;

        // set the error and unlock the waiting thread
        synchronized (lock) {
            this.error = error;
            completed = true;
            failed = true;
            lock.notify();
        }

        // call the failure handlers
        handleFailed(error);
        return true;
    }

    /**
     * Try to call the completion handlers.
     * @param error the error occurred whilst completing
     */
    private void handleFailed(@NotNull Throwable error) {
        // call the completion handlers
        for (Consumer<Throwable> handler : errorHandlers) {
            try {
                // try call the failure handler
                handler.accept(error);
            } catch (Throwable ignored) {
                // TODO should it handle error handler exceptions?
            }
        }
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
        synchronized (lock) {
            // register the action if the Future hasn't been completed yet
            if (!completed)
                completionHandlers.add(action);

            // the Future is already completed
            // call the callback if the completion was successful
            else if (!failed)
                action.accept(value);

            return this;
        }
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
        synchronized (lock) {
            // register the action if the Future hasn't been completed yet
            if (!completed)
                completionHandlers.add(value -> {
                    try {
                        action.accept(value);
                    } catch (Throwable e) {
                        fail(e);
                    }
                });

            // the Future is already completed
            // call the callback if the completion was successful
            else if (!failed) {
                try {
                    action.accept(value);
                } catch (Throwable e) {
                    fail(e);
                }
            }

            return this;
        }
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
        synchronized (lock) {
            // register the action if the Future hasn't been completed yet
            if (!completed)
                completionHandlers.add(value -> executeLockedAsync(() -> action.accept(value)));

            // the Future is already completed
            // call the callback if the completion was successful
            else if (!failed)
                executeLockedAsync(() -> action.accept(value));

            return this;
        }
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
    public @NotNull Future<T> thenComplete(@NotNull Runnable task) {
        synchronized (lock) {
            Future<T> future = new Future<>();

            if (completed) {
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    future.fail(error);
                } else {
                    task.run();
                    future.complete(value);
                }
            }

            else {
                completionHandlers.add(value -> {
                    task.run();
                    future.complete(value);
                });
                errorHandlers.add(future::fail);
            }

            return future;
        }
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
    public @NotNull Future<T> thenTryComplete(@NotNull ThrowableRunnable<Throwable> task) {
        synchronized (lock) {
            Future<T> future = new Future<>();

            if (completed) {
                if (failed) {
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
            }

            else {
                completionHandlers.add(value -> {
                    try {
                        task.run();
                        future.complete(value);
                    } catch (Throwable e) {
                        future.fail(e);
                    }
                });
                errorHandlers.add(future::fail);
            }

            return future;
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
     * <p>
     *
     * @param transformer the function that transforms the value from T to U
     * @param <U> the new Future type
     * @return a new Future of type U
     */
    @CanIgnoreReturnValue
    public <U> @NotNull Future<U> transform(@NotNull Function<T, U> transformer) {
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                // try to transform the future value
                try {
                    return completed(transformer.apply(value));
                } catch (Exception e) {
                    // unable to transform the Future, return a failed Future
                    return failed(e);
                }
            }

            // the future hasn't been completed yet, create a new Future
            // that will try to transform the value once it is completed
            Future<U> future = new Future<>();

            // register the Future completion transformer
            completionHandlers.add(value -> {
                // try to transform the Future value
                try {
                    future.complete(transformer.apply(value));
                } catch (Exception e) {
                    // unable to transform the value, fail the Future
                    future.fail(e);
                }
            });

            // register the error handler
            errorHandlers.add(future::fail);
            return future;
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                // try to transform the future value
                try {
                    return completed(transformer.apply(value));
                } catch (Throwable e) {
                    // unable to transform the Future, return a failed Future
                    return failed(e);
                }
            }

            // the future hasn't been completed yet, create a new Future
            // that will try to transform the value once it is completed
            Future<U> future = new Future<>();

            // register the Future completion transformer
            completionHandlers.add(value -> {
                // try to transform the Future value
                try {
                    future.complete(transformer.apply(value));
                } catch (Throwable e) {
                    // unable to transform the value, fail the Future
                    future.fail(e);
                }
            });

            // register the error handler
            errorHandlers.add(future::fail);
            return future;
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                // try to transform the future value
                try {
                    return transformer.apply(value);
                } catch (Exception e) {
                    // unable to transform the Future, return a failed Future
                    return failed(e);
                }
            }

            // the future hasn't been completed yet, create a new Future
            // that will try to transform the value once it is completed
            Future<U> future = new Future<>();

            // register the Future completion transformer
            completionHandlers.add(value -> {
                // try to transform the Future value
                try {
                    transformer.apply(value).then(future::complete);
                } catch (Exception e) {
                    // unable to transform the value, fail the Future
                    future.fail(e);
                }
            });

            // register the error handler
            errorHandlers.add(future::fail);
            return future;
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                // try to transform the future value
                try {
                    return transformer.apply(value);
                } catch (Throwable e) {
                    // unable to transform the Future, return a failed Future
                    return failed(e);
                }
            }

            // the future hasn't been completed yet, create a new Future
            // that will try to transform the value once it is completed
            Future<U> future = new Future<>();

            // register the Future completion transformer
            completionHandlers.add(value -> {
                // try to transform the Future value
                try {
                    transformer.apply(value).then(future::complete);
                } catch (Throwable e) {
                    // unable to transform the value, fail the Future
                    future.fail(e);
                }
            });

            // register the error handler
            errorHandlers.add(future::fail);
            return future;
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
        synchronized (lock) {
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                else
                    return completed(value);
            }

            // create a new Future that will supply the specified value
            Future<U> future = new Future<>();

            // supply the value when this Future completes
            completionHandlers.add(ignored -> future.complete(value));

            // proxy the error to the new Future
            errorHandlers.add(future::fail);

            return future;
        }
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
        synchronized (lock) {
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                else
                    return completed(supplier.get());
            }

            Future<U> future = new Future<>();

            completionHandlers.add(value -> future.complete(supplier.get()));
            errorHandlers.add(future::fail);

            return future;
        }
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
        synchronized (lock) {
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
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

            Future<U> future = new Future<>();

            completionHandlers.add(value -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable error) {
                    future.fail(error);
                }
            });

            return future;
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
        synchronized (lock) {
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
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

            // create a new Future that will supply the specified value
            Future<U> future = new Future<>();

            // supply the value when this Future completes
            completionHandlers.add(ignored -> Future.completeAsync(supplier).then(future::complete));

            // proxy the error to the new Future
            errorHandlers.add(future::fail);

            return future;
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
        synchronized (lock) {
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
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

            // create a new Future that will supply the specified value
            Future<U> future = new Future<>();

            // try to supply the value when this Future completes
            completionHandlers.add(ignored -> Future.tryCompleteAsync(supplier)
                .then(future::complete)
                .except(future::fail));

            // proxy the error to the new Future
            errorHandlers.add(future::fail);

            return future;
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                // check if the completion was unsuccessful
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                // try to transform the future value
                try {
                    return completed();
                } catch (Exception e) {
                    // unable to transform the Future, return a failed Future
                    return failed(e);
                }
            }

            // the future hasn't been completed yet, create a new Future
            // that will try to transform the value once it is completed
            Future<Void> future = new Future<>();
            // register the Future completion transformer
            completionHandlers.add(value -> future.complete(null));

            // register the error handler
            errorHandlers.add(future::fail);
            return future;
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed)
                return completed(!failed);

            // create a new Future that will be completed with the status of this Future
            Future<Boolean> future = new Future<>();

            // complete the Future with true, if it completes successfully
            completionHandlers.add(ignored -> future.complete(true));

            // complete the Future with false, if it fails with an exception
            errorHandlers.add(ignored -> future.complete(false));

            return future;
        }
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
        synchronized (lock) {
            // register the action if the Future hasn't been completed yet
            if (!completed)
                errorHandlers.add(action);

            // the Future is already completed
            // call the callback if the completion was unsuccessful
            else if (failed)
                action.accept(error);

            return this;
        }
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
        synchronized (lock) {
            // register the action if the Future hasn't been completed yet
            if (!completed)
                errorHandlers.add(error -> {
                    try {
                        action.accept(error);
                    } catch (Throwable ignored) {
                        // future is already failed, do not fail again
                    }
                });

            // the Future is already completed
            // call the callback if the completion was unsuccessful
            else if (failed) {
                try {
                    action.accept(error);
                } catch (Throwable ignored) {
                    // future is already failed, do not fail again
                }
            }

            return this;
        }
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
        synchronized (lock) {
            // register the action if the Future hasn't been completed yet
            if (!completed)
                errorHandlers.add(error -> executeLockedAsync(() -> action.accept(error)));

            // the Future is already completed
            // call the callback if the completion was unsuccessful
            else if (failed)
                executeLockedAsync(() -> action.accept(error));

            return this;
        }
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                // check if the completion was successful
                if (!failed)
                    return completed(value);

                // try to transform the error to a value
                try {
                    return completed(transformer.apply(error));
                } catch (Exception e) {
                    // unable to transform the Future, return a failed Future
                    return failed(e);
                }
            }

            // the future hasn't been completed yet, create a new Future
            // that will try to transform the error once it is failed
            Future<T> future = new Future<>();

            // register the completion handler
            completionHandlers.add(future::complete);

            // register the error transformer
            errorHandlers.add(error -> {
                // try to transform the Future error
                try {
                    future.complete(transformer.apply(error));
                } catch (Exception e) {
                    // unable to transform the error, fail the Future
                    future.fail(e);
                }
            });

            return future;
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                // complete the Future with the fallback value if the
                // current Future's completion was failed
                if (failed)
                    return completed(fallbackValue);

                // the completion was successful, return the completion value
                return completed(value);
            }

            // the future hasn't been completed yet, create a new Future
            // that will use the fallback value if the current Future fails
            Future<T> future = new Future<>();

            // register the completion handler
            completionHandlers.add(future::complete);

            // register the error fallback handler
            errorHandlers.add(error -> future.complete(fallbackValue));
            return future;
        }
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                // return a failed future if this future is already failed
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                // check if the completed value cannot be cast to the specified type
                T value = this.value;
                if (value != null && !value.getClass().isAssignableFrom(type))
                    return failed(new ClassCastException(value.getClass() + " cannot be casted to " + type));

                // return a completed future if this future is already completed
                return completed(type.cast(value));
            }

            // the future hasn't been completed yet, create a new Future
            // that will cast the completion value if the current Future completes
            Future<U> future = new Future<>();

            // register the completion handler
            completionHandlers.add(value -> {
                if (value != null && !value.getClass().isAssignableFrom(type))
                    future.fail(new ClassCastException(value.getClass() + " cannot be casted to " + type));
                else
                    future.complete(type.cast(value));
            });

            // register the error fallback handler
            errorHandlers.add(future::fail);
            return future;
        }
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
        synchronized (lock) {
            // call the action if the Future is already completed
            if (completed) {
                action.accept(value, error);
                return this;
            }

            // the Future hasn't been completed yet, register the callbacks
            completionHandlers.add(value -> action.accept(value, null));
            errorHandlers.add(error -> action.accept(null, error));

            return this;
        }
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                // try to transform the value and create a new Future with it
                try {
                    return completed(transformer.apply(value, error));
                } catch (Exception e) {
                    // unable to transform the error, create a Failed future
                    return failed(e);
                }
            }

            // the Future hasn't been completed yet, create a new one
            Future<U> future = new Future<>();

            // register the completion transformer
            completionHandlers.add(value -> {
                // try to transform the value
                try {
                    future.complete(transformer.apply(value, null));
                } catch (Exception e) {
                    // unable to transform the error, fail the Future
                    future.fail(e);
                }
            });

            // register the failure transformer
            errorHandlers.add(error -> {
                // try to transform the error
                try {
                    future.complete(transformer.apply(null, error));
                } catch (Exception e) {
                    // unable to transform the error, fail the Future
                    future.fail(e);
                }
            });

            return future;
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
        synchronized (lock) {
            // check if the future is already completed
            if (completed) {
                // fail the future it was already failed
                if (failed) {
                    Throwable completedError = this.error;
                    assert completedError != null;
                    return failed(completedError);
                }

                // fail the future if the predicate did not pass
                if (!predicate.test(value))
                    return failed(error.get());

                return completed(value);
            }

            // create a future that will fail if the predicate fails the completion value
            Future<T> future = new Future<>();

            completionHandlers.add(value -> {
                if (predicate.test(value))
                    future.complete(value);
                else
                    future.fail(error.get());
            });

            errorHandlers.add(future::fail);

            return future;
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
        synchronized (lock) {
            // check if the future is already completed
            if (completed) {
                // check if the future is already failed
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                // run the predicate and test if the future should fail
                Throwable error = predicate.apply(value);
                if (error != null)
                    return failed(error);

                // future passed the predicate, return the completion value
                return completed(value);
            }

            Future<T> future = new Future<>();

            // the future isn't completed yet
            completionHandlers.add(value -> {
                // run the predicate and test if the future should fail
                Throwable error = predicate.apply(value);
                if (error != null)
                    future.fail(error);
                // future passed the predicate, complete with the value
                future.complete(value);
            });

            return future;
        }
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
        synchronized (lock) {
            // check if the future is already completed
            if (completed) {
                // check if the future is already failed
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    return failed(error);
                }

                // run the predicate and test if the future should fail
                Throwable error = predicate.apply(value, this.error);
                if (error != null)
                    return failed(error);

                // future passed the predicate, return the completion value
                return completed(value);
            }

            Future<T> future = new Future<>();

            // the future isn't completed yet
            completionHandlers.add(value -> {
                // run the predicate and test if the future should fail
                Throwable error = predicate.apply(value, this.error);
                if (error != null)
                    future.fail(error);
                // future passed the predicate, complete with the value
                future.complete(value);
            });

            return future;
        }
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
        synchronized (lock) {
            // create a new Future to send the timeout result to
            Future<T> future = new Future<>();
            // check if the future is already completed
            if (completed) {
                // check if the completion was successful
                if (!failed)
                    return completed(value);

                // future was failed, retrieve the error
                Throwable error = this.error;
                assert error != null;
                return failed(error);
            }

            // create a new thread to run the timeout countdown on
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            // register the completion handler
            completionHandlers.add(value -> {
                // complete the timeout future
                future.complete(value);
                // shutdown the timeout task
                executor.shutdownNow();
            });

            // register the error handler
            errorHandlers.add(error -> {
                // fail the timeout future
                future.fail(error);
                // shutdown the timeout task
                executor.shutdownNow();
            });

            // execute the completion using the timeout delay
            executor.schedule(() -> {
                // fail the future if it hasn't been completed yet, and the
                // timeout limit has exceeded
                future.fail(new FutureTimeoutException(timeout));
                // execution has been finished, shutdown the executor
                executor.shutdown();

            }, timeout, TimeUnit.MILLISECONDS);
            return future;
        }
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
        synchronized (lock) {
            // create a new Future
            Future<T> future = new Future<>();
            // check if the Future is already completed
            if (completed) {
                // check if the completion was failed
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    future.fail(error);
                }
                    // handle successful completion
                else
                    future.complete(value);
            }

            // the Future hasn't been completed yet
            else {
                // register the completion handler
                completionHandlers.add(future::complete);
                // register the error handler
                errorHandlers.add(future::fail);
            }

            return future;
        }
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
        synchronized (lock) {
            Future<T> future = new Future<>();

            // check if the Future is already completed
            if (completed) {
                // do not complete the other Future if this Future fails
                if (failed) {
                    Throwable error = this.error;
                    assert error != null;
                    future.fail(error);
                }
                // try to complete the other Future if this Future was already completed
                else other
                    .then(value -> future.complete(this.value))
                    .except(future::fail);
            }

            // the Future hasn't been completed yet
            else {
                // try to complete the other Future, when this Future will complete
                completionHandlers.add(value -> other
                    .then(ignored -> future.complete(value))
                    .except(future::fail));
                // fail the new Future if this Future fails
                errorHandlers.add(future::fail);
            }

            return future;
        }
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
        return completed;
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
        return failed;
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
        synchronized (lock) {
            // check if the Future is already completed
            if (completed) {
                if (failed)
                    future.completeExceptionally(error);
                else
                    future.complete(value);
                return future;
            }

            // handle pending Future completion
            completionHandlers.add(future::complete);
            errorHandlers.add(future::completeExceptionally);
        }
        return future;
    }

    /**
     * Perform a task whilst the value is locked.
     *
     * @param task the task to perform
     */
    private void executeLockedAsync(@NotNull Runnable task) {
        // use the executor of the caller's context to run the task on
        getExecutor(Thread.currentThread().getStackTrace()).execute(() -> {
            // lock the Future operations and run the task
            synchronized (lock) {
                task.run();
            }
        });
    }

    /**
     * Create a new Future, that is completed initially using the specified value.
     *
     * @param value the completion result
     * @param <T> the type of the Future
     * @return a new, completed Future
     */
    @CheckReturnValue
    public static <T> @NotNull Future<T> completed(@Nullable T value) {
        // create a new empty Future
        Future<T> future = new Future<>();

        // set the future state
        future.value = value;
        future.completed = true;

        return future;
    }

    /**
     * Create a new Future, that is completed without a specified value.
     *
     * @param <T> the type of the Future
     * @return a new, completed Future
     */
    @CheckReturnValue
    public static <T> @NotNull Future<T> completed() {
        // create a new empty Future
        Future<T> future = new Future<>();

        // set the future state
        future.completed = true;

        return future;
    }

    /**
     * Create a new Future, that is completed initially using the specified value.
     *
     * @param value the completion result
     * @param <T> the type of the Future
     * @return a new, completed Future
     */
    @CheckReturnValue
    public static <T> @NotNull Future<T> completed(@NotNull Supplier<T> value) {
        // create a new empty Future
        Future<T> future = new Future<>();

        // set the future state
        future.value = value.get();
        future.completed = true;

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
        future.completed = true;
        future.failed = true;

        return future;
    }

    /**
     * Create a new Future, that will be completed automatically on a different thread using the specified value.
     * <p>
     * Note that if the new Future is completed faster, than the current one is able to append any callbacks on it,
     * then some callbacks might be executed on the current thread.
     * Therefore, make sure to register the callbacks to this Future first.
     * <p>
     * If the result object is not a constant, consider using {@link #completeAsync(Supplier, Executor)} instead,
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
     * If the result object is a constant, consider using {@link #completeAsync(Object, Executor)} instead,
     * as it does not require allocating a supplier.
     *
     * @param result the value that is used to complete the Future with
     * @param executor the executor used to complete the Future on
     * @param <T> the type of the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> completeAsync(@NotNull Supplier<T> result, @NotNull Executor executor) {
        // create an empty future
        Future<T> future = new Future<>();

        // complete the future on the executor thread
        executor.execute(() -> {
            try {
                future.complete(result.get());
            } catch (Exception ignored) {
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
     * If the result object is a constant, consider using {@link #completeAsync(Object, Executor)} instead,
     * as it does not require allocating a supplier.
     *
     * @param result the value that is used to complete the Future with
     * @param executor the executor used to complete the Future on
     * @param <T> the type of the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> tryCompleteAsync(
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
     * If the result object is not a constant, consider using {@link #completeAsync(Supplier)} instead,
     * as it does allow dynamic object creation.
     *
     * @param result the value that is used to complete the Future with
     * @param <T> the type of the future
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> completeAsync(@Nullable T result) {
        // create an empty future
        Future<T> future = new Future<>();

        // use the executor of the caller class context to run the completion on
        getExecutor(Thread.currentThread().getStackTrace()).execute(() -> {
            // complete the future
            try {
                future.complete(result);
            } catch (Exception ignored) {
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
    public static <T> @NotNull Future<T> completeAsync(@NotNull Supplier<T> result) {
        // create an empty future
        Future<T> future = new Future<>();

        // use the executor of the caller class context to run the completion on
        getExecutor(Thread.currentThread().getStackTrace()).execute(() -> {
            // complete the future
            try {
                future.complete(result.get());
            } catch (Exception ignored) {
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
    public static <T> @NotNull Future<T> tryCompleteAsync(@NotNull ThrowableSupplier<T, Throwable> result) {
        // create an empty future
        Future<T> future = new Future<>();

        // use the executor of the caller class context to run the completion on
        getExecutor(Thread.currentThread().getStackTrace()).execute(() -> {
            // complete the future
            try {
                future.complete(result.get());
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
    public static @NotNull Future<Void> completeAsync(@NotNull Runnable task) {
        // create an empty future
        Future<Void> future = new Future<>();

        // use the executor of the caller class context to run the completion on
        getExecutor(Thread.currentThread().getStackTrace()).execute(() -> {
            try {
                task.run();
                future.complete(null);
            } catch (Exception ignored) {
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
    public static @NotNull Future<Void> tryCompleteAsync(@NotNull ThrowableRunnable<Throwable> task) {
        // create an empty future
        Future<Void> future = new Future<>();

        // use the executor of the caller class context to run the completion on
        getExecutor(Thread.currentThread().getStackTrace()).execute(() -> {
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
     * If the result object is a constant, consider using {@link #completeAsync(Object, Executor)} instead,
     * as it does not require allocating a supplier.
     *
     * @param task the task to run to complete the future
     * @param executor the executor used to complete the Future on
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static @NotNull Future<Void> completeAsync(@NotNull Runnable task, @NotNull Executor executor) {
        // create an empty future
        Future<Void> future = new Future<>();

        executor.execute(() -> {
            try {
                task.run();
                future.complete(null);
            } catch (Exception ignored) {
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
     * If the result object is a constant, consider using {@link #completeAsync(Object, Executor)} instead,
     * as it does not require allocating a supplier.
     *
     * @param task the task to run to complete the future
     * @param executor the executor used to complete the Future on
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static @NotNull Future<Void> completeAsync(
        @NotNull ThrowableRunnable<Throwable> task, @NotNull Executor executor
    ) {
        // create an empty future
        Future<Void> future = new Future<>();

        executor.execute(() -> {
            try {
                task.run();
                future.complete(null);
            } catch (Throwable ignored) {
            }
        });

        return future;
    }

    /**
     * Try to complete the Future successfully with the value given.
     * Call all the callbacks waiting on the completion of this Future.
     * <p>
     * If the supplier throws an exception, the Future will be completed with the exception.
     *
     * @param supplier the completion value supplier
     * @return a new Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> tryComplete(@NotNull ThrowableSupplier<T, Throwable> supplier) {
        Future<T> future = new Future<>();

        try {
            future.complete(supplier.get());
        } catch (Throwable e) {
            future.fail(e);
        }

        return future;
    }

    /**
     * Try to complete the Future successfully by completing the specified action.
     * Call all the callbacks waiting on the completion of this Future.
     * <p>
     * If the action throws an exception, the Future will be completed with the exception.
     * <p>
     *
     * @param action the task to try to complete
     * @return a new Future
     * @param <T> the type of the Future
     */
    @CanIgnoreReturnValue
    public static <T> @NotNull Future<T> tryComplete(@NotNull ThrowableRunnable<Throwable> action) {
        Future<T> future = new Future<>();

        try {
            action.run();
            future.complete(null);
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
     *
     * @param callback the callback to pass the Future completer to
     * @return a new Future
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
        callback.accept(completer);

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

        executor.execute(() -> callback.accept(completer));

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
    private static @NotNull Executor getExecutor(@NotNull StackTraceElement @NotNull [] stackTrace) {
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
        Executor executor = contextExecutors.get(key);
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
}
