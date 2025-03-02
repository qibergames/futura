package com.atlas.futura.concurrent.future;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Future represents a callback, which can be completed or failed either synchronously or asynchronously.
 * A Future can be completed with the creation of A new I object, and can be failed by an exception
 * happening whilst executing a Future task.
 * <p>
 * This class also contains useful methods to attach callbacks for completion/failure events,
 * and to create new Future objects based on this instance.
 * <br>
 * The syntax encourages chaining, therefore less code is needed to handle certain tasks/events.
 *
 * @param <T> the type of the returned value of the completed Future
 *
 * @author AdvancedAntiSkid
 * @author MrGazdag
 *
 * @since 1.0
 */
public interface Promise<T> extends Callback<T> {
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
    T get() throws FutureExecutionException;

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
    @CanIgnoreReturnValue
    T await();

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
    T get(long timeout) throws FutureTimeoutException, FutureExecutionException;

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
    T getOrDefault(@Nullable T defaultValue);

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
    T getOrDefault(long timeout, @Nullable T defaultValue) throws FutureTimeoutException;

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
    <E extends Throwable> T getOrThrow(long timeout, @NotNull E error) throws E;

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
    <E extends Throwable> T getOrThrow(@NotNull E error) throws E;

    /**
     * Get instantly the completion value or the default value if the Future hasn't been completed yet.
     *
     * @param defaultValue default value to return if the Future isn't completed
     * @return the completion value or the default value
     */
    @CheckReturnValue
    T getNow(@Nullable T defaultValue);
}
