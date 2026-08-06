package dev.fforj.docs;

import dev.fforj.NonEmptyList;
import dev.fforj.Result;
import dev.fforj.Retry;
import dev.fforj.Validated;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// ---
/// title: Putting it together: an order, end to end
/// slug: order
/// order: 5
/// summary: Result, Validated, and NonEmptyList meeting in one pipeline, raw input to response.
/// ---
///
/// The three types are designed to meet. The whole idea in one sentence:
/// **accumulate at the boundary, short-circuit in the core, and make illegal states
/// unrepresentable in between.** This page walks a single scenario, placing an
/// order, from raw untrusted input to the final response.
///
/// The domain first. `RawOrder` is what arrives over the wire: all strings, anything
/// possible. `Order` is what the rest of the system works with, and none of its
/// fields are strings: each is a domain type that follows
/// [parse, don't validate](https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/).
/// Every type has a *wall*, a constructor that makes an illegal value impossible to
/// construct. The fallible ones also have a *door*: a static `parse` returning
/// `Validated`, so boundary failures can accumulate. Each rule is written once, as a
/// small private predicate the wall and the door both consult. The tempting
/// alternative, letting `parse` call the constructor and catch the wall's exception,
/// runs routine validation on exception handling and can mislabel a genuine bug in
/// the constructor as bad user input:
class CombinedDocTest {

    // site:include
    record RawOrder(String email, List<String> skus, String country) {}

    // site:include
    sealed interface OrderError {
        record BadEmail(String raw) implements OrderError {}
        record NoItems() implements OrderError {}
        record BadSku(String raw) implements OrderError {}
        record TooManyItems(int count) implements OrderError {}
        record BadCountry(String raw) implements OrderError {}
        record UnshippableCountry(Country country) implements OrderError {}
        record OutOfStock(Sku sku) implements OrderError {}
        record GatewayTimeout() implements OrderError {}          // transient, worth retrying
        record PaymentFailed(String reason) implements OrderError {} // terminal, never retry
    }

    // site:include
    record Email(String value) {
        Email {                                     // the wall: an illegal Email cannot exist
            if (!isEmail(value)) {
                throw new IllegalArgumentException("not an email: " + value);
            }
        }

        static Validated<OrderError, Email> parse(String raw) {   // the door: typed errors
            return isEmail(raw)
                    ? Validated.valid(new Email(raw))
                    : Validated.invalid(new OrderError.BadEmail(raw));
        }

        private static boolean isEmail(String s) {  // the rule, written once
            return s.contains("@");
        }
    }

    // site:include
    record Country(String code) {
        Country {
            if (!wellFormed(code)) {
                throw new IllegalArgumentException("not a country code: " + code);
            }
        }

        static Validated<OrderError, Country> parse(String raw) {
            return wellFormed(raw)
                    ? Validated.valid(new Country(raw))
                    : Validated.invalid(new OrderError.BadCountry(raw));
        }

        private static boolean wellFormed(String code) {
            return code.length() == 2 && code.chars().allMatch(Character::isUpperCase);
        }
    }

    // site:include
    record Sku(String code) {
        Sku {
            if (!wellFormed(code)) {
                throw new IllegalArgumentException("malformed SKU: " + code);
            }
        }

        static Validated<OrderError, Sku> parse(String raw) {
            return wellFormed(raw)
                    ? Validated.valid(new Sku(raw))
                    : Validated.invalid(new OrderError.BadSku(raw));
        }

        private static boolean wellFormed(String code) {
            return code.startsWith("SKU-");
        }
    }

    // site:include
    record Order(Email email, NonEmptyList<Sku> items, Country country) {}

    /// Note what `Order` rules out by shape alone: a malformed email, a malformed
    /// country code, a malformed SKU, and, because `items` is a `NonEmptyList`, an
    /// order with nothing in it. Downstream code never re-checks any of this; the
    /// proof lives in the types. One rule is deliberately missing from that list,
    /// whether we *ship* to a country. Keep reading. The wall holds even against
    /// code that skips the door:
    @Test
    void the_wall_rejects_what_the_door_would() {
        assertThrows(IllegalArgumentException.class, () -> new Email("not-an-email"));
        assertThrows(IllegalArgumentException.class, () -> new Country("Romania"));
        assertThrows(IllegalArgumentException.class, () -> new Sku("junk"));
        // "XX" is a well-formed code. Whether we ship there is state, not shape:
        assertEquals("XX", new Country("XX").code());
    }

