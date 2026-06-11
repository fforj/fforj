package dev.fforj;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopesTest {

    @Test
    void parallel_all_ok_returns_valid_with_each_value() throws InterruptedException {
        Validated<Failure, List<Integer>> v = Scopes.parallel(
                List.of(
                        () -> Result.ok(1),
                        () -> Result.ok(2),
                        () -> Result.ok(3)
                ),
                t -> new Failure.Message("uncaught: " + t.getClass().getSimpleName())
        );

        assertEquals(Validated.<Failure, List<Integer>>valid(List.of(1, 2, 3)), v);
    }

    @Test
    void parallel_accumulates_every_error() throws InterruptedException {
        Validated<Failure, List<Integer>> v = Scopes.parallel(
                List.<Supplier<? extends Result<Failure, Integer>>>of(
                        () -> Result.err(new Failure.Message("first")),
                        () -> Result.ok(2),
                        () -> Result.err(new Failure.Message("third"))
                ),
                t -> new Failure.Message("uncaught: " + t.getClass().getSimpleName())
        );

        // We don't assert ordering — parallel completion order is not guaranteed.
        assertTrue(v instanceof Validated.Invalid<Failure, List<Integer>>);
        var errs = ((Validated.Invalid<Failure, List<Integer>>) v).errors().toList();
        assertEquals(2, errs.size());
        assertTrue(errs.contains(new Failure.Message("first")));
        assertTrue(errs.contains(new Failure.Message("third")));
    }

    @Test
    void parallel_collects_uncaught_exceptions_via_onThrow() throws InterruptedException {
        Validated<Failure, List<Integer>> v = Scopes.parallel(
                List.<Supplier<? extends Result<Failure, Integer>>>of(
                        () -> { throw new RuntimeException("kaboom"); },
                        () -> Result.ok(2)
                ),
                t -> new Failure.Message("thrown: " + t.getMessage())
        );

        assertTrue(v instanceof Validated.Invalid<Failure, List<Integer>>);
        var errs = ((Validated.Invalid<Failure, List<Integer>>) v).errors().toList();
        assertEquals(List.of(new Failure.Message("thrown: kaboom")), errs);
    }

    @Test
    void parallel_empty_input_returns_valid_empty_list() throws InterruptedException {
        Validated<Failure, List<Integer>> v = Scopes.parallel(
                List.of(),
                t -> new Failure.Message("x")
        );

        assertEquals(Validated.<Failure, List<Integer>>valid(List.of()), v);
    }

    @Test
    void race_returns_the_first_successful_result() throws InterruptedException {
        // Deterministic ordering without sleeps: the slow task blocks on a latch that is
        // never opened — it can only finish by being cancelled when the fast Ok wins.
        var neverOpened = new CountDownLatch(1);

        Result<Failure, String> r = Scopes.race(
                List.<Supplier<? extends Result<Failure, String>>>of(
                        () -> {
                            try {
                                neverOpened.await();
                            } catch (InterruptedException expected) {
                                Thread.currentThread().interrupt();
                            }
                            return Result.ok("slow");
                        },
                        () -> Result.ok("fast")
                ),
                t -> new Failure.Message("thrown: " + t.getMessage())
        );

        assertEquals(Result.<Failure, String>ok("fast"), r);
    }

    @Test
    void race_waits_for_a_slow_ok_instead_of_returning_a_fast_err() throws InterruptedException {
        // Regression for the audit finding: an Err completing first must NOT win the
        // race. The latch guarantees the Err returns before the Ok even starts finishing.
        var errReturned = new CountDownLatch(1);

        Result<Failure, String> r = Scopes.race(
                List.<Supplier<? extends Result<Failure, String>>>of(
                        () -> {
                            var err = Result.<Failure, String>err(new Failure.Message("fast failure"));
                            errReturned.countDown();
                            return err;
                        },
                        () -> {
                            try {
                                errReturned.await();
                            } catch (InterruptedException unexpected) {
                                Thread.currentThread().interrupt();
                                return Result.err(new Failure.Message("interrupted"));
                            }
                            return Result.ok("slow success");
                        }
                ),
                t -> new Failure.Message("thrown: " + t.getMessage())
        );

        assertEquals(Result.<Failure, String>ok("slow success"), r);
    }

    @Test
    void race_returns_an_error_when_every_task_fails() throws InterruptedException {
        Result<Failure, String> r = Scopes.race(
                List.<Supplier<? extends Result<Failure, String>>>of(
                        () -> Result.err(new Failure.Message("a")),
                        () -> Result.err(new Failure.Message("b"))
                ),
                t -> new Failure.Message("thrown: " + t.getMessage())
        );

        // Which error wins is unspecified; it must be one of the two, untouched.
        assertInstanceOf(Result.Err.class, r);
        var error = ((Result.Err<Failure, String>) r).error();
        assertTrue(error.equals(new Failure.Message("a")) || error.equals(new Failure.Message("b")),
                "error must be one of the task errors, was: " + error);
    }

    @Test
    void race_maps_a_thrown_exception_via_onThrow_when_no_task_succeeds() throws InterruptedException {
        Result<Failure, String> r = Scopes.race(
                List.<Supplier<? extends Result<Failure, String>>>of(
                        () -> { throw new RuntimeException("kaboom"); }
                ),
                t -> new Failure.Message("thrown: " + t.getMessage())
        );

        assertEquals(Result.<Failure, String>err(new Failure.Message("thrown: kaboom")), r);
    }
}
