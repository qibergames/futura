package com.atlas.futura.concurrent.future;

import org.jetbrains.annotations.NotNull;

/**
 * An exception, that represents if a {@link Future} has completed unsuccessfully with or without an exception.
 * The original cause can be retrieved with {@link #getCause()}.
 */
public class FutureExecutionException extends Exception {
    /**
     * Initialize the future execution exception.
     */
    public FutureExecutionException() {
    }

    /**
     * Initialize the future execution exception.
     *
     * @param message the exception cause description
     */
    public FutureExecutionException(@NotNull String message) {
        super(message);
    }

    /**
     * Initialize the future execution exception.
     *
     * @param message the exception cause description
     * @param cause the exception cause error
     */
    public FutureExecutionException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }

    /**
     * Initialize the future execution exception.
     *
     * @param cause the exception cause error
     */
    public FutureExecutionException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Initialize the future execution exception.
     *
     * @param message exception cause description
     * @param cause exception cause error
     * @param enableSuppression whether suppression is enabled or disabled
     * @param writableStackTrace whether the stack trace should be writable
     */
    public FutureExecutionException(
        @NotNull String message, @NotNull Throwable cause, boolean enableSuppression, boolean writableStackTrace
    ) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
