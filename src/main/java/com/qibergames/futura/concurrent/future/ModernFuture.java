package com.qibergames.futura.concurrent.future;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.qibergames.futura.concurrent.atomic.VarHandle;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

public class ModernFuture<T> {
    private final List<Consumer<T>> completionHandlers = new ArrayList<>();
    private final List<Consumer<Throwable>> errorHandlers = new CopyOnWriteArrayList<>();

    private final ReentrantReadWriteLock completionLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock errorLock = new ReentrantReadWriteLock();

    private final VarHandle<Object> resultHandle;

    private volatile Object result;

    public ModernFuture() {
        resultHandle = VarHandle.ofInstance(this, "result");
    }

    @CanIgnoreReturnValue
    public boolean complete(T value) {
        return completeInternal(value);
    }

    private boolean completeInternal(T value) {
        return resultHandle.compareAndSet(null, new Completion(value));
    }

    @CanIgnoreReturnValue
    public boolean fail(@NotNull Throwable error) {
        return failInternal(error);
    }

    private boolean failInternal(@NotNull Throwable error) {
        return resultHandle.compareAndSet(null, new Failure(error));
    }

    @CanIgnoreReturnValue
    public ModernFuture<T> then(@NotNull Consumer<T> action) {
        return thenInternal(action, null);
    }

    public ModernFuture<T> thenInternal(@NotNull Consumer<T> action, @Nullable Executor executor) {
        Object result = resultHandle.getVolatile();
        if (result == null)
            return thenScheduleLater(action, executor);
        else
            return thenInvokeNow(result, action, executor);
    }

    private @NotNull ModernFuture<T> thenScheduleLater(@NotNull Consumer<T> action, @Nullable Executor executor) {
        ModernFuture<T> future = new ModernFuture<>();
        try {
            completionLock.writeLock().lock();
            completionHandlers.add(val -> {
                try {
                    if (executor == null) {
                        action.accept(val);
                        future.completeInternal(val);
                        return;
                    }

                    executor.execute(() -> {
                        try {
                            action.accept(val);
                            future.completeInternal(val);
                        } catch (Throwable error) {
                            future.failInternal(error);
                        }
                    });
                } catch (Throwable error) {
                    future.failInternal(error);
                }
            });

            errorHandlers.add(future::failInternal);
        } finally {
            completionLock.writeLock().unlock();
        }
        return future;
    }

    private @NotNull ModernFuture<T> thenInvokeNow(
        @NotNull Object result, @NotNull Consumer<T> action, @Nullable Executor executor
    ) {
        ModernFuture<T> future = new ModernFuture<>();
        if (result instanceof Failure) {
            future.result = result;
            return future;
        }

        try {
            @SuppressWarnings("unchecked")
            T completion = (T) ((Completion) result).value;

            if (executor == null) {
                action.accept(completion);
                future.result = result;
                return future;
            }

            executor.execute(() -> {
                try {
                    action.accept(completion);
                    future.result = result;
                } catch (Throwable error) {
                    future.result = new Failure(error);
                }
            });
        } catch (Throwable error) {
            future.result = new Failure(error);
        }

        return future;
    }

    @CanIgnoreReturnValue
    public @NotNull ModernFuture<T> except(@NotNull Consumer<Throwable> action) {
        Object result = resultHandle.getVolatile();
        if (result == null) {
            try {
                errorLock.writeLock().lock();
                errorHandlers.add(action);
            } finally {
                errorLock.writeLock().unlock();
            }
        } if (result instanceof Failure) {
            Throwable failure = ((Failure) result).error;
            action.accept(failure);
        }

        return this;
    }

    @Data
    private static class Completion {
        private final @NotNull Object value;
    }

    @Data
    private static class Failure {
        private final @NotNull Throwable error;
    }
}
