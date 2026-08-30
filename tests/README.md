# Test guide

The automated Java suite is under `janus/src/test/java` and currently covers:

- strict DCR parsing, duplicate JSON keys, unknown/oversized metadata
- client-name, redirect URI, grant, response, public-client, and exact-scope policy
- protocol-neutral provisioning and idempotency/rate controls
- Graph request payloads, pagination origin checks, retry/throttling, and errors
- positive ownership, use-evidence ambiguity, retention, cleanup dry-run,
  re-fetch, race, and bounded deletion behavior
- a test-only reference gateway claims policy covering `iat`, `nbf`, `exp`,
  skew, issuer, audience, tenant, group overage, user/app token confusion, and
  missing/malformed claims

Run it with:

```bash
cd janus
mvn -B clean verify
```

Also validate the non-Java surfaces:

```bash
az bicep build --file infra/main.bicep --stdout >/dev/null
pwsh -NoProfile -File bootstrap/Test-BootstrapStatic.ps1
docker build -t janus:local janus
```

The claims policy is not a bespoke JWT verifier and never processes unverified
tokens; it documents the gateway admission behavior expected after a mature
library has validated signature and token syntax. `iat` is a temporal signal,
not replay prevention.

Live Entra, Graph, Conditional Access, client compatibility, and gateway tests
are intentionally opt-in. They are not represented as automated public-PR
tests because they create tenant objects and require external identities. Use
a dedicated tenant, an explicit object inventory, bounded admission, and a
reviewed teardown when adding them.
