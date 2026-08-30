package io.github.goldjg.janus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CleanupJobTest {

    private static final String TENANT_ID = "22222222-2222-4222-8222-222222222222";
    private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

    @Test
    void run_dryRunNeverFetchesOrDeletesCandidate() {
        GraphClientService graph = mock(GraphClientService.class);
        var candidate = candidate("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        when(graph.listJanusApplications("janus")).thenReturn(List.of(candidate));

        CleanupJob.CleanupSummary summary = CleanupJob.run(config(true, 10), graph,
                fixedClock(), new ObjectMapper(), "correlation");

        assertEquals(1, summary.wouldDelete());
        assertEquals(0, summary.deleted());
        verify(graph, never()).getApplication(candidate.id);
        verify(graph, never()).deleteApplication(candidate.id);
    }

    @Test
    void run_rechecksAndRetainsWhenOwnershipChanges() {
        GraphClientService graph = mock(GraphClientService.class);
        var candidate = candidate("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        var changed = candidate(candidate.id, candidate.appId);
        changed.tags.remove(GraphClientService.TAG_LIFECYCLE_SCHEMA);
        when(graph.listJanusApplications("janus")).thenReturn(List.of(candidate));
        when(graph.getApplication(candidate.id)).thenReturn(Optional.of(changed));

        CleanupJob.CleanupSummary summary = CleanupJob.run(config(false, 10), graph,
                fixedClock(), new ObjectMapper(), "correlation");

        assertEquals(1, summary.retained());
        assertEquals(0, summary.deleted());
        verify(graph, never()).deleteApplication(candidate.id);
    }

    @Test
    void run_boundsDeleteAttempts() {
        GraphClientService graph = mock(GraphClientService.class);
        var first = candidate("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        var second = candidate("cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        when(graph.listJanusApplications("janus")).thenReturn(List.of(first, second));
        when(graph.getApplication(first.id)).thenReturn(Optional.of(first));
        when(graph.deleteApplication(first.id)).thenReturn(true);

        CleanupJob.CleanupSummary summary = CleanupJob.run(config(false, 1), graph,
                fixedClock(), new ObjectMapper(), "correlation");

        assertEquals(1, summary.deleted());
        assertEquals(1, summary.retained());
        verify(graph, never()).getApplication(second.id);
        verify(graph, never()).deleteApplication(second.id);
    }

    private static LifecycleConfig config(boolean dryRun, int maximumDeletes) {
        return new LifecycleConfig(TENANT_ID, "janus", Duration.ofDays(30), dryRun,
                maximumDeletes, Duration.ofHours(48));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static GraphClientService.GraphApplicationResponse candidate(
            String objectId,
            String appId) {
        var app = new GraphClientService.GraphApplicationResponse();
        app.id = objectId;
        app.appId = appId;
        app.createdDateTime = NOW.minus(Duration.ofDays(90)).toString();
        app.tags = new ArrayList<>(List.of(
                GraphClientService.TAG_JANUS_MANAGED,
                GraphClientService.TAG_LIFECYCLE_SCHEMA,
                GraphClientService.TAG_REALM_PREFIX + "janus",
                GraphClientService.TAG_TENANT_PREFIX + TENANT_ID,
                GraphClientService.TAG_LAST_OBSERVED_USE_PREFIX + NOW.minus(Duration.ofDays(60)),
                GraphClientService.TAG_USE_OBSERVED_THROUGH_PREFIX + NOW.minus(Duration.ofHours(1))));
        return app;
    }
}
