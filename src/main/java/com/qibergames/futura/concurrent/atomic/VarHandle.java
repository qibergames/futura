package com.qibergames.futura.concurrent.atomic;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;

/**
 * Represents dynamically strongly typed reference to a variable, or to a parametrically-defined family of variables,
 * including static fields, non-static fields, array elements, or components of an off-heap data structure.
 * <p>
 * This interface is an abstraction over the legacy Unsafe, and the modern jdk VarHandle. This system will decide
 * which version to use, depending on the underlying platform.
 *
 * @param <T> the type of the variable
 */
public interface VarHandle<T> {
    /**
     * Retrieve the value of the variable, with memory semantics of reading as if the variable was declared
     * non-{@code volatile}. Commonly referred to as plain read access.
     *
     * @return the held value of the variable
     */
    T get();

    /**
     * Update the value of the variable to {@code newValue}, with memory semantics of setting as if the variable
     * was declared non-{@code volatile} and non-{@code final}. Commonly referred to as plain write access.
     *
     * @param value the new value of the variable
     */
    void set(T value);

    /**
     * Retrieve the value of the variable, with memory semantics of reading as if the variable was declared
     * {@code volatile}.
     *
     * @return the held value of the variable
     */
    T getVolatile();

    /**
     * Update the value of the variable to {@code newValue}, with memory semantics of setting as if the variable
     * was declared {@code volatile}.
     *
     * @param value the new value of the variable
     */
    void setVolatile(T value);

    /**
     * Atomically set the value of the variable to {@code update} with the memory semantics of {@link #setVolatile}
     * if the variable's current value, referred to as the <em>witness value</em>, {@code ==} the {@code expected},
     * as accessed with the memory semantics of {@link #getVolatile}.
     *
     * @param expected the condition of the update
     * @param update the new value of the variable
     *
     * @return {@code true} if the value of the variable was changed, {@code false} otherwise
     */
    boolean compareAndSet(T expected, T update);

    /**
     * Atomically set the value of the variable to {@code newValue} with the memory semantics of {@link #setVolatile}
     * and return the variable's previous value, as accessed with the memory semantics of {@link #getVolatile}.
     *
     * @param newValue the new value of the variable
     * @return the previous value of the variable
     */
    T getAndSet(T newValue);

    /**
     * Create a new {@link VarHandle} for the specified non-static {@code field} of an {@code instance}.
     *
     * @param instance the instance of the type which holds the field
     * @param field the target field to create a handle for
     **
     * @return a new {@link VarHandle} for the specified {@code field}
     *
     * @param <T> the type of the field
     */
    static <T> @NotNull VarHandle<T> ofInstance(@NotNull Object instance, @NotNull Field field) {
        return VarHandleSupport.createVarHandle(instance, field);
    }

    /**
     * Create a new {@link VarHandle} for the specified static {@code field} of a class {@code type}.
     *
     * @param type the class type which holds the field
     * @param field the target field to create a handle for
     *
     * @return a new {@link VarHandle} for the specified {@code field}
     *
     * @param <T> the type of the field
     */
    static <T> @NotNull VarHandle<T> ofStatic(@NotNull Class<?> type, @NotNull Field field) {
        return VarHandleSupport.createVarHandle(type, field);
    }
}
