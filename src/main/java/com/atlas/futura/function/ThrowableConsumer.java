package com.atlas.futura.function;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Represents an operation that accepts a single input argument and returns no
 * result. Unlike most other functional interfaces, {@code ThrowableConsumer} is expected
 * to operate via side effects.
 *
 * <p>This is a <a href="package-summary.html">functional interface</a>
 * whose functional method is {@link #accept(Object)}.
 *
 * @param <T> the type of the input to the operation
 */
@FunctionalInterface
public interface ThrowableConsumer<T, E extends Throwable> {
    /**
     * Performs this operation on the given argument.
     *
     * @param t the input argument
     */
    void accept(T t) throws E;

    /**
     * Returns a composed {@code Consumer} that performs, in sequence, this
     * operation followed by the {@code after} operation. If performing either
     * operation throws an exception, it is relayed to the caller of the
     * composed operation. If performing this operation throws an exception,
     * the {@code after} operation will not be performed.
     *
     * @param after the operation to perform after this operation
     * @return a composed {@code Consumer} that performs in sequence this
     * operation followed by the {@code after} operation
     *
     * @throws NullPointerException if {@code after} is null
     */
    default @NotNull ThrowableConsumer<T, E> andThen(@NotNull ThrowableConsumer<? super T, E> after) {
        Objects.requireNonNull(after);
        return (T t) -> { accept(t); after.accept(t); };
    }

    /**
     * Convert this throwable consumer to a java consumer.
     *
     * @return the java consumer representation
     */
    default @NotNull Consumer<T> toConsumer() {
        return t -> {
            try {
                accept(t);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Convert a java consumer to a throwable consumer.
     *
     * @param consumer the java consumer
     * @return the throwable consumer representation
     */
    static <T, E extends Throwable> @NotNull ThrowableConsumer<T, E> fromConsumer(@NotNull Consumer<T> consumer) {
        return consumer::accept;
    }
}
