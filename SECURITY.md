# Security Policy

## Supported Versions

JANUS follows semantic versioning. Security updates are provided for:

| Version | Supported          |
| ------- | ------------------ |
| 1.x     | :white_check_mark: |
| < 1.0   | :x:                |

We provide security updates for the latest minor version of each major release. Users should upgrade to the latest patch version within their major version series.

## Security Invariant

**CRITICAL SECURITY PROPERTY:**

> **JANUS NEVER issues, proxies, caches, re-signs, inspects-for-reuse, or stores bearer tokens used to access the MCP gateway. Microsoft Entra ID is the sole token issuer and the sole token validator authority. JANUS operates exclusively on the registration plane.**

This invariant is architectural and non-negotiable. Any change that would put JANUS in the token path violates the core security model and will be rejected.

## Reporting a Security Vulnerability

**DO NOT open a public GitHub issue for security vulnerabilities.**

### Responsible Disclosure Process

1. **Report privately** via GitHub's Security Advisory feature:
   - Navigate to the Security tab
   - Click "Report a vulnerability"
   - Provide detailed information about the vulnerability

2. **What to include in your report:**
   - Description of the vulnerability and its potential impact
   - Steps to reproduce or proof-of-concept code
   - Affected versions
   - Any mitigations you've identified
   - Your contact information for follow-up

3. **Response timeline:**
   - **Initial response:** Within 72 hours
   - **Triage and assessment:** Within 7 days
   - **Status updates:** Every 14 days until resolution
   - **Fix development:** Based on severity (Critical: 30 days, High: 60 days, Medium: 90 days)

4. **Coordinated disclosure:**
   - We follow a 90-day coordinated disclosure policy
   - We will work with you to understand and address the issue
   - Public disclosure occurs after a fix is available or 90 days, whichever comes first
   - We will credit you in the security advisory (unless you prefer anonymity)

5. **No bug bounty program:**
   - JANUS is an open-source project without financial backing for bounties
   - We deeply appreciate security research and will publicly acknowledge contributors

### Encryption

If you need to send encrypted information, request our PGP key via the Security Advisory interface.

## Security Scope

### In Scope

Security issues in the following components are in scope for vulnerability reports:

- **JANUS broker container** (`janus/` directory)
  - Keycloak extension code (`janus/extensions/`)
  - Custom SPI providers (`JanusDcrRegistrationProvider`, `JanusMetadataResourceProvider`)
  - Policy validation logic (`RegistrationPolicy`, `RedirectUriPolicy`)
  - Software statement validation (`SoftwareStatementValidator`)
  - Microsoft Graph client (`EntraAppRegistrationService`, `GraphHttpClient`)
  - Managed identity token handling (`ManagedIdentityTokenProvider`)
  - Logging and error handling (`StructuredLogger`)

- **Cleanup job** (`cleanup/` directory)
  - Lifecycle decision logic
  - Microsoft Graph enumeration and deletion
  - Sign-in activity analysis

- **Infrastructure as Code** (`infra/` directory)
  - Bicep templates for Azure resources
  - Default configurations and security settings
  - Network isolation and managed identity setup

- **Bootstrap script** (`bootstrap/bootstrap.ps1`)
  - OIDC federation configuration
  - Permission grants
  - Secret handling

- **Dependencies**
  - Known CVEs in direct dependencies (provide CVE ID)
  - Supply chain concerns (e.g., compromised Maven coordinates)

### Out of Scope

The following are **not** in scope for JANUS security reports:

