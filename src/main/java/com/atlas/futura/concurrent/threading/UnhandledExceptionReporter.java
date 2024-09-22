package com.atlas.futura.concurrent.threading;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a thread uncaught exception reporter.
 */
public class UnhandledExceptionReporter implements Thread.UncaughtExceptionHandler {
    /**
     * Handle uncaught thread exception.
     *
     * @param thread the target thread
     * @param e the unhandled exception
     */
    @Override
    public void uncaughtException(@NotNull Thread thread, @NotNull Throwable e) {
        System.err.println("An uncaught exception occurred on thread " + thread.getName() + "\n"
            + "\tpriority: " + thread.getPriority() + "\n"
            + "\tstate: " + thread.getState() + "\n"
            + "\tid: " + thread.getId()
        );
        e.printStackTrace();
    }
}
