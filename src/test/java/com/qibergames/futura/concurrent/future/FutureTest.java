package com.qibergames.futura.concurrent.future;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FutureTest {
    @Test
    public void handlerRegistrationRaceDoesNotMissCompletion() throws InterruptedException {
        for (int i = 0; i < 1_000; i++) {
            Future<Integer> future = new Future<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger observed = new AtomicInteger(-1);

            Thread registerThread = new Thread(() -> {
                await(start);
                future.then(observed::set);
            });
            Thread completeThread = new Thread(() -> {
                await(start);
                future.complete(42);
            });

            registerThread.start();
            completeThread.start();
            start.countDown();

            registerThread.join();
            completeThread.join();

            assertEquals(42, observed.get());
        }
    }

    @Test
    public void handlerRegistrationRaceDoesNotMissFailure() throws InterruptedException {
        for (int i = 0; i < 1_000; i++) {
            Future<Integer> future = new Future<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean observed = new AtomicBoolean(false);

            Thread registerThread = new Thread(() -> {
                await(start);
                future.except(error -> observed.set(true));
            });
            Thread failThread = new Thread(() -> {
                await(start);
                future.fail(new RuntimeException("boom"));
            });

            registerThread.start();
            failThread.start();
            start.countDown();

            registerThread.join();
            failThread.join();

            assertTrue(observed.get());
        }
    }

    @Test
    public void completionRaceOnlyOneOutcomeWins() throws InterruptedException {
        for (int i = 0; i < 1_000; i++) {
            Future<Integer> future = new Future<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicBoolean failed = new AtomicBoolean(false);

            Thread completeThread = new Thread(() -> {
                await(start);
                completed.set(future.complete(1));
            });
            Thread failThread = new Thread(() -> {
                await(start);
                failed.set(future.fail(new RuntimeException("fail")));
            });

            completeThread.start();
            failThread.start();
            start.countDown();

            completeThread.join();
            failThread.join();

            assertTrue(completed.get() ^ failed.get());
            if (completed.get()) {
                assertFalse(future.isFailed());
            } else {
                assertTrue(future.isFailed());
            }
        }
    }

    @Test
    public void getWithTimeoutCompletesOrTimesOut() throws Exception {
        Future<String> pending = new Future<>();
        assertThrows(FutureTimeoutException.class, () -> pending.get(10));

        Future<String> future = new Future<>();
        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            future.complete("ok");
        });
        completer.start();

        assertEquals("ok", future.get(1_000));
        completer.join();
    }

    @Test
    public void handlerExceptionsDoNotStopOtherHandlers() {
        Future<Integer> future = new Future<>();
        AtomicBoolean ranSecond = new AtomicBoolean(false);

        future.then(value -> {
            throw new RuntimeException("boom");
        });
        future.then(value -> ranSecond.set(true));

        assertThrows(FutureRuntimeException.class, () -> future.complete(1));
        assertTrue(ranSecond.get());
    }

    @Test
    public void completeAndGetNowBehaveAsExpected() throws Exception {
        Future<String> future = new Future<>();
        assertFalse(future.tryGetNow().isPresent());

        future.complete("value");
        assertEquals("value", future.get());
        assertEquals("value", future.getNow("fallback"));
    }

    @Test
    public void failAndFallbackBehaveAsExpected() throws Exception {
        Future<String> future = new Future<>();
        future.fail(new IllegalStateException("boom"));

        assertEquals("fallback", future.getOrDefault("fallback"));
        assertThrows(FutureExecutionException.class, future::get);

        Future<String> fallback = future.fallback("alt");
        assertEquals("alt", fallback.get());
    }

    @Test
    public void transformAndTryTransformHandleExceptions() throws Exception {
        Future<Integer> source = new Future<>();
        source.complete(2);

        assertEquals("v2", source.transform(value -> "v" + value).get());

        Future<Integer> failed = new Future<>();
        failed.complete(1);
        Future<Integer> transformed = failed.tryTransform(value -> {
            throw new IllegalArgumentException("bad");
        });

        assertThrows(FutureExecutionException.class, transformed::get);
    }

    @Test
    public void statusAndExceptBehaveAsExpected() throws Exception {
        Future<String> success = new Future<>();
        Future<Boolean> successStatus = success.status();
        success.complete("ok");
        assertEquals(true, successStatus.get());

        Future<String> failed = new Future<>();
        AtomicBoolean exceptCalled = new AtomicBoolean(false);
        failed.except(error -> exceptCalled.set(true));
        failed.fail(new RuntimeException("nope"));
        assertTrue(exceptCalled.get());
        assertEquals(false, failed.status().get());
    }

    @Test
    public void toJavaFutureCompletesOrFails() {
        Future<String> success = new Future<>();
        success.complete("ok");
        assertEquals("ok", success.toJavaFuture().join());

        Future<String> failed = new Future<>();
        failed.fail(new RuntimeException("boom"));
        CompletionException error = assertThrows(CompletionException.class, () -> failed.toJavaFuture().join());
        assertNotNull(error.getCause());
    }

    @Test
    public void chainCompletesWithParentValue() throws Exception {
        Future<String> parent = new Future<>();
        Future<Integer> other = new Future<>();
        Future<String> chained = parent.chain(other);

        other.complete(1);
        parent.complete("parent");

        assertEquals("parent", chained.get());
    }

    @Test
    public void getOrThrowUsesProvidedException() {
        Future<String> future = new Future<>();
        future.fail(new RuntimeException("boom"));

        IllegalArgumentException provided = new IllegalArgumentException("provided");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> future.getOrThrow(provided));
        assertEquals(provided, thrown);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }
}
