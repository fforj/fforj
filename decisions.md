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

Build uses Gradle 9.5.1 with Kotlin DSL, Java 25 toolchain, JUnit Jupiter 5.12
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

### Addendum (2026-08-06): sharpen the "no IO monad" rationale

External feedback, and it is correct: virtual threads are not *equivalent* to an
`IO` type. `IO` also provides referential transparency, effects as first-class
values (compose, retry, or race a description of an effect before running it),
and deferred execution — no thread model provides those. The original wording
here ("solve the problem `IO` was invented to solve") overclaimed.

The decision stands on the honest version of the argument: (1) the dominant
practical reason JVM code adopted `IO` — cheap, cancellable, composable
concurrency without blocking platform threads — is now provided by the platform
itself (virtual threads + structured concurrency); (2) the remaining benefit,
pure effect tracking, has a cost profile specific to Java — it colors every API
boundary it touches, cannot be expressed well without HKTs (a locked NO), and
splits a codebase into two dialects. fforj's lane is errors-as-values, not
effects-as-values. Wording corrected in README, CLAUDE.md, and the site's
"deliberately not here" section; this addendum is the record.

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
- Nested `binding` calls are safe: each abort carries the identity of the
  invocation that threw it, and a boundary rethrows aborts it does not own
  (see the 2026-07-23 addendum — the original per-invocation-class claim was
  wrong).
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
  carries `E` with no unchecked cast. (The original rationale also claimed it
  "cannot leak across unrelated `binding` calls" — that part was wrong; see the
  2026-07-23 addendum.)
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

### Addendum (2026-07-23): owner-tokened `Halt` — nested-block correctness fix

The original claim "each invocation's `Halt` is a distinct class/instance caught
by its own boundary" was wrong: a method-local class is **one class shared by
every invocation** of its method, so a nested `binding` (or ADR-2 `accumulate`)
block's catch also caught an *outer* block's abort. A full-codebase review
(2026-07-23) reproduced both failure modes:

- `Result.binding`: using the outer binder inside an inner block returned the
  outer error as the inner block's `Err` with the wrong error type — heap
  pollution surfacing later as a `ClassCastException`.
- `Validated.accumulate`: unwrapping an outer `Bound` inside an inner block was
  caught by the inner boundary, whose own accumulator was empty, crashing with
  `NoSuchElementException`.

Fix: a package-private abstract `Halt` base class (`Halt.java`, stack-trace-free)
now carries an `owner` identity token — the `Binder`/`Accumulator` instance of
the invocation that threw. Each boundary catches its method-local subclass and
**rethrows aborts whose owner is not its own handle**, so an abort always unwinds
to the block that created it. The typed error still rides a method-local subclass
field, so there is still no unchecked cast. `Halt` is package-private, not a
public type — the five-type budget is unchanged.

Bonus: `Result.attempt` now rethrows `Halt` instead of capturing it, so an abort
crossing an `attempt` body is no longer silently converted into a meaningless
mapped `Err` (documented in `attempt`'s Javadoc).

Regression tests: nested `binding` using the outer binder from the inner block;
nested `accumulate` unwrapping an outer `Bound` inside an inner block; a binding
abort passing through `attempt` untouched.

---

## ADR-2 (2026-06-05): `Validated.accumulate` — error-accumulation DSL

### Context

Combining N independent validations with `Validated.zip` works at arity 2 but degrades
fast above it: building a 3-field record forces currying gymnastics (see the
`IntFunction` cast in `ValidatedTest.real_world_form_validation_accumulates_all_problems_at_once`).
Vavr solves this with arity-fixed `Validation.combine(...).ap(f)` overloads; Arrow (Kotlin)
solves it with the `accumulate { accumulating { ... }.value }` Raise DSL. The requester
pointed at Arrow's shape (via tibtof/fun-vs-framework's
`CategorizedTransactionController`) and asked for the same against `Validated`.

ADR-1 already established the do-notation mechanism for the short-circuiting case
(`Result.binding`). This decision extends the same mechanism — not a new carve-out — to
the accumulating case, where the crucial extra requirement is that a failure must NOT
stop later validations from running and contributing their errors.

### Decision

Add to `Validated` (no new public top-level type; the five-type budget is unchanged):

```java
@FunctionalInterface
interface Bound<T> {                       // a bound-but-not-yet-unwrapped value
    T value();                             // unwrap; aborts the block if its binding failed
}

interface Accumulator<E> {                 // the handle passed to the block
    <T> Bound<T> on(Validated<E, T> validated);                                  // primary
    default <T> Bound<T> on(Result<E, T> result) { ... }                         // bridge
    default <T> Bound<T> on(Optional<? extends T> maybe, Supplier<? extends E> ifEmpty) { ... }
}

static <E, T> Validated<E, T> accumulate(Function<? super Accumulator<E>, ? extends T> block);
```

The two-phase shape is the design's core: **binding** (`acc.on`) is total — a failed
validation records its errors into the accumulator and returns a poisoned `Bound`, so
every validation runs; **unwrapping** (`.value()`) is where a failure finally aborts,
via the same local, stack-trace-free control-flow exception as ADR-1's `Halt`. The
result is `Valid(blockResult)` only when zero bindings failed; otherwise `Invalid`
carrying ALL errors in binding order — including when the block completes without ever
unwrapping a failed handle (accumulation must not depend on unwraps).

Idiom (documented in the Javadoc): **bind first, unwrap last.** Interleaving degrades
to short-circuiting; dependent steps belong in `Result.binding`.

Test plan (covered, 8 cases): all-valid composition; all errors in binding order;
later validations run after an earlier failure; unwrap aborts the rest of the block;
`Result`/`Optional` bridge overloads accumulate alongside `Validated`; a multi-error
`Invalid` contributes every error; invalid-without-unwrap still yields `Invalid`; the
real-world form test rewritten without arity gymnastics.

### Consequences

- N-ary validation reads as straight-line record construction; `zip` remains for the
  two-value case and as the algebraic primitive underneath.
- Same ADR-1 caveats apply and are documented: catch-all `catch (RuntimeException)`
  around an unwrap swallows the abort; `Bound` handles must not escape their block
  (`value()` after `accumulate` returns throws an unclassifiable control exception).
- The "bind first, unwrap last" idiom is a convention the compiler cannot enforce —
  the price of straight-line syntax in Java. The Javadoc states it loudly.
- Forward compat: same as ADR-1 — a future Java with value-carrying extraction could
  reimplement this without the exception while keeping the signature.

### Alternatives considered

- **Arity-fixed `zipN` overloads (`zip3`..`zip8`, Vavr-style `combine`)**: rejected.
  Eight near-identical overloads is exactly the Vavr surface-area trap CLAUDE.md exists
  to prevent, and it still caps composition at the largest arity shipped.
- **`Validated.sequence(List<Validated<E,T>>)`**: rejected as the *general* answer — it
  only handles homogeneous lists, not "three differently-typed fields into one record".
  (It may still earn its place later as a small utility; separate decision.)
- **Reuse `Result.binding` and convert at the end**: rejected. `binding` short-circuits
  by design; accumulation requires failures to keep the block running, which needs the
  deferred-unwrap handle.
- **Eager abort on first unwrap with errors-so-far vs. running the whole block**: the
  chosen design does both — binding is total, unwrap aborts — which is exactly Arrow's
  semantics and the reason the bind/unwrap split exists.

### Files to change

- `src/main/java/dev/fforj/Validated.java` — added `Bound`, `Accumulator`, `accumulate`.
- `src/test/java/dev/fforj/ValidatedTest.java` — eight accumulate tests.
- `README.md` — accumulation bullet + example in "Composing `Result`s".

---

## ADR-3 (2026-07-23): Retarget to Java 21 LTS; shelve `Scopes` until JEP 505 finalizes

> Supersedes the "Java 25 (LTS)" and "Preview features allowed" locked decisions
> in CLAUDE.md (both rows updated to point here).

### Context

