package io.github.goldjg.janus;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated configuration for the standalone JANUS lifecycle job.
 *
 * <p>Cleanup is deliberately dry-run by default. Invalid values fail closed
 * instead of falling back to a value that could make deletion more aggressive.
 */
public record LifecycleConfig(
        String tenantId,
        String realm,
        Duration retention,
        boolean dryRun,
        int maximumDeletes,
        Duration maximumEvidenceAge) {

    public static final String ENV_REALM = "JANUS_REALM";
    public static final String ENV_TENANT_ID = "JANUS_TENANT_ID";
    public static final String ENV_RETENTION_DAYS = "JANUS_CLEANUP_RETENTION_DAYS";
    public static final String ENV_DRY_RUN = "JANUS_CLEANUP_DRY_RUN";
    public static final String ENV_MAXIMUM_DELETES = "JANUS_CLEANUP_MAX_DELETE_COUNT";
    public static final String ENV_MAXIMUM_EVIDENCE_AGE_HOURS =
            "JANUS_CLEANUP_EVIDENCE_MAX_AGE_HOURS";

    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int DEFAULT_MAXIMUM_DELETES = 10;
    public static final int DEFAULT_MAXIMUM_EVIDENCE_AGE_HOURS = 48;

    private static final int MAX_RETENTION_DAYS = 3650;
    private static final int MAX_DELETE_LIMIT = 1000;
    private static final int MAX_EVIDENCE_AGE_HOURS = 24 * 30;
    private static final Pattern REALM_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Validate an explicitly constructed configuration. */
    public LifecycleConfig {
        try {
            tenantId = java.util.UUID.fromString(tenantId).toString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(ENV_TENANT_ID + " must be a canonical UUID", e);
        }
        if (realm == null || !REALM_PATTERN.matcher(realm).matches()) {
            throw new IllegalArgumentException(
                    ENV_REALM + " must match " + REALM_PATTERN.pattern());
        }
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(maximumEvidenceAge, "maximumEvidenceAge");
        if (retention.isNegative() || retention.isZero()
                || retention.compareTo(Duration.ofDays(MAX_RETENTION_DAYS)) > 0) {
            throw new IllegalArgumentException("retention must be between 1 and "
                    + MAX_RETENTION_DAYS + " days");
        }
        if (maximumDeletes < 1 || maximumDeletes > MAX_DELETE_LIMIT) {
            throw new IllegalArgumentException("maximumDeletes must be between 1 and "
                    + MAX_DELETE_LIMIT);
        }
        if (maximumEvidenceAge.isNegative() || maximumEvidenceAge.isZero()
                || maximumEvidenceAge.compareTo(Duration.ofHours(MAX_EVIDENCE_AGE_HOURS)) > 0) {
            throw new IllegalArgumentException("maximumEvidenceAge must be between 1 and "
                    + MAX_EVIDENCE_AGE_HOURS + " hours");
        }
    }

    /** Load and strictly validate lifecycle settings from process environment variables. */
    public static LifecycleConfig fromEnvironment() {
        return fromMap(System.getenv());
    }

    /**
     * Load settings from a map. This method exists so configuration can be tested
     * without changing the process environment.
     *
     * @param values environment-style key/value pairs
     * @return validated lifecycle configuration
     */
    static LifecycleConfig fromMap(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        String realm = required(values, ENV_REALM);
        String tenantId = required(values, ENV_TENANT_ID);
        int retentionDays = boundedInteger(values, ENV_RETENTION_DAYS,
                DEFAULT_RETENTION_DAYS, 1, MAX_RETENTION_DAYS);
        boolean dryRun = strictBoolean(values.get(ENV_DRY_RUN), true, ENV_DRY_RUN);
        int maximumDeletes = boundedInteger(values, ENV_MAXIMUM_DELETES,
                DEFAULT_MAXIMUM_DELETES, 1, MAX_DELETE_LIMIT);
        int evidenceHours = boundedInteger(values, ENV_MAXIMUM_EVIDENCE_AGE_HOURS,
                DEFAULT_MAXIMUM_EVIDENCE_AGE_HOURS, 1, MAX_EVIDENCE_AGE_HOURS);
        return new LifecycleConfig(tenantId, realm, Duration.ofDays(retentionDays), dryRun,
                maximumDeletes, Duration.ofHours(evidenceHours));
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static int boundedInteger(
            Map<String, String> values,
            String name,
            int defaultValue,
            int minimum,
            int maximum) {
        String raw = values.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        final int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum
                    + " and " + maximum);
        }
        return value;
    }

    private static boolean strictBoolean(String raw, boolean defaultValue, String name) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(raw.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw.trim())) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }
}
