package io.github.goldjg.janus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleConfigTest {

    private static final String TENANT_ID = "22222222-2222-4222-8222-222222222222";

    @Test
    void fromMap_defaultsToThirtyDaysAndDryRun() {
        LifecycleConfig config = LifecycleConfig.fromMap(Map.of(
                LifecycleConfig.ENV_TENANT_ID, TENANT_ID,
                LifecycleConfig.ENV_REALM, "janus"));

        assertEquals(Duration.ofDays(30), config.retention());
        assertTrue(config.dryRun());
        assertEquals(10, config.maximumDeletes());
        assertEquals(Duration.ofHours(48), config.maximumEvidenceAge());
    }

    @Test
    void fromMap_rejectsMalformedRetentionRatherThanFallingBack() {
        Map<String, String> values = baseValues();
        values.put(LifecycleConfig.ENV_RETENTION_DAYS, "thirty");

        assertThrows(IllegalArgumentException.class, () -> LifecycleConfig.fromMap(values));
    }

    @Test
    void fromMap_rejectsZeroRetention() {
        Map<String, String> values = baseValues();
        values.put(LifecycleConfig.ENV_RETENTION_DAYS, "0");

        assertThrows(IllegalArgumentException.class, () -> LifecycleConfig.fromMap(values));
    }

    @Test
    void fromMap_rejectsAmbiguousDryRunValue() {
        Map<String, String> values = baseValues();
        values.put(LifecycleConfig.ENV_DRY_RUN, "yes");

        assertThrows(IllegalArgumentException.class, () -> LifecycleConfig.fromMap(values));
    }

    @Test
    void fromMap_rejectsMissingOrMalformedTenant() {
        assertThrows(IllegalArgumentException.class,
                () -> LifecycleConfig.fromMap(Map.of(LifecycleConfig.ENV_REALM, "janus")));

        Map<String, String> values = baseValues();
        values.put(LifecycleConfig.ENV_TENANT_ID, "common");
        assertThrows(IllegalArgumentException.class, () -> LifecycleConfig.fromMap(values));
    }

    private static Map<String, String> baseValues() {
        Map<String, String> values = new HashMap<>();
        values.put(LifecycleConfig.ENV_TENANT_ID, TENANT_ID);
        values.put(LifecycleConfig.ENV_REALM, "janus");
        return values;
    }
}
