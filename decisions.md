# fforj architecture decisions

Append-only ADR log. Newest at the bottom.

**Immutability applies from v1.0 onward.** While the library is pre-1.0 (currently
`0.x`), the design is still settling, so ADRs MAY be edited in place — corrected,
clarified, or extended with dated addenda. Once we cut v1.0 this log freezes: never
edit a closed ADR after that point — supersede it with a new one if reality changes.

ADR numbering is sequential. Each ADR has Context / Decision / Consequences /
Alternatives / Files-to-change sections (see `.claude/agents/architect.md` for
the template).

---

## ADR-0 (2026-05-26): Initial scaffold

### Context

Library is being built fresh under the `fforj` GitHub org. CLAUDE.md captures
the locked decisions (Java 25, zero deps, no IO monad, etc). The initial type
surface — `Result`, `Validated`, `NonEmptyList`, `Retry`, `Scopes` — was sketched
in a session before the agent pipeline was set up. This ADR ratifies that
scaffold so the formal architect/dev/reviewer pipeline can proceed from a known
baseline for any subsequent work.

### Decision

Adopt the existing scaffold as the canonical starting point:

- `Result<E, T>` — sealed interface with `Ok` / `Err` records, standard
  combinators (`map`, `flatMap`, `mapErr`, `zip`, `recover`, `fold`,
  `getOrElse`, `orElseThrow`, `attempt`). Phantom type parameters on each
  record case.
- `Validated<E, T>` — sealed interface with `Valid` / `Invalid` records.
  `Invalid` carries a `NonEmptyList<E>`. `zip` accumulates errors.
- `NonEmptyList<T>` — record with `head: T` and `tail: List<T>` (defensively
  copied). Standard `map`, `concat`, `append`, `toList`, `stream`.
- `Retry.Policy` — record with `maxAttempts`, `initialDelay`, `backoffFactor`.
  `Retry.run` is a virtual-thread-friendly loop returning `Result`.
- `Scopes.parallel` and `Scopes.race` — wrappers around
  `StructuredTaskScope` (JEP 505) returning `Validated` / `Result`.

Build uses Gradle 9.0 with Kotlin DSL, Java 25 toolchain, JUnit Jupiter 5.11
for testing. `--enable-preview` set in compile and test JVM args until the
remaining preview features finalize.

### Consequences

- Surface area is BOUNDED. Adding a sixth public type requires a future ADR
  that proves the gap is a real missing primitive (per CLAUDE.md scope
  discipline).
- The library compiles and runs against Java 25 only; users on earlier LTS
  versions must wait or upgrade.
- Zero runtime deps mean we can never add `commons-lang3`, `Guava`, or
  similar. Production code is `java.base` + nothing.

### Alternatives considered

- **Ship `Try<T>` as its own type**: rejected. `Result<Throwable, T>` plus
  `Result.attempt` covers every use case `Try` does, in fewer types.
- **Ship a `Lazy<T>` / memoization helper**: rejected. The four-line
  `Suppliers.memoize` pattern from Guava is not worth its own public type;
  users can write it inline.
- **Include an `IO<E, A>` monad**: rejected explicitly. Virtual threads and
  `StructuredTaskScope` solve the problem `IO` was invented to solve in
  TS/Haskell, where there are no native green threads.
- **Use abstract classes instead of sealed interfaces for sum types**:
  rejected. Sealed interfaces give exhaustive pattern matching and record
  cases give value semantics + `equals`/`hashCode` for free.

### Files to change

(All already in place from the scaffolding session.)

- `src/main/java/dev/fforj/Result.java`
- `src/main/java/dev/fforj/NonEmptyList.java`
- `src/main/java/dev/fforj/Validated.java`
- `src/main/java/dev/fforj/Retry.java`
- `src/main/java/dev/fforj/Scopes.java`
- `src/test/java/dev/fforj/*Test.java`
- `CLAUDE.md`
- `build.gradle.kts`, `settings.gradle.kts`, `gradle/wrapper/`
- `README.md`, `.gitignore`

---

## ADR-1 (2026-06-05): `Result.binding` — do-notation for sequencing `Result`

> Process note: this ADR is recorded **after** the code was merged, not before.
> The implementation was done interactively at the requester's direction; this
> entry ratifies it and — more importantly — puts the deliberate exception to the
> "No magic" rule on the record so a future reader (or the architect agent) does
> not mistake it for a violation. Future changes of this kind should follow the
> normal architect → dev → reviewer order.

### Context

Composing several dependent `Result`-returning calls forces a tower of nested
`flatMap` closures, and the nesting gets genuinely bad when a later step needs
*multiple* earlier values (each value has to stay in scope, so the closures
stack). The requester pointed at monadyssey's TypeScript `IO.Do` do-notation
(`const x = await bind(effect)`) and asked for the same shape against `Result`:
call functions that return `Result`, get the unwrapped success value directly,
and short-circuit on the first failure — without pattern-matching or `flatMap`
at each step.

Two prior helpers already cover adjacent needs and are **not** what was asked
for here:
- `Result.attempt(Callable, onThrow)` wraps code that *throws* (boundary calls).
- `flatMap` / `zip` chain or combine `Result` *values* but reintroduce the
  nesting the requester wants gone.

In Java there is no for-comprehension and no way to extract `T` from a
`Result<E, T>` in straight-line code without either pattern-matching it (which
the requester explicitly wants to avoid) or throwing. So the only mechanism that
delivers the requested syntax is an exception-based `bind`. That bends the locked
**"No magic"** decision (no control-flow surprises; copy-paste determinism), so
it needs to be on the record.

