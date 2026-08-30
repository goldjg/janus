package io.github.goldjg.janus;

/** Result of registration, including whether provisioning was idempotently reused. */
record RegistrationOutcome(ProvisionedClient client, boolean reused) {}
