package io.github.goldjg.janus;

/** DCR admission boundary, separate from metadata validation and Entra provisioning. */
@FunctionalInterface
interface RegistrationAdmission {
    AdmissionTicket authorize(DcrRequest request);

    record AdmissionTicket(String subjectKey, Runnable consumeOnSuccess) {
        public AdmissionTicket {
            if (subjectKey == null || subjectKey.isBlank() || consumeOnSuccess == null) {
                throw new IllegalArgumentException("admission ticket fields are required");
            }
        }

        void consume() {
            consumeOnSuccess.run();
        }
    }
}
