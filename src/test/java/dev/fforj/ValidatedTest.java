package dev.fforj;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ValidatedTest {

    // A tiny form to demonstrate "accumulate every reason this DTO is broken".
    record Form(String name, String email, int age) {}

    @Test
    void zip_combines_two_valids_via_constructor() {
        var name = Validated.<Failure, String>valid("Tibtof");
        var email = Validated.<Failure, String>valid("tibtof@example.org");

        var combined = name.zip(email, (n, e) -> n + " <" + e + ">");

        assertEquals(Validated.<Failure, String>valid("Tibtof <tibtof@example.org>"), combined);
    }

    @Test
    void zip_accumulates_errors_from_both_sides() {
        var name = Validated.<Failure, String>invalid(new Failure.Message("name required"));
        var email = Validated.<Failure, String>invalid(new Failure.Message("email malformed"));

        var combined = name.zip(email, (n, e) -> n + " <" + e + ">");

        assertEquals(Validated.<Failure, String>invalid(NonEmptyList.of(
                new Failure.Message("name required"),
                new Failure.Message("email malformed"))), combined);
    }

    @Test
    void zip_propagates_left_error_when_right_is_valid() {
        var nameInvalid = Validated.<Failure, String>invalid(new Failure.Message("name required"));
        var emailValid = Validated.<Failure, String>valid("ok@example.org");

        var combined = nameInvalid.zip(emailValid, (n, e) -> n + " " + e);

        assertEquals(Validated.<Failure, String>invalid(new Failure.Message("name required")), combined);
    }

    @Test
    void real_world_form_validation_accumulates_all_problems_at_once() {
        // Simulate validating each field independently.
        Validated<Failure, String> name = "".isBlank()
                ? Validated.invalid(new Failure.Message("name is blank"))
                : Validated.valid("");
        Validated<Failure, String> email = "no-at-sign".contains("@")
                ? Validated.valid("no-at-sign")
                : Validated.invalid(new Failure.Message("email is malformed"));
        Validated<Failure, Integer> age = -3 >= 0
                ? Validated.valid(-3)
                : Validated.invalid(new Failure.NotPositive(-3));

        Validated<Failure, Form> form = name
                .zip(email, (n, e) -> (IntFunction<Form>) a -> new Form(n, e, a))
                .zip(age, (mk, a) -> mk.apply(a));

        // Three independent problems, all surfaced at once.
        assertEquals(
                Validated.<Failure, Form>invalid(NonEmptyList.of(
                        new Failure.Message("name is blank"),
                        new Failure.Message("email is malformed"),
                        new Failure.NotPositive(-3))),
                form);
    }

    @Test
    void fromResult_lifts_a_short_circuit_result_into_a_single_error() {
        Result<Failure, Integer> r = Result.err(new Failure.Message("nope"));
        Validated<Failure, Integer> v = Validated.fromResult(r);

        assertEquals(Validated.<Failure, Integer>invalid(new Failure.Message("nope")), v);
    }

    @Test
    void toResult_collapses_accumulated_errors_into_a_single_NonEmptyList_error() {
        var a = new Failure.Message("a");
        var b = new Failure.Message("b");
        Validated<Failure, Integer> v = Validated.invalid(NonEmptyList.of(a, b));
        Result<NonEmptyList<Failure>, Integer> r = v.toResult();

        assertEquals(Result.err(NonEmptyList.of(a, b)), r);
    }

    @Test
    void fold_collapses_both_cases_into_one_value() {
        String s = Validated.<Failure, Integer>invalid(NonEmptyList.of(
                        new Failure.Message("a"), new Failure.Message("b")))
                .fold(errs -> "errors=" + errs.toList().stream()
                                .map(Failure::message)
                                .collect(Collectors.joining(",")),
                        v -> "ok=" + v);

        assertEquals("errors=a,b", s);

        String s2 = Validated.<Failure, Integer>valid(42)
                .fold(errs -> "errors", v -> "ok=" + v);

        assertEquals("ok=42", s2);
    }

    @Test
    void map_transforms_only_the_valid_case() {
        var mapped = Validated.<Failure, Integer>valid(5).map(i -> i * 2);
        assertEquals(Validated.<Failure, Integer>valid(10), mapped);
    }

    // Field validators for the accumulate DSL tests — independent, each can fail.
    private static Validated<Failure, String> nonBlank(String s) {
        return s.isBlank()
                ? Validated.invalid(new Failure.Message("blank"))
                : Validated.valid(s);
    }

    private static Validated<Failure, Integer> positiveAge(int age) {
        return age > 0
                ? Validated.valid(age)
                : Validated.invalid(new Failure.NotPositive(age));
    }

    @Test
    void accumulate_returns_valid_when_every_binding_succeeds() {
        Validated<Failure, Form> form = Validated.accumulate(acc -> {
            var name = acc.on(nonBlank("Tibtof"));
            var email = acc.on(nonBlank("tibtof@example.org"));
            var age = acc.on(positiveAge(40));
            return new Form(name.value(), email.value(), age.value());
        });

        assertEquals(Validated.<Failure, Form>valid(
                new Form("Tibtof", "tibtof@example.org", 40)), form);
    }

    @Test
    void accumulate_collects_every_error_in_binding_order() {
        Validated<Failure, Form> form = Validated.accumulate(acc -> {
            var name = acc.on(nonBlank(""));
            var email = acc.on(nonBlank(" "));
            var age = acc.on(positiveAge(-3));
            return new Form(name.value(), email.value(), age.value());
        });

        assertEquals(Validated.<Failure, Form>invalid(NonEmptyList.of(
                new Failure.Message("blank"),
                new Failure.Message("blank"),
                new Failure.NotPositive(-3))), form);
    }

    @Test
    void accumulate_runs_later_validations_even_after_an_earlier_failure() {
        var validationsRun = new ArrayList<String>();

        Validated<Failure, Integer> v = Validated.accumulate(acc -> {
            validationsRun.add("first");
            var bad = acc.on(positiveAge(-1));        // fails — but binding doesn't abort
            validationsRun.add("second");
            var good = acc.on(positiveAge(7));        // still bound, still runs
            validationsRun.add("third");
            return bad.value() + good.value();        // first unwrap of `bad` aborts here
        });

        assertEquals(Validated.<Failure, Integer>invalid(new Failure.NotPositive(-1)), v);
        assertEquals(List.of("first", "second", "third"), validationsRun);
    }

    @Test
    void accumulate_unwrap_of_a_failed_binding_skips_the_rest_of_the_block() {
        var reachedAfterUnwrap = new boolean[]{false};

        Validated<Failure, Integer> v = Validated.accumulate(acc -> {
            var bad = acc.on(positiveAge(-1));
            int unwrapped = bad.value();              // aborts the block here
            reachedAfterUnwrap[0] = true;             // must never run
            return unwrapped;
        });

        assertEquals(Validated.<Failure, Integer>invalid(new Failure.NotPositive(-1)), v);
        assertFalse(reachedAfterUnwrap[0], "code after a failed unwrap must not run");
    }

    @Test
    void accumulate_binds_result_and_optional_alongside_validated() {
        Validated<Failure, String> v = Validated.accumulate(acc -> {
            var fromValidated = acc.on(nonBlank(""));                          // 1 error
            var fromResult = acc.on(Result.<Failure, String>err(
                    new Failure.Message("nope")));                             // 1 error
            var fromOptional = acc.on(Optional.<String>empty(),
                    () -> new Failure.Message("absent"));                      // 1 error
            return fromValidated.value() + fromResult.value() + fromOptional.value();
        });

        assertEquals(Validated.<Failure, String>invalid(NonEmptyList.of(
                new Failure.Message("blank"),
                new Failure.Message("nope"),
                new Failure.Message("absent"))), v);
    }

    @Test
    void accumulate_keeps_all_errors_from_a_multi_error_invalid_binding() {
        var batch = Validated.<Failure, Integer>invalid(NonEmptyList.of(
                new Failure.Message("a"), new Failure.Message("b")));

        Validated<Failure, Integer> v = Validated.accumulate(acc -> {
            var bound = acc.on(batch);
            var more = acc.on(positiveAge(-9));
            return bound.value() + more.value();
        });

        assertEquals(Validated.<Failure, Integer>invalid(NonEmptyList.of(
                new Failure.Message("a"),
                new Failure.Message("b"),
                new Failure.NotPositive(-9))), v);
    }

    @Test
    void accumulate_is_invalid_even_when_failed_bindings_are_never_unwrapped() {
        Validated<Failure, String> v = Validated.accumulate(acc -> {
            acc.on(positiveAge(-1));     // failure recorded, handle discarded
            return "computed anyway";    // block completes normally...
        });

        // ...but the result must still be Invalid: accumulation doesn't depend on unwraps.
        assertEquals(Validated.<Failure, String>invalid(new Failure.NotPositive(-1)), v);
    }

    @Test
    void real_world_form_validation_with_the_accumulate_dsl() {
        // The same scenario as the zip-based test above, without arity gymnastics: no
        // curried IntFunction, just bind each field and build the record at the end.
        Validated<Failure, Form> form = Validated.accumulate(acc -> {
            var name = acc.on(nonBlank(""));
            var email = acc.on("no-at-sign".contains("@")
                    ? Validated.<Failure, String>valid("no-at-sign")
                    : Validated.<Failure, String>invalid(new Failure.Message("email is malformed")));
            var age = acc.on(positiveAge(-3));
            return new Form(name.value(), email.value(), age.value());
        });

        assertEquals(
                Validated.<Failure, Form>invalid(NonEmptyList.of(
                        new Failure.Message("blank"),
                        new Failure.Message("email is malformed"),
                        new Failure.NotPositive(-3))),
                form);
    }
}
