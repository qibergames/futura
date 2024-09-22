package com.atlas.futura.concurrent.future;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

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
public interface Callback<T> {
    /**
     * Register a completion handler to be called when the Future completes without an error.
     * <p>
     * If the Future completes with an exception, the specified <code>action</code> will not be called.
     * <p>
     * If the Future is already completed successfully, the action will be called immediately with
     * the completion value. If the Future failed with an exception, the action will not be called.
     *
     * @param action the successful completion callback
     * @return this Future
     */
    @CanIgnoreReturnValue
    @NotNull Future<T> then(@NotNull Consumer<T> action);

    /**
     * Register a failure handler to be called when the Future completes with an error.
     * <p>
     * If the Future completes successfully, the specified <code>action</code> will not be called.
     * <p>
     * If the Future is already completed unsuccessfully, the action will be called immediately with
     * the completion error. If the Future has completed with a result, the action will not be called.
     *
     * @param action the failed completion handler
     * @return this Future
     */
    @CanIgnoreReturnValue
    @NotNull Future<T> except(@NotNull Consumer<Throwable> action);
}
