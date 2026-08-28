<!-- version: 1.0.0 -->
# Java Language Pack

Use this guidance when working with Java code.

## Core approach

Prefer clear, idiomatic, standard-library-first Java.

Follow existing project conventions. When project conventions conflict with this guidance, prefer the project convention for style or packaging and note the deviation in the final response; for safety or security guidance, follow this document.

Do not introduce frameworks or dependencies for small tasks.

Use explicit types at all public boundaries. Rely on `var` only for obvious local values where the inferred type is immediately apparent.

Prefer boring, readable code that can be reviewed and audited quickly. Avoid clever abstractions.

Target **Java 17 LTS** unless the project's `pom.xml` or build configuration specifies otherwise.

Match the project's configured build tool (`mvn`, `gradle`). Do not introduce a new build tool unless asked.

## Maven conventions

Use Maven as the default build tool for this project.

Place source files under `src/main/java/<package>/` and test files under `src/test/java/<package>/`.

Keep `pom.xml` minimal. Group properties in `<properties>`, declare `<dependencyManagement>` for version alignment across multi-module projects.

Pin dependency versions explicitly in `pom.xml`. Do not rely on version ranges or floating LATEST/RELEASE.

Use the `maven-compiler-plugin` with `<source>` and `<target>` (or `<release>`) set to the project's Java version.

Do not add plugins for tasks that the project has not opted in to.

## Standard library preference

For self-contained features expected to be under about 300 LOC, prefer the Java standard library. If the implementation grows beyond about 400 LOC, or requires complex protocol handling, assess existing project dependencies before adding new ones.

Good standard-library candidates include:

- file handling with `java.nio.file`
- JSON with the project's existing library (Jackson, Gson); do not add a second JSON library
- HTTP clients with `java.net.http.HttpClient` (Java 11+) unless the project already uses another
- logging with SLF4J + the project's configured backend (Logback, Log4j 2)
- argument parsing with a library already in the project
- date/time with `java.time`
- collections with `java.util`
- cryptography with `javax.crypto` / `java.security`; never roll bespoke crypto

Do not implement complex cryptography, JWT parsing, or OAuth protocol logic manually. Use well-maintained libraries already present in the project or listed in the approved dependency set.

## Keycloak SPI conventions

This project extends Keycloak via the Service Provider Interface (SPI).

Follow these conventions when writing Keycloak extension code:

- Implement the correct SPI interface (`ClientRegistrationProvider`, `ClientRegistrationProviderFactory`, etc.). Do not subclass Keycloak internal classes unless the SPI requires it.
- Register provider factories via `META-INF/services/<interface-FQN>` files on the classpath.
- Keep provider classes focused on a single responsibility. Delegate Graph calls, policy evaluation, and logging to separate collaborator classes.
- Use Keycloak's `KeycloakSession` and `RealmModel` for session and realm context. Do not hold references across requests.
- Honour Keycloak's `getId()` contract: return a stable, unique string identifier for each factory.
- Implement `close()` on providers that hold resources.
- Do not use Keycloak internals (packages under `org.keycloak.services.managers.*` or similar) without documenting the reason and risk.

When Keycloak's built-in capability can safely replace bespoke code, use Keycloak.

Do not make Keycloak the issuer of the MCP gateway access token. The JANUS security invariant applies regardless of Keycloak's capabilities.

## JANUS security invariant

The following invariant is non-negotiable and must be respected in all Java code in this project:

> **No JANUS component may issue, proxy, cache, exchange, or re-sign the bearer token used to access the protected MCP gateway. Microsoft Entra ID must remain the issuer of the gateway access token.**

When implementing or reviewing Java code, check that:

- No code path returns, caches, or forwards a gateway access token.
- No code path signs a JWT intended for gateway consumption.
- Token validation code validates issuer, audience, signature, and lifetime using a well-maintained library. It does not skip or weaken any of these checks.
- Tokens are not written to logs at any level (see Logging below).

If an implementation choice would violate this invariant, do not implement it.

## Dependencies

Follow the repository dependency discipline.

Prefer latest stable versions without unresolved Critical or High CVEs.

Before adding a dependency, explain:

- why it is needed
- why the standard library or an existing project dependency is not sufficient
- security and maintenance track record
- whether it pulls in a large transitive graph

Add dependencies to `pom.xml` with explicit versions. Do not use version ranges.

Preferred libraries for common concerns in this project:

- **HTTP client (Graph API)**: Azure SDK for Java (`com.azure:azure-identity`, `com.microsoft.graph:microsoft-graph`) — already in scope for Managed Identity and Graph calls.
- **JSON**: Jackson (`com.fasterxml.jackson.core:jackson-databind`).
- **JWT validation**: `com.nimbusds:nimbus-jose-jwt` or the MSAL/Azure SDK validation utilities already present.
- **Logging**: SLF4J API; do not add a second logging framework.
- **Testing**: JUnit 5 (`org.junit.jupiter`), Mockito (`org.mockito:mockito-core`).

## Error handling

Handle exceptions explicitly. Do not swallow exceptions silently.