An audit of what each type actually requires showed that `Result`, `Validated`,
`NonEmptyList`, and `Retry` need nothing newer than Java 21: records, sealed
interfaces, exhaustive switch patterns, record deconstruction,
`SequencedCollection`, virtual threads, and `Thread.sleep(Duration)` are all
final in 21. Only `Scopes` forced Java 25 — and not merely 25:
`StructuredTaskScope`'s redesigned `open()`/`Joiner` API is JEP 505, the API's
**fifth preview**. The costs of shipping it:

- A preview-flagged class loads only on exactly the JDK it was compiled for,
  with `--enable-preview`. `Scopes.class` built on 25 will not load on 26.
  Every JDK release until finalization forces a recompile.
- The API has been redesigned across its five previews and may change again.
- The library's core trust pitch — "copy 30 lines into your project and it
  keeps working forever" — is directly contradicted by a class that stops
  loading on the next JDK.
- Requiring Java 25 shrinks the addressable audience badly; Java 21 is where
  most modern-Java shops are.

`Scopes` is not decorative, though: it is the code-shaped proof of the locked
"no IO monad" decision — the bridge from structured-scope outcomes to
`Result`/`Validated`. Deleting it outright would weaken that story.

### Decision

1. **Retarget `main` to Java 21.** The build keeps a newer toolchain for
   day-to-day development but compiles everything with `--release 21`, so all
   published classes are plain Java 21 class files checked against the 21 API.
2. **No preview features on `main`, ever.** Preview-dependent code lives on
   `poc/*` branches until the underlying API finalizes.
3. **Shelve `Scopes` on branch `poc/scopes-jep505`.** The branch preserves the
   full working implementation and its deterministic test suite, frozen against
   the Java 25 preview API. The branch name states the unblocking event.
4. **Standing reservation for the fifth type.** When JEP 505 finalizes, `Scopes`
   returns to `main` via a short ADR that ratifies the port to the final API —
   rebased, recompiled without flags, tests green. The five-type budget in
   CLAUDE.md becomes four-plus-one-reserved until then.

### Consequences

- The published jar runs on every JDK from 21 up, with no flags and no
  load-time surprises — the trust dimension holds for the entire artifact.
- The audience for the library grows to every Java 21+ shop.
- The "no IO monad" rationale temporarily rests on documentation rather than
  shipped code. Accepted: the argument (virtual threads are 21-final) is
  unchanged, and the proof is one branch away.
- The `poc/scopes-jep505` branch does not build on `main`'s toolchain settings
  as they evolve; it is a frozen snapshot, not a maintained parallel line. It
  gets rebased once, when JEP 505 finalizes.
- README, CLAUDE.md (three locked-decision rows, scope section, testing
  section), and the GitHub description all state Java 21+ and four types.

### Alternatives considered

- **Status quo (five types, Java 25 + preview)**: rejected. The recompile
  treadmill and the "won't load on 26" footgun contradict the library's own
  trust principle, for a type that is not the center of the project.
- **Split artifacts (`fforj` on 21, `fforj-scopes` on 25/preview)**: rejected
  for now. Two artifacts is real, permanent complexity for a deliberately tiny
  library; the branch achieves the same preservation at zero shipped cost.
  Revisit only if finalization drags on for multiple releases AND users ask.
- **Keep `Scopes` in-tree but excluded from compilation**: rejected. Uncompiled
  code rots invisibly; a branch is explicit about being frozen and against what.

### Files to change

- `src/main/java/dev/fforj/Scopes.java`, `src/test/java/dev/fforj/ScopesTest.java`
  — removed from `main`; preserved on `poc/scopes-jep505`.
- `build.gradle.kts` — `--release 21`, preview args removed.
- `src/main/java/dev/fforj/Retry.java`, `Result.java` — Javadoc references to
  `Scopes` generalized to "structured-concurrency scope".
- `CLAUDE.md` — language-version, preview-features, checked-exceptions rows;
  bounded-scope and testing sections.
- `README.md` — intro, type table (shelved note), requirements.

---

## ADR-4 (2026-07-23): Build & release pipeline — GitHub Actions → Maven Central

### Context

The library needs a repeatable path from a git tag to a signed artifact on Maven
Central. Since mid-2025, OSSRH is sunset and publishing goes through the Sonatype
**Central Portal**, which has no first-party Gradle plugin. The dependency rules
say build deps are "Gradle plugins only" and any new dep needs an ADR — this is
that ADR.

### Decision

- **Plugin: `com.vanniktech.maven.publish` 0.37.0** (build-time only; the
  published artifact still has zero runtime deps). It wires the Central Portal
  upload/validate/release flow, sources+javadoc jars, POM, and GPG signing
  behind one `mavenPublishing` block, and is the de-facto standard for
  Portal-era Gradle publishing.
- **CI workflow** (`ci.yml`): `./gradlew build` on a JDK **21 + 25 matrix**
  (toolchain overridable via `-PtoolchainJdk`), so the Java 21 floor from ADR-3
  is continuously proven on a real 21 JVM, not just via `--release 21`.
- **Release workflow** (`release.yml`): triggered by `v*` tags. The version is
  derived from the tag (`v1.2.3` → `-Pversion=1.2.3`; `-SNAPSHOT` tags are
  refused); `gradle.properties` keeps a `-SNAPSHOT` placeholder. Publishes with
  `automaticRelease = true` (no manual portal click) and creates a GitHub
  release with generated notes.
- **Secrets**: `MAVEN_CENTRAL_USERNAME`/`MAVEN_CENTRAL_PASSWORD` (portal user
  token) and `SIGNING_KEY`/`SIGNING_KEY_PASSWORD` (in-memory ASCII-armored GPG
  key). One-time account/namespace/key setup is documented in `RELEASING.md`.

### Consequences

- A release is one command (`git tag vX.Y.Z && git push origin vX.Y.Z`) from a
  green main; humans keep the decision, automation keeps the mechanics.
- `withSourcesJar()`/`withJavadocJar()` moved from the `java` extension to the
  plugin's publication config (it configures both; declaring them twice
  conflicts).
- The plugin is a build-time trust dependency. Accepted: it never touches the
  produced class files (verifiable — the jar contains only `dev.fforj` classes),
  and it is replaceable by hand-rolled `maven-publish` + Portal REST calls if it
  is ever abandoned.

### Alternatives considered

- **Hand-rolled `maven-publish` + `signing` + Portal API via curl**: zero new
  plugins, but reimplements upload/validation/polling logic in bash — more
  surface to get subtly wrong than the plugin adds in trust.
- **JReleaser**: does far more (changelogs, announcements, multi-registry) than
  a one-artifact library needs — surface-area trap.
- **`com.gradleup.nmcp`**: thinner Portal wrapper, but leaves POM/signing/jar
  wiring manual; the vanniktech plugin covers the whole path with less config.

### Files to change

- `build.gradle.kts` — plugin, `mavenPublishing` block, toolchain property.
- `gradle.properties` — group/version (CLI-overridable).
- `gradle/libs.versions.toml` — `[plugins]` section.
- `.github/workflows/ci.yml`, `.github/workflows/release.yml` — new.
- `RELEASING.md` — new. `README.md` — badges + dependency coordinates.

---

## ADR-5 (2026-08-06): Documentation site — doc-tests as the single source of truth

### Context

The library needs a documentation site (`fforj.dev`): published Javadoc plus
guides with examples. The standing failure mode of hand-written docs is rot —
examples that no longer compile or no longer behave as described. The requester
asked for docs kept in sync with the repo mechanically: examples that are tests,
extracted for the website, with the test source itself serving as the example.

No mainstream Java tool does this direction. Javadoc's `{@snippet}` (JEP 413)
embeds compiled regions into API docs but produces no site and no tests; Kotlin's
Knit and Scala's mdoc are the JVM-adjacent equivalents in other languages. The
tests→website direction for Java is an open niche.

### Decision

- **Doc-tests**: guides live in `src/test/java/dev/fforj/docs/*DocTest.java`.
  Prose is written in `///` markdown comment blocks (plain line comments — they
  compile on Java 21 and are invisible to javadoc); every example is the body of
  a real `@Test` method run by the normal suite; members marked with a preceding
  `// site:include` line render as code too. Front matter (`title`, `slug`,
  `order`, `summary`) rides the first `///` block; a `[landing]` marker picks the
  homepage example.
