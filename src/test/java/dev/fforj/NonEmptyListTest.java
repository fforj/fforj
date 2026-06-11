package dev.fforj;

import org.junit.jupiter.api.Test;

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
}
