# Contributing to fforj

Thank you for your interest. fforj is a deliberately small library — the bar for
new types is high (see [decisions.md](decisions.md) for the rationale), but fixes,
polish, docs improvements, and test coverage are always welcome.

## Scope

fforj has **four public types** (`Result`, `Validated`, `NonEmptyList`, `Retry`)
and a standing reservation for a fifth (`Scopes`, shelved on `poc/scopes-jep505`
until JEP 505 finalizes). Adding any other type requires an ADR. The library
**will not grow** into Vavr.

Before investing time in a feature, open an issue to discuss it. Unsolicited PRs
that add new public types, pull in dependencies, or relitigate locked decisions
in [CLAUDE.md](CLAUDE.md) will be declined.

## Getting started

- Java 21 or newer (the jar is compiled with `--release 21`)
- Gradle 9.5+ (wrapper checked in)

1. [Fork the repo](https://github.com/fforj/fforj/fork) on GitHub.
2. Clone your fork:
   ```sh
   git clone https://github.com/<your-username>/fforj.git
   cd fforj
   ```
3. Verify everything builds:
   ```sh
   ./gradlew test
   ```

### Tools for increased Developer Experience

The project includes a `mise.toml` for managing tools versions such as apm, and an
`apm.yml` for AI-assistant harness configs (opencode, Claude Code, Copilot, etc).

```sh
brew install mise                       # runtime version manager
apm install --update && apm compile     # AI harness for the targets in apm.yml (opencode, clause or copilot).
```

The Agent Package Manager allow contributor to use their preferred AI assistant (opencode, claude, etc), and LLM.

## Finding something to work on

- Issues tagged `good first issue` are small, self-contained starting points.
- Issues tagged `help wanted` are agreed-upon but unstaffed.
- If you find a bug, open an issue before sending a PR.

## Workflow

1. Fork the repo and create a branch from `main`.
2. Branch naming: `chore/<issue_number_or_description`, `feat/<issue-number>-<short-slug>` or `fix/<issue-number>-<short-slug>`.
3. Make your changes. Keep commits small and focused.
4. Run the full gate: `./gradlew check`.
5. Open a PR against `main`.

### Commit style

Conventional commits: `feat:`, `fix:`, `chore:`, `docs:`, `test:`, `refactor:`.

PR titles should follow the same convention.

## Code style

- Java 21+ strict. Records, sealed types, switch patterns, `var` for locals.
- One public top-level type per file. Inner records/classes for sealed cases are fine.
- Public types and methods have Javadoc with at least one short paragraph on intent.
- No `@Generated`, no Lombok, no `@Value`.
- No `null` returns from public methods. Use `Optional` for absence, `Result` for failure.
- Constants use `SCREAMING_SNAKE_CASE` only when truly constant; otherwise `camelCase`.
- Imports: explicit, never `*`.
- Line length 100 cols (soft).
- No annotation processors, code generation, reflection in hot paths, service
  loaders, or `Unsafe`.

## Testing

- JUnit Jupiter. One `*Test.java` per source `*.java`.
- Test method names describe behavior (`zip_accumulates_errors_from_both_sides`),
  not method names (`testZip`).
- Test the contract, not the implementation. Don't assert internal state through
  reflection.
- Property-based tests (jqwik) are welcome but must not become a runtime dependency.
- Concurrency tests must be deterministic. Use controllable delays, not arbitrary
  `Thread.sleep(100)`.
- No mocking framework. Sums and records are too simple to need mocks; if a test
  needs mocking it's testing the wrong thing.

## Dependencies

- **Production**: zero. The library is `java.base` + nothing.
- **Test**: JUnit Jupiter only. No assertion library beyond
  `org.junit.jupiter.api.Assertions`. No mocking framework.
- Adding any new dep — runtime, test, or build — requires an ADR (pull request +
  entry in `decisions.md`).

## Architecture principles

- **Pattern matching is the API.** Every sum type is a `sealed interface` with
  `record` cases. Combinators (`map`, `flatMap`, etc.) exist as `default` methods
  but are not the primary interface — exhaustive `switch` is.
- **Defensive copies.** Any record holding a `List`/`Map`/array must `List.copyOf()`
  in its compact constructor.
- **No preview features.** Preview-dependent code lives on `poc/*` branches until
  the API finalizes.
