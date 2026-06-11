package dev.fforj;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Like {@link Result}, but the {@code Invalid} case accumulates ALL errors instead of
 * short-circuiting on the first one.
 *
 * <p>Pick {@code Validated} over {@link Result} when you want every reason a form,
 * config, or DTO is invalid — not just the first.
 *
 * <pre>{@code
 * Validated<String, Form> v = nameField.zip(emailField, Form::new);
 * // If both invalid: v carries both error messages.
 * }</pre>
 *
 * <p>Convert to/from {@link Result} when you need to switch behaviour mid-pipeline.
 */
public sealed interface Validated<E, T> {

    record Valid<E, T>(T value) implements Validated<E, T> {
        public Valid {
            Objects.requireNonNull(value);
        }
    }

    record Invalid<E, T>(NonEmptyList<E> errors) implements Validated<E, T> {
        public Invalid {
            Objects.requireNonNull(errors);
        }
    }

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /** Build a success. */
    static <E, T> Validated<E, T> valid(T value) {
        return new Valid<>(value);
    }

    /** Build a failure carrying a single error. */
    static <E, T> Validated<E, T> invalid(E error) {
        return new Invalid<>(NonEmptyList.of(error));
    }

    /** Build a failure carrying one or more accumulated errors. */
    static <E, T> Validated<E, T> invalid(NonEmptyList<E> errors) {
        return new Invalid<>(errors);
    }

    /** Lift a {@link Result} into a single-error {@code Validated}. */
    static <E, T> Validated<E, T> fromResult(Result<E, T> r) {
        return switch (r) {
            case Result.Ok<E, T> ok -> new Valid<>(ok.value());
            case Result.Err<E, T> err -> new Invalid<>(NonEmptyList.of(err.error()));
        };
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    /** {@code true} if this is {@link Valid}. */
    default boolean isValid() {
        return this instanceof Valid<E, T>;
    }

    /** {@code true} if this is {@link Invalid}. */
    default boolean isInvalid() {
        return this instanceof Invalid<E, T>;
    }

    // ---------------------------------------------------------------------
    // Combinators
    // ---------------------------------------------------------------------

    /** Transform the success value; preserve the errors. */
    default <U> Validated<E, U> map(Function<? super T, ? extends U> f) {
        return switch (this) {
            case Valid<E, T> v -> new Valid<>(f.apply(v.value()));
            case Invalid<E, T> i -> new Invalid<>(i.errors());
        };
    }

    /**
     * Combine two {@code Validated}s into one via a binary function.
     *
     * <p>Error semantics (the key difference from {@link Result}):
     * <ul>
     *   <li>Both {@code Valid}: applies {@code f}, returns {@code Valid<R>}.</li>
     *   <li>One {@code Invalid}: errors propagate.</li>
     *   <li>Both {@code Invalid}: errors concatenate via {@link NonEmptyList#concat}.</li>
     * </ul>
     *
     * <p>This is the {@code ap} ("applicative") combinator. Use to validate independent
     * fields together and surface all problems at once.
     */
    default <U, R> Validated<E, R> zip(
            Validated<E, U> other,
            BiFunction<? super T, ? super U, ? extends R> f
    ) {
        return switch (this) {
            case Valid<E, T> v1 -> switch (other) {
                case Valid<E, U> v2 -> new Valid<>(f.apply(v1.value(), v2.value()));
                case Invalid<E, U> i2 -> new Invalid<>(i2.errors());
            };
            case Invalid<E, T> i1 -> switch (other) {
                case Valid<E, U> ignored -> new Invalid<>(i1.errors());
                case Invalid<E, U> i2 -> new Invalid<>(i1.errors().concat(i2.errors()));
            };
        };
    }

    /**
     * Convert back into a {@link Result}. The error side becomes the accumulated list
     * so the caller can pattern-match on either a single error or a batch.
     */
    default Result<NonEmptyList<E>, T> toResult() {
        return switch (this) {
            case Valid<E, T> v -> Result.ok(v.value());
            case Invalid<E, T> i -> Result.err(i.errors());
        };
    }

    /** Fold both cases into a single value. */
    default <R> R fold(
            Function<NonEmptyList<E>, ? extends R> onInvalid,
            Function<? super T, ? extends R> onValid
    ) {
        return switch (this) {
            case Valid<E, T> v -> onValid.apply(v.value());
            case Invalid<E, T> i -> onInvalid.apply(i.errors());
        };
    }
}
