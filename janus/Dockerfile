# ── Stage 1: Build the JANUS extension ─────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS builder

WORKDIR /build

# Copy pom first for dependency caching
COPY pom.xml .

# Download dependencies (cached layer if pom.xml unchanged). Fail the build if
# dependency resolution is unavailable rather than producing a partial image.
RUN mvn dependency:go-offline -q

# Copy source and build
COPY src/ src/
RUN mvn clean package -DskipTests -q

# ── Stage 2: Keycloak with JANUS extension ───────────────────────────────────
FROM quay.io/keycloak/keycloak:26.7.2@sha256:9d1f1b2b7261ff53c66cb1092dfcdc34a5fb77e81f9e6a6e75b8b6a795de8067

USER root

# Create the JANUS runtime directory
RUN mkdir -p /opt/janus

# Install the JANUS extension JAR into the Keycloak providers directory.
# Keycloak discovers extensions in /opt/keycloak/providers/ at startup.
# Also copy to /opt/janus/ for use by the cleanup job.
COPY --from=builder /build/target/janus-dcr-provider.jar /opt/keycloak/providers/janus-dcr-provider.jar
COPY --from=builder /build/target/*-cleanup.jar /opt/janus/janus-cleanup.jar

# A fresh deployment must expose the documented JANUS realm. Runtime policy is
# read from fail-closed environment variables; no client or credential is
# embedded in the realm export.
COPY keycloak/realm-janus.json /opt/keycloak/data/import/realm-janus.json

# Cleanup job entry point script
COPY cleanup.sh /opt/janus/cleanup.sh
RUN chmod +x /opt/janus/cleanup.sh

# Run Keycloak in build mode to index the new provider.
# This is required by Keycloak 17+ to pre-compile providers.
ENV KC_DB=postgres
RUN /opt/keycloak/bin/kc.sh build

USER keycloak

# ── Runtime configuration ────────────────────────────────────────────────────
# All secrets must be injected at runtime via environment variables.
# Do not embed secrets, tokens, or credentials in this image.

# Required at runtime (supplied by Container Apps environment variables):
# KC_BOOTSTRAP_ADMIN_USERNAME      - one-time Keycloak bootstrap admin username
# KC_BOOTSTRAP_ADMIN_PASSWORD      - one-time Keycloak bootstrap admin password
# KC_DB                            - Database type (e.g. postgres)
# KC_DB_URL                        - Database JDBC URL
# KC_DB_USERNAME                   - Database username
# KC_DB_PASSWORD                   - Database password
# KC_HOSTNAME                      - Public hostname of this Keycloak instance
# JANUS_TENANT_ID                  - Entra tenant ID
# JANUS_GATEWAY_CLIENT_ID          - MCP gateway application/client UUID
# JANUS_GATEWAY_RESOURCE_URI       - MCP gateway app ID URI (api://<client-id>)
# JANUS_ALLOWED_GATEWAY_SCOPES     - scope URI to permission UUID mappings
# JANUS_ADMISSION_MODE             - must be initial-access-token
# AZURE_CLIENT_ID                  - Client ID of the user-assigned managed identity

# Optional:
# JANUS_ALLOWED_REDIRECT_URI_PATTERNS  - Comma-separated exact/controlled loopback rules
# JANUS_MAX_REDIRECT_URIS              - Max redirect URIs per registration
# JANUS_CLEANUP_RETENTION_DAYS         - Cleanup retention in days
# JANUS_CLEANUP_DRY_RUN                - Defaults true; set false to permit bounded deletes
# JANUS_CLEANUP_MAX_DELETE_COUNT       - Maximum delete attempts per run (default 10)
# JANUS_CLEANUP_EVIDENCE_MAX_AGE_HOURS - Maximum trusted observer evidence age

# For cleanup job (Container Apps Job mode):
# JANUS_REALM                      - Keycloak realm to clean up

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
