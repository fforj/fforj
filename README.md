# fforj — Java 25 FP essentials, no nonsense

Five small types you'd otherwise re-implement in every Java project. Zero runtime
dependencies. Stdlib only.

## Why

- **Vavr** still targets Java 8 by contract. Tuples 1–8, no records, no sealed types,
  no native pattern matching. The ergonomics are stuck where Java was a decade ago.
- **Arrow** is Kotlin-only.
- Most teams hand-roll a `Result` type per project. This is that, packaged.

## What's in the box

| Type | What it is | Why it's here |
|------|------------|---------------|
| `Result<E, T>` | `Ok \| Err` sum type. | Replace checked exceptions + `Optional<Throwable>` everywhere. |
| `Validated<E, T>` | Like `Result`, but accumulates every error via `NonEmptyList`. | Form / config / DTO validation: surface all the reasons it's broken, not just the first. |
| `NonEmptyList<T>` | A list the compiler knows is non-empty. | Foundation for `Validated`; also useful for domain "must have at least one" invariants. |
| `Retry` | Backoff loop that respects `Predicate<E>` for retryability. | The wrapper around any flaky call, virtual-thread safe. |
| `Scopes` | Helpers over `StructuredTaskScope` (JEP 505). | Parallel fan-out and races that return `Validated` / `Result` directly. |

### Composing `Result`s

Three tools, by the shape of your steps:

- **Steps that throw** → one `Result.attempt` around straight-line code; the first throw
  short-circuits and the mapper turns it into your error type.
- **Steps that return `Result`** → `Result.binding`: call `bind.on(step())` to get the raw
  value, the first `Err` aborts the block. `bind.on(optional, () -> err)` binds
  `Optional`-returning steps in the same block.
- **Independent values** → `zip` (and on `Validated`, `zip` accumulates errors instead of
  short-circuiting).

```java
Result<Failure, Summary> r = Result.binding(bind -> {
    var candidates = bind.on(NonEmptyList.fromList(xs), Failure.NoCandidates::new);
    var best       = bind.on(score(candidates));         // Result-returning step
    var enriched   = bind.on(Result.attempt(() -> fetch(best), Failure::from));
    return Summary.of(enriched);
});
```

Bridges: `Result.fromOptional(opt, ifEmpty)` lifts an `Optional` in; `result.okValue()` /
`result.errValue()` drop back down to `Optional`.

## What is *not* here

- **No `IO` monad.** Virtual threads + `StructuredTaskScope` solve the problem `IO`
  was invented for. Write straight-line blocking code in a virtual thread; cancellation
  works structurally; composition works via scope nesting. Adding `IO` on top would be
  paying complexity tax for benefits already in the JVM.
- **No `Future`/`Promise` reimplementation.** Stdlib has `CompletableFuture`. Virtual
  threads make most of that surface area unnecessary.
- **No tuples.** Use records.
- **No "functional collections" reimplementation.** Use the stdlib `List.copyOf`,
  `Stream`, `Map.copyOf`.
- **No tagless-final, type classes, HKTs, or higher-rank polymorphism.** Java's type
  system doesn't support them; emulating them produces hostile code.

## Requirements

- Java 25
- Gradle 9.5+ (wrapper checked in)
- **`Scopes` only**: `--enable-preview`. `StructuredTaskScope` (JEP 505) is still a
  preview API in Java 25, so `Scopes.class` is preview-flagged — code that loads it must
  run on Java 25 exactly, with `--enable-preview`. The other four types are plain class
  files with no such constraint. The flag goes away when the API finalizes.

## Build

```sh
./gradlew test
```

## License

MIT
