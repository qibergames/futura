package com.qibergames.futura.concurrent.future;

/**
 * Represents the completion state of a {@link Future}.
 * <p>
 * This indicates whether a Future is still pending, has completed successfully, or has completed with an error.
 * Accessible via {@link Future#getStatus()}.
 */
public enum Status {
    /**
     * The Future has not completed yet.
     * <p>
     * No completion value or error is available. Completion handlers may still be registered and will run once the
     * Future completes.
     */
    PENDING,

    /**
     * The Future completed successfully.
     * <p>
     * The completion value is available via {@link Future#get()}, {@link Future#tryGet()}, or related accessors.
     * Error handlers will not be invoked.
     */
    COMPLETED,

    /**
     * The Future completed with an error.
     * <p>
     * The completion error is available via {@link Future#get()} (as a {@link FutureExecutionException} cause).
     * Completion handlers will not be invoked.
     */
    FAILED
}
