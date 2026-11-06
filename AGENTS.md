# Project instructions

This Java 21 / Spring Boot service prioritizes readability over compactness.
Apply these conventions to production code, tests, and build configuration.

## Responsibilities and decomposition

- Give each class one cohesive responsibility and each method one clear purpose.
- Keep controllers focused on HTTP input, validation, and response construction.
  Services coordinate application workflows and own transaction boundaries.
  Repositories own SQL, row mapping, and persistence operations.
- Keep token generation in `TokenIssuer`, provider payload encoding and parsing
  in `OpenAiPayload`, and HTTP transport in `OpenAiProvider`.
- Keep request-body admission, asynchronous upload lifecycle, and buffered
  request replay separate, following the existing filter and upload classes.
- Use these boundaries where they clarify ownership; do not create an interface
  or a new class for every small operation.
- Make methods short enough to read as a sequence of well-named steps. Extract
  distinct phases of long methods and keep each method at one abstraction level.
- Prefer meaningful local variables, descriptive names, and guard clauses over
  nested expressions, deep branching, and repeated chains.
- Keep related operations together. Avoid excessive pass-through helpers and
  fragmentation that forces readers to jump between methods without benefit.
  Use judgment rather than an arbitrary method-line limit.

## Formatting

- Use four spaces for Java indentation and keep Java lines at most 110 characters.
- Wrap long signatures, constructor calls, annotations, expressions, and chains.
  When a call or chain needs several lines, put arguments or chained operations
  on separate lines and indent continuations consistently.
- Always use braces for conditionals and loops, including single statements.
- Put method and field annotations on their own lines. Keep declarations and
  executable statements separate; do not compress several statements onto a line.
- Use explicit imports and consistent import grouping; remove unused imports.
- Use whitespace to separate logical phases, without adding empty lines between
  every statement or explanatory comments that repeat the code.
- Format XML and configuration as readable blocks rather than compressed lines.

## SQL, payloads, and literals

- Prefer indented Java text blocks for multiline SQL. Wrap clauses and column
  lists so statements and parameter ordering can be checked together.
- Build structured JSON from named components instead of deeply nested maps or
  long escaped strings. Keep the resulting wire format and validation unchanged.
- Preserve literal values, error messages, prompt text, and meaningful whitespace
  when wrapping or moving them. Source formatting must not change runtime text.

## Refactoring safeguards

- Preserve unrelated user changes and keep edits within the requested scope.
- Treat style refactors as behavior-preserving unless behavior changes are
  explicitly requested. Preserve API contracts, status codes, error handling,
  authorization, token claims, prompt content, and provider request semantics.
- Preserve transaction propagation, rollback rules, and database lock ordering.
  In particular, retain refresh replay revocation commits, authentication limiter
  transaction boundaries, and global-before-account quota locking.
- Preserve asynchronous body deadlines and admission-slot release timing.
  Upload completion and full request completion have different responsibilities.
- Do not edit already applied Liquibase changesets. Schema changes belong in new
  changesets and require their own task scope.

## Tests and validation

- Apply the same readability rules to tests. Extract named fixtures and support
  helpers for setup, transport, and repeated mechanics; keep scenario intent and
  important assertions visible in the test.
- Preserve existing scenarios and assertions when reorganizing tests. Avoid tests
  that merely mirror implementation details or verify formatting.
- Use the Maven Wrapper with JDK 21. Run focused tests for scoped changes and the
  full `verify` suite for changes that span shared behavior or class boundaries:
  `./mvnw verify` or `.\mvnw.cmd verify`.
- Docker must be available for PostgreSQL Testcontainers integration tests.
  Follow the README for process-local Java and Maven environment overrides.
- Use the existing local HTTP provider stand-in for adapter tests; validation
  must not make paid provider calls. Report checks that could not run and why.
- Follow the developer-review pipeline by default: implement and validate, obtain
  a read-only reviewer pass, fix actionable findings, and repeat validation and
  review until no actionable findings remain.