    /// ## The boundary: accumulate everything
    ///
    /// The items door is *composed from smaller doors*: an empty list is one failure
    /// (there's nothing to validate item by item), otherwise `Validated.traverse`
    /// parses every element and reports every bad one. Non-empty in, non-empty out,
    /// so the result is already the `NonEmptyList<Sku>` the domain wants:
    // site:include
    static Validated<OrderError, NonEmptyList<Sku>> parseItems(List<String> raws) {
        return Result.fromOptional(NonEmptyList.fromList(raws), OrderError.NoItems::new)
                .fold(Validated::invalid, skus -> Validated.traverse(skus, Sku::parse));
    }

    /// ## Rules that live in data: ports
    ///
    /// One rule can't be a type invariant no matter how much we'd like it to be:
    /// which countries we ship to. That list lives in a database and changes when
    /// the business does. A type invariant must be timeless; a `Country` parsed
    /// yesterday cannot become retroactively malformed because operations dropped a
    /// row tonight. So `Country` proves *shape*, and shippability is a question we
    /// ask, not a property we prove.
    ///
    /// The question goes through a *port*: a one-method interface the domain owns.
    /// Production adapts a database query behind it; tests hand in a lambda. This is
    /// hexagonal architecture in one sentence, the domain never imports the database
    /// and dependencies point inward, with a seam small enough to be a
    /// `@FunctionalInterface`:
    // site:include
    @FunctionalInterface
    interface ShippingPolicy {
        boolean shipsTo(Country country);
    }

    // site:include
    static final ShippingPolicy SHIPS_EU =        // in production: a repository adapter
            country -> Set.of("RO", "NL", "DE").contains(country.code());

    /// Field validations are independent, so a failure in one must not hide a failure
    /// in another. `accumulate` binds the doors, `ensure` joins the valueless rules
    /// (an order-size limit is *policy*, not shape), and the shipping check shows
    /// how a *dependent* validation fits: it needs the parsed `Country`, so its
    /// unwrap becomes a deliberate gate. Independent bindings come first and keep
    /// accumulating; the gate sits last, and if the country is malformed the policy
    /// question is unaskable anyway:
    // site:include
    static final int MAX_ITEMS = 10;

    // site:include
    static Validated<OrderError, Order> parse(RawOrder raw, ShippingPolicy shipping) {
        return Validated.accumulate(acc -> {
            var email = acc.on(Email.parse(raw.email()));
            var items = acc.on(parseItems(raw.skus()));
            acc.ensure(raw.skus().size() <= MAX_ITEMS,
                    () -> new OrderError.TooManyItems(raw.skus().size()));
            Country country = acc.on(Country.parse(raw.country())).value();  // the gate
            acc.ensure(shipping.shipsTo(country),
                    () -> new OrderError.UnshippableCountry(country));
            return new Order(email.value(), items.value(), country);
        });
    }

    @Test
    void a_broken_request_reports_every_problem_at_once() {
        var raw = new RawOrder("not-an-email", List.of(), "XX");

        assertEquals(
                Validated.invalid(NonEmptyList.of(
                        new OrderError.BadEmail("not-an-email"),
                        new OrderError.NoItems(),
                        new OrderError.UnshippableCountry(new Country("XX")))),
                parse(raw, SHIPS_EU));
    }

    /// The gate in action: a malformed country reports its shape error, and the
    /// policy check simply never runs, because there is no `Country` to ask about:
    @Test
    void a_malformed_country_gates_the_policy_check() {
        var raw = new RawOrder("ada@lovelace.dev", List.of("SKU-1"), "Romania");

        assertEquals(
                Validated.invalid(NonEmptyList.of(new OrderError.BadCountry("Romania"))),
                parse(raw, SHIPS_EU));
    }

