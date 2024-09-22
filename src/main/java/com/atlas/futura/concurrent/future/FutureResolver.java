package com.atlas.futura.concurrent.future;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an interface that can be used to complete a {@link Future} from an external context.
 *
 * @param <T> the type of the future value
 */
@Getter
@Setter
public abstract class FutureResolver<T> implements Resolver {
    /**
     * The completion value of the Future.
     */
    private volatile @Nullable T value;

    /**
     * The error occurred whilst completing the Future.
     */
    private volatile @Nullable Throwable error;

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
        this.value = value;
        return onComplete(value);
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
    public abstract boolean onComplete(@Nullable T value);

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
        this.error = error;
        return onFail(error);
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
    public abstract boolean onFail(@NotNull Throwable error);

    /**
     * Complete the Future successfully with a previously set value.
     * <p>
     * The completion value might be initialized beforehand, by the {@link FutureResolver}.
     *
     * @return <code>true</code> if the Future was completed with an error, <code>false</code> otherwise
     */
    @CanIgnoreReturnValue
    public boolean complete() {
        return complete(value);
    }

    /**
     * Fail the Future completion with a previously set error.
     * <p>
     * The error might be initialized beforehand, by the {@link FutureResolver}.
     *
     * @return <code>true</code> if the Future was completed with an error, <code>false</code> otherwise
     */
    @CanIgnoreReturnValue
    public boolean fail() {
        Throwable error = this.error;
        if (error == null)
            error = new FutureExecutionException("Lazy resolver error was not provided.");
        return fail(error);
    }
}
