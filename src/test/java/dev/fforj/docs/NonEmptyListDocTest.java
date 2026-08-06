package dev.fforj.docs;

import dev.fforj.NonEmptyList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// ---
/// title: NonEmptyList: emptiness, unrepresentable
/// slug: nonemptylist
/// order: 3
/// summary: A list the compiler knows has at least one element, so head() is total.
/// ---
///
/// Half the defensive code in a codebase guards against collections that "shouldn't"
/// be empty. `NonEmptyList<T>` deletes that code by making emptiness impossible to
/// construct: it's a `head` plus a `tail`, so `head()` always succeeds and `size()`
/// is always at least one. This is
/// [parse, don't validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/)
/// applied to collections. It is also the foundation `Validated` stands on, which is
/// why an `Invalid` can never carry zero errors.
class NonEmptyListDocTest {

    /// ## The parser is the boundary
    ///
    /// `fromList` is the only door in from a possibly-empty world, and it returns
    /// `Optional`: absence, not failure, because only the caller knows what an empty
    /// input means in their domain. Past that door, the guarantee holds everywhere
    /// the value flows.
    @Test
    void parse_a_plain_list_once_at_the_boundary() {
        Optional<NonEmptyList<String>> owners = NonEmptyList.fromList(List.of("ada", "grace"));
        Optional<NonEmptyList<String>> nobody = NonEmptyList.fromList(List.of());

        assertTrue(owners.isPresent());
        assertEquals("ada", owners.get().head());   // total: no isEmpty() check, ever
        assertTrue(nobody.isEmpty());
    }

    /// ## Operations preserve the guarantee
    ///
    /// `map`, `append`, and `concat` all return `NonEmptyList`, so non-emptiness
    /// survives by construction and downstream code keeps the proof without
    /// re-checking anything.
    @Test
    void transform_without_losing_non_emptiness() {
        NonEmptyList<Integer> counts = NonEmptyList.of(1, 2, 3).map(n -> n * 10);
        assertEquals(List.of(10, 20, 30), counts.toList());

        NonEmptyList<Integer> more = counts.append(40).concat(NonEmptyList.of(50));
        assertEquals(5, more.size());
        assertEquals(10, more.head());
    }

    /// ## It iterates, and it can't be mutated
    ///
    /// `NonEmptyList` is `Iterable` (head first), and it defends its invariant: the
    /// list you build it from is defensively copied, so no one holding the original
    /// reference can empty it out from under you.
    @Test
    void iterate_head_first_and_stay_immutable() {
        var source = new ArrayList<>(List.of("keep", "these"));
        var nel = NonEmptyList.fromList(source).orElseThrow();

        source.clear();                          // mutate the original all you like

        var seen = new ArrayList<String>();
        for (String s : nel) {
            seen.add(s);
        }
        assertEquals(List.of("keep", "these"), seen);
    }
}