    /// A failed guard accumulates with everything else. The oversized order still
    /// gets its other problems reported in the same response:
    @Test
    void a_guard_failure_accumulates_with_everything_else() {
        var elevenSkus = IntStream.rangeClosed(1, 11).mapToObj(i -> "SKU-" + i).toList();
        var raw = new RawOrder("not-an-email", elevenSkus, "RO");

        assertEquals(
                Validated.invalid(NonEmptyList.of(
                        new OrderError.BadEmail("not-an-email"),
                        new OrderError.TooManyItems(11))),
                parse(raw, SHIPS_EU));
    }

    /// Every malformed item reports individually, alongside the other fields'
    /// problems. One response, the complete story:
    @Test
    void every_malformed_item_is_reported_alongside_other_field_errors() {
        var raw = new RawOrder("not-an-email", List.of("SKU-1", "junk", "bogus"), "RO");

        assertEquals(
                Validated.invalid(NonEmptyList.of(
                        new OrderError.BadEmail("not-an-email"),
                        new OrderError.BadSku("junk"),
                        new OrderError.BadSku("bogus"))),
                parse(raw, SHIPS_EU));
    }

    /// Past the boundary, the guarantees hold by construction: every field is
    /// already a domain type, and no later code path can un-prove them:
    @Test
    void a_parsed_order_carries_its_invariants() {
        var order = parse(new RawOrder("ada@lovelace.dev", List.of("SKU-1", "SKU-2"), "RO"), SHIPS_EU);

        assertEquals(Validated.valid(new Order(
                new Email("ada@lovelace.dev"),
                NonEmptyList.of(new Sku("SKU-1"), new Sku("SKU-2")),
                new Country("RO"))), order);
    }

    /// ## The core: short-circuit dependent steps
    ///
    /// After parsing, the steps *depend* on each other. There's no point pricing an
    /// order that failed the stock check, and no point charging for it either. That's
    /// `Result` territory. The stock check returns a `Result`; the payment gateway is
    /// the other kind of boundary, the kind that *throws*, so `attempt` will wrap it
    /// into the same typed world. The warehouse is a port again, the same move as
    /// `ShippingPolicy`, and everything speaks in domain types: `Inventory` is asked
    /// about a `Sku`, `OutOfStock` carries one, and the gateway takes an `Email`.
    // site:include
    @FunctionalInterface
    interface Inventory {
        boolean has(Sku sku);
    }

    // site:include
    static final Inventory WAREHOUSE =            // in production: the inventory service
            sku -> Set.of("SKU-1").contains(sku.code());

