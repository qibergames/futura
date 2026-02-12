package com.qibergames.futura.concurrent.threading;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Represents a utility class for creating and managing threads.
 */
@UtilityClass
public class Threading {
    /**
     * The executor service creator factory.
     */
    private final @NotNull ThreadFactory FACTORY = new ThreadFactoryBuilder()
        .setNameFormat("thread-%d")
        .setPriority(7)
        .setUncaughtExceptionHandler(new UnhandledExceptionReporter())
        .build();

    /**
     * The reflection method handle for {@link Thread#onSpinWait()}.
     */
    private static final Method ON_SPIN_WAIT;

    static {
        Method method;
        try {
            method = Thread.class.getMethod("onSpinWait");
        } catch (NoSuchMethodException e) {
            method = null;
        }
        ON_SPIN_WAIT = method;
    }

    /**
     * Create a virtual executor service, or a thread pool if virtual threads are not supported by the JVM
     * in the current environment.
     *
     * @param poolSize the size of the pool
     * @return a virtual or pool executor service
     */
    @SneakyThrows
    public @NotNull ExecutorService createVirtualOrPool(int poolSize) {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) method.invoke(null);
        } catch (NoSuchMethodException e) {
            return Executors.newFixedThreadPool(poolSize, FACTORY);
        }
    }

    /**
     * Hint to the runtime that the current thread is in a spin-wait loop.
     * <p>
     * Uses {@link Thread#onSpinWait()} when available (Java 9+), otherwise
     * falls back to {@link Thread#yield()} for Java 8 compatibility.
     */
    public static void onSpinWait() {
        if (ON_SPIN_WAIT != null) {
            try {
                ON_SPIN_WAIT.invoke(null);
                return;
            } catch (ReflectiveOperationException ignored) {
                // fall through to yield
            }
        }
        Thread.yield();
    }
}
