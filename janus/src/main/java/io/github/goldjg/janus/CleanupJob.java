package io.github.goldjg.janus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Standalone cleanup job for JANUS-managed Entra application registrations.
 *
 * <p>Run as a Container Apps Job. Uses the same Managed Identity and
 * {@link GraphClientService} as the Keycloak extension. Deletes JANUS-managed
 * registrations older than the configured retention period.
 *
 * <p>Entry point: {@code java -cp janus-dcr-provider.jar io.github.goldjg.janus.CleanupJob}
 */
public class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    public static void main(String[] args) {
        String correlationId = UUID.randomUUID().toString();
        log.info("operation=cleanup_start correlationId={}", correlationId);

        JanusConfig config = JanusConfig.fromEnvironment();

        // Realm is required to filter JANUS-owned registrations correctly.
        String realm = System.getenv("JANUS_REALM");
        if (realm == null || realm.isBlank()) {
            log.error("JANUS_REALM environment variable is required for cleanup.");
            System.exit(1);
        }

        int retentionDays = config.getCleanupRetentionDays();
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));

        log.info("operation=cleanup_config correlationId={} realm={} retentionDays={} cutoff={}",
                correlationId, realm, retentionDays, cutoff);

        GraphClientService graphService = new GraphClientService(config, correlationId);

        List<GraphClientService.GraphApplicationResponse> apps;
        try {
            apps = graphService.listJanusApplications(realm);
        } catch (JanusRegistrationException e) {
            log.error("operation=cleanup_list_failed correlationId={} error={}", correlationId, e.getMessage());
            System.exit(1);
            return;
        }

        log.info("operation=cleanup_list correlationId={} realm={} evaluated={}", correlationId, realm, apps.size());

        int deleted = 0;
        int errors = 0;

        for (GraphClientService.GraphApplicationResponse app : apps) {
            Instant createdAt = parseCreatedAt(app);
            if (createdAt == null || createdAt.isAfter(cutoff)) {
                continue; // Not yet stale; skip
            }

            long ageSeconds = Duration.between(createdAt, Instant.now()).getSeconds();

            try {
                graphService.deleteApplication(app.id);
                log.info("operation=cleanup_delete correlationId={} objectId={} appId={} displayName={} createdAt={} ageSeconds={}",
                        correlationId, app.id, app.appId, app.displayName, createdAt, ageSeconds);
                deleted++;
            } catch (JanusRegistrationException e) {
                log.error("operation=cleanup_delete_failed correlationId={} objectId={} error={}",
                        correlationId, app.id, e.getMessage());
                errors++;
            }
        }

        log.info("operation=cleanup_complete correlationId={} realm={} evaluated={} deleted={} errors={}",
                correlationId, realm, apps.size(), deleted, errors);

        if (errors > 0) {
            System.exit(1);
        }
    }

    /**
     * Parse the creation time from the {@code createdDateTime} field of a
     * Graph application response.
     *
     * @return the creation instant, or {@code null} if the field is absent or unparseable.
     */
    private static Instant parseCreatedAt(GraphClientService.GraphApplicationResponse app) {
        if (app.createdDateTime == null) {
            return null;
        }
        try {
            return Instant.parse(app.createdDateTime);
        } catch (Exception e) {
            log.warn("operation=cleanup_parse_date objectId={} createdDateTime={} error={}",
                    app.id, app.createdDateTime, e.getMessage());
            return null;
        }
    }
}
