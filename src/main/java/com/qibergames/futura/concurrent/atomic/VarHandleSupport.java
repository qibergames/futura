package com.qibergames.futura.concurrent.atomic;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Represents a utility class that manages the creation of {@link VarHandle}s.
 */
@UtilityClass
class VarHandleSupport {
    /**
     * A placeholder value that is used to represent a dummy handle.
     */
    public static final Object DUMMY_HANDLE = new Object();

    /**
     * The singleton access to the {@link Unsafe} system.
     */
    public final Unsafe UNSAFE;

    /**
     * Create a new {@link VarHandle} based on the underlying platform.
     *
     * @param handle the object that owns the field (either a class or an instance)
     * @param field the field to create a handle for
     *
     * @return a new {@link VarHandle} for the {@code field}
     *
     * @param <T> the type of the field
     */
    public <T> @NotNull VarHandle<T> createVarHandle(@NotNull Object handle, @NotNull Field field, boolean isStatic) {
        // TODO create jdk native var handle for jdk9+
        return new LegacyVarHandle<>(handle, field, isStatic);
    }

    /**
     * Retrieve the field from the specified handle.
     *
     * @param handle the holder of the field (either a class or an instance)
     * @param fieldName the name of the field
     * @param isStatic whether the field is static or not
     *
     * @return the resolved field
     */
    public @NotNull Field getField(@NotNull Object handle, @NotNull String fieldName, boolean isStatic) {
        Class<?> type = isStatic ? (Class<?>) handle : handle.getClass();
        try {
            return type.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("No such field: " + fieldName, e);
        }
    }

    // attempt to resolve the Unsafe instance
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Unsafe", e);
        }
    }
}