- **Microsoft Entra ID platform vulnerabilities** → Report to Microsoft Security Response Center (MSRC)
- **Keycloak core vulnerabilities** → Report to the [Keycloak security team](https://www.keycloak.org/security)
- **MCP gateway implementation** → Report to the gateway maintainer
- **MCP client implementations** → Report to the client maintainer
- **Microsoft Graph API vulnerabilities** → Report to MSRC
- **Azure platform vulnerabilities** → Report to MSRC
- **Social engineering attacks** (e.g., phishing users to consent to a JANUS-registered app)
- **Physical security** of the hosting environment
- **Denial of service via legitimate API usage** (e.g., hitting Microsoft Graph throttling limits)
- **Customer misconfiguration:**
  - Overly permissive `JANUS_ALLOWED_REDIRECT_URIS`
  - Disabling recommended security controls
  - Running JANUS in a multi-tenant configuration without proper isolation
  - Granting excessive Graph permissions to the managed identity
- **Vulnerabilities requiring physical access** to the Azure subscription
- **Issues requiring an Entra Global Administrator** to deliberately misconfigure JANUS

### Special Cases

- **Privilege escalation via Graph permissions:** If you discover that JANUS's use of `Application.ReadWrite.OwnedBy` can be exploited for privilege escalation without requiring additional permissions, this is in scope.
- **Container escape leading to managed identity token theft:** In scope for JANUS if it demonstrates a novel attack against Azure Container Apps.
- **Bypass of JANUS policy validation:** In scope (e.g., getting JANUS to create an app with a redirect URI it should have rejected).

## Security Architecture Overview

JANUS implements defense in depth across multiple layers:

### Registration Plane Isolation

JANUS operates exclusively on the **registration plane**:

```
Registration plane (JANUS):
  MCP Client ──RFC 7591 DCR──▶ JANUS ──Microsoft Graph──▶ Entra App Registration

Authentication plane (NOT JANUS):
  MCP Client ──Auth Code + PKCE──▶ Entra ID ──token──▶ Gateway ──▶ MCP Server
```

**Implications:**
- JANUS never sees, handles, or validates gateway access tokens
- Compromising JANUS does not give an attacker gateway tokens
- Token security is fully delegated to Microsoft Entra ID

### Graph Permission Minimization

JANUS uses **`Application.ReadWrite.OwnedBy`** (not `.All`):
- Can only manage apps it creates
- Cannot read or modify existing app registrations
- Cannot grant itself additional permissions (prevents privilege escalation)

**Critical:** JANUS must NEVER be granted `AppRoleAssignment.ReadWrite.All` — this would enable privilege escalation to Global Admin.

### Public Client Enforcement

All JANUS-created apps are public clients:
- `isFallbackPublicClient: true`
- No `passwordCredentials` or `keyCredentials`
- `token_endpoint_auth_method: none`
- PKCE is mandatory (enforced by Entra ID for public clients)

**Result:** There is no secret to leak or steal.

### Strict Redirect URI Policy

- No wildcards (`*`) allowed
- No HTTP on non-loopback hosts
- No fragments (`#`)
- No userinfo (`user:pass@`)
- Loopback URIs validated to RFC 8252 standards
- Custom URI schemes require explicit allowlist
- Exact URI matching (no substring or pattern matching)

### Registration ≠ Authorization

Creating an Entra app registration does **not** grant access to the gateway:

1. JANUS creates the app with `requiredResourceAccess` pointing to the gateway
2. Gateway service principal has `appRoleAssignmentRequired = true`
3. **Human intervention required:** An Entra administrator must assign users/groups
4. Without assignment, tokens cannot be issued for the gateway scope

### Lifecycle Management

The cleanup job prevents app sprawl:
- 24-hour grace period (can't delete apps mid-onboarding)
- 30-day unused window (won't delete active apps)
- 90-day absolute maximum age
- Dry-run mode by default (operators must explicitly opt in)
- 50-app deletion limit per run (prevents mass deletion)

### Identity and Access Management

- **User-assigned managed identity** (not system-assigned)
- Identity lifecycle independent of JANUS container lifecycle
- No long-lived credentials (no secrets, no certificates)
- OIDC federation for CI/CD (GitHub → Entra workload identity)
- Graph tokens cached in-memory only (never persisted)

### Input Validation and Sanitization

- Strict schema validation (closed-world parser)
- Size limits on all fields (max request size, max array lengths, max string lengths)
- Character set restrictions (especially on `client_name` to prevent homograph attacks)
- No echoing of attacker-controlled input in error responses
- Type validation (rejects nested objects where strings expected)
- UTF-8 validation and Unicode normalization
- Rejection of bidi/format control characters

### Logging and Monitoring

- Structured JSON logs (machine-parseable)
- Automatic redaction of bearer tokens and secrets
- Correlation IDs for request tracing
- No logging of `software_statement` content (may contain sensitive metadata)
- Audit trail for all registrations and deletions
- Alerts on anomalous registration rates

## Operator Hardening Checklist

Operators deploying JANUS should verify:

- [ ] **Managed identity has minimal permissions:**
  - JANUS: `Application.ReadWrite.OwnedBy` only
  - Cleanup: `Application.ReadWrite.OwnedBy` + `AuditLog.Read.All` only
  - NOT granted: `Application.ReadWrite.All`, `AppRoleAssignment.ReadWrite.All`, `Directory.ReadWrite.All`

- [ ] **Gateway service principal configured:**
  - `appRoleAssignmentRequired = true` on gateway SP
  - Only intended users/groups assigned to gateway SP
  - Conditional Access policies applied to gateway SP

- [ ] **Redirect URI allowlists are minimal:**
  - Review `JANUS_ALLOWED_REDIRECT_URIS` and `JANUS_ALLOWED_REDIRECT_HOSTS`
  - Remove any unnecessary entries
  - Verify allowed domains are under your control

- [ ] **Loopback restrictions:**
  - `JANUS_ALLOW_LOCALHOST_HOSTNAME=false` (default) unless required
  - Understand loopback port-squatting risks if `JANUS_ALLOW_LOOPBACK_ANY_PORT=true`

- [ ] **Custom URI schemes:**
  - `JANUS_ALLOWED_CUSTOM_SCHEMES` contains only well-known schemes (e.g., `vscode`, `cursor`)
  - Document the risk of URI scheme hijacking on end-user machines

- [ ] **Software statement validation:**
  - If `JANUS_REQUIRE_SOFTWARE_STATEMENT=true`, ensure `JANUS_SOFTWARE_STATEMENT_JWKS` is populated
  - Verify `JANUS_SOFTWARE_STATEMENT_ISSUERS` contains only trusted issuers

- [ ] **Cleanup job safety:**
  - Leave `JANUS_CLEANUP_DRY_RUN=true` for at least 7 days in production
  - Monitor dry-run logs to verify deletion decisions are correct
  - Set up alerts for cleanup job deletions

- [ ] **Network isolation:**
  - JANUS container app in private VNET (or with ingress restrictions)
  - Egress to Microsoft Graph and Entra ID only
  - No direct internet egress

- [ ] **CI/CD security:**
  - GitHub OIDC subject claim restricts to specific branches (e.g., `ref:refs/heads/main`)
  - `environment` protection rules on production deployments
  - Required reviewers on infrastructure changes

- [ ] **Container security:**
  - Base images pinned by digest (not `latest` tag)
  - Images scanned for CVEs (e.g., Trivy, Grype)
  - Non-root user in container (UID 1000, not 0)
  - Read-only root filesystem where possible

- [ ] **Monitoring and alerting:**
  - Log Analytics workspace connected to Container Apps
  - Alerts on registration spikes (> 10 per minute)
  - Alerts on cleanup deletions (> 10 per run)
  - Alerts on Graph API throttling (429 responses)
  - Alerts on Graph permission errors (403 responses)

- [ ] **Tenant isolation:**
  - JANUS deployed to a dedicated Entra tenant (not shared with production apps)
  - Or: JANUS uses a service-tree tag to prevent cross-contamination

- [ ] **Backup and recovery:**
  - Procedure documented for restoring soft-deleted apps (`/directory/deletedItems`)
  - Backup of JANUS configuration (env vars, allowlists)

## Security Review Process for Pull Requests

All PRs must pass the security review checklist:

1. **Does this PR add or modify input validation?**
   - Are size limits enforced?
   - Are character sets restricted?
   - Are untrusted strings sanitized before use in logs/errors?

2. **Does this PR add new dependencies?**
   - Run `runtime-tools-gh-advisory-database` before merging
   - Check Maven Central for typosquatting (verify group ID and publisher)
   - Review dependency's SBOM and license

3. **Does this PR modify Graph API calls?**
   - Are Graph tokens still acquired via managed identity only?
   - Are tokens still cached in-memory (not persisted)?
   - Are tokens redacted in logs?

4. **Does this PR modify policy enforcement?**
   - Does it relax any security controls? (Requires extra justification)
   - Are policy changes backward-compatible with existing apps?
   - Are policy changes documented?

5. **Does this PR touch the token path?**
   - **REJECT:** Any PR that would make JANUS issue, proxy, or cache gateway tokens
   - **REJECT:** Any PR that adds a `client_secret` to created apps
   - **REJECT:** Any PR that implements token exchange or on-behalf-of flows

6. **Does this PR add new environment variables?**
   - Are defaults secure (deny-by-default)?
   - Are they documented in `docs/architecture.md` and `docs/deployment.md`?

7. **Does this PR modify the cleanup job?**
   - Are deletion decisions conservative (prefer keeping over deleting)?
   - Is dry-run mode still the default?
   - Is the deletion limit still enforced?

## Security Development Lifecycle

- **Threat modeling:** All features must update `docs/threat-model.md`
- **Secure coding standards:** Follow `CONTRIBUTING.md` conventions
- **Dependency updates:** Automated PRs via Dependabot (review before merge)
- **Static analysis:** SpotBugs, OWASP Dependency-Check in CI
- **Container scanning:** Trivy scan on every build
- **Secret scanning:** `runtime-tools-secret_scanning` before every commit
- **Security testing:** Fuzzing of DCR endpoint, policy bypass tests in `tests/security/`

## Known Limitations and Risks

### Accepted Risks

1. **Loopback port squatting:** A malicious app on the same machine can squat `localhost:*` ports to intercept authorization codes. Mitigated by PKCE (authorization code alone is useless without the code verifier).

2. **Custom URI scheme hijacking:** On desktop OSes, another app can register the same scheme (e.g., `vscode://`). Mitigated by: (a) allowlist of known-safe schemes, (b) user awareness, (c) PKCE.

3. **DNS hijacking of `localhost`:** If an attacker controls DNS, they can resolve `localhost` to a remote IP. Mitigated by: `JANUS_ALLOW_LOCALHOST_HOSTNAME=false` by default.

4. **Sign-in activity lag:** Microsoft Graph sign-in activity data has hours of latency. Mitigated by: 24-hour grace period + 30-day unused window.

5. **Cleanup deletion of live apps:** Refresh tokens can keep an app "alive" without sign-in activity. Mitigated by: long unused window, operator dry-run soak period.

6. **Tenant-wide DoS via app registration flooding:** An attacker can exhaust the app registration quota. Mitigated by: rate limiting at the Azure Front Door / Container Apps level (not in JANUS code).

### Future Work

- **Rate limiting:** Add per-IP and per-user rate limiting in JANUS code (currently relies on Azure infrastructure)
- **Soft delete on registration:** Keep a local record of client_id → correlation_id mapping for forensics
- **Anomaly detection:** Machine learning-based detection of suspicious registration patterns
- **Geo-fencing:** Restrict registrations to specific regions

## Contact

For security questions that don't rise to the level of a vulnerability report, open a **public** GitHub Discussion in the Security category.

For security vulnerabilities, use GitHub Security Advisories (private reporting).

---

**Last Updated:** 2026-08-28  
**Policy Version:** 1.0
