# CIMD migration roadmap

## Status

The MCP 2026-07-28 specification formally deprecated Dynamic Client
Registration in favour of Client ID Metadata Documents (CIMD). DCR remains a
backward-compatibility mechanism for now and is expected to be removed in a
future specification version.

JANUS therefore remains useful to existing DCR clients, but new architecture
must not bind Entra provisioning or lifecycle behavior to RFC 7591 objects.

Primary reference: [MCP 2026-07-28 release](https://blog.modelcontextprotocol.io/posts/2026-07-28/).

## Architecture direction

```text
legacy MCP client --> DCR adapter ----+
                                      |
future MCP client --> CIMD adapter ---+--> admission + normalized registration
                                             |
                                             v
                                      provisioning core
                                             |
                                             v
                                      Microsoft Entra ID
```

Protocol adapters are responsible for parsing, protocol errors and response
shape. Shared policy is responsible for tenant, redirect, gateway scope,
public-client, admission and creation-limit decisions. The provisioning core
accepts only normalized approved values and does not know whether they came
from DCR or CIMD.

## What remains shared

- Entra tenant and gateway-resource binding;
- redirect URI and public-client policy;
- approved gateway scope mapping;
- admission, idempotency and bounded creation controls;
- deterministic operational metadata and collision-resistant naming;
- managed-identity Graph transport;
- positive ownership/lifecycle markers;
- conservative cleanup and observability;
- the rule that registration does not grant gateway authorization.

## Migration phases

### 1. DCR compatibility baseline

- Prove selected legacy MCP clients can register and then use the configured
  Entra issuer directly.
- Keep DCR in a narrow adapter.
- Record issuer-binding behavior per client; never silently reuse a client ID
  with another authorization server.

### 2. CIMD discovery and compatibility research

- Track the normative MCP authorization text and supported SDK/client releases.
- Test how CIMD client identifiers are represented and bound to the Entra issuer.
- Determine whether an Entra registration must be pre-provisioned, cached or
  mapped, without inventing unsupported metadata semantics.
- Threat-model document hosting, origin binding, cache behavior and metadata
  substitution.

### 3. Optional CIMD adapter

Implement only after at least one target client and the relevant specification
are stable enough for contract tests. The adapter must feed the existing
normalized provisioning interface and must not broaden Graph permissions or
move JANUS into token issuance.

### 4. DCR retirement

- Measure actual DCR use without logging client secrets or tokens.
- Publish a deprecation window aligned with the MCP policy.
- Disable new DCR registration before removing lifecycle support for existing
  Entra applications.
- Preserve explicit operator control over existing registrations.

## Explicit non-goals

- no speculative CIMD endpoint in the initial release;
- no generic client metadata hosting service;
- no JWT/software-statement feature merely to mimic a future standard;
- no token exchange, issuer impersonation or Keycloak gateway-token issuance;
- no automatic migration that changes a client ID or gateway authorization.

## Exit criteria for a CIMD implementation

- normative behavior and issuer binding are understood;
- at least one real MCP client is covered by opt-in end-to-end tests;
- metadata substitution and origin threats are addressed;
- Entra object lifecycle and idempotency behavior are defined;
- DCR and CIMD adapters produce equivalent normalized policy inputs;
- documentation clearly distinguishes registration from authorization.
