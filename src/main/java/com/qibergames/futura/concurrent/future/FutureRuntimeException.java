package com.qibergames.futura.concurrent.future;

import org.jetbrains.annotations.NotNull;

public class FutureRuntimeException extends RuntimeException {
    public FutureRuntimeException(@NotNull String message) {
        super(message);
    }

    public FutureRuntimeException(@NotNull String message, @NotNull Throwable cause) {
        super(message, cause);
    }
}
