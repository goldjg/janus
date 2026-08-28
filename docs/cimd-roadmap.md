# JANUS Client Identity Metadata Descriptor (CIMD) Roadmap

## Status: Exploratory

This document describes a planned extension to JANUS that would allow MCP clients to declare richer identity metadata at registration time. The CIMD capability is not implemented in the initial release.

## Problem

The initial JANUS release creates Entra app registrations with minimal metadata: display name, redirect URIs, and JANUS ownership tags. MCP clients and gateway operators may benefit from richer, structured metadata that describes:

- The purpose of the MCP client
- The user or organisation responsible for the client
- The expected usage patterns
- Version and lifecycle metadata

## Proposed approach

CIMD would extend the DCR request with a `janus_client_metadata` extension field:

```json
{
  "client_name": "Claude Code",
  "redirect_uris": ["http://localhost:8080/callback"],
  "janus_client_metadata": {
    "client_description": "Local MCP client for Claude Code IDE extension",
    "client_owner": "developer@example.com",
    "client_version": "1.2.0",
    "client_environment": "development"
  }
}
```

JANUS would validate the `janus_client_metadata` fields, sanitise them, and include relevant fields in the Entra app registration `notes` field and/or custom extension attributes.

## Constraints

- CIMD metadata is stored in Entra, not in a JANUS database. JANUS remains stateless.
- Metadata is descriptive only; it does not affect gateway authorisation.
- No PII (email addresses, user identifiers) in CIMD metadata unless explicitly configured and documented.
- CIMD fields are bounded (maximum field length, maximum field count).
- CIMD is an extension field; its presence is optional. Requests without it are accepted normally.

## Entra storage

Candidate Entra fields for CIMD metadata:

| Field | Entra property | Limit |
|---|---|---|
| Description | `notes` | 1024 characters |
| Tags | `tags` array | 256 characters per tag |
| Custom attributes | Extension attributes (requires schema extension) | Complex |

The initial CIMD implementation would use `notes` and `tags` only to avoid schema extension complexity.

## Timeline

CIMD is not planned for the initial release. It will be considered once:

1. The base JANUS implementation is stable and validated in production.
2. There is demonstrated demand from MCP client operators.
3. The Entra metadata storage approach is validated against production tenant limits.

## Non-goals for CIMD

- CIMD must not affect Entra authorisation policies
- CIMD must not include credential material, tokens, or secrets
- CIMD must not exceed Entra API field limits in a way that causes registration failures
- CIMD must not require a JANUS database or persistent store
