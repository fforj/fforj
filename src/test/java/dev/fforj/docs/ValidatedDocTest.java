package dev.fforj.docs;

import dev.fforj.NonEmptyList;
import dev.fforj.Result;
import dev.fforj.Validated;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// ---
/// title: Validated — every error, at once
/// slug: validated
/// order: 2
/// summary: Like Result, but Invalid accumulates all errors instead of stopping at the first.
/// ---
///
/// `Result` stops at the first failure — right for dependent steps, wrong for forms.
/// When a user submits three broken fields, telling them about one is a bad product.
/// `Validated<E, T>` accumulates: its `Invalid` case carries a `NonEmptyList<E>` of
/// everything that went wrong.
class ValidatedDocTest {

    // site:include
    record Form(String name, String email, int age) {}

    // site:include
    static Validated<String, String> nonBlank(String field, String value) {
        return value.isBlank()
                ? Validated.invalid(field + " must not be blank")
                : Validated.valid(value);
    }

    // site:include
    static Validated<String, Integer> validAge(int age) {
        return age > 0
                ? Validated.valid(age)
                : Validated.invalid("age must be positive, was " + age);
    }

    /// ## Two fields: `zip`
    ///
    /// `zip` combines two independent validations. If both are valid it applies the
    /// function; if either failed, the errors propagate — and if *both* failed, the
    /// errors concatenate. Nothing is dropped.
    @Test
    void combine_two_validations_and_keep_both_errors() {
        Validated<String, String> greeting =
                nonBlank("name", "").zip(nonBlank("email", " "),
                        (name, email) -> name + " <" + email + ">");

        assertEquals(
                Validated.invalid(NonEmptyList.of(
                        "name must not be blank",
                        "email must not be blank")),
                greeting);
    }

    /// ## N fields: the `accumulate` block
    ///
    /// [landing]
    /// Past two fields, chained `zip`s turn into currying gymnastics. `accumulate`
    /// is the arity-free form: bind each validation with `acc.on(...)` — a failure is
    /// recorded but *doesn't* stop later validations from running — then unwrap with
    /// `.value()` at the end. The result is `Valid` only if nothing failed, otherwise
    /// `Invalid` with every error in binding order.
    @Test
    void validate_a_whole_form_and_report_everything() {
        Validated<String, Form> form = Validated.accumulate(acc -> {
            var name = acc.on(nonBlank("name", ""));
            var email = acc.on(nonBlank("email", "ada@analytical.engine"));
            var age = acc.on(validAge(-3));
            return new Form(name.value(), email.value(), age.value());
        });

        assertEquals(
                Validated.invalid(NonEmptyList.of(
                        "name must not be blank",
                        "age must be positive, was -3")),
                form);
    }

    /// ## Mix in Result and Optional
    ///
    /// Real validation pipelines aren't uniform: some steps return `Result`, some
    /// return `Optional`. The accumulator binds all three shapes side by side — a
    /// `Result.Err` accumulates as a single error, and an empty `Optional` accumulates
    /// the error you name for it.
    @Test
    void bind_result_and_optional_alongside_validated() {
        Validated<String, String> v = Validated.accumulate(acc -> {
            var fromValidated = acc.on(nonBlank("name", ""));
            var fromResult = acc.on(
                    Result.<String, String>err("country not recognized"));
            var fromOptional = acc.on(
                    Optional.<String>empty(), () -> "currency is required");
            return fromValidated.value() + fromResult.value() + fromOptional.value();
        });

        assertEquals(
                Validated.invalid(NonEmptyList.of(
                        "name must not be blank",
                        "country not recognized",
                        "currency is required")),
                v);
    }

    /// ## Back to Result when you're done
    ///
    /// Accumulation is for the boundary; once errors are gathered you often want the
    /// short-circuiting world back. `toResult` collapses `Invalid` into a single
    /// `Err` carrying the whole batch.
    @Test
    void hand_the_batch_back_to_a_result_pipeline() {
        Validated<String, Integer> invalid =
                Validated.invalid(NonEmptyList.of("too short", "too vague"));

        Result<NonEmptyList<String>, Integer> r = invalid.toResult();

        assertEquals(Result.err(NonEmptyList.of("too short", "too vague")), r);
    }
}