- **Generator**: `docs/SiteGen.java` — a single-file, zero-dependency Java
  program run via the plain source launcher (`java docs/SiteGen.java`), mirroring
  the library's copy-paste ethos. It parses doc-tests, interleaves prose and
  extracted code in source order, renders a markdown subset and a small Java
  highlighter, and folds `javadoc` output in under `/api/`. No Node, no static
  site framework, no new build plugin.
- **Gradle**: a `site` task (`test` + `javadoc` + generator). The install snippet
  shows the latest released version, derived from the newest `v*` git tag — not
  the working `-SNAPSHOT`.
- **Deploy**: `.github/workflows/site.yml` deploys `build/site` to GitHub Pages
  on every push to `main`, custom domain `fforj.dev`. Because `site` depends on
  `test`, a broken example can never reach the website.
- **Design**: type-specimen identity around the ﬀ ligature; rubrication (red-ink
  emphasis from early printing) as the accent system; Newsreader for prose,
  JetBrains Mono with ligatures for code.

### Consequences

- Examples cannot rot: editing a doc-test re-renders the site; breaking one
  breaks CI. The site says so ("every example on this site is a test").
- Prose quality is bounded by the markdown subset the generator supports
  (headings, paragraphs, lists, links, inline code, bold/italic). Acceptable for
  guide-length writing; extend the renderer when a real need appears.
- The doc-test format (front matter, `site:include`, `[landing]`) is deliberately
  tool-shaped: if it proves out, the generator can be extracted into a reusable
  fforj-org tool as a separate project — a possible answer to the empty
  tests→docs niche in Java. Extraction is a future decision, not this one;
  generalizing before a second consumer exists is the same trap the library's
  bounded-scope rule guards against.
- The `*DocTest` classes are additional tests, an exception to the
  "one `*Test.java` per source file" convention (noted in CLAUDE.md).

### Alternatives considered

- **Hand-written markdown site (MkDocs/Antora/Jekyll)**: fastest to start, but
  reintroduces doc rot — the exact problem the requester asked to eliminate —
  and drags in a non-JVM toolchain.
- **Javadoc `{@snippet}` with external snippet files**: keeps API-doc examples
  compiling, but produces no guides/website, and snippet files aren't tests.
  May still be adopted *inside* the library's Javadoc later; complementary.
- **Adopt Knit/mdoc-style markdown→tests**: wrong direction — the markdown
  stays primary and the tests are generated, so the site and the suite can
  still drift apart at the seam; and neither tool targets Java sources.

### Files to change

- `src/test/java/dev/fforj/docs/{Result,Validated,NonEmptyList,Retry}DocTest.java` — new.
- `docs/SiteGen.java` — new. `build.gradle.kts` — `site` task.
- `.github/workflows/site.yml` — new. `CLAUDE.md`, `README.md` — pointers.

### Addendum (2026-08-06): versioned site + auto-maintained install version

Extends ADR-5 with versioning (requested so example diffs between releases are
visible):

- **Layout**: site root = latest release's docs; `/v/X.Y.Z/` = frozen snapshot
  per release, generated from that tag's own doc-tests (so version differences in
  examples are real, tested behavioral differences); `/next/` = main, unreleased.
- **Selector**: every page's header has a version dropdown populated at page-load
  from the single live `/versions.json` — frozen snapshots therefore list
  versions released *after* them. Navigation lands on the same page in the chosen
  version, falling back to that version's landing page if it doesn't exist there.
- **Hosting**: the `gh-pages` branch of `fforj/fforj` (Pages "legacy" branch
  serving, CNAME `fforj.dev`). Considered a dedicated site repo; rejected for
  now: the branch is pure build output, and every deploy **amends the single
  existing commit and force-pushes**, so the library repo's clone size never
  grows — which removes the main argument for a separate repo while keeping
  same-repo `GITHUB_TOKEN` deploys (no cross-repo PAT). Revisit if the org gains
  more sites (blog, extracted doctest tool, org-wide landing).
- **Writers**: `site.yml` (push to main) rebuilds only `/next/`; `release.yml`
  (tag) replaces the root, adds `/v/X.Y.Z/`, regenerates `versions.json`, and
  rewrites the README install snippets to the released version (committed back
  to main by the workflow). Both share a `site` concurrency group so they queue
  rather than race on the branch.
- The `/v/` archive starts at 0.1.0 (bootstrapped from current main, whose
  library sources are identical to the v0.1.0 tag; the tag itself predates the
  doc-test infrastructure).

---

## ADR-0 addendum (2026-08-06): `Retry.run` attempt-aware overload

PR-level addition in the spirit of the ADR-1 `fromOptional` addendum (no new
type, no locked-decision tension). The `Supplier` body couldn't see which
attempt it was on, so every example and caller that cared smuggled a counter in
via outside mutable state (`new int[]{0}` — the exact noise the library exists
to delete). The requester asked for the counter to live in the retry itself.

Where it lives matters: **not in `Policy`** — a `Policy` is an immutable value
meant to be shared across concurrent calls, so a counter there would be shared
mutable state. The attempt count belongs to a single run, so it flows as the
body's argument: a new primary overload
`run(Policy, Predicate, IntFunction<? extends Result<E,T>>)` hands the body the
1-based attempt number; the existing `Supplier` overload delegates to it for
bodies that don't care. Overload resolution is unambiguous (arity 0 vs 1
lambdas). Doc examples rewritten counter-free; two new unit tests pin the
attempt sequence.

---

## ADR-6 (2026-08-06): DSL completeness — `traverse`, `onEach`, `ensure`, `Validated.mapErr`, `tap`

### Context

Writing the documentation site (ADR-5) put the DSLs through sustained real use,
which surfaced four gaps. (1) Validating *every element of a collection* and
accumulating all their errors — the traverse shape — had no clean expression;
the capstone guide had to dodge it. (2) Rules with no value to bind (limits,
guards, permissions) forced wrapping a dummy value in `Validated`/`Result`.
(3) `Validated` had no error-side map, an asymmetry with `Result.mapErr` felt
exactly where batches get translated for API boundaries. (4) Observing a chain
for logging/metrics meant breaking it. All four are method-level additions; no
new types; the five-type budget (four-plus-one-reserved) is unchanged.

### Decision

- **`Validated.traverse(List, f)`** — the accumulative traverse: `Valid` of the
  parsed list only if every element passed, else `Invalid` with every failing
  element's errors in input order. Empty list is trivially `Valid`. An overload
  **`traverse(NonEmptyList, f)`** carries non-emptiness through.
