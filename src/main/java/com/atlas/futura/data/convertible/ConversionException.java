package com.atlas.futura.data.convertible;

import org.jetbrains.annotations.NotNull;

/**
 * Represents an exception caused by an invalid argument conversion.
 */
public class ConversionException extends Exception {
    /**
     * Initialize the conversion exception.
     */
    public ConversionException() {
    }

    /**
     * Initialize the conversion exception.
     *
     * @param message the exception cause description
     */
    public ConversionException(@NotNull String message) {
        super(message);
    }

    /**
     * Initialize the conversion exception.
     *
     * @param message the exception cause description
     * @param cause the exception cause error
     */
    public ConversionException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }

    /**
     * Initialize the conversion exception.
     *
     * @param cause the exception cause error
     */
    public ConversionException(@NotNull Throwable cause) {
        super(cause);
    }

    /**
     * Initialize the conversion exception.
     *
     * @param message exception cause description
     * @param cause exception cause error
     * @param enableSuppression whether suppression is enabled or disabled
     * @param writableStackTrace whether the stack trace should be writable
     */
    public ConversionException(
        @NotNull String message, @NotNull Throwable cause, boolean enableSuppression, boolean writableStackTrace
    ) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
