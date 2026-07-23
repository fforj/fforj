package dev.fforj;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sum type for an operation that either succeeds with a {@code T} or fails with an {@code E}.
 *
 * <p>Compare with Vavr's {@code Either} or {@code Try} — this type discards the implicit
 * "left/right" naming convention in favour of explicit {@link Ok} and {@link Err} cases
 * that pattern-match cleanly on Java 21+ {@code switch}.
 *
 * <p>Idiomatic use:
 * <pre>{@code
 * Result<AuthError, User> result = fetchUser(id);
 * String message = switch (result) {
 *   case Ok<AuthError, User> ok -> "Welcome, " + ok.value().name();
 *   case Err<AuthError, User> err -> "Auth failed: " + err.error().reason();
 * };
 * }</pre>
 *
 * <p>The {@code <E, T>} type parameters are carried in both variants even though
 * {@link Err} doesn't use {@code T} and {@link Ok} doesn't use {@code E}. This is the
 * standard Scala / Rust shape for ADTs — the unused parameter survives as a phantom so
 * the compiler can prove exhaustiveness in pattern matches without unchecked casts.
 */
public sealed interface Result<E, T> {

    /** Success case: carries a {@code T}. */
    record Ok<E, T>(T value) implements Result<E, T> {
        public Ok {
            Objects.requireNonNull(value,
                    "Ok value must not be null — use Optional<T> for absence");
        }
    }

    /** Failure case: carries an {@code E}. */
    record Err<E, T>(E error) implements Result<E, T> {
        public Err {
            Objects.requireNonNull(error, "Err must carry a non-null error");
        }
    }

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /** Build a success. */
    static <E, T> Result<E, T> ok(T value) {
        return new Ok<>(value);
    }

    /** Build a failure. */
    static <E, T> Result<E, T> err(E error) {
        return new Err<>(error);
    }

    /**
     * Run a {@code Callable} and capture any thrown exception as a typed {@link Err}.
     *
     * <p>The body is a {@link Callable} (rather than a {@code Supplier}) precisely so it
     * may throw <em>checked</em> exceptions: this method exists to wrap boundaries that
     * talk to APIs which still throw (legacy stdlib, third-party libraries). The error
     * mapper translates the thrown {@code Throwable} into an {@code E}. {@code attempt}
     * itself never throws — every {@code Throwable} becomes an {@link Err}.
     *
     * <p><strong>Composing several fallible steps.</strong> When you have a sequence of
     * dependent operations that each throw on failure, prefer one {@code attempt} wrapping
     * straight-line code over a tower of nested {@link #flatMap(Function) flatMap} calls.
     * On a virtual thread these are ordinary blocking calls; the first one to throw
     * short-circuits the rest, and the single {@code onThrow} mapper turns whatever was
     * thrown into your error type:
     * <pre>{@code
     * Result<AppError, Conditions> conditions = Result.attempt(() -> {
     *     var location = geocoder.locate(query);        // throws on failure
     *     var coords   = geocoder.coordinates(location);
     *     var weather  = weatherApi.current(coords);    // never reached if locate() threw
     *     return Conditions.of(location, weather);
     * }, AppError::from);
     * }</pre>
     * This reads like do-notation but needs no monad: it is the fforj-idiomatic
     * replacement for {@code flatMap}-in-{@code flatMap} when the steps are effectful.
     * Use {@link #flatMap(Function) flatMap}/{@link #zip(Result, BiFunction) zip} instead
     * when the steps are pure {@code Result} values rather than throwing calls.
     *
     * <p><strong>Interruption.</strong> If the body throws {@link InterruptedException},
     * the thread's interrupt status is re-asserted before the exception is mapped to an
     * {@link Err} — {@code attempt} stays total, but the cooperative-cancellation signal
     * is preserved for the surrounding scope ({@code Scopes}, {@code Retry}, or any other
     * blocking call further up). Note that {@code Throwable} capture includes
     * {@link Error}s; if you don't want to handle those as values, rethrow from
     * {@code onThrow}.
     *
     * <p><strong>Binding aborts pass through.</strong> The control-flow abort used by
     * {@link #binding(Function)} and {@code Validated.accumulate} is <em>not</em> captured:
     * if the body short-circuits an enclosing block ({@code bind.on(...)} of an {@code Err},
     * {@code Bound.value()} of a failed binding), the abort propagates through
     * {@code attempt} untouched instead of being mapped to a meaningless {@code Err}.
     */
    static <E, T> Result<E, T> attempt(
            Callable<? extends T> body,
            Function<? super Throwable, ? extends E> onThrow
    ) {
        try {
            return ok(body.call());
        } catch (Halt halt) {
            // Control flow of an enclosing binding/accumulate block, not a failure of the
            // body — let it unwind to its own boundary.
            throw halt;
        } catch (InterruptedException ie) {
            // Don't swallow cooperative cancellation: keep attempt total (the caller gets
            // an Err) but re-assert the flag so enclosing scopes still observe it.
            Thread.currentThread().interrupt();
            return err(onThrow.apply(ie));
        } catch (Throwable t) {
            return err(onThrow.apply(t));
        }
    }

