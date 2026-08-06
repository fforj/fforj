# ﬀorj: functional for Java

[![CI](https://github.com/fforj/fforj/actions/workflows/ci.yml/badge.svg)](https://github.com/fforj/fforj/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.fforj/fforj)](https://central.sonatype.com/artifact/dev.fforj/fforj)

Java 21+ FP essentials, no nonsense: four small types you'd otherwise re-implement
in every project. Zero runtime dependencies. Stdlib only. Nothing preview-flagged:
every class loads on any JDK from 21 up.

**Docs: [fforj.dev](https://fforj.dev)**, guides and full Javadoc. Every example there
is a test in this repo ([`src/test/java/dev/fforj/docs`](src/test/java/dev/fforj/docs)),
so the docs can't drift from the code.

## The name

**ﬀorj** is **f**unctional **for** **J**ava, and the `ff` is one character, not
two: ﬀ, U+FB00, the double-f ligature. A ligature is what a typesetter reaches for
when two letters compose better as a single glyph than side by side. That's the
whole design goal, in one character: functional types and plain Java, fused,
nothing added.

(Everywhere machines read the name, like the Maven artifact, the `dev.fforj`
package, and the GitHub org, it's spelled with two plain `f`s, because build
tools have no taste for typography.)

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

A fifth type, `Scopes` (parallel fan-out and races over `StructuredTaskScope` that
return `Validated`/`Result` directly), is **shelved on the
[`poc/scopes-jep505`](../../tree/poc/scopes-jep505) branch** until structured
concurrency (JEP 505) finalizes. It was built and tested against the Java 25
preview API; shipping a preview-flagged class would break the "keeps working
forever" promise below, so it waits (see ADR-3 in `decisions.md`).

### Composing `Result`s

Three tools, by the shape of your steps:

- **Steps that throw** → one `Result.attempt` around straight-line code; the first throw
  short-circuits and the mapper turns it into your error type.
- **Steps that return `Result`** → `Result.binding`: call `bind.on(step())` to get the raw
  value, the first `Err` aborts the block. `bind.on(optional, () -> err)` binds
  `Optional`-returning steps in the same block.
- **Independent validations, all errors at once** → `Validated.accumulate`: bind each
  piece with `acc.on(...)` (failures record, later validations still run), unwrap with
  `.value()` at the end. Arity-free alternative to chained `zip`s.
- **Two values** → `zip` (and on `Validated`, `zip` accumulates errors instead of
  short-circuiting).

```java
// AppError is your own domain error type; the library never prescribes one.
Validated<AppError, Form> form = Validated.accumulate(acc -> {
    var name  = acc.on(validateName(raw));    // Invalid -> recorded, keeps going
    var email = acc.on(validateEmail(raw));   // still runs
    var age   = acc.on(validateAge(raw));     // still runs
    return new Form(name.value(), email.value(), age.value());
});
// Invalid([nameError, emailError, ageError]) if all three failed
```

```java
Result<AppError, Summary> r = Result.binding(bind -> {
    var candidates = bind.on(NonEmptyList.fromList(xs), AppError.NoCandidates::new);
    var best       = bind.on(score(candidates));         // Result-returning step
    var enriched   = bind.on(Result.attempt(() -> fetch(best), AppError::from));
    return Summary.of(enriched);
});
```

Bridges: `Result.fromOptional(opt, ifEmpty)` lifts an `Optional` in; `result.okValue()` /
`result.errValue()` drop back down to `Optional`.

### Parse, don't validate

The intended way to use all of the above (after [Alexis King's essay][pdv]): don't
*check* a property and throw the evidence away. *Parse* the input into a type that
makes the property unrepresentable, once, at the boundary.

```java
public record Email(String value) {
    public Email {                                            // the wall: an illegal
        if (!isEmail(value))                                  // Email cannot exist
            throw new IllegalArgumentException("not an email: " + value);
    }
    public static Result<AppError, Email> parse(String raw) { // the door: typed errors
        return isEmail(raw) ? Result.ok(new Email(raw))
                            : Result.err(new AppError.Malformed(raw));
    }
    private static boolean isEmail(String s) {                // the rule, written once
        return s.contains("@");
    }
}

Validated<AppError, Registration> reg = Validated.accumulate(acc -> {
    var email = acc.on(Email.parse(rawEmail));     // every field error surfaces at once
    var age   = acc.on(Age.parse(rawAge));
    return new Registration(email.value(), age.value());
});
```

Downstream code takes `Registration`, never re-checks anything, and has no error
path; the proof lives in the type. `NonEmptyList` is the canonical case: `fromList` is the
parser, and `head()` is total because emptiness is unrepresentable.

[pdv]: https://lexi-lambda.github.io/blog/2019/11/05/parse-don-t-validate/

## What is *not* here

- **No `IO` monad.** Not because virtual threads make one pointless: an `IO` type
  also buys referential transparency and effects-as-values (compose, retry, race a
  description before running it), which no thread model provides. But the dominant
  *practical* reason JVM code reached for `IO` was cheap, cancellable, composable
  concurrency without blocking platform threads, and that now lives in the language
  itself: virtual threads plus structured concurrency. What remains of the pitch is pure
  effect tracking, and in Java that costs more than it pays: it colors every API it
  touches and can't be expressed well without higher-kinded types. fforj's lane is
  errors-as-values (`Result`), leaving effects to the runtime.
- **No `Future`/`Promise` reimplementation.** Stdlib has `CompletableFuture`. Virtual
  threads make most of that surface area unnecessary.
- **No tuples.** Use records.
- **No "functional collections" reimplementation.** Use the stdlib `List.copyOf`,
  `Stream`, `Map.copyOf`.
- **No tagless-final, type classes, HKTs, or higher-rank polymorphism.** Java's type
  system doesn't support them; emulating them produces hostile code.

## Get it

```kotlin
// Gradle
dependencies {
    implementation("dev.fforj:fforj:0.2.0")
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>dev.fforj</groupId>
    <artifactId>fforj</artifactId>
    <version>0.2.0</version>
</dependency>
```

The version above is kept current automatically: the release workflow rewrites it
on every release.

Or don't: every class stands alone by design, so copying the source of the types
you need into your own project (package renamed) gives you the exact same behavior.
That's not a workaround, it's a supported way to consume the library.

## Requirements

- Java 21 or newer (the jar is compiled with `--release 21`; no preview flags)
- Gradle 9.5+ to build (wrapper checked in)

## Build

```sh
./gradlew test
```

Releases are tag-driven (`git push origin vX.Y.Z` publishes to Maven Central);
see [RELEASING.md](RELEASING.md).

## License

MIT
