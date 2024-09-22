package com.atlas.futura.function;

import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Represents a supplier of results.
 *
 * <p>There is no requirement that a new or distinct result be returned each
 * time the supplier is invoked.
 *
 * <p>This is a <a href="package-summary.html">functional interface</a>
 * whose functional method is {@link #get()}.
 *
 * @param <T> the type of results supplied by this supplier
 */
@FunctionalInterface
public interface ThrowableSupplier<T, E extends Throwable> {
    /**
     * Gets a result.
     *
     * @return a result
     */
    T get() throws E;

    /**
     * Convert this throwable supplier to a java supplier.
     *
     * @return the java supplier representation
     */
    default @NotNull Supplier<T> toSupplier() {
        return () -> {
            try {
                return get();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Convert a java supplier to a throwable supplier.
     *
     * @param supplier the java supplier
     *
     * @param <T> the type of the result
     * @param <E> the type of the exception
     * 
     * @return the throwable supplier representation
     */
    static <T, E extends Throwable> @NotNull ThrowableSupplier<T, E> fomSupplier(@NotNull Supplier<T> supplier) {
        return supplier::get;
    }
}
