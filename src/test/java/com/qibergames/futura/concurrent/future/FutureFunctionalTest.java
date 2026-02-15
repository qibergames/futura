package com.qibergames.futura.concurrent.future;

import static org.junit.jupiter.api.Assertions.*;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FutureFunctionalTest {
    @Test
    public void getNoTimeout() {
        Future<Integer> future = Future.completed(10);
        assertEquals(10, future.await());
    }

    @Test
    public void getWithDelayedCompletion() {
        Future<Integer> future = Future.incomplete();
        Future.completed()
            .delay(1, TimeUnit.SECONDS)
            .then(v -> future.complete(123));
        assertEquals(123, future.await());
    }

    @Test
    @SneakyThrows
    public void getDeadlineExceed() {
        Future<Integer> future = Future.incomplete();
        assertThrows(FutureTimeoutException.class, () -> future.get(TimeUnit.SECONDS.toMillis(1)));
    }

    @Test
    public void getFailure() {
        Future<Integer> future = Future.failed(new RuntimeException("boo"));
        assertThrows(FutureExecutionException.class, future::get);
    }

    @Test
    @SneakyThrows
    public void getDeadlineExceedWithDefaultValue() {
        Future<Integer> future = Future.incomplete();
        assertEquals(1234, future.getOrDefault(TimeUnit.SECONDS.toMillis(1), 1234));
    }

    @Test
    public void getFailureWithDefaultValue() {
        Future<Integer> future = Future.failed(new RuntimeException("boo"));
        assertEquals(20, future.getOrDefault(20));
    }

    @Test
    public void getNow() {
        Future<Integer> future = Future.completed(10);
        assertEquals(10, future.getNow(123));
    }

    @Test
    public void getNowWithIncompleteFuture() {
        Future<Integer> future = Future.incomplete();
        assertEquals(100, future.getNow(100));
    }

    @Test
    public void tryGetNowPresent() {
        Future<Integer> future = Future.completed(10);
        assertTrue(future.tryGetNow().isPresent());
    }

    @Test
    public void tryGetNowEmpty() {
        Future<Integer> future = Future.incomplete();
        assertFalse(future.tryGetNow().isPresent());
    }

    @Test
    public void complete() {
        AtomicInteger completionValue = new AtomicInteger(0);
        Future<Integer> future = Future.<Integer>incomplete().then(completionValue::set);

        assertTrue(future.complete(123));
        assertFalse(future.complete(456));

        assertEquals(123, completionValue.get());
    }

    @Test
    public void fail() {
        AtomicReference<Throwable> failure = new AtomicReference<>(null);
        Future<Object> future = Future.incomplete().except(failure::set);

        assertTrue(future.fail(new RuntimeException("boo")));
        assertFalse(future.fail(new RuntimeException("haha")));

        assertEquals("boo", failure.get().getMessage());
    }
}
