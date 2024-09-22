package com.atlas.futura.util;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a utility class for validating values and states.
 */
@UtilityClass
public class Validator {
    /**
     * Validate that the specified {@param o} is not null.
     *
     * @param o the object to validate
     * @param name the name of the object
     *
     * @throws IllegalStateException if the object is null
     */
    public void notNull(@Nullable Object o, @NotNull String name) {
        if (o == null)
            throw new IllegalStateException("'" + name + "' must not be null");
    }
}
