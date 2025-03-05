package com.qibergames.futura.concurrent.atomic;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

/**
 * Represents an implementation of {@link VarHandle} for legacy java runtimes, that utilise the {@link Unsafe} API.
 *
 * @param <T> the type of the variable
 */
@Getter
class LegacyVarHandle<T> implements VarHandle<T> {
    /**
     * The object that owns the field (either a class or an instance).
     */
    private final @NotNull Object handle;

    /**
     * The field to create a handle for.
     */
    private final @NotNull Field field;

    /**
     * The internal offset of the field.
     */
    private final long offset;

    /**
     * Create a new {@link LegacyVarHandle} for the specified field.
     *
     * @param handle the object that owns the field (either a class or an instance)
     * @param field the field to create a handle for
     * @param isStatic whether the field is static or not
     */
    public LegacyVarHandle(@NotNull Object handle, @NotNull Field field, boolean isStatic) {
        this.handle = handle;
        this.field = field;
        this.offset = isStatic ? VarHandleSupport.UNSAFE.staticFieldOffset(field) : VarHandleSupport.UNSAFE.objectFieldOffset(field);
    }

    /**
     * Retrieve the value of the variable, with memory semantics of reading as if the variable was declared
     * non-{@code volatile}. Commonly referred to as plain read access.
     *
     * @return the held value of the variable
     */
    @Override
    @SuppressWarnings("unchecked")
    public T get() {
        verifyHandle();
        return (T) VarHandleSupport.UNSAFE.getObject(handle, offset);
    }

    /**
     * Retrieve the value of the variable, with memory semantics of reading as if the variable was declared
     * non-{@code volatile}. Commonly referred to as plain read access.
     *
     * @param handle the holder of the field (either a class or an instance)
     * @return the held value of the variable
     */
    @Override
    @SuppressWarnings("unchecked")
    public T get(@NotNull Object handle) {
        return (T) VarHandleSupport.UNSAFE.getObject(handle, offset);
    }

    /**
     * Retrieve the value of the variable, with memory semantics of reading as if the variable was declared
     * {@code volatile}.
     *
     * @return the held value of the variable
     */
    @Override
    @SuppressWarnings("unchecked")
    public T getVolatile() {
        verifyHandle();
        return (T) VarHandleSupport.UNSAFE.getObjectVolatile(handle, offset);
    }

    /**
     * Retrieve the value of the variable, with memory semantics of reading as if the variable was declared
     * {@code volatile}.
     *
     * @param handle the holder of the field (either a class or an instance)
     * @return the held value of the variable
     */
    @Override
    @SuppressWarnings("unchecked")
    public T getVolatile(@NotNull Object handle) {
        return (T) VarHandleSupport.UNSAFE.getObjectVolatile(handle, offset);
    }

    /**
     * Update the value of the variable to {@code newValue}, with memory semantics of setting as if the variable
     * was declared non-{@code volatile} and non-{@code final}. Commonly referred to as plain write access.
     *
     * @param value the new value of the variable
     */
    public void set(T value) {
        verifyHandle();
        VarHandleSupport.UNSAFE.putObject(handle, offset, value);
    }

    /**
     * Update the value of the variable to {@code newValue}, with memory semantics of setting as if the variable was
     * declared non-{@code volatile} and non-{@code final}. Commonly referred to as plain write access.
     *
     * @param handle the holder of the field (either a class or an instance)
     * @param value the new value of the variable
     */
    @Override
    public void set(@NotNull Object handle, T value) {
        VarHandleSupport.UNSAFE.putObject(handle, offset, value);
    }

    /**
     * Update the value of the variable to {@code newValue}, with memory semantics of setting as if the variable
     * was declared {@code volatile}.
     *
     * @param value the new value of the variable
     */
    public void setVolatile(T value) {
        verifyHandle();
        VarHandleSupport.UNSAFE.putObjectVolatile(handle, offset, value);
    }

    /**
     * Update the value of the variable to {@code newValue}, with memory semantics of setting as if the variable was
     * declared {@code volatile}.
     *
     * @param handle the holder of the field (either a class or an instance)
     * @param value the new value of the variable
     */
    @Override
    public void setVolatile(@NotNull Object handle, T value) {
        VarHandleSupport.UNSAFE.putObjectVolatile(handle, offset, value);
    }

    /**
     * Update the value of the variable to {@code newValue}.
     * <p>
     * This method acts like {@link #setVolatile(Object)}, except it does not guarantee immediate visibility of the
     * store to other threads. This method is generally only useful if the underlying field is a Java volatile (or if
     * an array cell, one that is otherwise only accessed using volatile accesses).
     *
     * @param value the new value of the variable
     */
    @Override
    public void setLazy(T value) {
        verifyHandle();
        VarHandleSupport.UNSAFE.putOrderedObject(handle, offset, value);
    }

    /**
     * Update the value of the variable to {@code newValue}.
     * <p>
     * This method acts like {@link #setVolatile(Object)}, except it does not guarantee immediate visibility of the
     * store to other threads. This method is generally only useful if the underlying field is a Java volatile (or if
     * an array cell, one that is otherwise only accessed using volatile accesses).
     *
     * @param handle the holder of the field (either a class or an instance)
     * @param value the new value of the variable
     */
    @Override
    public void setLazy(@NotNull Object handle, T value) {
        VarHandleSupport.UNSAFE.putOrderedObject(handle, offset, value);
    }

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
    public boolean compareAndSet(T expected, T update) {
        verifyHandle();
        return VarHandleSupport.UNSAFE.compareAndSwapObject(handle, offset, expected, update);
    }

    /**
     * Atomically set the value of the variable to {@code update} with the memory semantics of {@link #setVolatile} if
     * the variable's current value, referred to as the <em>witness value</em>, {@code ==} the {@code expected}, as
     * accessed with the memory semantics of {@link #getVolatile}.
     *
     * @param handle the holder of the field (either a class or an instance)
     * @param expected the condition of the update
     * @param update the new value of the variable
     * @return {@code true} if the value of the variable was changed, {@code false} otherwise
     */
    @Override
    public boolean compareAndSet(@NotNull Object handle, T expected, T update) {
        return VarHandleSupport.UNSAFE.compareAndSwapObject(handle, offset, expected, update);
    }

    /**
     * Atomically set the value of the variable to {@code newValue} with the memory semantics of {@link #setVolatile}
     * and return the variable's previous value, as accessed with the memory semantics of {@link #getVolatile}.
     *
     * @param newValue the new value of the variable
     * @return the previous value of the variable
     */
    public T getAndSet(T newValue) {
        verifyHandle();
        T prev;
        do {
            prev = get();
        } while (!compareAndSet(prev, newValue));
        return prev;
    }

    /**
     * Atomically set the value of the variable to {@code newValue} with the memory semantics of {@link #setVolatile}
     * and return the variable's previous value, as accessed with the memory semantics of {@link #getVolatile}.
     *
     * @param handle the holder of the field (either a class or an instance)
     * @param newValue the new value of the variable
     * @return the previous value of the variable
     */
    @Override
    public T getAndSet(@NotNull Object handle, T newValue) {
        T prev;
        do {
            prev = get(handle);
        } while (!compareAndSet(handle, prev, newValue));
        return prev;
    }

    /**
     * Check if the user is trying to invoke a function that requires an implicit handle.
     */
    private void verifyHandle() {
        if (handle == VarHandleSupport.DUMMY_HANDLE)
            throw new IllegalStateException("You must explicitly pass in a handle to use this function");
    }
}