    // site:include
    static Result<OrderError, Order> checkStock(Order order, Inventory inventory) {
        return order.items().stream()
                .filter(sku -> !inventory.has(sku))
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
    /// with `mapErr(NonEmptyList::of)`, because a single error is just a batch of one.
    /// `NonEmptyList` quietly unifies the whole pipeline's error type. Note the
    /// `attempt` mapper: it's a pattern match itself, because a timeout is not a
    /// decline. Classifying the throwable *here* is what keeps every later decision
    /// (like "is this worth retrying?") a matter of types instead of message-parsing:
    // site:include
    static Result<NonEmptyList<OrderError>, String> place(
            RawOrder raw, Inventory inventory, ShippingPolicy shipping, Gateway gateway) {
        return Result.binding(bind -> {
            Order order = bind.on(parse(raw, shipping).toResult());         // all boundary errors at once
            bind.on(checkStock(order, inventory).mapErr(NonEmptyList::of)); // then fail fast
            int cents = order.items().size() * 700;
            return bind.on(Result.attempt(
                    () -> gateway.charge(order.email(), cents),
                    t -> switch (t) {
                        case SocketTimeoutException ignored -> new OrderError.GatewayTimeout();
                        default -> new OrderError.PaymentFailed(t.getMessage());
                    })
                    .mapErr(NonEmptyList::of));
        });
    }

    @Test
    void a_valid_order_flows_straight_through() {
        var raw = new RawOrder("ada@lovelace.dev", List.of("SKU-1"), "RO");

        Result<NonEmptyList<OrderError>, String> placed =
                place(raw, WAREHOUSE, SHIPS_EU, (email, cents) -> "receipt-7391/" + cents);

        assertEquals(Result.ok("receipt-7391/700"), placed);
    }

    /// Boundary failures arrive as the full batch, and the pipeline proves its
    /// laziness: nothing downstream runs, and the gateway is never touched:
    @Test
    void boundary_failures_arrive_as_a_batch_and_stop_the_core() {
        var charged = new boolean[]{false};
        var raw = new RawOrder("not-an-email", List.of(), "XX");

        var placed = place(raw, WAREHOUSE, SHIPS_EU, (email, cents) -> {
            charged[0] = true;
            return "receipt";
        });

        assertEquals(Result.err(NonEmptyList.of(
                new OrderError.BadEmail("not-an-email"),
                new OrderError.NoItems(),
                new OrderError.UnshippableCountry(new Country("XX")))), placed);
        assertFalse(charged[0], "a rejected order must never reach the gateway");
    }

    /// Core failures are singular. The first dependent step to fail ends the story,
    /// as a batch of one:
    @Test
    void core_failures_short_circuit_one_at_a_time() {
        var raw = new RawOrder("ada@lovelace.dev", List.of("SKU-1", "SKU-9"), "RO");

        var placed = place(raw, WAREHOUSE, SHIPS_EU, (email, cents) -> {
            throw new IOException("must not be reached");
        });

        assertEquals(Result.err(NonEmptyList.of(
                new OrderError.OutOfStock(new Sku("SKU-9")))), placed);
    }

    /// And a throwing gateway becomes a typed error like everything else, each kind
    /// of throwable landing in its own variant:
    @Test
    void a_throwing_gateway_becomes_a_typed_error() {
        var raw = new RawOrder("ada@lovelace.dev", List.of("SKU-1"), "RO");

        var placed = place(raw, WAREHOUSE, SHIPS_EU, (email, cents) -> {
            throw new IOException("card declined");
        });

        assertEquals(Result.err(NonEmptyList.of(
                new OrderError.PaymentFailed("card declined"))), placed);
    }

    @Test
    void a_gateway_timeout_becomes_its_own_error_variant() {
        var raw = new RawOrder("ada@lovelace.dev", List.of("SKU-1"), "RO");

        var placed = place(raw, WAREHOUSE, SHIPS_EU, (email, cents) -> {
            throw new SocketTimeoutException("read timed out");
        });

        assertEquals(Result.err(NonEmptyList.of(new OrderError.GatewayTimeout())), placed);
    }

    /// ## The response: one exhaustive switch
    ///
    /// The pipeline's result is a value, so the edge of the system is a single
    /// pattern match. The compiler guarantees both outcomes are handled, and the
    /// error branch has the *complete* story to tell the caller:
    @Test
    void the_edge_of_the_system_is_a_single_pattern_match() {
        var raw = new RawOrder("not-an-email", List.of(), "XX");
        var placed = place(raw, WAREHOUSE, SHIPS_EU, (email, cents) -> "receipt");

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
    /// `Result`-returning step, handing the body the current attempt number.
    /// The retry predicate is where a stringly error type would hurt most: if
    /// telling transient from terminal meant parsing a message, the type would be
    /// missing a variant. It isn't, so the predicate is a pattern, and the whole
    /// retry policy reads off the ADT: `GatewayTimeout` retries, everything else is
    /// final. (With several transient variants, the predicate grows into an
    /// exhaustive `switch` table over the error type.)
    @Test
    void a_flaky_gateway_is_retried_but_a_declined_card_would_not_be() throws InterruptedException {
        var policy = Retry.Policy.exponential(4, Duration.ZERO);

        Result<OrderError, String> charged = Retry.run(policy,
                e -> e instanceof OrderError.GatewayTimeout,
                attempt -> attempt < 3
                        ? Result.err(new OrderError.GatewayTimeout())
                        : Result.ok("receipt-7391 after " + attempt + " tries"));

        assertEquals(Result.ok("receipt-7391 after 3 tries"), charged);
    }
}
