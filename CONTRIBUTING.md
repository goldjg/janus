# Contributing to JANUS

Thank you for your interest in contributing to JANUS! This guide will help you set up your development environment, understand the codebase, and submit high-quality contributions.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Development Setup](#development-setup)
- [Repository Layout](#repository-layout)
- [Building and Testing](#building-and-testing)
- [Coding Standards](#coding-standards)
- [Contribution Workflow](#contribution-workflow)
- [Security Review Checklist](#security-review-checklist)
- [Non-Negotiable Rules](#non-negotiable-rules)

## Code of Conduct

We are committed to providing a welcoming and inclusive environment. Be respectful, constructive, and professional in all interactions. Harassment, discrimination, and abusive behavior will not be tolerated.

## Development Setup

### Prerequisites

Install the following tools:

- **Java Development Kit (JDK) 17** (Temurin, Zulu, or Oracle)
  ```bash
  java -version  # Must show 17.x
  ```

- **Apache Maven 3.9+**
  ```bash
  mvn -version  # Must show 3.9.x or later
  ```

- **Docker with buildx** (for building container images)
  ```bash
  docker version
  docker buildx version
  ```

- **PowerShell 7.4+** (for bootstrap scripts)
  ```bash
  pwsh -Version  # Must show 7.4.x or later
  ```

- **Azure CLI 2.60+** (for deployment)
  ```bash
  az version  # Must show 2.60.x or later
  ```

- **Git** (with commit signing recommended)
  ```bash
  git --version
  ```

### Clone the Repository

```bash
git clone https://github.com/your-org/janus.git
cd janus
```

### IDE Setup

**IntelliJ IDEA (recommended):**

1. Open the repository root in IntelliJ
2. IntelliJ will auto-detect the Maven projects
3. Enable annotation processing: Settings → Build → Compiler → Annotation Processors → Enable
4. Install plugins: Lombok (if used), SonarLint (recommended)

**Visual Studio Code:**

1. Install extensions: Extension Pack for Java, Maven for Java
2. Open the repository root
3. VSCode will detect `pom.xml` files automatically

### Environment Configuration

JANUS requires an Entra tenant and a deployed MCP gateway for end-to-end testing. For local development:

1. **Create a `.env` file** (do not commit this):
   ```bash
   JANUS_TENANT_ID=your-tenant-id
   JANUS_MANAGED_IDENTITY_CLIENT_ID=local-dev  # Placeholder
   JANUS_GATEWAY_APP_ID=your-gateway-app-id
   JANUS_GATEWAY_SCOPE_ID=your-scope-id
   JANUS_GATEWAY_SCOPE_NAME=mcp.invoke
   JANUS_ALLOWED_REDIRECT_URIS=http://127.0.0.1:*,http://[::1]:*
   JANUS_ALLOW_LOOPBACK=true
   JANUS_DRY_RUN=true
   ```

2. **Local testing without managed identity:**
   - Unit tests mock Graph API calls (no real Entra access needed)
   - Integration tests require real Entra credentials (use `az login` + service principal for CI)

## Repository Layout

```
janus/
├── .github/
│   └── workflows/
│       ├── ci.yml                    # Continuous integration (build, test, scan)
│       └── deploy.yml                # Deployment pipeline (manual dispatch + merge-to-main)
│
├── bootstrap/
│   └── bootstrap.ps1                 # PowerShell script to bootstrap Azure resources
│
├── cleanup/                          # Cleanup job (Container Apps Job)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       └── main/
│           └── java/io/janus/cleanup/
│               ├── CleanupJob.java           # Main entry point
│               ├── LifecycleDecision.java    # Decision enum and logic
│               ├── GraphClient.java          # Microsoft Graph REST client
│               ├── SignInActivityService.java
│               └── StructuredLogger.java
│
├── docs/                             # Documentation (YOU ARE HERE)
│   ├── architecture.md
│   ├── cimd-roadmap.md
│   ├── dcr-flow.md
│   ├── deployment.md
│   ├── entra-permissions.md
│   ├── lifecycle.md
│   ├── security-model.md
│   └── threat-model.md
│
├── examples/
│   └── mcp/
│       ├── README.md                 # Example MCP client registration flows
│       └── test-dcr.sh               # curl-based DCR test script
│
├── infra/                            # Azure infrastructure (Bicep)
│   ├── main.bicep                    # Main template
│   ├── main.bicepparam               # Parameters file
│   └── modules/
│       ├── container-apps.bicep      # Container Apps environment, apps, jobs
│       ├── container-registry.bicep  # Azure Container Registry
│       ├── identity.bicep            # User-assigned managed identity
│       ├── log-analytics.bicep       # Log Analytics workspace
│       └── role-assignments.bicep    # Graph app role assignments
│
├── janus/                            # JANUS broker (Keycloak + extensions)
│   ├── Dockerfile                    # Multi-stage build (Maven + Keycloak)
│   ├── keycloak/
│   │   └── keycloak.conf             # Keycloak configuration
│   └── extensions/                   # Keycloak extension module
│       ├── pom.xml
│       └── src/
│           └── main/
│               ├── java/io/janus/keycloak/
│               │   ├── provider/
│               │   │   ├── JanusDcrRegistrationProvider.java
│               │   │   ├── JanusDcrRegistrationProviderFactory.java
│               │   │   ├── JanusMetadataResourceProvider.java
│               │   │   └── JanusMetadataResourceProviderFactory.java
│               │   ├── policy/
│               │   │   ├── RegistrationPolicy.java
│               │   │   ├── RedirectUriPolicy.java
│               │   │   └── ScopePolicy.java
│               │   ├── jwt/
│               │   │   ├── SoftwareStatementValidator.java
│               │   │   └── JwksProvider.java
│               │   ├── graph/
│               │   │   ├── EntraAppRegistrationService.java
│               │   │   ├── GraphHttpClient.java
│               │   │   ├── ManagedIdentityTokenProvider.java
│               │   │   └── models/          # Graph request/response POJOs
│               │   ├── log/
│               │   │   ├── StructuredLogger.java
│               │   │   └── LogRedaction.java
│               │   └── util/
│               │       ├── CorrelationIdGenerator.java
│               │       └── JsonParser.java
│               └── resources/
│                   └── META-INF/services/
│                       ├── org.keycloak.services.clientregistration.ClientRegistrationProviderFactory
│                       └── org.keycloak.services.resource.RealmResourceProviderFactory
│
├── tests/
│   ├── unit/                         # JUnit 5 unit tests
│   │   └── src/test/java/io/janus/
│   ├── integration/                  # Integration tests (require real Entra tenant)
│   │   └── src/test/java/io/janus/
│   └── security/                     # Security-focused tests
│       ├── policy-bypass-tests/      # Fuzzing, boundary tests
│       ├── jwt-attacks/              # software_statement attack vectors
│       └── graph-client-tests/       # Token handling, SSRF prevention
│
├── .gitignore
├── CONTRIBUTING.md                   # This file
├── LICENSE                           # Apache 2.0
├── README.md                         # Project overview (DO NOT EDIT - owned by another agent)
├── SECURITY.md                       # Security policy
└── pom.xml                           # Parent POM (reactor build)
```

## Building and Testing

### Build Everything

From the repository root:

```bash
mvn -B clean verify
```

This will:
1. Compile all Java modules (`janus/extensions` and `cleanup`)
2. Run unit tests
3. Run static analysis (SpotBugs, OWASP Dependency-Check)
4. Package JARs

**Build output:**
- `janus/extensions/target/janus-keycloak-extensions-<version>.jar`
- `cleanup/target/janus-cleanup-<version>.jar`

### Run Unit Tests Only

```bash
mvn -B test
```

### Run Integration Tests

Integration tests require:
- A real Entra tenant
- A service principal with `Application.ReadWrite.OwnedBy`
- A test gateway app registration

Set environment variables:
```bash
export JANUS_TENANT_ID=...
export JANUS_TEST_CLIENT_ID=...
export JANUS_TEST_CLIENT_SECRET=...
export JANUS_GATEWAY_APP_ID=...
```

Run:
```bash
mvn -B verify -Pintegration-tests
```

### Build Docker Images

**JANUS broker:**

```bash
cd janus
docker buildx build --platform linux/amd64 -t janus:local .
```

**Cleanup job:**

```bash
cd cleanup
docker buildx build --platform linux/amd64 -t janus-cleanup:local .
```

### Run Locally with Docker Compose

(Requires `docker-compose.yml` in repo root — not shown here)

```bash
docker compose up
```

Access:
- JANUS broker: `http://localhost:8080/realms/janus/clients-registrations/janus-dcr`
- Keycloak admin: `http://localhost:8080/admin` (if admin console enabled)

### Linting and Formatting

We use Maven Checkstyle:

```bash
mvn checkstyle:check
```

Auto-format code:
```bash
mvn fmt:format  # If using spotify/fmt-maven-plugin
```

### Security Scanning

**Dependency vulnerabilities:**

```bash
mvn org.owasp:dependency-check-maven:check
```

**Container image scanning:**

```bash
docker run --rm aquasec/trivy:latest image janus:local
```

**Secret scanning (before commit):**

```bash
# This is enforced by pre-commit hook
runtime-tools-secret_scanning --paths src/
```

## Coding Standards

### General Principles

1. **Simplicity over cleverness:** Prefer straightforward code to complex abstractions
2. **Fail securely:** Deny-by-default; errors should reject the request
3. **No silent failures:** Log all errors with structured context
4. **Minimal dependencies:** Every new dependency increases attack surface

### Java Coding Style

- **Package naming:** `io.janus.<module>.<component>`
- **Indentation:** 4 spaces (no tabs)
- **Line length:** 120 characters max
- **Braces:** Always use braces, even for single-line blocks
- **Naming conventions:**
  - Classes: `PascalCase`
  - Methods/fields: `camelCase`
  - Constants: `UPPER_SNAKE_CASE`
  - Packages: `lowercase`

### Documentation Requirements

#### JavaDoc on All Public Types and Methods

Every public class, interface, method, and constant must have JavaDoc:

```java
/**
 * Validates redirect URIs against the configured allowlist policy.
 * <p>
 * This class enforces RFC 8252 loopback rules, HTTPS requirements for non-loopback,
 * and strict custom URI scheme validation.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8252.html#section-7.3">RFC 8252 §7.3</a>
 */
public class RedirectUriPolicy {
    /**
     * Validates a single redirect URI.
     *
     * @param uri the URI to validate (must not be null)
     * @return ValidationResult indicating success or specific failure reason
     * @throws IllegalArgumentException if uri is null
     */
    public ValidationResult validate(URI uri) {
        // ...
    }
}
```

#### Security Invariant Comments

Use the `// JANUS SECURITY INVARIANT:` comment convention to mark code that enforces a security property:

```java
// JANUS SECURITY INVARIANT: Reject all HTTP redirect URIs on non-loopback hosts.
// Rationale: HTTP is unencrypted and vulnerable to man-in-the-middle attacks that could
// intercept authorization codes. This check prevents downgrade attacks where an attacker
// tricks a client into using HTTP.
if ("http".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
    return ValidationResult.reject("HTTP redirect URIs are not allowed on non-loopback hosts");
}
```

These comments:
- Make security properties explicit and auditable
- Explain the *why*, not just the *what*
- Help reviewers verify the implementation matches the threat model
- Are searchable (use `git grep "SECURITY INVARIANT"`)

#### Structured Logging Requirement

All log statements must use `StructuredLogger` and include:

```java
logger.info()
    .operation("dcr.register")
    .correlationId(correlationId)
    .outcome("denied")
    .field("errorCode", "invalid_redirect_uri")
    .field("rejectedUri", sanitize(uri))  // Never log raw attacker input
    .message("Redirect URI validation failed")
    .log();
```

**Log levels:**
- `ERROR`: Unexpected failures (Graph API errors, internal bugs)
- `WARN`: Policy violations, suspicious patterns
- `INFO`: Successful registrations, lifecycle events
- `DEBUG`: Detailed request/response data (disabled in production)

**Never log:**
- ****** (Graph tokens, gateway tokens)
- Client secrets (JANUS doesn't have any, but enforce this convention)
- Raw `software_statement` JWTs (log the `iss` and `sub`, not the full token)
- Full request bodies (log sanitized excerpts only)
- Personally identifiable information (PII) like email addresses (log hashed values)

### Error Handling

**Always return RFC 7591 error responses:**

```java
// Good
return Rfc7591ErrorResponse.invalidClientMetadata(
    "client_name must not exceed 120 characters"
);

// Bad
throw new RuntimeException("Name too long");  // Leaks internal details
```

**Never echo attacker input unescaped:**

```java
// Bad
return Rfc7591ErrorResponse.invalidRedirectUri(
    "Invalid URI: " + requestedUri  // XSS if logged to HTML dashboard
);

// Good
return Rfc7591ErrorResponse.invalidRedirectUri(
    "Redirect URI does not match allowlist policy"
);
```

### Testing Requirements

Every policy decision must have tests:

```java
@Test
void rejectHttpOnNonLoopback() {
    var policy = new RedirectUriPolicy(config);
    var result = policy.validate(URI.create("http://example.com/callback"));
    
    assertThat(result.isRejected()).isTrue();
    assertThat(result.getReason()).contains("HTTP");
}

@Test
void allowHttpOnLoopbackIPv4() {
    var config = new Config(allowLoopback = true);
    var policy = new RedirectUriPolicy(config);
    var result = policy.validate(URI.create("http://127.0.0.1:8080/callback"));
    
    assertThat(result.isAccepted()).isTrue();
}
```

**Test coverage goals:**
- Policy classes: 100% branch coverage
- Graph client: 90%+ (mock Graph API responses)
- Validators: 100% (including boundary cases)

**Security test categories:**

1. **Boundary tests:** Max lengths, empty strings, null, integer overflow
2. **Injection tests:** CRLF in `client_name`, SQL in metadata, XSS payloads
3. **Type confusion:** Sending objects where strings expected, nested arrays
4. **Algorithm attacks:** JWT `alg: none`, RS256→HS256 confusion, expired tokens
5. **Policy bypass:** Trying to sneak through disallowed URIs, scopes, grant types

### Dependency Management

**Adding a new dependency:**

1. Check if it's truly necessary (can we implement it ourselves in 50 lines?)
2. Verify the Maven coordinate and publisher on Maven Central
3. Check for known CVEs: `mvn org.owasp:dependency-check-maven:check`
4. Run GitHub Advisory Database check: `runtime-tools-gh-advisory-database`
5. Check the license (must be Apache 2.0, MIT, BSD, or compatible)
6. Add a comment explaining why it's needed:

```xml
<dependency>
    <!-- Required for RS256/PS256 JWT signature verification in software_statement.
         Keycloak includes an older version; we shade this to avoid conflicts. -->
    <groupId>com.nimbusds</groupId>
    <artifactId>nimbus-jose-jwt</artifactId>
    <version>9.37.3</version>
</dependency>
```

**Forbidden dependencies:**
- Any library that requires netty/reactor in the Keycloak extension (classloader conflict risk)
- Libraries with known unpatched CVEs
- Libraries under restrictive licenses (GPL, AGPL, proprietary)

## Contribution Workflow

### 1. Create an Issue

Before writing code, open a GitHub issue describing:
- The problem you're solving
- Your proposed approach
- Any security implications
- Whether this is a breaking change

For **security vulnerabilities**, do NOT open a public issue — see [SECURITY.md](../SECURITY.md).

### 2. Fork and Branch

```bash
# Fork the repo on GitHub, then:
git clone https://github.com/YOUR-USERNAME/janus.git
cd janus
git remote add upstream https://github.com/original-org/janus.git

# Create a feature branch
git checkout -b feature/your-feature-name
```

**Branch naming conventions:**
- `feature/` for new features
- `fix/` for bug fixes
- `docs/` for documentation only
- `chore/` for build/tooling changes

### 3. Make Your Changes

- **Small, focused commits:** One logical change per commit
- **Conventional Commits:** Use the format:
  ```
  type(scope): description
  
  Body (optional)
  
  Footer (optional)
  ```
  
  **Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `chore`, `security`
  
  **Example:**
  ```
  security(policy): reject HTTP redirect URIs on non-loopback hosts
  
  Adds validation to RedirectUriPolicy to enforce that HTTP redirect URIs
  are only allowed on loopback addresses (127.0.0.1, [::1]). Non-loopback
  HTTP URIs are now rejected with an invalid_redirect_uri error.
  
  Closes #42
  ```

- **Sign your commits (DCO):**
  ```bash
  git commit -s -m "feat(cleanup): add sign-in activity fallback logic"
  ```
  
  This adds:
  ```
  Signed-off-by: Your Name <your.email@example.com>
  ```
  
  Signing certifies you have the right to submit the code under the project's license (Apache 2.0).

### 4. Write Tests

All changes must include tests:

```bash
# Unit tests
mvn test

# Integration tests (if applicable)
mvn verify -Pintegration-tests

# Security tests (if policy/validation changed)
cd tests/security && mvn test
```

### 5. Run Pre-Commit Checks

Before committing:

```bash
# Format code
mvn fmt:format

# Lint
mvn checkstyle:check

# Security scan
runtime-tools-secret_scanning --paths src/

# Build and test
mvn clean verify
```

### 6. Push and Open a Pull Request

```bash
git push origin feature/your-feature-name
```

Open a PR on GitHub with:
- **Title:** Conventional Commit format (`feat: ...`, `fix: ...`)
- **Description:**
  - What problem does this solve?
  - What changes did you make?
  - How did you test it?
  - Any security implications?
  - Link to the issue (`Closes #123`)

### 7. Code Review

Expect feedback! Reviewers will check:

- Does this align with JANUS's security model?
- Is the code clear and maintainable?
- Are tests comprehensive?
- Are there any security risks?
- Does documentation need updating?

**Address feedback:**

```bash
# Make changes
git add .
git commit -s -m "fix: address review feedback"
git push origin feature/your-feature-name
```

### 8. Merge

Once approved and CI passes, a maintainer will merge your PR. We use **squash and merge** to keep history clean.

## Security Review Checklist

All PRs must pass this checklist (reviewers: copy this into your review):

- [ ] **No secrets committed** (checked by secret scanning)
- [ ] **Dependencies scanned** (OWASP Dependency-Check passed)
- [ ] **Input validation added/reviewed** for all new user-supplied data
- [ ] **Error messages do not leak internal details** or echo raw attacker input
- [ ] **Structured logging used** with appropriate redaction
- [ ] **Security invariant comments** added for policy/validation code
- [ ] **Tests cover security properties** (policy bypass attempts, injection, boundary cases)
- [ ] **JavaDoc complete** for all public APIs
- [ ] **No new Graph permissions** requested (or justified in PR description)
- [ ] **Does not touch the token path** (JANUS remains on the registration plane)
- [ ] **Threat model updated** if this adds new attack surface
- [ ] **Documentation updated** (especially `docs/architecture.md`, `docs/deployment.md`)

**Special attention for:**

- [ ] Changes to `RegistrationPolicy`, `RedirectUriPolicy`, `ScopePolicy`
- [ ] Changes to `SoftwareStatementValidator` (JWT validation)
- [ ] Changes to `GraphHttpClient`, `ManagedIdentityTokenProvider` (token handling)
- [ ] Changes to `EntraAppRegistrationService` (app creation logic)
- [ ] Changes to `CleanupJob` (deletion logic)
- [ ] Changes to Bicep templates (infrastructure security)

## Non-Negotiable Rules

The following are **absolute requirements** and will result in immediate PR rejection:

### 1. JANUS Never Enters the Token Path

**Forbidden changes:**

- ❌ Adding endpoints to issue tokens
- ❌ Proxying token requests to Entra ID
- ❌ Caching or storing gateway access tokens
- ❌ Implementing token exchange (`urn:ietf:params:oauth:grant-type:token-exchange`)
- ❌ Implementing on-behalf-of (OBO) flows
- ❌ Validating or inspecting gateway access tokens
- ❌ Adding a token introspection endpoint

**Rationale:** JANUS's security model depends on never handling gateway tokens. Entering the token path would:
- Make JANUS a high-value target for attackers
- Require JANUS to implement token validation, replay protection, revocation
- Negate the "registration plane only" isolation
- Break the security invariant

If your use case requires token handling, **this is not the right project**.

### 2. JANUS Never Creates Confidential Clients

**Forbidden changes:**

- ❌ Creating apps with `client_secret` (password credentials)
- ❌ Creating apps with `keyCredentials` (certificate credentials)
- ❌ Returning `client_secret` in the RFC 7591 response
- ❌ Storing secrets in Azure Key Vault or any other vault

**Rationale:** Secrets can be stolen, leaked, or phished. Public clients with PKCE are cryptographically secure and have no secret to leak.

### 3. All Inputs Are Untrusted

**Required practices:**

- ✅ Validate all request fields (type, length, character set)
- ✅ Sanitize before logging or echoing in errors
- ✅ Reject unknown/extra fields (closed-world parser)
- ✅ Enforce size limits (request body, arrays, strings)
- ✅ Fail securely (default deny)

**Forbidden:**

- ❌ Trusting client-supplied `software_id` without validation
- ❌ Fetching remote resources based on user input (`logo_uri`, `jwks_uri`)
- ❌ Eval-ing, parsing, or executing code from metadata

### 4. Graph Permissions Are Minimal

**Current permissions:**

- JANUS broker: `Application.ReadWrite.OwnedBy`
- Cleanup job: `Application.ReadWrite.OwnedBy` + `AuditLog.Read.All`

**Forbidden permissions:**

- ❌ `Application.ReadWrite.All` (too broad)
- ❌ `AppRoleAssignment.ReadWrite.All` (privilege escalation risk)
- ❌ `Directory.ReadWrite.All` (way too broad)
- ❌ `RoleManagement.ReadWrite.Directory` (could grant Global Admin)

Any PR requesting broader permissions must provide:
- Detailed justification
- Threat model analysis
- Alternative approaches considered
- Sign-off from two maintainers

### 5. Deletion Is Conservative

**Cleanup job requirements:**

- ✅ Dry-run mode is the default (`JANUS_CLEANUP_DRY_RUN=true`)
- ✅ Grace period protects new apps (24 hours min)
- ✅ Unused window is generous (30 days default)
- ✅ Max deletions per run (50 default)
- ✅ Audit log for every deletion decision

**Forbidden:**

- ❌ Reducing grace period below 1 hour
- ❌ Removing the deletion limit
- ❌ Deleting apps without the `janus-managed` tag

### 6. No Backdoors or Escape Hatches

**Forbidden:**

- ❌ Environment variables to bypass policy validation
- ❌ "Admin mode" that skips security checks
- ❌ Special-casing specific `client_name` values
- ❌ Hardcoded credentials or API keys

If a policy is too restrictive, fix the policy — don't add an escape hatch.

## Getting Help

- **Questions about the codebase:** Open a GitHub Discussion
- **Bug reports:** Open a GitHub Issue
- **Security vulnerabilities:** See [SECURITY.md](../SECURITY.md)
- **Design discussions:** Open a GitHub Issue with the `design` label

## Recognition

Contributors will be acknowledged in:
- Release notes
- `CONTRIBUTORS.md` (if we create one)
- Git history (via signed commits)

Significant contributions (new features, major security improvements) will be highlighted in blog posts and presentations.

---

Thank you for contributing to JANUS! Your work helps make MCP client onboarding secure and seamless for Entra-protected estates.
