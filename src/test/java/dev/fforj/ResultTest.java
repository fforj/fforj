package dev.fforj;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultTest {

    @Test
    void pattern_match_on_switch_works_with_records() {
        Result<Failure, Integer> r = Result.ok(42);

        String out = switch (r) {
            case Result.Ok<Failure, Integer> ok -> "value=" + ok.value();
            case Result.Err<Failure, Integer> err -> "error=" + err.error().message();
        };

        assertEquals("value=42", out);
    }

    @Test
    void map_transforms_the_success_branch_only() {
        var ok = Result.<Failure, Integer>ok(5).map(i -> i * 2);
        var err = Result.<Failure, Integer>err(new Failure.Message("nope")).map(i -> i * 2);

        assertEquals(Result.<Failure, Integer>ok(10), ok);
        assertEquals(Result.<Failure, Integer>err(new Failure.Message("nope")), err);
    }

    @Test
    void flatMap_chains_sequential_fallible_operations() {
        Result<Failure, Integer> chain =
                Result.<Failure, Integer>ok(10)
                        .flatMap(i -> Result.<Failure, Integer>ok(i + 1))
                        .flatMap(i -> i > 5
                                ? Result.<Failure, Integer>ok(i * 10)
                                : Result.err(new Failure.NotPositive(i)));

        assertEquals(Result.<Failure, Integer>ok(110), chain);
    }

    @Test
    void flatMap_short_circuits_on_first_error() {
        var calls = new int[]{0};

        Result<Failure, Integer> chain =
                Result.<Failure, Integer>err(new Failure.Message("boom"))
                        .flatMap(i -> {
                            calls[0]++;
                            return Result.ok(i + 1);
                        });

        assertEquals(Result.<Failure, Integer>err(new Failure.Message("boom")), chain);
        assertEquals(0, calls[0], "flatMap function must not run on Err");
    }

    @Test
    void zip_combines_two_oks_and_propagates_first_error() {
        var left = new Failure.Message("left");
        var right = new Failure.Message("right");

        var both = Result.<Failure, Integer>ok(3).zip(Result.<Failure, Integer>ok(4), Integer::sum);
        var leftErr = Result.<Failure, Integer>err(left).zip(Result.<Failure, Integer>ok(4), Integer::sum);
        var rightErr = Result.<Failure, Integer>ok(3).zip(Result.<Failure, Integer>err(right), Integer::sum);

        assertEquals(Result.<Failure, Integer>ok(7), both);
        assertEquals(Result.<Failure, Integer>err(left), leftErr);
        assertEquals(Result.<Failure, Integer>err(right), rightErr);
    }

    @Test
    void attempt_captures_thrown_exception_as_typed_error() {
        Result<Failure, String> r = Result.attempt(
                () -> { throw new IOException("disk full"); },
                t -> new Failure.Message("io: " + t.getMessage())
        );

        assertEquals(Result.<Failure, String>err(new Failure.Message("io: disk full")), r);
    }

    @Test
    void attempt_restores_the_interrupt_flag_when_body_throws_InterruptedException() {
        try {
            Result<Failure, String> r = Result.attempt(
                    () -> { throw new InterruptedException("cancelled"); },
                    t -> new Failure.Message("interrupted: " + t.getMessage())
            );

            assertEquals(Result.<Failure, String>err(new Failure.Message("interrupted: cancelled")), r);
            assertTrue(Thread.currentThread().isInterrupted(),
                    "attempt must re-assert the interrupt flag, not swallow cancellation");
        } finally {
            // Drain the flag so other tests are unaffected.
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted();
        }
    }

    // A fallible step that throws a *checked* exception on bad input — the kind of
    // boundary call that attempt is meant to wrap.
    private static int parsePositive(String s) throws IOException {
        int n = Integer.parseInt(s);
        if (n <= 0) {
            throw new IOException("not positive: " + n);
        }
        return n;
    }

    @Test
    void attempt_composes_dependent_steps_into_a_single_ok() {
        // Straight-line composition: each step depends on the previous one, no
        // flatMap-in-flatMap nesting. This is the do-notation-style use case.
        Result<Failure, Integer> r = Result.attempt(() -> {
            int a = parsePositive("3");
            int b = parsePositive("4");
            return a * b;
        }, t -> new Failure.Message("bad: " + t.getMessage()));

        assertEquals(Result.<Failure, Integer>ok(12), r);
    }

    @Test
    void attempt_short_circuits_remaining_steps_on_first_failure() {
        var stepsRun = new ArrayList<String>();

        Result<Failure, Integer> r = Result.attempt(() -> {
            stepsRun.add("a");
            int a = parsePositive("-1");   // throws here
            stepsRun.add("b");             // must never run
            int b = parsePositive("4");
            return a * b;
        }, t -> new Failure.Message("bad: " + t.getMessage()));

        assertEquals(Result.<Failure, Integer>err(new Failure.Message("bad: not positive: -1")), r);
        // Proves the later steps were skipped: only "a" ever executed.
        assertEquals(List.of("a"), stepsRun);
    }

    // A fallible step that *returns* a Result (rather than throwing) — the kind of call
    // you sequence inside a binding block. Note the structured, typed failures.
    private static Result<Failure, Integer> positive(String s) {
        int n;
        try {
            n = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Result.err(new Failure.NotANumber(s));
        }
        return n > 0 ? Result.ok(n) : Result.err(new Failure.NotPositive(n));
    }

    @Test
    void binding_sequences_result_returning_steps_into_a_single_ok() {
        // Straight-line: each bound call yields the raw value, no flatMap nesting.
        Result<Failure, Integer> total = Result.binding(bind -> {
            int a = bind.on(positive("3"));
            int b = bind.on(positive("4"));
            int c = bind.on(positive("5"));
            return a + b + c;
        });

        assertEquals(Result.<Failure, Integer>ok(12), total);
    }

    @Test
    void binding_short_circuits_on_first_err_and_skips_later_steps() {
        var stepsRun = new ArrayList<String>();

        Result<Failure, Integer> total = Result.binding(bind -> {
            stepsRun.add("a");
            int a = bind.on(positive("3"));
            stepsRun.add("b");
            int b = bind.on(positive("-1"));   // Err -> aborts the block here
            stepsRun.add("c");                 // must never run
            int c = bind.on(positive("5"));
            return a + b + c;
        });

        assertEquals(Result.<Failure, Integer>err(new Failure.NotPositive(-1)), total);
        assertEquals(List.of("a", "b"), stepsRun);
    }

    @Test
    void binding_propagates_the_exact_error_value_unchanged() {
        Result<Failure, Integer> r = Result.binding(bind -> {
            int a = bind.on(Result.ok(1));
            int b = bind.on(positive("oops"));   // Err(NotANumber("oops")) -> aborts
            return a + b;
        });

        assertEquals(Result.<Failure, Integer>err(new Failure.NotANumber("oops")), r);
    }

    @Test
    void nested_binding_blocks_each_catch_their_own_short_circuit() {
        Result<Failure, Integer> outer = Result.binding(bind -> {
            int a = bind.on(positive("10"));
            // Inner block fails and yields an Err *value*; the outer block decides what to
            // do with it rather than being aborted by the inner short-circuit.
            Result<Failure, Integer> inner = Result.binding(innerBind -> {
                int x = innerBind.on(positive("2"));
                int y = innerBind.on(positive("-9"));   // aborts only the inner block
                return x + y;
            });
            int b = bind.on(inner.recover(e -> Result.ok(0)));
            return a + b;
        });

        assertEquals(Result.ok(10), outer);
    }

    @Test
    void nested_binding_using_the_outer_binder_inside_an_inner_block_aborts_the_outer_block() {
        var innerCompleted = new boolean[]{false};
        var outerResumed = new boolean[]{false};

        // Regression: the abort thrown by the OUTER binder must not be caught by the
        // inner block's boundary (which would return the outer error as the inner
        // block's Err with the wrong error type — note the inner block's E is String).
        Result<Failure, Integer> outer = Result.binding(bind -> {
            Result<String, Integer> inner = Result.binding(innerBind -> {
                int x = bind.on(positive("-7"));   // outer Err -> aborts the OUTER block
                innerCompleted[0] = true;          // must never run
                return x;
            });
            outerResumed[0] = true;                // must never run either
            return inner.getOrElse(0);
        });

        assertEquals(Result.<Failure, Integer>err(new Failure.NotPositive(-7)), outer);
        assertFalse(innerCompleted[0], "the outer abort must skip the rest of the inner block");
        assertFalse(outerResumed[0], "the outer abort must not be swallowed by the inner boundary");
    }

    @Test
    void binding_abort_passes_through_an_attempt_wrapping_the_bound_call() {
        // attempt must not capture the control-flow abort of an enclosing binding block:
        // the short-circuit inside the attempt body aborts the whole block, it does not
        // become a mapped Err of the attempt.
        Result<Failure, Integer> r = Result.binding(bind -> bind.on(Result.attempt(
                () -> bind.on(positive("-1")) + 1,
                t -> new Failure.Message("swallowed: " + t))));

        assertEquals(Result.<Failure, Integer>err(new Failure.NotPositive(-1)), r);
    }

    @Test
    void mapErr_transforms_the_error_branch_only() {
        var err = Result.<Failure, Integer>err(new Failure.NotPositive(-1))
                .mapErr(Failure::message);
        var ok = Result.<Failure, Integer>ok(5).mapErr(Failure::message);

        assertEquals(Result.<String, Integer>err("not positive: -1"), err);
        assertEquals(Result.<String, Integer>ok(5), ok);
    }

    @Test
    void getOrElse_returns_value_on_ok_and_fallback_on_err() {
        assertEquals(7, Result.<Failure, Integer>ok(7).getOrElse(0));
        assertEquals(0, Result.<Failure, Integer>err(new Failure.Message("nope")).getOrElse(0));
    }

    @Test
    void getOrElse_rejects_a_null_fallback() {
        assertThrows(NullPointerException.class,
                () -> Result.<Failure, Integer>ok(7).getOrElse(null));
    }

    @Test
    void getOrElseGet_computes_fallback_from_the_error_only_on_err() {
        var fallbackCalls = new int[]{0};

        int onOk = Result.<Failure, Integer>ok(7).getOrElseGet(e -> {
            fallbackCalls[0]++;
            return 0;
        });
        int onErr = Result.<Failure, Integer>err(new Failure.NotPositive(-3))
                .getOrElseGet(e -> ((Failure.NotPositive) e).value());

        assertEquals(7, onOk);
        assertEquals(0, fallbackCalls[0], "fallback must not run on Ok");
        assertEquals(-3, onErr);
    }

    @Test
    void okValue_and_errValue_lift_each_case_into_an_Optional() {
        Result<Failure, Integer> ok = Result.ok(42);
        Result<Failure, Integer> err = Result.err(new Failure.Message("nope"));

        assertEquals(Optional.of(42), ok.okValue());
        assertEquals(Optional.empty(), ok.errValue());
        assertEquals(Optional.empty(), err.okValue());
        assertEquals(Optional.of(new Failure.Message("nope")), err.errValue());
    }

    @Test
    void fromOptional_lifts_present_to_ok_and_empty_to_err() {
        Result<Failure, Integer> present =
                Result.fromOptional(Optional.of(7), () -> new Failure.Message("absent"));
        Result<Failure, Integer> absent =
                Result.fromOptional(Optional.empty(), () -> new Failure.Message("absent"));

        assertEquals(Result.<Failure, Integer>ok(7), present);
        assertEquals(Result.<Failure, Integer>err(new Failure.Message("absent")), absent);
    }

    @Test
    void fromOptional_does_not_evaluate_the_error_supplier_when_present() {
        var supplierCalls = new int[]{0};

        Result.fromOptional(Optional.of(1), () -> {
            supplierCalls[0]++;
            return new Failure.Message("unused");
        });

        assertEquals(0, supplierCalls[0], "ifEmpty must not run when the Optional is present");
    }

    @Test
    void binding_can_bind_an_optional_and_continues_when_present() {
        Result<Failure, Integer> r = Result.binding(bind -> {
            int a = bind.on(NonEmptyList.fromList(List.of(10, 20)), () -> new Failure.Message("empty"))
                    .head();
            int b = bind.on(positive("5"));
            return a + b;
        });

        assertEquals(Result.<Failure, Integer>ok(15), r);
    }

    @Test
    void binding_short_circuits_when_a_bound_optional_is_empty() {
        var reachedSecondStep = new boolean[]{false};

        Result<Failure, Integer> r = Result.binding(bind -> {
            int first = bind.on(NonEmptyList.fromList(List.<Integer>of()), () -> new Failure.Message("no candidates"))
                    .head();
            reachedSecondStep[0] = true;          // must never run
            int second = bind.on(positive("5"));
            return first + second;
        });

        assertEquals(Result.<Failure, Integer>err(new Failure.Message("no candidates")), r);
        assertFalse(reachedSecondStep[0], "steps after an empty Optional bind must not run");
    }

    @Test
    void orElseThrow_unwraps_value_and_throws_on_error() {
        Result<Failure, Integer> ok = Result.ok(99);
        assertEquals(99, ok.orElseThrow(f -> new IllegalStateException(f.message())));

        Result<Failure, Integer> err = Result.err(new Failure.Message("nope"));
        var thrown = assertThrows(IllegalStateException.class,
                () -> err.orElseThrow(f -> new IllegalStateException(f.message())));
        assertEquals("nope", thrown.getMessage());
    }

    @Test
    void recover_replaces_error_with_a_fresh_result() {
        Result<Failure, Integer> recovered = Result.<Failure, Integer>err(new Failure.Message("transient"))
                .recover(e -> Result.ok(0));

        assertEquals(Result.<Failure, Integer>ok(0), recovered);
    }

    @Test
    void fold_collapses_both_cases_into_one_value() {
        String okStr = positive("7").fold(f -> "err:" + f.message(), v -> "ok:" + v);
        String errStr = positive("-5").fold(f -> "err:" + f.message(), v -> "ok:" + v);

        assertEquals("ok:7", okStr);
        assertEquals("err:not positive: -5", errStr);
    }

    @Test
    void isOk_and_isErr_are_self_consistent() {
        assertTrue(Result.<Failure, Integer>ok(1).isOk());
        assertFalse(Result.<Failure, Integer>ok(1).isErr());
        assertTrue(Result.<Failure, Integer>err(new Failure.Message("e")).isErr());
        assertFalse(Result.<Failure, Integer>err(new Failure.Message("e")).isOk());
    }
}
