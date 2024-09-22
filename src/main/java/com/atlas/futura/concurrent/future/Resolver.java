package com.atlas.futura.concurrent.future;

import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Represents an interface that can be used to complete a {@link Future} from an external context.
 * <p>
 * This interface is implemented by {@link FutureResolver}.
 */
public interface Resolver {
    /**
     * Complete the Future successfully with a previously set value.
     * <p>
     * The completion value might be initialized beforehand, by the {@link FutureResolver}.
     *
     * @return <code>true</code> if the Future was completed with an error, <code>false</code> otherwise
     */
    @CanIgnoreReturnValue
    boolean complete();

    /**
     * Fail the Future completion with a previously set error.
     * <p>
     * The error might be initialized beforehand, by the {@link FutureResolver}.
     *
     * @return <code>true</code> if the Future was completed with an error, <code>false</code> otherwise
     */
    @CanIgnoreReturnValue
    boolean fail();
}