    /**
     * Lift an {@link Optional} into a {@code Result}: a present value becomes {@link Ok},
     * an empty {@code Optional} becomes {@link Err} carrying {@code ifEmpty.get()}.
     *
     * <p>The error is caller-supplied because emptiness alone carries no reason — only the
     * caller knows what an absent value means in their domain. The supplier is evaluated
     * lazily, so no error is built when the value is present. This is the bridge from
     * {@code Optional}-returning APIs (lookups, {@code NonEmptyList.fromList}, etc.) into a
     * {@code Result} pipeline; the reverse direction is {@link #okValue()} / {@link #errValue()}.
     *
     * <pre>{@code
     * Result<AppError, NonEmptyList<X>> r =                     // AppError is your own type
     *     Result.fromOptional(NonEmptyList.fromList(xs), AppError.NoCandidates::new);
     * }</pre>
     */
    static <E, T> Result<E, T> fromOptional(
            Optional<? extends T> maybe,
            Supplier<? extends E> ifEmpty
    ) {
        Objects.requireNonNull(maybe, "maybe must not be null");
        Objects.requireNonNull(ifEmpty, "ifEmpty supplier must not be null");
        return maybe.isPresent() ? ok(maybe.get()) : err(ifEmpty.get());
    }

    /**
     * The unwrapping handle passed to {@link #binding(Function)}.
     *
     * <p>Calling {@link #on(Result)} inside a binding block either returns the success
     * value or aborts the whole block at the first {@link Err} encountered.
     */
    @FunctionalInterface
    interface Binder<E> {
        /**
         * Unwrap a {@code Result} inside a {@link #binding(Function) binding} block:
         * returns the value if {@link Ok}, otherwise short-circuits the enclosing block,
         * which then evaluates to that {@link Err}. Only legal while the surrounding
         * {@code binding} call is on the stack.
         */
        <T> T on(Result<E, T> result);

        /**
         * Bind an {@link Optional} inside a {@link #binding(Function) binding} block: a
         * present value is returned, an empty {@code Optional} short-circuits the block
         * with {@code ifEmpty.get()}. Lets {@code Optional}- and {@code Result}-returning
         * calls be sequenced together without bridging each one by hand.
         *
         * <pre>{@code
         * var candidates = bind.on(NonEmptyList.fromList(xs), AppError.NoCandidates::new);
         * }</pre>
         */
        default <T> T on(Optional<? extends T> maybe, Supplier<? extends E> ifEmpty) {
            return on(fromOptional(maybe, ifEmpty));
        }
    }

    /**
     * Sequence several {@code Result}-returning calls as straight-line code, unwrapping
     * each success and short-circuiting on the first failure — do-notation for {@code Result}.
     *
     * <p>Inside the block you call {@link Binder#on(Result) bind.on(...)} on any
     * {@code Result<E, ?>}; it hands back the raw success value, so you never pattern-match
     * or nest {@link #flatMap(Function) flatMap}. The first {@link Err} aborts the rest of
     * the block and becomes the result:
     * <pre>{@code
     * Result<String, Integer> total = Result.binding(bind -> {
     *     int a = bind.on(parsePositive("3"));    // Ok  -> 3
     *     int b = bind.on(parsePositive("4"));    // Ok  -> 4
     *     int c = bind.on(parsePositive("-1"));   // Err -> aborts here
     *     return a + b + c;                        // never reached
     * });
     * // total == Result.err("not positive: -1")
     * }</pre>
     *
     * <p>Use this when your steps already return {@code Result}. When the steps instead
     * <em>throw</em>, reach for {@link #attempt(Callable, Function)}; the two compose
     * (wrap a throwing call in {@code attempt}, then {@code bind.on} its result).
     *
     * <h4>How it short-circuits, and the one caveat</h4>
     * <p>{@code bind.on} aborts the block by throwing a private, stack-trace-free
     * control-flow exception that {@code binding} catches at the boundary. Two consequences
     * to know:
     * <ul>
     *   <li>A broad {@code try { ... } catch (RuntimeException e)} <em>around a
     *       {@code bind.on} call inside the block</em> will swallow the short-circuit and
     *       break the abort. Don't wrap bound calls in catch-all handlers.</li>
     *   <li>Steps that throw a genuine exception (rather than returning {@code Err}) are
     *       <em>not</em> captured here — the throwable propagates out of {@code binding}.
     *       Use {@link #attempt(Callable, Function)} for those.</li>
     * </ul>
     * Nested {@code binding} calls are safe; each abort carries the identity of the block
     * that created it and is caught only by that block's boundary — using an outer binder
     * inside an inner block aborts the outer block, as it should.
     *
     * @param block receives a {@link Binder} and returns the composed success value.
     * @return {@link Ok} of the block's return value, or the first {@link Err} that
     *         {@code bind.on} encountered.
     */
    static <E, T> Result<E, T> binding(Function<? super Binder<E>, ? extends T> block) {
        Objects.requireNonNull(block, "binding block must not be null");

        // Local subclass so it closes over E — the error rides the exception with no cast.
        final class Abort extends Halt {
            final E error;
            Abort(Object owner, E error) {
                super(owner);
                this.error = error;
            }
        }

        var binder = new Binder<E>() {
            @Override
            public <U> U on(Result<E, U> result) {
                return switch (result) {
                    case Ok<E, U> ok -> ok.value();
                    case Err<E, U> err -> throw new Abort(this, err.error());
                };
            }
        };

        try {
            return ok(block.apply(binder));
        } catch (Abort abort) {
            // A local class is shared by every invocation of this method, so a nested
            // binding block also catches an outer block's abort here. Only handle our
            // own; rethrow foreign aborts so they unwind to the block that created them.
            if (abort.owner != binder) {
                throw abort;
            }
            return err(abort.error);
        }
    }

