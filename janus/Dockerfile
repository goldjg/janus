# ── Stage 1: Build the JANUS extension ─────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

# Copy Maven wrapper and pom first for layer caching
COPY pom.xml .
COPY .mvn/ .mvn/

# Download dependencies (cached layer if pom.xml unchanged)
RUN mvn dependency:go-offline -q || true

# Copy source and build
COPY src/ src/
RUN mvn clean package -DskipTests -q

# ── Stage 2: Keycloak with JANUS extension ───────────────────────────────────
FROM quay.io/keycloak/keycloak:24.0.5

# Install the JANUS extension JAR into the Keycloak providers directory.
# Keycloak discovers extensions in /opt/keycloak/providers/ at startup.
COPY --from=builder /build/target/janus-dcr-provider-*.jar /opt/keycloak/providers/

# Run Keycloak in build mode to index the new provider.
# This is required by Keycloak 17+ to pre-compile providers.
RUN /opt/keycloak/bin/kc.sh build

# ── Runtime configuration ────────────────────────────────────────────────────
# All secrets must be injected at runtime via environment variables.
# Do not embed secrets, tokens, or credentials in this image.

# Required at runtime (supplied by Container Apps environment variables):
# KEYCLOAK_ADMIN                   - Keycloak admin username
# KEYCLOAK_ADMIN_PASSWORD          - Keycloak admin password
# KC_DB                            - Database type (e.g. postgres)
# KC_DB_URL                        - Database JDBC URL
# KC_DB_USERNAME                   - Database username
# KC_DB_PASSWORD                   - Database password
# KC_HOSTNAME                      - Public hostname of this Keycloak instance
# JANUS_TENANT_ID                  - Entra tenant ID
# JANUS_GATEWAY_RESOURCE_URI       - MCP gateway app ID URI (api://<client-id>)
# AZURE_CLIENT_ID                  - Client ID of the user-assigned managed identity

# Optional:
# JANUS_ALLOWED_REDIRECT_URI_PATTERNS  - Comma-separated redirect URI prefixes
# JANUS_MAX_REDIRECT_URIS              - Max redirect URIs per registration
# JANUS_CLEANUP_RETENTION_DAYS         - Cleanup retention in days

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
