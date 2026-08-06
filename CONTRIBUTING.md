# Contributing to fforj

Thank you for your interest in contributing to fforj! This library is deliberately small and focused. While the bar for new types is high (see [decisions.md](decisions.md) for context), we warmly welcome bug fixes, documentation improvements, test coverage, and general polish.

## Scope

fforj strictly maintains **four public types** (`Result`, `Validated`, `NonEmptyList`, `Retry`), with a standing reservation for a fifth (`Scopes`, shelved on `poc/scopes-jep505` until JEP 505 finalizes). The library is bounded by design and **will not grow** into a larger framework like Vavr.

To respect your time, please open an issue to discuss any feature requests before implementing them. Unsolicited pull requests that add new public types, introduce dependencies, or relitigate locked decisions in [CLAUDE.md](CLAUDE.md) will be declined.

## Finding something to work on

- Look for issues tagged `good first issue` for small, self-contained starting points.
- Issues tagged `help wanted` are approved for implementation but unstaffed.
- If you discover a bug, please open an issue with reproduction steps before submitting a pull request.

## Getting started

**Prerequisites:**
- Java 21 or newer (the jar is compiled with `--release 21`)
- Gradle 9.5+ (wrapper checked in)

1. [Fork the repository](https://github.com/fforj/fforj/fork) on GitHub.
2. Clone your fork locally:
   ```sh
   git clone https://github.com/<your-username>/fforj.git
   cd fforj
   ```
3. Verify the build passes:
   ```sh
   ./gradlew test
   ```

### Optional Developer Experience (DX) tools

The project includes configurations to streamline development, including `mise.toml` for runtime version management and `apm.yml` for AI-assistant harness contexts (OpenCode, Claude Code, Copilot, etc.).

```sh
brew install mise                       # Install the runtime version manager
apm install --update && apm compile     # Setup AI harness targets defined in apm.yml
```

The Agent Package Manager (APM) integration ensures contributors get project-specific context injected directly into their preferred AI assistant and LLM.

## Workflow

1. Create a branch from `main`. Use the format: `feat/<issue-number>-<slug>`, `fix/<issue-number>-<slug>`, or `chore/<description>`.
2. Make focused, logical changes.
3. Run the full validation gate before pushing: `./gradlew check`.
4. Open a Pull Request against `main`.

### Commit style

We use [Conventional Commits](https://www.conventionalcommits.org/). Your commit messages and your Pull Request title must start with one of the following prefixes: `feat:`, `fix:`, `chore:`, `docs:`, `test:`, or `refactor:`.

## Code style

- **Modern Java:** Java 21+ strict. Use records, sealed types, switch patterns, and `var` for locals.
- **Structure:** One public top-level type per file. Inner records/classes for sealed cases are permitted.
- **Documentation:** Public types and methods must have Javadoc containing at least one short paragraph explaining intent.
- **No magic:** Do not use `@Generated`, Lombok, `@Value`, annotation processors, code generation, reflection in hot paths, service loaders, or `Unsafe`.
- **Null safety:** No `null` returns from public methods. Use `Optional` for absence and `Result` for failure.
- **Naming:** Constants use `SCREAMING_SNAKE_CASE` only when truly constant; otherwise use `camelCase`.
- **Formatting:** Line length is soft-capped at 100 columns. Imports must be explicit (never `*`).

## Testing

- **Framework:** JUnit Jupiter. Map one `*Test.java` file per source `*.java` file.
- **Naming conventions:** Test method names should describe behavior (e.g., `zip_accumulates_errors_from_both_sides`), not mirror the method being tested (e.g., `testZip`).
- **Testing philosophy:** Test the public contract, not the internal implementation. Do not assert internal state using reflection.
- **Concurrency:** Concurrency tests must be deterministic. Use controllable delays rather than arbitrary `Thread.sleep(100)`.
- **Property-based testing:** Welcome (e.g., jqwik), provided it does not leak into the production dependency graph.
- **Mocking:** Do not use mocking frameworks. Sums and records are simple enough to instantiate directly; if a test requires mocking, it is likely testing the wrong thing.

## Dependencies

- **Production code:** Zero dependencies. The library uses `java.base` and nothing else.
- **Test code:** JUnit Jupiter. No assertion libraries beyond `org.junit.jupiter.api.Assertions`. No mocking frameworks.
- Adding any new dependency (runtime, test, or build) requires an Architecture Decision Record (an entry in `decisions.md` submitted alongside the PR).

## Architecture principles

- **Pattern matching is the API.** Every sum type is a `sealed interface` with `record` cases. Combinators (`map`, `flatMap`, etc.) exist as `default` methods, but exhaustive `switch` statements remain the primary interface.
- **Defensive copies.** Any record holding a `List`, `Map`, or array must use `List.copyOf()` (or equivalent) in its compact constructor.
- **No preview features.** Preview-dependent code belongs on `poc/*` branches until the relevant API is finalized in the JDK.