### Decision

Add a static factory and a small nested handle to `Result` (no new public
top-level type — the five-type budget is unchanged):

```java
/** Unwrapping handle passed to {@link Result#binding(Function)}. */
@FunctionalInterface
interface Binder<E> {
    /** Return the value if Ok, else short-circuit the enclosing binding block. */
    <T> T on(Result<E, T> result);
}

/** Do-notation for Result: straight-line sequencing, short-circuit on first Err. */
static <E, T> Result<E, T> binding(Function<? super Binder<E>, ? extends T> block);
```

Call site:

```java
Result<Failure, Integer> total = Result.binding(bind -> {
    int a = bind.on(positive("3"));   // Ok  -> 3
    int b = bind.on(positive("4"));   // Ok  -> 4
    int c = bind.on(positive("-1"));  // Err -> aborts the block here
    return a + b + c;                  // never reached
});
// total == Result.err(new Failure.NotPositive(-1))
```

Implementation: `bind.on` aborts by throwing a **method-local** `RuntimeException`
subclass (`Halt`) constructed with `super(null, null, false, false)` — no message,
no cause, and `writableStackTrace = false` so `fillInStackTrace()` is skipped and
the throw stays cheap. Because `Halt` is a *local* class it closes over the
method's type variable `E`, so the error rides the exception as a typed field
with **no unchecked cast**. `binding` catches `Halt` at the boundary and returns
`Result.err(halt.error)`.

Test plan (covered): success composition; short-circuit with a step-execution log
proving later steps don't run; an exact-error-value round-trip (structured ADT
case in == out); nested `binding` blocks each catching their own abort.

### Consequences

- **Enables** straight-line composition of `Result`-returning steps; this is the
  pure-`Result` complement to `attempt` (throwing steps). The two compose: wrap a
  throwing call in `attempt`, then `bind.on` its result.
- **Documented carve-out from "No magic":** `bind.on` short-circuits via a private
  control-flow exception. Two consequences a caller must know, and which the
  Javadoc states:
  1. A broad `try { … } catch (RuntimeException e)` wrapped around a `bind.on`
     call *inside* the block will swallow the abort and break short-circuiting.
     Don't wrap bound calls in catch-all handlers.
  2. Steps that genuinely *throw* (rather than returning `Err`) are not captured
     by `binding` — the throwable propagates out. Use `attempt` for those.
- Nested `binding` calls are safe: each invocation's `Halt` is a distinct
  class/instance caught by its own boundary.
- Cost: one cheap allocation per short-circuit (no stack trace). Fine for the
  hot-path and deterministic-concurrency constraints in CLAUDE.md.
- **Forward compat:** if a future Java gains for-comprehensions or value-carrying
  binding patterns that extract from sealed types without throwing, a later ADR
  can supersede this with a no-magic implementation while keeping the signature.

### Alternatives considered

- **Do nothing; keep `flatMap` / `zip`.** Rejected: it is exactly the nesting the
  requester is trying to eliminate, and there is no flat way in Java to thread
  multiple earlier values into a later step without it.
- **A fluent for-comprehension builder** (`.bind(...).bind(...).yield(...)`).
  Rejected: still forces a lambda per step and does not let a step see more than
  the immediately-threaded value without re-nesting — no better than `flatMap`.
- **A generic `ShortCircuit extends RuntimeException` holding `Object error`,
  cast to `E` on catch.** Rejected in favour of the method-local class, which
  carries `E` with no unchecked cast and cannot leak across unrelated `binding`
  calls.
- **Mirror monadyssey literally with an `IO`/effect type.** Rejected: forbidden
  locked decision. `binding` sequences plain `Result` values; it is do-notation,
  not an IO monad.

### Files to change

- `src/main/java/dev/fforj/Result.java` — added `Binder<E>` and `binding(...)`.
- `src/test/java/dev/fforj/ResultTest.java` — added the four binding tests.
- `CLAUDE.md` — **not edited.** The "No magic" row stands; this ADR is the
  single, scoped exception. Add a pointer to ADR-1 from that row only if the
  carve-out is ever broadened.

### Addendum (2026-06-05): `Optional` ↔ `Result` bridges

Extends this decision (edited in place per the pre-1.0 policy above) with the
bridge from `Optional` into a `Result` pipeline. The reverse direction already
exists as `Result.okValue()` / `errValue()`, so only the lift was missing.

- **`Result.fromOptional(Optional<? extends T>, Supplier<? extends E>)`** — present
  → `Ok`, empty → `Err(ifEmpty.get())`. The error is caller-supplied because an
  empty `Optional` carries no reason of its own (this is the same absence-vs-failure
  line the library draws elsewhere — the caller names the failure). The supplier is
  lazy. No magic; pure factory.
- **`Binder.on(Optional<? extends T>, Supplier<? extends E>)`** — a `default`
  overload on the `binding` handle (keeps `Binder` a `@FunctionalInterface`) so an
  empty `Optional` short-circuits a `binding` block just like an `Err`. Implemented
  as `on(fromOptional(maybe, ifEmpty))`, so it inherits — and stays within — the
  short-circuit behavior already documented above; it introduces no new mechanism.

These are PR-level additions that would not independently warrant an ADR (no new
type, no locked-decision tension); they live here only because they round out the
`binding` composition story this ADR introduced.

Files: `src/main/java/dev/fforj/Result.java` (added `fromOptional` + the `Binder`
overload); `src/test/java/dev/fforj/ResultTest.java` (four bridge tests).
