package dev.fforj;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonEmptyListTest {

    @Test
    void of_with_single_element_produces_size_one() {
        var nel = NonEmptyList.of("a");
        assertEquals(1, nel.size());
        assertEquals(List.of("a"), nel.toList());
    }

    @Test
    void of_with_varargs_carries_all_elements_in_order() {
        var nel = NonEmptyList.of(1, 2, 3, 4);
        assertEquals(4, nel.size());
        assertEquals(List.of(1, 2, 3, 4), nel.toList());
    }

    @Test
    void map_preserves_head_and_tail_shape() {
        var mapped = NonEmptyList.of(1, 2, 3).map(i -> i * 10);
        assertEquals(List.of(10, 20, 30), mapped.toList());
    }

    @Test
    void concat_joins_two_non_empty_lists() {
        var a = NonEmptyList.of(1, 2);
        var b = NonEmptyList.of(3, 4);
        assertEquals(List.of(1, 2, 3, 4), a.concat(b).toList());
    }

    @Test
    void append_adds_elements_after_existing_tail() {
        var nel = NonEmptyList.of(1).append(2, 3);
        assertEquals(List.of(1, 2, 3), nel.toList());
    }

    @Test
    void fromList_returns_empty_for_empty_input() {
        var lifted = NonEmptyList.fromList(List.of());
        assertTrue(lifted.isEmpty());   // Optional.isEmpty(): the lift produced no value
    }

    @Test
    void fromList_returns_present_for_non_empty_input() {
        var lifted = NonEmptyList.fromList(List.of("x", "y"));
        assertTrue(lifted.isPresent());
        assertEquals(List.of("x", "y"), lifted.get().toList());
    }

    @Test
    void cannot_pass_null_head() {
        assertThrows(NullPointerException.class, () -> NonEmptyList.of((Object) null));
    }

    @Test
    void cannot_pass_null_tail_or_null_tail_element() {
        assertThrows(NullPointerException.class, () -> new NonEmptyList<>("a", null));
        assertThrows(NullPointerException.class,
                () -> new NonEmptyList<>("a", Arrays.asList("b", null)));
    }

    @Test
    void mutating_the_tail_passed_to_the_constructor_does_not_affect_the_list() {
        var tail = new ArrayList<>(List.of(2, 3));
        var nel = new NonEmptyList<>(1, tail);

        tail.add(99);

        assertEquals(List.of(1, 2, 3), nel.toList());
    }

    @Test
    void mutating_the_list_passed_to_fromList_does_not_affect_the_result() {
        var source = new ArrayList<>(List.of("x", "y"));
        var nel = NonEmptyList.fromList(source).orElseThrow();

        source.add("z");
        source.set(0, "mutated");

        assertEquals(List.of("x", "y"), nel.toList());
    }

    @Test
    void toList_and_tail_are_unmodifiable() {
        var nel = NonEmptyList.of(1, 2, 3);

        assertThrows(UnsupportedOperationException.class, () -> nel.toList().add(4));
        assertThrows(UnsupportedOperationException.class, () -> nel.tail().add(4));
    }

    @Test
    void iterates_all_elements_head_first() {
        var seen = new ArrayList<Integer>();
        for (int i : NonEmptyList.of(1, 2, 3)) {
            seen.add(i);
        }
        assertEquals(List.of(1, 2, 3), seen);
    }
}
