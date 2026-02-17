package com.qibergames.futura.concurrent.future;

import static org.junit.jupiter.api.Assertions.*;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FutureFunctionalTest {
    @Test
    @SneakyThrows
    public void get() {
        completed: {
            Future<Integer> future = Future.completed(10);
            assertEquals(10, future.await());
        }

        delayed: {
            Future<Integer> future = Future.incomplete();
            Future.completed()
                .delay(1, TimeUnit.SECONDS)
                .then(v -> future.complete(123));
            assertEquals(123, future.await());
        }

        deadline: {
            Future<Integer> future = Future.incomplete();
            assertThrows(FutureTimeoutException.class, () -> future.get(TimeUnit.SECONDS.toMillis(1)));
        }

        failure: {
            Future<Integer> future = Future.failed(new RuntimeException("boo"));
            assertThrows(FutureExecutionException.class, future::get);
        }
    }

    @Test
    @SneakyThrows
    public void getOrDefault() {
        deadline: {
            Future<Integer> future = Future.incomplete();
            assertEquals(1234, future.getOrDefault(TimeUnit.SECONDS.toMillis(1), 1234));
        }

        failure: {
            Future<Integer> future = Future.failed(new RuntimeException("boo"));
            assertEquals(20, future.getOrDefault(20));
        }
    }

    @Test
    public void getNow() {
        completed: {
            Future<Integer> completed = Future.completed(10);
            assertEquals(10, completed.getNow(123));
        }

        incomplete: {
            Future<Integer> incomplete = Future.incomplete();
            assertEquals(100, incomplete.getNow(100));
        }
    }

    @Test
    public void tryGetNow() {
        completed: {
            Future<Integer> completed = Future.completed(10);
            assertTrue(completed.tryGetNow().isPresent());
        }

        incomplete: {
            Future<Integer> incomplete = Future.incomplete();
            assertFalse(incomplete.tryGetNow().isPresent());
        }
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

    @Test
    public void then() {
        pending: {
            Future<Integer> pending = Future.incomplete();
            AtomicInteger pendingRef = new AtomicInteger();
            pending.then(pendingRef::set).complete(20);
            assertEquals(20, pendingRef.get());
        }

        completed: {
            Future<Integer> completed = Future.completed(10);
            AtomicInteger completedRef = new AtomicInteger();
            completed.then(completedRef::set);
            assertEquals(10, completedRef.get());
        }

        failed: {
            Future<Integer> failed = Future.incomplete();
            AtomicBoolean failedCalled = new AtomicBoolean();

            failed.then(ignored -> failedCalled.set(true));
            failed.fail(new RuntimeException("boo"));

            assertFalse(failedCalled.get());
            assertThrows(FutureExecutionException.class, failed::get);
        }
    }

    @Test
    public void tryThen() {
        success: {
            Future<Integer> foo = Future.completed(10);
            AtomicInteger fooRef = new AtomicInteger();

            Future<Integer> ignored = foo.tryThen(fooRef::set);
            assertEquals(10, fooRef.get());
        }

        failure: {
            Future<Integer> bar = Future.completed(20);
            bar = bar.tryThen(val -> { throw new RuntimeException("boo"); });
            assertThrows(FutureExecutionException.class, bar::get);
        }
    }

    @Test
    public void thenAsync() {
        Thread main = Thread.currentThread();

        pending: {
            Future<Integer> pending = Future.incomplete();
            pending.thenAsync(val -> {
                assertNotEquals(Thread.currentThread(), main);
                assertEquals(10, val);
            });
            pending.complete(10);
        }

        completed: {
            Future<Integer> completed = Future.completed(20);
            completed.thenAsync(val -> {
                assertNotEquals(Thread.currentThread(), main);
                assertEquals(20, val);
            });
            completed.complete(20);
        }
    }

    @Test
    public void thenInvoke() {
        pending: {
            Future<Integer> future = Future.incomplete();
            AtomicBoolean invoked = new AtomicBoolean();
            future.thenInvoke(() -> invoked.set(true));
            future.complete(0);
            assertTrue(invoked.get());
        }

        completed: {
            Future<Integer> completed = Future.completed(20);
            AtomicBoolean invoked = new AtomicBoolean();
            completed.thenInvoke(() -> invoked.set(true));
            completed.complete(0);
            assertTrue(invoked.get());
        }

        failed: {
            Future<Integer> failed = Future.failed(new RuntimeException("boo"));
            AtomicBoolean invoked = new AtomicBoolean();
            failed.thenInvoke(() -> invoked.set(true));
            assertFalse(invoked.get());
        }

        failing: {
            Future<Integer> future = Future.incomplete();
            AtomicBoolean invoked = new AtomicBoolean();
            future.thenInvoke(() -> invoked.set(true));
            future.fail(new RuntimeException("boo"));
            assertFalse(invoked.get());
        }
    }
}
