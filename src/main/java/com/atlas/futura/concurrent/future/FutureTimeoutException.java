package com.atlas.futura.concurrent.future;

/**
 * Represents a future exception caused by exceeding the wait time limit.
 */
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

    /**
     * Returns the timeout that has been exceeded.
     * @return the timeout in milliseconds
     */
    public long getTimeout() {
        return timeout;
    }
}
