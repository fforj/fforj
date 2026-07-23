package dev.fforj;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

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

    /**
     * A value bound inside an {@link #accumulate(Function) accumulate} block, not yet
     * unwrapped.
     *
     * <p>Binding ({@link Accumulator#on}) and unwrapping ({@link #value()}) are separate
     * steps on purpose: binding never aborts the block — it only records the error — so
     * every validation gets a chance to run and contribute its error before the first
     * unwrap of a failed binding stops the block. Bind everything first, unwrap at the end.
     */
    @FunctionalInterface
    interface Bound<T> {
        /**
         * Unwrap: returns the bound value if its validation succeeded; if it failed,
         * aborts the enclosing {@code accumulate} block, which then evaluates to
         * {@link Invalid} carrying every error accumulated so far. Only legal while the
         * surrounding {@code accumulate} call is on the stack.
         */
        T value();
    }

    /** The binding handle passed to {@link #accumulate(Function)}. */
    interface Accumulator<E> {

        /**
         * Bind a {@code Validated}: a {@code Valid}'s value becomes available via
         * {@link Bound#value()}; an {@code Invalid}'s errors are added to the
         * accumulator. Binding never aborts the block — later validations still run.
         */
        <T> Bound<T> on(Validated<E, T> validated);

        /** Bind a {@link Result}: {@code Err} accumulates as a single error. */
        default <T> Bound<T> on(Result<E, T> result) {
            return on(fromResult(result));
        }

        /** Bind an {@link Optional}: empty accumulates {@code ifEmpty.get()}. */
        default <T> Bound<T> on(Optional<? extends T> maybe, Supplier<? extends E> ifEmpty) {
            return on(fromResult(Result.fromOptional(maybe, ifEmpty)));
        }
    }

    /**
     * Error-accumulation DSL: validate several independent pieces as straight-line code
     * and surface <em>every</em> failure at once — the {@code Validated} counterpart of
     * {@code Result.binding}, and the arity-free alternative to chained
     * {@link #zip(Validated, BiFunction) zip} calls.
     *
     * <p>Inside the block, {@code acc.on(...)} binds each validation and returns a
     * {@link Bound} handle. A failed binding records its errors and keeps going, so every
     * validation runs. Unwrap the handles with {@link Bound#value()} once everything is
     * bound; the first unwrap of a failed binding ends the block. The result is
     * {@link Valid} of the block's return value only if <em>no</em> binding failed,
     * otherwise {@link Invalid} with all errors in binding order:
     * <pre>{@code
     * Validated<Failure, Form> form = Validated.accumulate(acc -> {
     *     var name  = acc.on(validateName(raw));    // Invalid -> recorded, no abort
     *     var email = acc.on(validateEmail(raw));   // still runs
     *     var age   = acc.on(validateAge(raw));     // still runs
     *     return new Form(name.value(), email.value(), age.value());
     * });
     * // form == Invalid([nameError, emailError, ageError]) if all three failed
     * }</pre>
     *
     * <p><strong>Bind first, unwrap last.</strong> Interleaving ({@code acc.on(a).value()}
     * before binding {@code b}) silently degrades to short-circuiting: a failed unwrap
     * aborts before later validations bind. Dependent validations belong in
     * {@code Result.binding}; this DSL is for independent ones.
     *
     * <p>The abort uses the same private control-flow exception mechanism as
     * {@code Result.binding} (see ADR-1), with the same caveat: don't wrap unwraps in a
     * catch-all {@code catch (RuntimeException e)}, and don't let {@code Bound} handles
     * escape the block.
     *
     * @param block receives the {@link Accumulator} and returns the composed value.
     * @return {@link Valid} of the block's return value, or {@link Invalid} carrying all
     *         accumulated errors in binding order.
     */
    static <E, T> Validated<E, T> accumulate(Function<? super Accumulator<E>, ? extends T> block) {
        Objects.requireNonNull(block, "accumulate block must not be null");

        // Same shape as Result.binding's Halt (ADR-1): local class, no stack trace.
        // Carries no payload — the errors live in the accumulator list.
        final class Halt extends RuntimeException {
            Halt() {
                super(null, null, false, false);
            }
        }

        var errors = new ArrayList<E>();

        var acc = new Accumulator<E>() {
            @Override
            public <U> Bound<U> on(Validated<E, U> validated) {
                return switch (validated) {
                    case Valid<E, U> valid -> valid::value;
                    case Invalid<E, U> invalid -> {
                        errors.addAll(invalid.errors().toList());
                        yield () -> { throw new Halt(); };
                    }
                };
            }
        };

        T outcome;
        try {
            outcome = block.apply(acc);
        } catch (Halt halt) {
            // Unreachable with empty errors: Halt is only thrown by a failed binding,
            // which always records its errors first.
            return new Invalid<>(new NonEmptyList<>(
                    errors.getFirst(), errors.subList(1, errors.size())));
        }
        if (errors.isEmpty()) {
            return new Valid<>(outcome);
        }
        // The block completed without unwrapping any failed binding, but failures were
        // recorded — the result is still Invalid; accumulation doesn't depend on unwraps.
        return new Invalid<>(new NonEmptyList<>(
                errors.getFirst(), errors.subList(1, errors.size())));
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