Use checked exceptions for recoverable conditions at API boundaries. Use unchecked exceptions (`IllegalArgumentException`, `IllegalStateException`) for programming errors.

Wrap low-level exceptions with context:

```java
throw new JanusRegistrationException("redirect URI validation failed for client " + clientName, cause);
```

Do not log-and-continue unless the business logic explicitly requires degraded operation. Document the reason.

Return meaningful error responses to callers (e.g., RFC 7591 error response for DCR failures) rather than exposing internal stack traces.

## Input validation

Treat all inputs from DCR clients as untrusted.

Validate at the earliest entry point. Do not defer validation to deeper layers.

Enforce limits on:

- string length (client name, redirect URI, scope strings)
- collection sizes (number of redirect URIs, grant types, response types)
- allowed character sets (client names)
- allowed URI schemes (only `https` and loopback `http`)

Reject inputs that exceed limits with a clear, specific error message. Do not silently truncate or coerce.

Do not use `String.matches()` with unbounded quantifiers on untrusted input (ReDoS risk). Prefer compiled `Pattern` objects with anchors and bounded quantifiers.

## Logging

Use SLF4J (`org.slf4j.Logger`) throughout. Do not use `System.out`, `System.err`, or `java.util.logging` directly.

Use **structured JSON logging**. Emit log records with consistent fields:

```
correlationId, operation, clientId, appObjectId, policyDecision, outcome, durationMs
```

**Never log**:

- bearer tokens
- authorization codes
- refresh tokens
- client secrets
- full JWT contents (header + payload + signature)
- redirect URIs beyond what is needed for debugging (they may encode sensitive state)

**Always log at appropriate levels**:

- `DEBUG`: detailed flow tracing (correlation ID, step entry/exit)
- `INFO`: significant lifecycle events (registration created, cleanup decision)
- `WARN`: recoverable policy violations, Graph retries
- `ERROR`: unrecoverable failures, unexpected exceptions

Include a correlation ID on every log record in a request context.

## Testing

Use JUnit 5 and Mockito.

Write focused unit tests for:

- registration policy validation (valid and invalid inputs)
- redirect URI allowlist logic
- JWT temporal validation (iat, nbf, exp, clock skew, future tokens, expired tokens)
- Graph client behaviour (mock the Graph HTTP calls)
- Keycloak provider wiring

Follow the arrange-act-assert pattern. Name test methods descriptively:

```java
@Test
void validate_rejectsHttpRedirectUriOnNonLocalhostHost() { ... }
```

Use `@ParameterizedTest` with `@MethodSource` or `@CsvSource` for table-driven cases.

Do not write tests that require a live Entra tenant, live Keycloak, or live Azure subscription unless they are explicitly annotated `@Tag("live-integration")` and documented as opt-in.

Use Mockito to mock external dependencies (Graph client, Keycloak session, realm model).

For policy tests, test boundary conditions: values at, just below, and just above limits.

Aim for meaningful coverage of security-critical paths (input validation, JWT validation, policy enforcement). Do not inflate coverage with trivial getter/setter tests.

## Security

Be careful with:

- **Deserialization**: validate and constrain JSON inputs before deserializing into domain objects. Do not deserialize untrusted data into polymorphic types without explicit type constraints.
- **ReDoS**: use bounded regex patterns. Avoid catastrophic backtracking on untrusted string inputs.
- **Path traversal**: validate any file paths constructed from external input.
- **SSRF**: do not allow DCR client-supplied URIs to be fetched by JANUS. Allowlists only.
- **Log injection**: sanitise untrusted strings before including them in log messages (strip newlines, control characters).
- **Credential handling**: do not pass secrets via environment variables or system properties where they may appear in logs or process listings. Use Azure Managed Identity and the Azure SDK credential chain.
- **TLS**: do not disable TLS validation. Do not accept self-signed certificates in production paths.
- **JWT libraries**: do not implement JWT parsing, signature verification, or claim validation manually. Use a well-maintained library and configure it with explicit algorithm allow-lists, issuer checks, audience checks, and clock skew tolerance.

## Immutability and thread safety

Prefer immutable value objects at API boundaries. Use `final` fields and builder patterns.

Document thread-safety assumptions on classes that may be shared across requests (provider factories are singletons in Keycloak).

Use `Collections.unmodifiableList()` / `List.copyOf()` when returning collections from APIs.

## Code style

Follow standard Java naming conventions:

- `PascalCase` for classes and interfaces
- `camelCase` for methods and fields
- `UPPER_SNAKE_CASE` for constants
- Package names in lower case, no underscores

Keep methods short and focused. Aim for methods that fit in one screen.

Add JavaDoc to all public classes and methods. Include:

- what the method does (not how)
- parameter constraints
- return value semantics
- exceptions thrown and when

Do not add comments that restate what the code already says clearly.

## Final response

When completing Java work, include:

- files changed
- Java version and Maven version assumptions
- dependencies added or avoided, with justification
- tests written and what they cover
- tests not written and why
- any Keycloak SPI registration steps required
- security caveats
- whether the JANUS security invariant is preserved by the change
