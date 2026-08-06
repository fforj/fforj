package dev.fforj.docs;

import dev.fforj.NonEmptyList;
import dev.fforj.Result;
import dev.fforj.Retry;
import dev.fforj.Validated;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// ---
/// title: Putting it together — an order, end to end
/// slug: order
/// order: 5
/// summary: Result, Validated, and NonEmptyList meeting in one pipeline, raw input to response.
/// ---
///
/// The three types are designed to meet. The whole idea in one sentence:
/// **accumulate at the boundary, short-circuit in the core, and make illegal states
/// unrepresentable in between.** This page walks a single scenario — placing an
/// order — from raw untrusted input to the final response.
///
/// The domain first. `RawOrder` is what arrives over the wire: all strings, anything
/// possible. `Order` is what the rest of the system works with, and none of its
/// fields are strings: each is a domain type that follows
/// [parse, don't validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/).
/// Every type has a *wall* — a constructor that makes an illegal value impossible to
/// construct — and the fallible ones have a *door*: a static `parse` returning
/// `Validated`, so boundary failures can accumulate:
class CombinedDocTest {

    // site:include
    record RawOrder(String email, List<String> skus, String country) {}

    // site:include
    sealed interface OrderError {
        record BadEmail(String raw) implements OrderError {}
        record NoItems() implements OrderError {}
        record UnknownCountry(String raw) implements OrderError {}
        record OutOfStock(Sku sku) implements OrderError {}
        record PaymentFailed(String reason) implements OrderError {}
    }

    // site:include
    record Email(String value) {
        Email {                                     // the wall: an illegal Email cannot exist
            if (!value.contains("@")) {
                throw new IllegalArgumentException("not an email: " + value);
            }
        }

        static Validated<OrderError, Email> parse(String raw) {   // the door: typed errors
            return raw.contains("@")
                    ? Validated.valid(new Email(raw))
                    : Validated.invalid(new OrderError.BadEmail(raw));
        }
    }

    // site:include
    record Country(String code) {
        static final Set<String> SHIPS_TO = Set.of("RO", "NL", "DE");

        Country {
            if (!SHIPS_TO.contains(code)) {
                throw new IllegalArgumentException("no shipping to: " + code);
            }
        }

        static Validated<OrderError, Country> parse(String raw) {
            return SHIPS_TO.contains(raw)
                    ? Validated.valid(new Country(raw))
                    : Validated.invalid(new OrderError.UnknownCountry(raw));
        }
    }

    // site:include
    record Sku(String code) {
        Sku {
            if (code.isBlank()) {
                throw new IllegalArgumentException("blank SKU");
            }
        }
    }

    // site:include
    record Order(Email email, NonEmptyList<Sku> items, Country country) {}

    /// Note what `Order` rules out by shape alone: a malformed email, a country we
    /// don't ship to, a blank SKU, and — because `items` is a `NonEmptyList` — an
    /// order with nothing in it. Downstream code never re-checks any of this; the
    /// proof lives in the types. The wall holds even against code that skips the
    /// door:
    @Test
    void the_wall_rejects_what_the_door_would() {
        assertThrows(IllegalArgumentException.class, () -> new Email("not-an-email"));
        assertThrows(IllegalArgumentException.class, () -> new Country("XX"));
    }

    /// ## The boundary: accumulate everything
    ///
    /// Field validations are independent, so a failure in one must not hide a failure
    /// in another. `accumulate` binds all three doors — and note `NonEmptyList.fromList`
    /// doing double duty: it *is* the items validation, its empty case accumulating
    /// `NoItems` alongside whatever else is wrong. The final line assembles the
    /// domain: `map(Sku::new)` walks through the wall, and non-emptiness survives
    /// `map` by construction.
    // site:include
    static Validated<OrderError, Order> parse(RawOrder raw) {
        return Validated.accumulate(acc -> {
            var email = acc.on(Email.parse(raw.email()));
            var items = acc.on(NonEmptyList.fromList(raw.skus()), OrderError.NoItems::new);
            var country = acc.on(Country.parse(raw.country()));
            return new Order(email.value(), items.value().map(Sku::new), country.value());
        });
    }

    @Test
    void a_broken_request_reports_every_problem_at_once() {
        var raw = new RawOrder("not-an-email", List.of(), "XX");

        assertEquals(
                Validated.invalid(NonEmptyList.of(
                        new OrderError.BadEmail("not-an-email"),
                        new OrderError.NoItems(),
                        new OrderError.UnknownCountry("XX"))),
                parse(raw));
    }

    /// Past the boundary, the guarantees hold by construction — every field is
    /// already a domain type, and no later code path can un-prove them:
    @Test
    void a_parsed_order_carries_its_invariants() {
        var order = parse(new RawOrder("ada@lovelace.dev", List.of("SKU-1", "SKU-2"), "RO"));

        assertEquals(Validated.valid(new Order(
                new Email("ada@lovelace.dev"),
                NonEmptyList.of(new Sku("SKU-1"), new Sku("SKU-2")),
                new Country("RO"))), order);
    }

    /// ## The core: short-circuit dependent steps
    ///
    /// After parsing, the steps *depend* on each other — there's no point pricing an
    /// order that failed the stock check, and no point charging for it either. That's
    /// `Result` territory. The stock check returns a `Result`; the payment gateway is
    /// the other kind of boundary — it *throws* — so `attempt` will wrap it into the
    /// same typed world. Notice both speak in domain types: `OutOfStock` carries a
    /// `Sku`, the gateway takes an `Email`.
    // site:include
    static Result<OrderError, Order> checkStock(Order order, Set<String> inStock) {
        return order.items().stream()
                .filter(sku -> !inStock.contains(sku.code()))
                .findFirst()
                .<Result<OrderError, Order>>map(gone -> Result.err(new OrderError.OutOfStock(gone)))
                .orElse(Result.ok(order));
    }

