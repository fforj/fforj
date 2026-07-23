# fforj — project constitution

## What this is

A small Java 21+ library of FP essentials: `Result`, `Validated`, `NonEmptyList`,
`Retry`. Zero runtime dependencies. Standard library only. A fifth type, `Scopes`
(structured-concurrency helpers), is shelved on branch `poc/scopes-jep505` until
JEP 505 finalizes (ADR-3).

Designed for projects that would otherwise hand-roll a `Result` type per repo or
pull Vavr just for that one thing. The library deliberately ships a bounded surface
area and **will not grow** into Vavr.

License: MIT

Org: [github.com/fforj](https://github.com/fforj). Website: `fforj.dev`.

---

## Locked decisions (do not relitigate)

| Decision | Choice | Reason |
|---|---|---|
| Language version | Java 21 (LTS) baseline, compiled with `--release 21` | Records, sealed interfaces, pattern matching, virtual threads — all final in 21. Widest modern-LTS reach; nothing preview-flagged ships (ADR-3, which superseded the original Java 25 target). |
| Build tool | Gradle 9+ with Kotlin DSL | Idiomatic, supports Java 25 toolchain, easier to read than Groovy |
| Runtime dependencies | **Zero.** Standard library only. | Trust dimension: a single-file copy of this library should keep working forever. Adding a transitive opens it up to break. |
| Test framework | JUnit Jupiter (BOM 5.11+) | Industry default, JUnit Platform integration |
| Mocking framework | **None.** | Sums and records are too simple to need mocks. If a test needs mocking it's testing the wrong thing. |
| Package root | `dev.fforj` | Reverse-domain convention from `fforj.dev` |
| Group ID | `dev.fforj` | Same; for Maven Central publishing |
| Artifact ID (default) | `fforj` | When we publish |
| IO monad | **NO.** Virtual threads + `StructuredTaskScope` replace it. | The reason `IO` exists in TS/Haskell does not apply to Java 21+. Adding it here would burn the JVM's strength. |
| Tuples | **NO.** Use records. | Records eliminate the reason `Tuple1..8` ever existed in Vavr. |
| Functional collections | **NO.** Use stdlib `List.copyOf`, `Stream`, `Map.copyOf`. | The stdlib is fine. Reimplementing it is the Vavr trap. |
| Type classes / HKTs | **NO.** | Java's type system doesn't support them; emulating them produces hostile code. |
| Optional reimplementation | **NO.** Use `java.util.Optional`. | Even though our `Result` could subsume some of its uses, replacing it would fragment the ecosystem. |
| Checked exceptions in public types | **NO** in domain types; allowed in `Retry` (and `Scopes`, when it returns) for `InterruptedException`. | `InterruptedException` is a cooperative-cancellation signal — the only place checked exceptions are still right. |
| Null handling | Reject in record constructors. Domain types return `Optional` for absence and `Result` for failure. | `Objects.requireNonNull` everywhere a `null` would silently corrupt the type's invariant. |
| Preview features | **NO** on `main` (ADR-3). Preview-dependent code lives on `poc/*` branches until the API finalizes. | A preview-flagged class stops loading on the next JDK — the opposite of the "keeps working forever" trust dimension. |

---

## Architecture principles

### Bounded scope

The library has FOUR public types (`Result`, `Validated`, `NonEmptyList`, `Retry`),
with a standing reservation for a fifth: `Scopes` returns from `poc/scopes-jep505`
when structured concurrency (JEP 505) finalizes (ADR-3). Adding any other type
requires an ADR. The bar is "this is a primitive we genuinely missed", not "this
would be nice to have."

**Things that look like they belong here but don't:**
- `Option<T>` — `Optional<T>` exists.
- `Tuple1..N` — records exist.
- `Try<T>` — `Result<Throwable, T>` covers it; use `Result.attempt`.
- `Lazy<T>` — `Suppliers.memoize` patterns; not worth its own type.
- `Future<T>` — virtual threads + `CompletableFuture` cover it.
- Effect / IO / Task — see locked decision above.

### No magic

- No annotation processors.
- No code generation.
- No reflection in hot paths.
- No service loaders.
- No `Unsafe`.

A user pasting the source of any class into their own project (with package
renamed) MUST get the same behaviour. The library is fundamentally a copy-paste
target packaged for convenience.

### Pattern matching is the API

Every sum type is a `sealed interface` with `record` cases that pattern-match
exhaustively on `switch`. Callers MUST be able to write:

```java
String s = switch (result) {
    case Result.Ok<E, T> ok -> "ok:" + ok.value();
    case Result.Err<E, T> err -> "err:" + err.error();
};
```

…and have the compiler enforce exhaustiveness. Combinators (`map`, `flatMap`, etc.)
exist as `default` methods on the sealed interface for chains; they are NOT the
primary interface. The pattern-match form is.

### Defensive copies at boundaries

Any record that holds a `List`/`Map`/array MUST `List.copyOf(...)` in its compact
constructor. Callers MUST NOT be able to mutate the internal state by holding a
reference to the constructor argument.

---

## Code style

- Java 25 strict. Use records, sealed types, switch patterns, `var` for locals.
- One public top-level type per file. Inner records/classes for sealed cases are fine.
- Public types and methods have Javadoc with at least one short paragraph on intent.
- No `@Generated`, no Lombok, no `@Value`.
- No `null` returns from public methods. Use `Optional` for absence, `Result` for failure.
- Constants use `SCREAMING_SNAKE_CASE` only when truly constant; otherwise `camelCase` private finals.
- Test method names use `snake_case_describing_behavior` (matches what the seed tests do).
- Imports: explicit, never `*`. Static imports allowed for `Assertions` in tests.
- Line length 100 cols (soft).

---

## Testing

- Vendor: JUnit Jupiter.
- One `*Test.java` per source `*.java`.
- Tests describe behavior (`zip_accumulates_errors_from_both_sides`), not method
  names (`testZip`).
- Test the contract, not the implementation. Don't assert internal field values
  through reflection.
- Property-based tests are welcome (e.g. jqwik) but not required and **must not**
  become a runtime dep on the production code path.
- Concurrency tests (`Retry`; `Scopes` when it returns) MUST be deterministic. Use `Duration.ZERO`
  or controllable delays, not arbitrary `Thread.sleep(100)`.

---

## Dependency rules

- **Production code**: ZERO runtime deps. The library is `java.base` + nothing.
- **Test code**: JUnit Jupiter (BOM, `junit-jupiter`, `junit-platform-launcher`).
  Property-based testing (jqwik) is the only acceptable expansion. NO mocking
  framework. NO assertion library beyond `org.junit.jupiter.api.Assertions`.
- **Build code**: Gradle plugins only. No custom convention plugins yet.

Adding any new dep — runtime, test, or build — requires an ADR (PR + entry in
`decisions.md`).

---

## Build, test, lint commands

```bash
./gradlew test          # JUnit tests
./gradlew build         # compile + test + jar + sourcesJar + javadocJar
./gradlew check         # test + (future: lint, spotless)
./gradlew clean         # wipe build/
```

If the wrapper jar is missing on a fresh clone, run once with system Gradle:

```bash
gradle wrapper --gradle-version 9.5.1
```

---

## Agent pipeline

Three stages. Each agent operates on a single GitHub issue, signals state through
issue comments, and hands off to the next stage.

```
Architect → Dev → Reviewer → (human merge) → repeat
```

- Always sequential. Each stage depends on the previous stage's output.
- State is passed between stages via GitHub issue status comments
  (`Status: READY_FOR_X`), not via memory or local files.
- The architect writes a full ADR to `decisions.md`; the issue thread gets a short
  pointer comment.
- The dev opens a PR; the reviewer either approves the PR or files structured
  change requests.
- Skip the architect for purely-mechanical changes (typo fixes, dep bumps). Note
  the skip in the PR description.

### Models

| Agent | Model | Reason |
|---|---|---|
| Architect | `claude-opus-4-8` | Design quality matters; cost is small per task |
| Dev | `claude-sonnet-4-6` | Implementation throughput; predictable code patterns |
| Reviewer | `claude-opus-4-8` | Catching subtle correctness bugs |

Bump the models in `.claude/agents/*.md` when a new generation is GA.

---

## GitHub conventions

- Org/repo: `fforj/fforj`.
- Branch naming: `feat/<issue-number>-<short-slug>`, `fix/<issue-number>-<short-slug>`.
- Commit style: conventional commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`,
  `refactor:`).
- PR title = conventional commit style.
- Labels: `area:result|validated|nonempty|retry|scopes|build|docs`, `breaking-change`
  when a public API changes shape.

---

## Status-machine vocabulary

Issues move through these states via comments:

- `READY_FOR_ARCH` — issue is well-specified, architect can start.
- `NEEDS_CLARIFICATION` — architect bounced; original requester owns the next move.
- `READY_FOR_DEV` — architect's ADR is in `decisions.md`.
- `READY_FOR_REVIEW` — dev pushed a PR.
- `CHANGES_REQUESTED` — reviewer found blockers; dev owns the next move.
- `APPROVED` — ready for human merge.

---

## What success looks like

A new user who only ever heard "I need a Result type" or "I want to validate this
form and surface all errors" can:

1. Find this library via "java result type sealed".
2. Read the Javadoc on `Result` and `Validated` in under 5 minutes.
3. Copy 30 lines into their project (without the dep) OR add the dep — same code
   in both cases.
4. Never have to upgrade unless they want a new feature; the library is small
   enough that breaking changes are rare and clearly announced.
