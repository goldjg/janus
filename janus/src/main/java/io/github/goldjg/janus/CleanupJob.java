package io.github.goldjg.janus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Standalone conservative cleanup job for JANUS-managed Entra applications.
 *
 * <p>The job is dry-run by default. A destructive run requires
 * {@code JANUS_CLEANUP_DRY_RUN=false}. Even then, each candidate is re-fetched,
 * re-evaluated, checked for immutable client-ID continuity, and subject to a
 * bounded delete-attempt count. Uncertain evidence always retains.
 */
public final class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    private CleanupJob() { }

    /** Run the Container Apps Job entry point. */
    public static void main(String[] args) {
        String correlationId = UUID.randomUUID().toString();
        final LifecycleConfig config;
        try {
            config = LifecycleConfig.fromEnvironment();
        } catch (IllegalArgumentException e) {
            logEvent(new ObjectMapper(), correlationId, "cleanup_configuration",
                    "failed", null, null, null, e.getMessage());
            System.exit(2);
            return;
        }

        GraphClientService graph = new GraphClientService(correlationId);
        CleanupSummary result = run(config, graph, Clock.systemUTC(),
                new ObjectMapper(), correlationId);
        if (result.errors() > 0) {
            System.exit(1);
        }
    }

    /**
     * Evaluate and, only when explicitly enabled, remove eligible applications.
     * This method performs no live calls when supplied a mocked Graph service.
     */
    static CleanupSummary run(
            LifecycleConfig config,
            GraphClientService graph,
            Clock clock,
            ObjectMapper objectMapper,
            String correlationId) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(correlationId, "correlationId");

        Instant evaluationTime = clock.instant();
        LifecyclePolicy policy = new LifecyclePolicy(config);
        logEvent(objectMapper, correlationId, "cleanup_start", "started",
                null, null, null, "dryRun=" + config.dryRun()
                        + ",tenantId=" + config.tenantId()
                        + ",realm=" + config.realm()
                        + ",retentionSeconds=" + config.retention().toSeconds()
                        + ",maximumDeletes=" + config.maximumDeletes());

        final List<GraphClientService.GraphApplicationResponse> applications;
        try {
            applications = graph.listJanusApplications(config.realm());
        } catch (JanusRegistrationException | IllegalArgumentException e) {
            logEvent(objectMapper, correlationId, "cleanup_list", "failed",
                    null, null, null, safeError(e));
            return new CleanupSummary(0, 0, 0, 0, 1);
        }

        int retained = 0;
        int wouldDelete = 0;
        int deleted = 0;
        int deletionAttempts = 0;
        int errors = 0;

        for (GraphClientService.GraphApplicationResponse application : applications) {
            LifecycleDecision decision = policy.evaluate(application, evaluationTime);
            if (!decision.isDeleteCandidate()) {
                retained++;
                logDecision(objectMapper, correlationId, application, decision, "retained");
                continue;
            }

            if (config.dryRun()) {
                wouldDelete++;
                logDecision(objectMapper, correlationId, application, decision, "would_delete");
                continue;
            }

            if (deletionAttempts >= config.maximumDeletes()) {
                retained++;
                logEvent(objectMapper, correlationId, "cleanup_decision", "retained",
                        application.id, application.appId, "DELETE_LIMIT_REACHED",
                        "maximum_delete_attempts_reached");
                continue;
            }

            try {
                Optional<GraphClientService.GraphApplicationResponse> current =
                        graph.getApplication(application.id);
                if (current.isEmpty()) {
                    logEvent(objectMapper, correlationId, "cleanup_recheck", "already_absent",
                            application.id, application.appId, decision.reason().name(),
                            "application_removed_before_recheck");
                    continue;
                }

                GraphClientService.GraphApplicationResponse rechecked = current.get();
                if (!Objects.equals(application.appId, rechecked.appId)) {
                    retained++;
                    logEvent(objectMapper, correlationId, "cleanup_recheck", "retained",
                            application.id, application.appId, "CLIENT_ID_CHANGED",
                            "client_id_changed_during_cleanup");
                    continue;
                }

                LifecycleDecision currentDecision = policy.evaluate(rechecked, clock.instant());
                if (!currentDecision.isDeleteCandidate()) {
                    retained++;
                    logDecision(objectMapper, correlationId, rechecked,
                            currentDecision, "retained_after_recheck");
                    continue;
                }

                deletionAttempts++;
                if (graph.deleteApplication(rechecked.id)) {
                    deleted++;
                    logDecision(objectMapper, correlationId, rechecked,
                            currentDecision, "deleted");
                } else {
                    logDecision(objectMapper, correlationId, rechecked,
                            currentDecision, "already_absent");
                }
            } catch (JanusRegistrationException | IllegalArgumentException e) {
                errors++;
                logEvent(objectMapper, correlationId, "cleanup_delete", "failed",
                        application.id, application.appId, decision.reason().name(), safeError(e));
            }
        }

        CleanupSummary summary = new CleanupSummary(applications.size(), retained,
                wouldDelete, deleted, errors);
        logEvent(objectMapper, correlationId, "cleanup_complete",
                errors == 0 ? "succeeded" : "completed_with_errors",
                null, null, null, "evaluated=" + summary.evaluated()
                        + ",retained=" + retained
                        + ",wouldDelete=" + wouldDelete
                        + ",deleted=" + deleted
                        + ",errors=" + errors);
        return summary;
    }

    private static void logDecision(
            ObjectMapper mapper,
            String correlationId,
            GraphClientService.GraphApplicationResponse application,
            LifecycleDecision decision,
            String outcome) {
        LastUseEvidence evidence = decision.lastUseEvidence();
        String detail = evidence == null ? null : evidence.detail();
        logEvent(mapper, correlationId, "cleanup_decision", outcome,
                application == null ? null : application.id,
                application == null ? null : application.appId,
                decision.reason().name(), detail);
    }

    private static void logEvent(
            ObjectMapper mapper,
            String correlationId,
            String operation,
            String outcome,
            String objectId,
            String clientId,
            String lifecycleDecision,
            String detail) {
        ObjectNode event = mapper.createObjectNode();
        event.put("correlationId", correlationId);
        event.put("operation", operation);
        event.put("outcome", outcome);
        putNullable(event, "appObjectId", objectId);
        putNullable(event, "clientId", clientId);
        putNullable(event, "lifecycleDecision", lifecycleDecision);
        putNullable(event, "detail", detail);
        log.info("{}", event);
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 256 ? message : message.substring(0, 256);
    }

    /** Aggregate result returned to tests and the process entry point. */
    record CleanupSummary(int evaluated, int retained, int wouldDelete, int deleted, int errors) { }
}
