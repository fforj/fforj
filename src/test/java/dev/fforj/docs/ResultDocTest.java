package dev.fforj.docs;

import dev.fforj.NonEmptyList;
import dev.fforj.Result;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// ---
/// title: Result: errors as values
/// slug: result
/// order: 1
/// summary: A sealed Ok | Err sum type. Pattern-match failures instead of throwing them.
/// ---
///
/// `Result<E, T>` is an operation that either succeeded with a `T` or failed with an
/// `E`. Both outcomes are ordinary values, so failure handling becomes plain code you
/// can read, type-check, and test, instead of a control-flow surprise three stack
/// frames up.
///
/// Errors are typed. Instead of `String` messages or a grab-bag `Exception`, model
/// the ways an operation can fail as a small sealed hierarchy:
class ResultDocTest {

    // site:include
    sealed interface ParseError {
        record NotANumber(String raw) implements ParseError {}
        record NotPositive(int value) implements ParseError {}
    }

    // site:include
    static Result<ParseError, Integer> parsePositive(String raw) {
        int n;
        try {
            n = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return Result.err(new ParseError.NotANumber(raw));
        }
        return n > 0 ? Result.ok(n) : Result.err(new ParseError.NotPositive(n));
    }

    /// ## Pattern matching is the API
    ///
    /// `Result` is a sealed interface with two record cases, `Ok` and `Err`. A `switch`
    /// over it is exhaustive: handle both cases or the compiler stops you. Combinators
    /// exist for chains, but this is the primary interface. There is no method to
    /// memorize, just the language.
    @Test
    void match_both_cases_exhaustively() {
        String message = switch (parsePositive("-3")) {
            case Result.Ok<ParseError, Integer> ok -> "got " + ok.value();
            case Result.Err<ParseError, Integer> err -> switch (err.error()) {
                case ParseError.NotANumber(String raw) -> raw + " is not a number";
                case ParseError.NotPositive(int n) -> n + " is not positive";
            };
        };

        assertEquals("-3 is not positive", message);
    }

    /// ## Transform without unwrapping
    ///
    /// `map` transforms a success and leaves an error untouched; `flatMap` chains
    /// another fallible step; `recover` turns an error back into a success. Chains
    /// stop doing work at the first `Err`. No null checks, no nesting.
    @Test
    void chain_fallible_steps() {
        Result<ParseError, Integer> doubled = parsePositive("21").map(n -> n * 2);
        assertEquals(Result.ok(42), doubled);

        Result<ParseError, Integer> chained = parsePositive("6")
                .flatMap(n -> parsePositive(String.valueOf(n - 7)))   // -1 -> Err
                .map(n -> n * 100);                                    // never runs
        assertEquals(Result.err(new ParseError.NotPositive(-1)), chained);

        assertEquals(Result.ok(0), chained.recover(e -> Result.ok(0)));
    }

    /// ## Wrap code that throws
    ///
    /// The world is full of APIs that throw. `attempt` runs a block, catches anything
    /// thrown, and maps it into your error type. Several dependent throwing calls
    /// compose as straight-line code, and the first throw short-circuits the rest.
    @Test
    void capture_throwing_calls_as_typed_errors() {
        Result<String, Integer> r = Result.attempt(() -> {
            int a = mustParse("12");        // throws on bad input
            int b = mustParse("oops");      // throws here...
            return a + b;                   // ...so this never runs
        }, t -> "failed: " + t.getMessage());

        assertEquals(Result.err("failed: not a number: oops"), r);
    }

    // site:include
    static int mustParse(String raw) throws IOException {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IOException("not a number: " + raw);
        }
    }

    /// ## Sequence Result-returning steps with `binding`
    ///
    /// When steps already return `Result`, `binding` gives you do-notation:
    /// `bind.on(...)` hands back the raw success value, and the first `Err` aborts the
    /// whole block and becomes its result. No `flatMap` towers, and every earlier
    /// value stays in scope for later steps.
    @Test
    void sequence_steps_as_straight_line_code() {
        Result<ParseError, Integer> total = Result.binding(bind -> {
            int a = bind.on(parsePositive("3"));
            int b = bind.on(parsePositive("4"));
            int c = bind.on(parsePositive("5"));
            return a * b * c;
        });

        assertEquals(Result.ok(60), total);
    }

    /// ## Guards without values: `ensure`
    ///
    /// Not every rule produces a value; some just have to hold. `bind.ensure` reads
    /// like an assertion but fails like everything else here: as a typed `Err` that
    /// short-circuits the block.
    @Test
    void a_failed_guard_short_circuits_like_any_err() {
        Result<String, Integer> withdrawal = Result.binding(bind -> {
            int balance = bind.on(Result.<String, Integer>ok(70));
            bind.ensure(balance >= 100, () -> "insufficient funds: " + balance);
            return balance - 100;                  // never reached
        });

        assertEquals(Result.err("insufficient funds: 70"), withdrawal);
    }

    /// ## Bridge to and from Optional
    ///
    /// `Optional` models absence; `Result` models failure with a reason. The bridges
    /// keep the line crisp: lifting an empty `Optional` into a `Result` asks *you* to
    /// name the failure, because emptiness alone doesn't carry one.
    @Test
    void name_the_failure_when_lifting_an_optional() {
        Optional<NonEmptyList<Integer>> none = NonEmptyList.fromList(List.of());

        Result<String, NonEmptyList<Integer>> r =
                Result.fromOptional(none, () -> "no candidates to rank");

        assertEquals(Result.err("no candidates to rank"), r);
        assertEquals(Optional.of("no candidates to rank"), r.errValue());
    }
}
