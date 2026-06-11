package dev.fforj;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A {@link List} with a compile-time guarantee that it contains at least one element.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Validation: {@code Validated.Invalid} carries {@code NonEmptyList<E>} so callers
 *       can't forget to handle the empty-error case.</li>
 *   <li>Domain constraints: a {@code Permission}'s required scopes, a tenant's owners,
 *       any "must have at least one" collection.</li>
 * </ul>
 *
 * <p>Backed by a defensively-copied {@link List} for {@link #tail()} so callers cannot
 * mutate the internal state.
 */
public record NonEmptyList<T>(T head, List<T> tail) {

    public NonEmptyList {
        Objects.requireNonNull(head, "head must not be null");
        Objects.requireNonNull(tail, "tail must not be null");
        tail = List.copyOf(tail);
    }

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /** One element. */
    public static <T> NonEmptyList<T> of(T head) {
        return new NonEmptyList<>(head, List.of());
    }

    /** One element + N more. */
    @SafeVarargs
    public static <T> NonEmptyList<T> of(T head, T... rest) {
        return new NonEmptyList<>(head, List.of(rest));
    }

    /**
     * Try to lift a {@link List}. Returns empty if the list itself is empty — absence,
     * not failure; use {@code Result.fromOptional} to attach a domain error if needed.
     */
    public static <T> Optional<NonEmptyList<T>> fromList(List<? extends T> list) {
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new NonEmptyList<>(list.getFirst(), List.copyOf(list.subList(1, list.size()))));
    }

    // ---------------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------------

    /** Total element count: head plus tail; always at least 1. */
    public int size() {
        return 1 + tail.size();
    }

    /** Convert to an immutable plain {@link List}, head first. */
    public List<T> toList() {
        var all = new ArrayList<T>(size());
        all.add(head);
        all.addAll(tail);
        return Collections.unmodifiableList(all);
    }

    /** Stream all elements, head first. */
    public Stream<T> stream() {
        return Stream.concat(Stream.of(head), tail.stream());
    }

    // ---------------------------------------------------------------------
    // Combinators
    // ---------------------------------------------------------------------

    /** Transform every element; the result is non-empty by construction. */
    public <U> NonEmptyList<U> map(Function<? super T, ? extends U> f) {
        return new NonEmptyList<>(f.apply(head), tail.stream().<U>map(f).toList());
    }

    /** Append one or more elements. */
    @SafeVarargs
    public final NonEmptyList<T> append(T first, T... more) {
        var combined = new ArrayList<>(tail);
        combined.add(first);
        Collections.addAll(combined, more);
        return new NonEmptyList<>(head, combined);
    }

    /** Concatenate two non-empty lists. */
    public NonEmptyList<T> concat(NonEmptyList<T> other) {
        var combined = new ArrayList<>(tail);
        combined.add(other.head);
        combined.addAll(other.tail);
        return new NonEmptyList<>(head, combined);
    }
}