- **`Accumulator.onEach(list, f)`** — DSL form, literally `on(traverse(...))`.
- **`Accumulator.ensure(condition, errorSupplier)`** — false records the error,
  the block keeps running. **`Binder.ensure`** — false short-circuits, exactly
  like binding an `Err`. (The most-used primitive in Arrow's Raise after bind.)
- **`Validated.mapErr(f)`** — maps every error in the batch, preserves `Valid`.
- **`Result.tap`/`tapErr`** — observe one case, return `this` unchanged.

### Consequences

- The capstone guide now validates every SKU individually via a door composed
  from smaller doors (`fromOptional` + `fold` + `traverse`), and the guides
  gained onEach/ensure/mapErr sections — all examples tests, as always.
- `tap`/`tapErr` is a deliberate, bounded concession to observability; the next
  "just one more combinator" still needs to clear the missing-primitive bar.

### Alternatives considered

- **`sequence(List<Validated<E,T>>)`**: subsumed — it's `traverse(list, identity)`;
  add only if identity-traverse turns out to dominate usage.
- **Arity-fixed `zipN`**: still rejected (ADR-2); `accumulate` + `onEach` cover it.
- **`Validated.tap`**: skipped until asked for; `fold` covers observation there.

### Files to change

- `Validated.java`, `Result.java` — the methods above.
- `ValidatedTest.java`, `ResultTest.java` — 14 new tests.
- Doc-tests: `ValidatedDocTest` (3 new sections), `ResultDocTest` (ensure),
  `CombinedDocTest` (per-item SKU validation via composed door).

---

## ADR-7 (2026-08-06): `Retry.Policy` hardening — `maxDelay`, `jitter`, `delayBefore`

### Context

Two production footguns: uncapped exponential backoff (attempt 10 of a 1-second
doubling policy sleeps 8.5 minutes) and synchronized retries (a fleet of clients
retrying in lockstep re-hammers a recovering service in waves). Both fixes are
standard practice (AWS architecture guidance, resilience4j). The tension: the
testing rule requires concurrency determinism, and jitter is randomness.

### Decision

`Policy` gains two components — `maxDelay` (cap applied after backoff) and
`jitter` (fraction in `[0,1)`; each sleep scaled by a uniform factor in
`[1-jitter, 1+jitter]` via `ThreadLocalRandom`) — with a three-arg convenience
constructor defaulting to uncapped/zero, and `withMaxDelay`/`withJitter` wither
methods. Determinism is preserved by construction: jitter defaults to `0`, and
the new **`Policy.delayBefore(attempt)`** exposes the planned schedule as a pure
function (jitter applies only at sleep time in `Retry.run`), so retry timing is
assertable in tests without sleeping. This is the only randomness in the library
and it is opt-in, applied at the last possible moment.

### Alternatives considered

- **Full-jitter (random in `[0, delay]`)**: rejected as default semantics —
  equal-jitter around the planned delay keeps `delayBefore` an honest center of
  the actual sleep; revisit if users ask.
- **Injectable sleeper/clock for testability**: rejected — `delayBefore` gives
  deterministic assertions without growing the API a seam that exists only for
  tests.

### Files to change

- `Retry.java` — `Policy` components, withers, `delayBefore`, jittered sleep.
- `RetryTest.java` — schedule/cap/jitter-validation tests.
- `RetryDocTest.java` — "Cap the backoff, spread the herd" section.

---

## ADR-8 (2026-08-11): APM (Agent Package Manager) as contributor tooling

### Context

The agent pipeline (architect → dev → reviewer) and the project's context have
to work identically across every AI assistant a contributor might use — GitHub
Copilot, Claude Code, Gemini CLI, and OpenCode. Before APM, those definitions
were hand-maintained as parallel per-assistant copies that drifted. The reviewer
of the PR that introduced the assistant harnesses asked that APM be recorded as
official contributor tooling, per the "new tooling deps need an ADR" convention.

### Decision

Adopt **APM — Agent Package Manager** as contributor tooling. It is an
open-source (MIT), community-driven dependency manager for AI-agent
configuration — "`package.json` for AI agents" — developed by **Microsoft**
(`github.com/microsoft/apm`). It is actively developed and fast-moving (v0.12 in
May 2026; v0.28 pinned in `mise.toml` today), so treat it as a tool here to
stay and evolve — but design so nothing depends on its survival.

Usage in this repo:

- `apm.yml` declares the harness targets (`copilot`, `gemini`, `opencode`) and
  the single local package `java-ai-primitives` (`companion/ai/bundle`).
- `apm install` pulls the package into `apm_modules/`; `apm compile` generates
  each assistant's native context from that one source of truth — `AGENTS.md`,
  `CLAUDE.md`, `GEMINI.md`, `.claude/agents/*.md`, `.github/agents/*.agent.md`,
  `.opencode/agents/*.md`.
- `apm.lock.yaml` pins package versions and content hashes so regeneration is
  deterministic.

### Consequences

- One definition of the pipeline, compiled into every assistant's format — no
  more drifting parallel copies.
- **Failure mode if APM disappears:** none structural. Every generated file is
  committed plain markdown and keeps working forever; the source primitives
  under `companion/ai/bundle/.apm/` are ordinary markdown with YAML frontmatter
  that a contributor can edit or copy by hand. We would lose only the
  regeneration automation — hand-sync the handful of copies or pin the last
  good binary. APM is contributor-DX only; the library build stays Gradle-only
  and untouched.
- The generated context files are build output in git: edit the primitives,
  never the generated copies.

### Alternatives considered

- **Hand-maintained per-assistant files (status quo ante)**: rejected — exactly
  the drift APM removes, and each new assistant multiplies the copies.
- **Settle on one assistant only**: rejected — the pipeline is deliberately
  assistant-agnostic so contributors can pick their tool.

### Files to change

- `apm.yml` — manifest (targets, package dependency).
- `apm.lock.yaml` — dependency lockfile (generated).
- `companion/ai/bundle/` — the `java-ai-primitives` package source.
- Generated outputs: `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.claude/`,
  `.github/agents/`, `.opencode/agents/`.
- `CONTRIBUTING.md` — setup instructions (`apm install --update && apm compile`).

---

## ADR-9 (2026-09-18): Make DSL misuse fail loudly — swallowed short-circuits and escaped handles

### Context

The `Result.binding` / `Validated.accumulate` DSLs abort a block by throwing a
package-private, stack-trace-free `Halt` subclass (`Abort`) caught at the
boundary (ADR-1, ADR-2). Three ways a caller can defeat that mechanism today,
all of which fail silently or obscurely rather than telling the developer what
they did ([issue #2](https://github.com/fforj/fforj/issues/2) and its follow-up
comment):

1. **Swallowed short-circuit.** Code that wraps a `bind.on` / `Bound.value()`
   call in `catch (RuntimeException e)` or `catch (Exception e)` intercepts the
   abort. The block runs on past the failed step and the boundary returns `Ok` /
   `Valid` of whatever the block fabricated. The first `Err` / the recorded
   errors are silently lost, and side effects after the failed step ran anyway.
   Today this is only a Javadoc caveat; nothing enforces it. The first external
   write-up of the library called this out as the one thing that "requires a bit
   of discipline."
2. **A `catch (Throwable)` swallow.** The broadest catch-all defeats even a
   supertype change; it needs a boundary-side check.
3. **Escaped handle.** A `Binder` / `Accumulator` (or a poisoned `Bound`)
   captured in a field or lambda and called *after* `binding` / `accumulate`
   returned has no boundary left to unwind to. The abort propagates out of the
   caller as a bare `Halt` subclass: null message, no stack trace, a
   package-private type name. Nothing tells the developer they used the handle
   out of scope.

There is no legitimate use for any of the three. `Halt` is package-private and
cannot be caught by name; every interception is an accident. The block ran past
a failed step, which is a structural bug the developer must see, not an outcome
to paper over by returning the swallowed error.

This ADR is scoped to #2 only. Issues #3 and #6 also touch these handles and are
separate later decisions; nothing here renumbers or pre-empts them.

### Decision

Three complementary measures across `Halt`, `Result.binding`, and
`Validated.accumulate`. No public API changes: `Halt` and the local `Abort`
classes stay package-private, the `Binder` / `Accumulator` / `Bound` signatures
are unchanged. The four-plus-one-reserved type budget is untouched.

**1. `Halt extends Error` (was `RuntimeException`).** The common accidental
catch-alls — `catch (RuntimeException e)`, `catch (Exception e)` — no longer
intercept a DSL abort at all, so a swallow written with either of them silently
becomes correct short-circuiting instead of a lost error. `Error` keeps the same
4-arg `(message, cause, enableSuppression, writableStackTrace)` protected
constructor, so `Halt`'s stack-trace-free construction is unchanged. The two
existing catch sites stay correct: each boundary catches its local `Abort` by
name (a `catch` clause may name any `Throwable` subtype), and `Result.attempt`
catches `Halt` explicitly *before* its `catch (Throwable t)`, so an abort still
passes through `attempt` untouched. Only `catch (Throwable)` / `catch (Error)`
can now intercept an abort, and measure 2 covers that residue.

**2. Boundary-side swallow detection.** Each `Binder` / `Accumulator` instance
carries a mutable `boolean aborted`, set at the exact site where it throws its
`Abort`:
- `binding`: set in `Binder.on` on the `Err` branch, immediately before throwing.
- `accumulate`: set inside the poisoned `Bound.value()` closure, immediately
  before throwing (binding a failure only *records* errors and never sets it).

The boundary, on **normal** completion of the block (its own `Abort` was not
caught, i.e. it was swallowed inside the block), checks the flag:
- `binding`: `aborted` true on normal return -> throw
  `IllegalStateException("Result.binding: a bind.on short-circuit was swallowed "
  + "by a catch inside the binding block; the block ran past a failed step")`
  instead of `ok(outcome)`.
- `accumulate`: `aborted` true on normal return -> throw the analogous
  `IllegalStateException`. Crucially this is distinct from the existing,
  legitimate "block completed without unwrapping a failed binding" path
  (`aborted` false, `errors` non-empty), which MUST still return `Invalid`.

Per-owner flags compose with the existing `owner` token for nested blocks: if an
inner block swallows an *outer* handle's abort with `catch (Throwable)`, the
outer handle's `aborted` flag is set, the inner boundary sees its own flag clear
and returns normally, and the outer boundary catches the swallow when the outer
block returns. The abort is always diagnosed by the boundary that owns it.

The thrown `IllegalStateException` does not chain the payload-less `Abort` (no
message, no stack trace, nothing useful); its own stack trace points at the
offending `binding` / `accumulate` call.

**3. Escaped-handle detection.** Each `Binder` / `Accumulator` carries a
`boolean closed`, set to `true` in a `finally` on the boundary (after normal
return, short-circuit, or a measure-2 throw). Guarded call sites throw
`IllegalStateException` when `closed`:
- `Binder.on(...)` after close -> `"bind.on called outside its binding block"`.
- `Accumulator.on(...)` after close ->
  `"Accumulator.on called outside its accumulate block"`.
- A poisoned `Bound.value()` (the closure that would throw `Abort`) after close
  -> `"Bound.value() called outside its accumulate block"`. The check runs
  before the abort would be thrown, so an escaped poisoned handle now yields a
  clean `IllegalStateException` rather than a bare `Halt` leaking out.

The `closed` check is placed first in each guarded method, before any switch on
the argument.

**Non-goals (unchanged, stated for the dev):** a valid (non-poisoned) `Bound`
returned by `accumulate` still just returns its captured value if called after
the block, harmlessly; it holds no control flow, so it is not guarded (guarding
it would mean wrapping every valid `Bound` in an extra closure for no safety
gain). Concurrent misuse — handing a handle to another thread that calls it
*while* the block is still running on the original thread — is out of scope; the
`closed`/`aborted` flags are plain `boolean`s addressing the sequential
escape/swallow the issue describes, matching the existing single-threaded,
synchronous execution model of both DSLs.

Cost stays zero on the happy path: two `boolean` writes per block plus two reads
at the boundary; no allocation, no stack capture.

#### Call-site shape (binding; accumulate is analogous)

```java
static <E, T> Result<E, T> binding(Function<? super Binder<E>, ? extends T> block) {
    Objects.requireNonNull(block, "binding block must not be null");

    final class Abort extends Halt {
        final E error;
        Abort(Object owner, E error) { super(owner); this.error = error; }
    }

    var binder = new Binder<E>() {
        boolean aborted = false;   // this handle threw its Abort
        boolean closed  = false;   // binding() has returned

        @Override public <U> U on(Result<E, U> result) {
            if (closed) throw new IllegalStateException(
                    "bind.on called outside its binding block");
            return switch (result) {
                case Ok<E, U> ok  -> ok.value();
                case Err<E, U> err -> { aborted = true; throw new Abort(this, err.error()); }
            };
        }
    };

    T outcome;
    try {
        outcome = block.apply(binder);              // may abort or complete
    } catch (Abort abort) {
        if (abort.owner != binder) throw abort;     // foreign abort: unwind to its owner
        return err(abort.error);                    // legitimate short-circuit
    } finally {
        binder.closed = true;
    }
    if (binder.aborted) {                           // normal return despite an abort => swallowed
        throw new IllegalStateException(
                "Result.binding: a bind.on short-circuit was swallowed by a catch "
                + "inside the binding block; the block ran past a failed step");
    }
    return ok(outcome);
}
```

#### Test plan (the dev MUST cover)

`ResultTest`:
- A `catch (RuntimeException e)` around a `bind.on(err)` inside the block no
  longer swallows: the block short-circuits and `binding` returns the `Err`
  (proves measure 1).
- A `catch (Throwable t)` around `bind.on(err)` that lets the block return
  normally makes `binding` throw `IllegalStateException` with the swallow message
  (measure 2).
- A `Binder` captured out of the block and called after `binding` returned throws
  `IllegalStateException("bind.on called outside its binding block")` (measure 3).
- Nested: an inner block that swallows the *outer* binder's abort with
  `catch (Throwable)` and returns normally makes the *outer* `binding` throw
  `IllegalStateException` (per-owner flag).
- Regression (must stay green): all existing binding/nested tests, and
  `binding_abort_passes_through_an_attempt_wrapping_the_bound_call` (proves
  `attempt` still rethrows `Halt` now that it is an `Error`).

`ValidatedTest`:
- `catch (Throwable)` around a `Bound.value()` that lets the block return
  normally makes `accumulate` throw `IllegalStateException`.
- `catch (RuntimeException)` around `Bound.value()` no longer swallows.
- An `Accumulator` used after `accumulate` returned throws
  `IllegalStateException("Accumulator.on called outside its accumulate block")`.
- A poisoned `Bound` unwrapped after `accumulate` returned throws
  `IllegalStateException("Bound.value() called outside its accumulate block")`.
- Regression (must stay green): the block that records errors but never unwraps a
  failed binding still returns `Invalid` (NOT `IllegalStateException` — this is
  the `aborted`-false / errors-present path), plus the existing nested-accumulate
  and traverse/onEach tests.

### Consequences

- The DSL's one documented "requires discipline" footgun becomes a loud failure:
  a swallowed short-circuit throws instead of fabricating a success, and an
  out-of-scope handle throws a named `IllegalStateException` instead of leaking a
  bare package-private `Halt`. The Javadoc "How it short-circuits, and the one
  caveat" section in `Result.binding` and the matching caveat in
  `Validated.accumulate` are rewritten: `RuntimeException`/`Exception` catch-alls
  around a bound call are now harmless (measure 1); only a `catch (Throwable)`
  swallow or an escaped handle can still misuse the DSL, and both now throw
  `IllegalStateException` rather than silently corrupting the result.
- `Halt` becoming an `Error` is invisible outside the package (it is
  package-private) but is a deliberate use of `Error` for control flow. Recorded
  here so a future reader does not "fix" it back to `RuntimeException`: the whole
  point is that ordinary `catch (Exception)` must not see it. `Result.attempt`'s
  `Halt`-before-`Throwable` catch order is load-bearing and must be preserved.
- Forward compat: if a future Java gains value-carrying extraction from sealed
  types without throwing (superseding ADR-1's mechanism), `Halt` and all three
  measures disappear together; the public signatures are unaffected either way.

### Alternatives considered

- **Return the swallowed `Err`/`Invalid` instead of throwing.** Rejected: the
  block already ran side effects past a failed step, which is the bug. Silently
  returning the "right" error hides a structural mistake the developer must fix,
  and it is impossible in general for `accumulate` (a swallowed unwrap leaves the
  block in an arbitrary state).
- **Only change `Halt`'s supertype to `Error` (drop measures 1 and 3).**
  Rejected: `catch (Throwable)` still swallows, and an escaped handle still leaks
  a bare `Halt`. The supertype change narrows the accident surface but does not
  close it; the boundary checks do.
- **Only add the boundary checks (keep `Halt extends RuntimeException`).**
  Rejected: workable but leaves the most common accidental catch-alls
  (`RuntimeException`/`Exception`) breaking short-circuiting up to the moment the
  block returns, detected only at the end. Making those catches harmless outright
  is strictly better and nearly free.
- **Make the handles thread-safe (volatile/atomic flags) to also catch
  concurrent escape.** Rejected as out of scope: both DSLs are synchronous
  single-thread constructs; issue #2 is about sequential swallow/escape. Revisit
  only if a concurrent-handle hazard is actually reported.

### Files to change

- `src/main/java/dev/fforj/Halt.java` — `extends Error`; update the class Javadoc
  (why `Error`, and that `attempt`'s catch order depends on it).
- `src/main/java/dev/fforj/Result.java` — `binding`: `aborted` + `closed` flags,
  swallow check on normal return, `closed` guard in `Binder.on`; rewrite the
  "How it short-circuits, and the one caveat" Javadoc. `attempt` Javadoc:
  note `Halt` is now an `Error` (the pass-through paragraph is otherwise
  unchanged).
- `src/main/java/dev/fforj/Validated.java` — `accumulate`: `aborted` + `closed`
  flags, swallow check distinct from the legitimate no-unwrap `Invalid` path,
  `closed` guard in `Accumulator.on` and in the poisoned `Bound.value()` closure;
  rewrite the caveat paragraph.
- `src/test/java/dev/fforj/ResultTest.java` — the binding tests above.
- `src/test/java/dev/fforj/ValidatedTest.java` — the accumulate tests above.
- Doc-tests / `README.md` — no change required (neither demonstrates the old
  caveat). A short "misuse fails loudly" section MAY be added to
  `ResultDocTest`/`ValidatedDocTest` later; not required by this ADR.
- `CLAUDE.md` — no change; the "No magic" carve-out (ADR-1) still stands, this
  ADR only hardens its failure modes.

---

## ADR-10 (2026-09-18): Void-returning fallible steps — the convention is `Optional<E>`, not `Result<E, Void>`

### Context

Some fallible operations have no meaningful success value: a permission check, a
side-effecting write whose only outcomes are "failed with an `E`" or "nothing to
report". The natural Java shape for them, `Result<E, Void>`, is **unconstructible
on the `Ok` side**: `Ok`'s compact constructor rejects `null` and `Void` has no
instances. The DSL itself already winks at this — `Binder.ensure(boolean, Supplier)`
builds a `Result<E, Void>` that is only ever an `Err`, never an `Ok`
([issue #3](https://github.com/fforj/fforj/issues/3)).

Two concrete friction points. (1) There is no clean way to sequence a
void-fallible step inside a `binding` / `accumulate` block: you must invent a
throwaway carrier value just to have something to bind. (2)
`attempt(() -> { sideEffect(); return null; }, mapper)` misbehaves: the body
returns `null`, `ok(null)` is evaluated *inside* `attempt`'s `try`, so the `Ok`
null-check NPE is caught by `catch (Throwable t)` and **mapped through `onThrow`
into an `Err`** — a programmer mistake (returning `null`) silently becomes a
domain error, handing `onThrow` a `NullPointerException` it never anticipated.

Non-goals, both locked: **no `Ok(null)`** (it destroys the null discipline the
whole library rests on) and **no new `Unit` type** (bounded-scope rule; Java has
no `Unit`, and `CompletableFuture<Void>`'s null convention is exactly the
non-null invariant we reject). The fforj-shaped reading instead: an operation
with no success value *is* "an error, or the absence of one" — which the standard
library already spells `Optional<E>`. Errors stay values; zero new types.

This ADR is scoped to #3. ADR-9 (same day, issue #2) hardened these same
`Binder`/`Accumulator` handles with `closed`/`aborted` flags; nothing here
contradicts it — the additions below are `default` methods that delegate to the
already-guarded `on(...)`. Issue #6 (`Binder.on` variance in `E`) is a separate
later decision and is not touched here.

### Decision

Three measures. No new types; the four-plus-one-reserved budget is unchanged. No
public signature on `on` changes.

**1. Convention (documentation).** A fallible step with no success value returns
either *natural evidence* when it has any (a deleted-row count, a stored id, the
validated input passed through) or **`Optional<E>`** when it genuinely does not:
present = the failure, empty = success. `Result<E, Void>` is documented as a
non-goal on `Result`'s class Javadoc, pointing at this convention. This is the
primary answer; the two code additions below just make the convention flow
through the DSLs and the `attempt` boundary.

**2. `ensure(Optional<? extends E>)` on both DSL handles.** A `default` overload
on `Binder` and, symmetrically, on `Accumulator` (the library keeps these two
handles feature-symmetric — ADR-2, ADR-6). Unambiguous against the existing
`ensure(boolean, Supplier)` by arity (1 arg vs 2), and it does **not** overload
`on(Optional, Supplier)` — that method reads an `Optional<T>` *value* where empty
means failure, the opposite polarity, so reusing `on` for an `Optional<E>` error
would be a footgun. The name `ensure` matches the existing "no value to bind, here
is how it can fail" family.

```java
// Result.Binder<E> — short-circuits, like binding an Err:
/**
 * Sequence a void-fallible step modeled as {@link Optional}{@code <E>}: present =
 * the failure, empty = success. A present error short-circuits the enclosing
 * {@code binding} block exactly like {@link #on(Result) on} of an {@link Err}; an
 * empty {@code Optional} is a no-op. The void counterpart to {@code on} — there is
 * no value to unwrap, so nothing is returned. Prefer natural evidence (a count, an
 * id, the input passed through) when the step has any; reach for {@code Optional<E>}
 * only when it genuinely has no success value ({@code Result<E, Void>} is
 * unconstructible on the {@code Ok} side by the library's null discipline).
 */
default void ensure(Optional<? extends E> failure) {
    Objects.requireNonNull(failure, "ensure failure Optional must not be null");
    failure.ifPresent(e -> this.<Void>on(Result.err(e)));
}
```

```java
// Validated.Accumulator<E> — records and keeps running, like every binding:
/**
 * Accumulate a void-fallible step modeled as {@link Optional}{@code <E>}: a present
 * error is recorded and the block keeps running (like every other binding); an
 * empty {@code Optional} records nothing. The accumulating counterpart to
 * {@link Binder#ensure(Optional)}.
 */
default void ensure(Optional<? extends E> failure) {
    Objects.requireNonNull(failure, "ensure failure Optional must not be null");
    failure.ifPresent(e -> on(Validated.<E, Void>invalid(e)));
}
```

Both delegate to the ADR-9-guarded `on(...)`, so an escaped/closed handle is
still caught, and `Binder.ensure` short-circuits via the same `closed`/`aborted`
machinery. Call site:

```java
Result<Failure, Order> r = Result.binding(bind -> {
    var order = bind.on(loadOrder(id));          // Ok  -> value
    bind.ensure(policy.check(order, actor));      // Optional<Failure>: present aborts here
    bind.ensure(inventory.reserve(order));        // another void-fallible step
    return order;                                  // reached only if both checks passed
});
```

**3. `attempt` treats a `null` body return as a loud programming error.** Restructure
so the body's value is captured inside the `try` but the null-check runs *after*
it, so its NPE propagates to the caller instead of being mapped through `onThrow`:

```java
static <E, T> Result<E, T> attempt(
        Callable<? extends T> body,
        Function<? super Throwable, ? extends E> onThrow) {
    T value;
    try {
        value = body.call();
    } catch (Halt halt) {
        throw halt;                                  // ADR-9: pass an enclosing abort through
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return err(onThrow.apply(ie));
    } catch (Throwable t) {
        return err(onThrow.apply(t));
    }
    // A null return is a bug in the body, not a domain failure: surface it loudly
    // instead of mapping a surprise NPE through onThrow.
    Objects.requireNonNull(value,
            "attempt body returned null; return a value, or model a void-fallible "
            + "operation as Optional<E> (an absent error means success)");
    return ok(value);
}
```

The `catch (Halt halt)` clause stays first (ADR-9 requires it; `Halt` is now an
`Error`), and the `InterruptedException`/`Throwable` behavior is unchanged. Only
the `null`-return path changes: it now throws a targeted `NullPointerException`
to the caller rather than an `onThrow`-mapped `Err`.

#### Test plan (the dev MUST cover)

`ResultTest`:
- `binding_ensure_optional_present_short_circuits` — `bind.ensure(Optional.of(e))`
  aborts the block; `binding` returns `Err(e)`, and later steps do not run
  (step-log proof).
- `binding_ensure_optional_empty_is_a_no_op` — `bind.ensure(Optional.empty())`
  leaves the block running; `binding` returns `Ok` of the final value.
- `attempt_body_returning_null_throws_targeted_npe` — a body returning `null`
  throws `NullPointerException` with the new message **to the caller**, and
  `onThrow` is NOT invoked (assert via a mapper that records a flag). This pins
  the behavior change: null is no longer mapped to `Err`.
- Regression (must stay green): existing `attempt` tests, and the ADR-9
  `binding`-abort-through-`attempt` pass-through test.

`ValidatedTest`:
- `accumulate_ensure_optional_present_records_error_and_keeps_running` — a present
  `Optional<E>` records the error, a later binding also runs, result is `Invalid`
  carrying both in order.
- `accumulate_ensure_optional_empty_is_a_no_op` — an empty `Optional<E>` records
  nothing; an otherwise all-valid block returns `Valid`.

Doc-tests: a short "steps with no value to return" section MAY be added to
`ResultDocTest` (the `ensure(Optional)` + `attempt` convention); not required by
this ADR.

### Consequences

- Void-fallible steps have one blessed shape (`Optional<E>`, or natural evidence)
  and flow through both DSLs without inventing a carrier value. `Result<E, Void>`
  stays unconstructible on the `Ok` side, as intended.
- **Behavior change in `attempt`:** a body that returns `null` now throws a
  targeted `NullPointerException` to the caller instead of being mapped through
  `onThrow` into an `Err`. This is a deliberate "misuse fails loudly" fix in the
  spirit of ADR-9 — the old path handed `onThrow` an unexpected NPE and disguised
  a programming error as a domain failure. Pre-1.0, acceptable; called out here so
  it is not mistaken for a regression.
- The `Binder`/`Accumulator` handles stay feature-symmetric.
- Forward compat: if a future Java gains a first-class no-value success shape, the
  convention can be revisited, but `Optional<E>` remains valid; the two `ensure`
  overloads and the `attempt` guard are unaffected.

### Alternatives considered

- **Make `Result<E, Void>` constructible (allow `Ok(null)` for `Void`, or a
  private sentinel).** Rejected: `Ok(null)` breaks the null discipline
  everywhere, and a `Void` sentinel is a `Unit` type in disguise — both locked
  non-goals. `Optional<E>` already models "error or nothing" in the stdlib.
- **A `Unit`/`Nothing` type plus `Result<E, Unit>`.** Rejected: bounded-scope
  rule; a sixth public type for a case `Optional<E>` covers is exactly the Vavr
  trap CLAUDE.md guards against.
- **Overload `on(Optional<? extends E>)` for the error-side Optional.** Rejected:
  `on(Optional<? extends T>, Supplier)` already reads an `Optional` *value* where
  empty means failure; a single-arg `on(Optional)` with present-means-failure
  polarity, resolved only by arity, is a live footgun. A distinctly-named
  `ensure` keeps the polarity legible.
- **Leave `attempt`'s null path as-is (map through `onThrow`).** Rejected: it
  disguises a programming error as a domain failure and feeds `onThrow` an NPE it
  was never written to handle. Failing loudly is strictly clearer and nearly free.

### Files to change

- `src/main/java/dev/fforj/Result.java` — add `Binder.ensure(Optional<? extends E>)`;
  restructure `attempt` for the post-`try` null-check with the targeted message;
  add the `Result<E, Void>` / `Optional<E>` convention paragraph to the class
  Javadoc and to `attempt`'s Javadoc.
- `src/main/java/dev/fforj/Validated.java` — add
  `Accumulator.ensure(Optional<? extends E>)`.
- `src/test/java/dev/fforj/ResultTest.java` — the binding-`ensure` and
  `attempt`-null tests above.
- `src/test/java/dev/fforj/ValidatedTest.java` — the accumulate-`ensure` tests.
- Doc-tests / `README.md` — optional "steps with no value" section in
  `ResultDocTest`; not required.
- `CLAUDE.md` — no change; no locked decision moves (the non-goals here restate
  existing locked rows).

---

## ADR-11 (2026-09-18): Error-covariant DSL handles — `Binder.on` / `Accumulator.on` accept `Result<? extends E, …>`

### Context

The flagship fforj pattern, recommended in the README and the first external
write-up, is a sealed error hierarchy with one record per failure, sequenced in a
`binding` block ([issue #6](https://github.com/fforj/fforj/issues/6)):

```java
sealed interface BankError permits NoSuchAccount, WrongPin, OverDailyLimit {}
Result<NoSuchAccount, String>  findAccount(String id);
Result<WrongPin, String>       verifyPin(String account, String pin);
Result<OverDailyLimit, Integer> withdraw(String account, int amount);
```

Today it does not compile. `Binder.on` is `<T> T on(Result<E, T> result)`,
invariant in `E`, so a block typed `Binder<BankError>` rejects a step returning
`Result<NoSuchAccount, String>`: `NoSuchAccount` is a subtype of `BankError`, but
`Result<NoSuchAccount, …>` is not a subtype of `Result<BankError, …>`. The only
workaround is `mapErr(e -> e)` on every step, the exact wart the DSL exists to
remove. `Validated.Accumulator` has the same shape on `on(Validated<E, T>)`
(`Validated.java:135`) and `on(Result<E, T>)` (`:138`).

The issue verified the Result-side fix and asked three questions: does the same
rule apply to `Accumulator.on`, does it apply to the point-free combinators
(`flatMap` and friends), and should the ATM withdrawal flow become the `binding`
doc-test. ADR-9 (issue #2) and ADR-10 (issue #3) landed the same day and touch
these same handles; this ADR is orthogonal to both and must not contradict them.

### Decision

Widen the error type to `? extends E` **only where the DSL consumes a Result or
Validated** — the `on` handles. Leave the point-free combinators invariant in `E`.

**1. `Result.Binder.on` becomes error-covariant.** Three lines, the issue's
verified fix:

```java
// interface
<T> T on(Result<? extends E, T> result);

// anonymous binder in Result.binding
public <U> U on(Result<? extends E, U> result) {
    // ... ADR-9's closed guard and aborted flag stay exactly as written ...
    return switch (result) {
        case Ok<? extends E, U> ok   -> ok.value();
        case Err<? extends E, U> err -> { /* aborted = true; */ throw new Abort(this, err.error()); }
    };
}
```

`err.error()` is now `? extends E`, which widens to `E` for `new Abort(this,
err.error())` (`Abort` still carries an `E`), so the boundary is unchanged. The
`default on(Optional<? extends T>, Supplier)` and `default ensure(...)` methods
delegate to `on` and need no edit.

**2. `Validated.Accumulator.on` becomes error-covariant, both overloads.**

```java
// interface
<T> Bound<T> on(Validated<? extends E, T> validated);
default <T> Bound<T> on(Result<? extends E, T> result) { return on(fromResult(result)); }

// anonymous accumulator in Validated.accumulate
public <U> Bound<U> on(Validated<? extends E, U> validated) {
    // ... ADR-9's closed guard stays ...
    return switch (validated) {
        case Valid<? extends E, U>   valid   -> valid::value;
        case Invalid<? extends E, U> invalid -> {
            errors.addAll(invalid.errors().toList());   // List<? extends E> into ArrayList<E>: ok
            yield () -> { /* aborted = true; */ throw new Abort(this); };
        }
    };
}
```

`invalid.errors().toList()` is `List<? extends E>` and `errors.addAll` accepts it.
The `Result` overload delegates to `fromResult`, whose `<E,T> Validated<E,T>
fromResult(Result<E,T>)` signature stays as-is: called with a `Result<? extends E,
T>` it captures and returns a `Validated<CAP, T>` that the widened `on` accepts —
verified compiling and running. `onEach`, `on(Optional, Supplier)`, `ensure` all
delegate to `on` and need no edit. `fromResult` and the other static bridges
(`Result.fromOptional`, `Validated.fromResult`) are left untouched.

**3. The point-free combinators stay invariant in `E`.** `Result.flatMap`
(`? extends Result<E, U>`), `Result.zip`, `Result.recover`, and `Validated.zip`
keep their single fixed `E`. The rule the library teaches:

> fforj consumes a Result/Validated error-covariantly **only at the `on` boundary
> of a `binding` / `accumulate` block**, where unifying per-step error subtypes
> into the block's `E` is the entire purpose. The algebraic combinators operate at
> one fixed `E`; `mapErr` is how you widen a Result's error before combining.

This is deliberate, not an oversight, for three reasons:

- **`binding` is strictly the better tool for heterogeneous errors, so widening
  `flatMap` buys little.** A widened `flatMap` is only sound if `self` is already
  typed at the supertype (the result stays `Result<E, U>` with `E` fixed; Java has
  no lower-bounded type parameter to express Scala's `flatMap[A1 >: A]`). So a
  heterogeneous `flatMap` chain still needs a widen at the head
  (`findAccount()` is `Result<NoSuchAccount, …>`, not `Result<BankError, …>`),
  whereas `binding` unifies every step, including the first, automatically. The
  combinator gains a wildcard but not the ergonomics.
- **Cost lands on the primary combinator for a secondary API.** CLAUDE.md: the
  pattern-match / DSL form is the API; combinators "are NOT the primary interface."
  Widening `flatMap` means `Function<? super T, ? extends Result<? extends E, ?
  extends U>>` and rebuilding the `Err` (today a zero-logic pass-through) or an
  unchecked cast, on every combinator that consumes a Result, for a pattern the
  library already steers into `binding`.
- **The escape hatch is one call.** For the rare raw-combinator chain, `.<BankError>mapErr(e -> e)`
  widens a subtype-errored Result to the supertype. `mapErr` means "adjust the
  error type"; using it to widen is legible.

Not a breaking change: `Result<E,T>` <: `Result<? extends E,T>`, so every argument
that binds today still binds. Verified: the full ATM repro (Result `binding` and
Validated `accumulate`, the latter via both `on(Validated)` and `on(Result)`)
compiles and runs, and every existing call shape still compiles.

**4. The ATM withdrawal flow becomes the `binding` doc-test in `ResultDocTest`.**
It replaces the single-error `parsePositive` binding example
(`sequence_steps_as_straight_line_code`) and pins the variance: three steps
returning three distinct `BankError` subtypes bind into one `Result<BankError, …>`
block with no per-step `mapErr`. Verified fixture:

```java
// site:include
sealed interface BankError {
    record NoSuchAccount(String id) implements BankError {}
    record WrongPin() implements BankError {}
    record OverDailyLimit(int requested, int remaining) implements BankError {}
}
static Result<BankError.NoSuchAccount, String>  findAccount(String id) { ... }
static Result<BankError.WrongPin, String>       verifyPin(String account, String pin) { ... }
static Result<BankError.OverDailyLimit, Integer> withdraw(String account, int amount) { ... }

Result<BankError, Integer> cash = Result.binding(bind -> {
    var account = bind.on(findAccount(id));         // Result<NoSuchAccount, String>
    var ok      = bind.on(verifyPin(account, pin)); // Result<WrongPin, String>
    return bind.on(withdraw(ok, amount));           // Result<OverDailyLimit, Integer>
});
```

The accompanying prose says explicitly: each step fails with its own subtype and
`binding` unifies them into `BankError` for you, no `mapErr` per step. The
`parsePositive` fixture and its other doc-tests stay; only the `binding` section's
example changes to this one.

#### Test plan (the dev MUST cover)

`ResultTest`:
- `binding_unifies_heterogeneous_error_subtypes` — a `Result<BankError, …>` block
  binds steps returning three distinct subtypes with no `mapErr`; an `Ok` path and
  each subtype's `Err` short-circuit path return the widened `Result<BankError, …>`
  carrying the right subtype value. This is the compile-and-behavior proof of the
  widening.
- Regression (must stay green): every existing `binding` test, including ADR-9's
  swallow/escape tests and ADR-10's `ensure(Optional)` tests, still pass
  unchanged. The widening is a parameter generalization; no existing behavior moves.

`ValidatedTest`:
- `accumulate_unifies_heterogeneous_error_subtypes` — an `Accumulator<BankError>`
  block binds a `Validated<SubtypeA, …>` via `on(Validated)` and a
  `Result<SubtypeB, …>` via `on(Result)`; when both fail, `Invalid` carries both
  errors (widened to `BankError`) in binding order.
- Regression (must stay green): all existing `accumulate`, `traverse`, `onEach`,
  and ADR-9/ADR-10 tests.

Doc-tests: the ATM `binding` doc-test above must run green under `./gradlew site`
extraction (`// site:include` fixtures, real `assertEquals`).

### Consequences

- The library's flagship shape — sealed error hierarchy, one record per failure,
  sequenced in `binding` — compiles as written, with no per-step `mapErr`. This is
  what Scala `for` / Arrow `either {}` users expect, and it is what the external
  write-up demonstrated.
- The two DSL handles stay feature-symmetric (ADR-2, ADR-6): both `on` families
  are now error-covariant on every overload.
- A clear, teachable variance rule: covariance lives at the `on` boundary; the
  combinators are single-`E` and `mapErr` is the explicit widen. No wildcard creep
  into the combinator signatures, no `Err`-path reallocation, no unchecked casts.
- Composes with ADR-9 and ADR-10 with no conflict, in any merge order: the
  widening changes only the parameter type and the switch case types; ADR-9's
  `closed`/`aborted` guards and ADR-10's `ensure(Optional<? extends E>)` overloads
  sit on top unchanged (both delegate through the widened `on`).
- Forward compat: if a future Java gains lower-bounded method type parameters
  (Scala's `A1 >: A`), the combinators could revisit covariance; the `on`
  widening is unaffected and remains correct.

### Alternatives considered

- **Widen the combinators too (`flatMap`, `zip`, `recover`, `Validated.zip`).**
  Rejected. Sound only when `self` is pre-widened to the supertype (Java cannot
  express `flatMap[A1 >: A]`), so a heterogeneous `flatMap` chain still needs a
  head-of-chain widen while `binding` needs none — the combinator gains wildcard
  noise and an `Err`-path rebuild/cast but not the ergonomics, on the library's
  non-primary API. The one-call `mapErr` escape hatch covers the rare case.
  Revisitable pre-1.0 if real demand appears; not now.
- **Leave `on` invariant; document `mapErr(e -> e)` per step as the pattern.**
  Rejected: it is exactly the wart the DSL exists to remove, on the library's
  flagship example. A three-line generalization erases it with no downside.
- **Add a covariant `Result.widenErr()` / upcast helper.** Rejected: a new method
  for what subtype polymorphism should give for free, and it does not fix the
  `binding` call site, only relocates the boilerplate.

### Files to change

- `src/main/java/dev/fforj/Result.java` — widen `Binder.on` (interface + anonymous
  binder) and its switch cases to `? extends E`; keep ADR-9's `closed`/`aborted`
  machinery. No combinator changes.
- `src/main/java/dev/fforj/Validated.java` — widen `Accumulator.on(Validated)` and
  `on(Result)` (interface + anonymous accumulator) and its switch cases to
  `? extends E`; keep ADR-9's machinery. `fromResult` and other statics unchanged.
- `src/test/java/dev/fforj/ResultTest.java` — `binding_unifies_heterogeneous_error_subtypes`.
- `src/test/java/dev/fforj/ValidatedTest.java` — `accumulate_unifies_heterogeneous_error_subtypes`.
- `src/test/java/dev/fforj/docs/ResultDocTest.java` — replace the `binding`
  example with the ATM withdrawal flow (new `// site:include` `BankError` fixtures
  and per-step methods); update the section prose.
- `README.md` — if the `binding` snippet shows a single-error example, update it to
  the sealed-hierarchy shape so the headline example matches what now compiles.
- `CLAUDE.md` — no change; no locked decision moves.
