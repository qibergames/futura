package com.atlas.futura.concurrent.future;

import lombok.Getter;

/**
 * Represents a future exception caused by exceeding the wait time limit.
 */
@Getter
public class FutureTimeoutException extends Exception {
    /**
     * The required maximum time for the future to complete.
     */
    private final long timeout;

    /**
     * Initialize the future timeout exception.
     * @param timeout future completion timeout
     */
    public FutureTimeoutException(long timeout) {
        super("Timeout of " + timeout + "ms exceeded.");
        this.timeout = timeout;
    }
}
