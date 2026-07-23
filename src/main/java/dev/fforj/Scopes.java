package dev.fforj;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Ergonomic helpers around {@link StructuredTaskScope} (JEP 505 — still a <em>preview</em>
 * API in Java 25, so loading this class requires {@code --enable-preview}; see the README).
 *
 * <p>The structured-concurrency model is the entire reason this library does NOT ship
 * an {@code IO} monad: with virtual threads + structured scopes you write straight-line
 * blocking code and get cancellation, composition, and resource lifecycle for free. The
 * only thing missing in the stdlib is the bridge between scope outcomes and the
 * {@link Result}/{@link Validated} types — that's what {@code Scopes} provides.
 *
 * <p>All methods here forward thread interruption to the surrounding scope: cancel the
 * caller and every forked subtask shuts down before the method returns.
 */
public final class Scopes {

    private Scopes() {}

    /**
     * Run all tasks in parallel virtual threads, accumulating every error.
     *
     * <p>Semantics:
     * <ul>
     *   <li>Every task runs to completion (even if some return {@code Err}). One slow
     *       task does not block another. This matches the "show me ALL the validation
     *       failures" use case.</li>
     *   <li>Values and errors are accumulated in task (argument) order, not completion
     *       order — results are stable regardless of scheduling.</li>
     *   <li>If any task throws an uncaught exception, it counts as {@code FAILED} on
     *       the scope handle; the exception is mapped to an error via {@code onThrow}
     *       and added to the accumulator.</li>
     *   <li>If the caller's thread is interrupted, the scope is closed and
     *       {@link InterruptedException} propagates.</li>
     * </ul>
     *
     * @param tasks    list of fallible operations.
     * @param onThrow  maps an uncaught {@code Throwable} from a task to an error {@code E}.
     * @return {@code Valid} containing all task values when every task succeeded;
     *         {@code Invalid} carrying the accumulated errors otherwise.
     * @throws InterruptedException if interrupted while joining.
     */
    public static <E, T> Validated<E, List<T>> parallel(
            List<? extends Supplier<? extends Result<E, T>>> tasks,
            Function<? super Throwable, ? extends E> onThrow
    ) throws InterruptedException {
        Objects.requireNonNull(tasks);
        Objects.requireNonNull(onThrow);

        if (tasks.isEmpty()) {
            return Validated.valid(List.of());
        }

        // awaitAll (not the default fail-fast joiner): every subtask must run to
        // completion so we can inspect each handle and accumulate ALL errors, including
        // uncaught throws mapped via onThrow. A fail-fast joiner would short-circuit.
        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Result<E, T>>awaitAll())) {
            var handles = new ArrayList<StructuredTaskScope.Subtask<Result<E, T>>>(tasks.size());
            for (var task : tasks) {
                // fork wants a Callable; our public API takes a Supplier (tasks return
                // Result rather than throwing), so bridge with a method reference.
                handles.add(scope.fork(task::get));
            }
            scope.join();

            var values = new ArrayList<T>(handles.size());
            var errors = new ArrayList<E>();

            for (var handle : handles) {
                switch (handle.state()) {
                    case SUCCESS -> {
                        switch (handle.get()) {
                            case Result.Ok<E, T> ok -> values.add(ok.value());
                            case Result.Err<E, T> err -> errors.add(err.error());
                        }
                    }
                    case FAILED -> errors.add(onThrow.apply(handle.exception()));
                    // awaitAll joins every subtask, so after join() each handle is SUCCESS
                    // or FAILED. Seeing UNAVAILABLE would mean values silently went missing
                    // from a Valid result — fail loudly instead.
                    case UNAVAILABLE -> throw new IllegalStateException(
                            "subtask unavailable after join(); broken joiner invariant");
                }
            }

            if (errors.isEmpty()) {
                return Validated.valid(List.copyOf(values));
            }

            var head = errors.get(0);
            var tail = errors.subList(1, errors.size());
            return Validated.invalid(new NonEmptyList<>(head, tail));
        }
    }

    /**
     * "First {@code Ok} wins" race: fork all tasks; the first task to produce an
     * {@code Ok} cancels the rest. Tasks that return {@code Err} or throw do NOT win the
     * race — the scope keeps waiting for a success.
     *
     * <p>If every task fails (no {@code Ok} at all), returns one of the errors; which one
     * is unspecified — the underlying joiner picks a failure, completion order is not
     * guaranteed, and callers must not rely on receiving any particular task's error.
     * Useful for fan-out-to-multiple-mirrors patterns where any one source is sufficient.
     *
     * @throws IllegalArgumentException if {@code tasks} is empty.
     * @throws InterruptedException     if interrupted while joining.
     */
    public static <E, T> Result<E, T> race(
            List<? extends Supplier<? extends Result<E, T>>> tasks,
            Function<? super Throwable, ? extends E> onThrow
    ) throws InterruptedException {
        Objects.requireNonNull(tasks);
        Objects.requireNonNull(onThrow);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("race requires at least one task");
        }

        // The anySuccessfulResultOrThrow joiner treats ANY normal return as success —
        // including a task returning Result.Err. To make only genuine Oks win, unwrap Ok
        // to its value in the fork and convert Err into a throw of this local,
        // stack-trace-free carrier; the catch below converts it back to a typed Err.
        final class ErrCarrier extends RuntimeException {
            final E error;
            ErrCarrier(E error) {
                super(null, null, false, false);
                this.error = error;
            }
        }

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<T>anySuccessfulResultOrThrow())) {
            for (var task : tasks) {
                scope.fork(() -> switch (task.get()) {
                    case Result.Ok<E, T>(T value) -> value;
                    case Result.Err<E, T>(E error) -> throw new ErrCarrier(error);
                });
            }
            return Result.ok(scope.join());
        } catch (StructuredTaskScope.FailedException fe) {
            return fe.getCause() instanceof ErrCarrier carrier
                    ? Result.err(carrier.error)
                    : Result.err(onThrow.apply(fe.getCause()));
        }
    }
}