    // ---------------------------------------------------------------------
    // Pattern queries (less verbose than explicit switch when you only need
    // a boolean / Optional)
    // ---------------------------------------------------------------------

    /** {@code true} if this is {@link Ok}. */
    default boolean isOk() {
        return this instanceof Ok<E, T>;
    }

    /** {@code true} if this is {@link Err}. */
    default boolean isErr() {
        return this instanceof Err<E, T>;
    }

    /**
     * Returns the success value if {@link Ok}, else empty.
     *
     * <p>Named {@code okValue} rather than {@code value} so it does not collide with the
     * {@link Ok#value()} record accessor (which returns the raw {@code T}); this query
     * lifts that into an {@link Optional} for call sites that don't pattern-match.
     */
    default Optional<T> okValue() {
        return switch (this) {
            case Ok<E, T> ok -> Optional.of(ok.value());
            case Err<E, T> ignored -> Optional.empty();
        };
    }

    /**
     * Returns the error if {@link Err}, else empty.
     *
     * <p>Named {@code errValue} rather than {@code error} so it does not collide with the
     * {@link Err#error()} record accessor (which returns the raw {@code E}); this query
     * lifts that into an {@link Optional} for call sites that don't pattern-match.
     */
    default Optional<E> errValue() {
        return switch (this) {
            case Ok<E, T> ignored -> Optional.empty();
            case Err<E, T> err -> Optional.of(err.error());
        };
    }

    // ---------------------------------------------------------------------
    // Combinators
    // ---------------------------------------------------------------------

    /** Transform the success value; preserve the error. */
    default <U> Result<E, U> map(Function<? super T, ? extends U> f) {
        return switch (this) {
            case Ok<E, T> ok -> new Ok<>(f.apply(ok.value()));
            case Err<E, T> err -> new Err<>(err.error());
        };
    }

    /** Transform the error; preserve the success value. */
    default <F> Result<F, T> mapErr(Function<? super E, ? extends F> f) {
        return switch (this) {
            case Ok<E, T> ok -> new Ok<>(ok.value());
            case Err<E, T> err -> new Err<>(f.apply(err.error()));
        };
    }

    /** Sequential composition: chain another operation that itself may fail. */
    default <U> Result<E, U> flatMap(Function<? super T, ? extends Result<E, U>> f) {
        return switch (this) {
            case Ok<E, T> ok -> f.apply(ok.value());
            case Err<E, T> err -> new Err<>(err.error());
        };
    }

    /** Combine two results into one via a binary function; both must be {@link Ok}. */
    default <U, R> Result<E, R> zip(
            Result<E, U> other,
            BiFunction<? super T, ? super U, ? extends R> f
    ) {
        return flatMap(t -> other.map(u -> f.apply(t, u)));
    }

    /** Provide a fallback when this is {@link Err}. The fallback must not be null. */
    default T getOrElse(T fallback) {
        Objects.requireNonNull(fallback,
                "getOrElse fallback must not be null — use okValue() for absence");
        return switch (this) {
            case Ok<E, T> ok -> ok.value();
            case Err<E, T> ignored -> fallback;
        };
    }

    /** Provide a lazy fallback when this is {@link Err}. */
    default T getOrElseGet(Function<? super E, ? extends T> fallback) {
        Objects.requireNonNull(fallback, "getOrElseGet fallback must not be null");
        return switch (this) {
            case Ok<E, T> ok -> ok.value();
            case Err<E, T> err -> fallback.apply(err.error());
        };
    }

    /** Recover from a failure with another {@code Result}. */
    default Result<E, T> recover(Function<? super E, ? extends Result<E, T>> recovery) {
        return switch (this) {
            case Ok<E, T> ok -> ok;
            case Err<E, T> err -> recovery.apply(err.error());
        };
    }

    /** Throw a custom exception on failure; return the value otherwise. */
    default <X extends RuntimeException> T orElseThrow(
            Function<? super E, ? extends X> mapper
    ) {
        return switch (this) {
            case Ok<E, T> ok -> ok.value();
            case Err<E, T> err -> { throw mapper.apply(err.error()); }
        };
    }

    /** Fold both cases into a single value. */
    default <R> R fold(
            Function<? super E, ? extends R> onErr,
            Function<? super T, ? extends R> onOk
    ) {
        return switch (this) {
            case Ok<E, T> ok -> onOk.apply(ok.value());
            case Err<E, T> err -> onErr.apply(err.error());
        };
    }
}
