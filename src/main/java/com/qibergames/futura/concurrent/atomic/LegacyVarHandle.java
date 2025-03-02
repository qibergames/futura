package com.qibergames.futura.concurrent.atomic;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import static com.qibergames.futura.concurrent.atomic.VarHandleSupport.UNSAFE;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@Getter
class LegacyVarHandle<T> implements VarHandle<T> {
    private final @NotNull Object handle;
    private final @NotNull Field field;
    private final long offset;

    public LegacyVarHandle(@NotNull Object handle, @NotNull Field field) {
        this.handle = handle;
        this.field = field;
        this.offset = Modifier.isStatic(field.getModifiers()) ?
            UNSAFE.staticFieldOffset(field) :
            UNSAFE.objectFieldOffset(field);
    }

    /**
     * Retrieve the value of the variable, with memory semantics of reading as if the variable was declared
     * non-{@code volatile}. Commonly referred to as plain read access.
     *
     * @return the held value of the variable
     */
    @SuppressWarnings("unchecked")
    public T get() {
        return (T) UNSAFE.getObject(handle, offset);
    }

    /**
     * Update the value of the variable to {@code newValue}, with memory semantics of setting as if the variable
     * was declared non-{@code volatile} and non-{@code final}. Commonly referred to as plain write access.
     *
     * @param value the new value of the variable
     */
    public void set(T value) {
        UNSAFE.putObject(handle, offset, value);
    }

    /**
     * Retrieve the value of the variable, with memory semantics of reading as if the variable was declared
     * {@code volatile}.
     *
     * @return the held value of the variable
     */
    @SuppressWarnings("unchecked")
    public T getVolatile() {
        return (T) UNSAFE.getObjectVolatile(handle, offset);
    }

    /**
     * Update the value of the variable to {@code newValue}, with memory semantics of setting as if the variable
     * was declared {@code volatile}.
     *
     * @param value the new value of the variable
     */
    public void setVolatile(T value) {
        UNSAFE.putObjectVolatile(handle, offset, value);
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
        return UNSAFE.compareAndSwapObject(handle, offset, expected, update);
    }

    /**
     * Atomically set the value of the variable to {@code newValue} with the memory semantics of {@link #setVolatile}
     * and return the variable's previous value, as accessed with the memory semantics of {@link #getVolatile}.
     *
     * @param newValue the new value of the variable
     * @return the previous value of the variable
     */
    public T getAndSet(T newValue) {
        T prev;
        do {
            prev = get();
        } while (!compareAndSet(prev, newValue));
        return prev;
    }
}