    // site:include
    @FunctionalInterface
    interface Gateway {
        String charge(Email email, int cents) throws IOException;
    }

    /// ## The bridge: one error channel for both worlds
    ///
    /// Here is where it all meets. `toResult()` carries the boundary's error *batch*
    /// into the short-circuiting world, and each core step joins the same channel
    /// with `mapErr(NonEmptyList::of)` — a single error is just a batch of one.
    /// `NonEmptyList` quietly unifies the whole pipeline's error type:
    // site:include
    static Result<NonEmptyList<OrderError>, String> place(
            RawOrder raw, Set<String> inStock, Gateway gateway) {
        return Result.binding(bind -> {
            Order order = bind.on(parse(raw).toResult());                  // all boundary errors at once
            bind.on(checkStock(order, inStock).mapErr(NonEmptyList::of));  // then fail fast
            int cents = order.items().size() * 700;
            return bind.on(Result.attempt(
                    () -> gateway.charge(order.email(), cents),
                    t -> new OrderError.PaymentFailed(t.getMessage()))
                    .mapErr(NonEmptyList::of));
        });
    }

    @Test
    void a_valid_order_flows_straight_through() {
        var raw = new RawOrder("ada@lovelace.dev", List.of("SKU-1"), "RO");

        Result<NonEmptyList<OrderError>, String> placed =
                place(raw, Set.of("SKU-1"), (email, cents) -> "receipt-7391/" + cents);

        assertEquals(Result.ok("receipt-7391/700"), placed);
    }

    /// Boundary failures arrive as the full batch, and the pipeline proves its
    /// laziness: nothing downstream runs — the gateway is never touched:
    @Test
    void boundary_failures_arrive_as_a_batch_and_stop_the_core() {
        var charged = new boolean[]{false};
        var raw = new RawOrder("not-an-email", List.of(), "XX");

        var placed = place(raw, Set.of("SKU-1"), (email, cents) -> {
            charged[0] = true;
            return "receipt";
        });

        assertEquals(Result.err(NonEmptyList.of(
                new OrderError.BadEmail("not-an-email"),
                new OrderError.NoItems(),
                new OrderError.UnknownCountry("XX"))), placed);
        assertFalse(charged[0], "a rejected order must never reach the gateway");
    }

    /// Core failures are singular — the first dependent step to fail ends the story,
    /// as a batch of one:
    @Test
    void core_failures_short_circuit_one_at_a_time() {
        var raw = new RawOrder("ada@lovelace.dev", List.of("SKU-1", "SKU-9"), "RO");

        var placed = place(raw, Set.of("SKU-1"), (email, cents) -> {
            throw new IOException("must not be reached");
        });

        assertEquals(Result.err(NonEmptyList.of(
                new OrderError.OutOfStock(new Sku("SKU-9")))), placed);
    }

    /// And a throwing gateway becomes a typed error like everything else:
    @Test
    void a_throwing_gateway_becomes_a_typed_error() {
        var raw = new RawOrder("ada@lovelace.dev", List.of("SKU-1"), "RO");

        var placed = place(raw, Set.of("SKU-1"), (email, cents) -> {
            throw new IOException("card declined");
        });

        assertEquals(Result.err(NonEmptyList.of(
                new OrderError.PaymentFailed("card declined"))), placed);
    }

    /// ## The response: one exhaustive switch
    ///
    /// The pipeline's result is a value, so the edge of the system is a single
    /// pattern match — the compiler guarantees both outcomes are handled, and the
    /// error branch has the *complete* story to tell the caller:
    @Test
    void the_edge_of_the_system_is_a_single_pattern_match() {
        var raw = new RawOrder("not-an-email", List.of(), "XX");
        var placed = place(raw, Set.of("SKU-1"), (email, cents) -> "receipt");

        String response = switch (placed) {
            case Result.Ok<NonEmptyList<OrderError>, String>(String receipt) ->
                    "201 Created: " + receipt;
            case Result.Err<NonEmptyList<OrderError>, String>(NonEmptyList<OrderError> errors) ->
                    "422 Unprocessable: " + errors.size() + " problems";
        };

        assertEquals("422 Unprocessable: 3 problems", response);
    }

    /// ## Epilogue: when the gateway is flaky, add Retry
    ///
    /// One piece was missing from the tour: real gateways time out. `Retry` wraps any
    /// `Result`-returning step, and because the errors are *typed*, the retry
    /// predicate can tell a transient timeout (worth retrying) from a declined card
    /// (never retry — it won't improve):
    @Test
    void a_flaky_gateway_is_retried_but_a_declined_card_would_not_be() throws InterruptedException {
        var calls = new int[]{0};
        var policy = Retry.Policy.exponential(4, Duration.ZERO);

        Result<OrderError, String> charged = Retry.run(policy,
                e -> e instanceof OrderError.PaymentFailed(String reason)
                        && reason.contains("timeout"),
                () -> {
                    calls[0]++;
                    return calls[0] < 3
                            ? Result.err(new OrderError.PaymentFailed("gateway timeout"))
                            : Result.ok("receipt-7391");
                });

        assertEquals(Result.ok("receipt-7391"), charged);
        assertEquals(3, calls[0]);
    }
}
