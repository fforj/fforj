package dev.fforj;

import org.junit.jupiter.api.Test;

import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
