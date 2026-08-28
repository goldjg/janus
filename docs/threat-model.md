# JANUS Threat Model

## Scope

This threat model covers the JANUS MCP Client Registration Broker and its interactions with:

- MCP clients (DCR requestors)
- Keycloak (DCR endpoint host)
- Microsoft Entra ID (application registration authority and token issuer)
- Microsoft Graph (Entra management plane)
- Azure Container Apps (compute platform)
- The cleanup Container Apps Job

This model does not cover the MCP gateway itself or end-user authentication flows (JANUS is not involved in those).

## Assets

| Asset | Sensitivity | Notes |
|---|---|---|
| Managed Identity credentials | Critical | Never exposed; platform-managed |
| Entra app registrations created by JANUS | High | Represent valid OAuth identities in the tenant |
| Keycloak admin credentials | High | Used only during bootstrap; not in running app |
| DCR endpoint (write) | Medium | Can create new Entra registrations |
| DCR endpoint (read) | Low | No sensitive data returned beyond client_id |
| Log data | Low–Medium | Must not contain tokens; may contain app IDs |

## Threat actors

| Actor | Capability | Goal |
|---|---|---|
| External attacker | Can send HTTP requests to the DCR endpoint | Register malicious redirect URIs; pollute tenant with registrations; cause DoS |
| Malicious MCP client | Can send RFC 7591 DCR requests | Register arbitrary redirect URIs; gain broader OAuth scopes |
| Compromised Keycloak | Full control of the DCR endpoint process | Read managed identity token from IMDS; call Graph with OwnedBy scope |
| Insider threat | Access to Azure subscription | Escalate JANUS permissions; access logs; modify policy |
| Supply chain attacker | Malicious dependencies or base image | Execute arbitrary code in the JANUS container |

## STRIDE analysis

### Spoofing

| Threat | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Attacker spoofs legitimate MCP client to register redirect URIs | Medium | Medium | Redirect URI allowlist prevents non-approved URIs; `Application.ReadWrite.OwnedBy` limits blast radius |
| Attacker spoofs Managed Identity IMDS endpoint | Low | Critical | IMDS is Azure platform-controlled; not accessible externally |
| Attacker presents forged DCR registration receipt to gateway | Medium | High | Gateway validates Entra token independently; DCR receipt is irrelevant to gateway access |

### Tampering

| Threat | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Attacker modifies Entra app registration created by JANUS | Low | Medium | `Application.ReadWrite.OwnedBy` allows JANUS to read/delete; but JANUS does not act as a secret store; gateway validates Entra token regardless |
| Attacker injects malicious redirect URI via DCR | Medium | Medium | RegistrationPolicy rejects URIs not matching allowlist |
| Attacker modifies JANUS policy configuration | Low | High | Config in Container Apps environment variables; requires Azure RBAC write |

### Repudiation

| Threat | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Registration activity not audited | Low | Medium | Structured logs with correlationId, outcome, appId; Azure Monitor |
| Cleanup deletions not audited | Low | Medium | Cleanup logs each deletion with objectId and age |

### Information disclosure

| Threat | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Managed Identity token leaked in logs | Low | Critical | Explicit log field allowlist; tokens never logged |
| DCR request body logged with sensitive fields | Low | Medium | Only safe fields logged; no secrets exist |
| Internal errors expose implementation details | Low | Low | Generic `server_error` returned to caller; details in structured internal logs |

### Denial of service

| Threat | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Flood of DCR requests creates many Entra registrations | Medium | High | Container Apps ingress rate limiting; Graph throttling; cleanup job removes stale registrations |
| Malformed requests consume resources | Medium | Low | Input validated before Graph call; validation is cheap |
| Graph throttling causes DCR failures | Low | Low | Graph 429 retried with backoff; returned as `server_error` after max retries |

### Elevation of privilege

| Threat | Likelihood | Impact | Mitigation |
|---|---|---|---|
| JANUS gains `Application.ReadWrite.All` | Low | Critical | Bootstrap script assigns only `Application.ReadWrite.OwnedBy`; documented in `entra-permissions.md` |
| Keycloak extension escapes SPI sandbox | Low | Critical | JANUS extension runs within Keycloak JVM; extension code reviewed; no native execution |
| Cleanup job deletes non-JANUS registrations | Low | High | `Application.ReadWrite.OwnedBy` prevents writes to unowned registrations |
| JANUS issues gateway access tokens | None (architectural) | Critical | JANUS has no token endpoint; Keycloak not used as gateway IdP |

## Residual risks

| Risk | Accepted? | Notes |
|---|---|---|
| DoS via registration flood | Accepted with rate limiting | Add Container Apps ingress rate limiting in deployment |
| `Application.ReadWrite.OwnedBy` allows reading all owned registrations | Accepted | Scope is limited to JANUS-owned apps; acceptable for lifecycle management |
| Keycloak admin API exposed on internal port | Accepted | Admin port must not be exposed externally in the Container Apps ingress configuration |

## Out of scope

- MCP gateway security (operated independently)
- End-user authentication security (Entra-controlled)
- Keycloak realm configuration beyond JANUS extension setup
- Multi-tenant scenarios (not supported)
