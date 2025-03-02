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
    public <T> @NotNull VarHandle<T> createVarHandle(@NotNull Object handle, @NotNull Field field) {
        // TODO create jdk native var handle for jdk9+
        return new LegacyVarHandle<>(handle, field);
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
