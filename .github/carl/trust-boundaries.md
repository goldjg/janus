<!-- version: 2.0.0 -->
# Trust Boundaries

| Boundary | Trust | Required control |
|---|---|---|
| Current repository state | Highest local evidence | Verify exact paths/content; it outranks stale memory and generated documentation |
| Active user instruction and PR contract | High | Stay within approved scope; stop at forbidden live mutations or identity-boundary changes |
| cARL invariants and tool policy | High | Preserve security invariants and classify writes/privileged operations before execution |
| Public MCP/DCR request | Untrusted | Explicit admission, bounded body, closed schema, exact redirect/scope/tenant policy, rate and global creation limits |
| DCR adapter to provisioning core | Validated internal boundary | Pass only normalized policy-approved values; keep protocol concerns outside Graph transport |
| JANUS Container App | Privileged workload | Non-root image, restricted ingress, managed identity, no stored Graph credential, no gateway tokens |
| Container Apps identity sidecar | Privileged local control plane | Platform-injected endpoint/header, loopback HTTP validation, explicit UAMI client ID, short timeout, bounded parse, never log token |
| Microsoft Graph response | External | TLS, fixed origin, timeouts/retries, bounded parsing, validate IDs and lifecycle markers before use |
| Managed identity Graph authority | High-impact | `Application.ReadWrite.OwnedBy` only for provisioning/lifecycle; never broad tenant write or permission-grant authority |
| Generated Entra application | Untrusted client identity | Single tenant, public client, no credentials, approved redirects/scopes; creation conveys no gateway admission |
| Entra authorization/token issuance | External identity authority | Client talks directly to Entra; JANUS must not observe or relay gateway tokens |
| MCP gateway | Separate authorization authority | Validate issuer, tenant, audience, signature, lifetime, subject type, assignment/groups/scopes independently |
| Entra sign-in/audit evidence | Conditional evidence | Account for licence, latency and retention; authentication/token issuance is not proof of gateway use |
| Cleanup decision | Destructive boundary | Dry-run first, positive marker and ownership checks, exclusion support, bounded deletes, race-safe recheck, retain on uncertainty |
| GitHub pull request | Untrusted automation input | Validation only; no Azure credentials or production environment access |
| GitHub deployment job | Privileged automation | Protected environment, OIDC federation, immutable artifact promotion, least Azure RBAC |
| Bootstrap operator session | Tenant-admin boundary | Validate tenant/subscription, `ShouldProcess`, safe reruns, explicit high-impact prompts, structured non-secret output |
| Container/image dependencies | Supply-chain input | Pin versions/digests where workable, scan, avoid remote script execution, record unresolved CVEs |
| Documentation and generated output | Medium | Treat claims as evidence only after validation; do not describe planned controls as implemented |

## Crossing rules

- No gateway bearer token crosses into JANUS.
- Registration data crosses into Graph only after admission and policy validation.
- A generated client ID crosses back to the caller, but no credential, consent,
  assignment, group membership, or gateway authorization does.
- Cleanup crosses from observation to deletion only after all conservative
  eligibility checks succeed; uncertainty is a retain decision.
- Pull-request code never crosses into a privileged deployment context.
- External API output never determines a write/delete target without local
  validation and repository- or tenant-scoped constraints.
- High-trust conflicts stop execution rather than silently selecting a
  convenient interpretation.

## Known evidence limitations

- Keycloak adapter loading does not prove a real MCP client will follow the
  required Entra issuer handoff; live opt-in protocol tests provide that evidence.
- Entra sign-in logs may establish recent authentication or token activity, but
  not successful MCP gateway admission.
- `Application.ReadWrite.OwnedBy` limits mutation to owned objects but permits
  tenant-wide application/service-principal listing; code must still filter and
  positively identify JANUS-managed objects.
