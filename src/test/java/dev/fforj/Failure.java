package dev.fforj;

/**
 * Test-only ADT of domain failures.
 *
 * <p>Tests use this in place of raw {@code String} errors so they assert on typed,
 * pattern-matchable values — the same sealed-interface-of-records shape the library
 * itself promotes — rather than comparing opaque strings.
 */
sealed interface Failure {

    /** A number that was required to be positive but wasn't. */
    record NotPositive(int value) implements Failure {}

    /** Input that didn't parse as an integer. */
    record NotANumber(String input) implements Failure {}

    /** A free-form failure carrying a message (e.g. mapped from a thrown exception). */
    record Message(String text) implements Failure {}

    /** Human-readable description — handy for the few tests that need a string back. */
    default String message() {
        return switch (this) {
            case NotPositive np -> "not positive: " + np.value();
            case NotANumber n -> "not a number: " + n.input();
            case Message m -> m.text();
        };
    }
}
