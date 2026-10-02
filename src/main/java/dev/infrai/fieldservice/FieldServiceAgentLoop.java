package dev.infrai.fieldservice;

import java.util.Objects;
import java.util.UUID;

public final class FieldServiceAgentLoop {
    private final FailureCapture capture;

    public FieldServiceAgentLoop(FailureCapture capture) {
        this.capture = Objects.requireNonNull(capture);
    }

    public DispatchDecision review(WorkOrder workOrder, PhotoAssessment assessment) {
        if (assessment.failure() == null) {
            return new DispatchDecision(workOrder.id(), DispatchStatus.READY_FOR_DISPATCH, false);
        }

        String incidentId = UUID.nameUUIDFromBytes(
                (workOrder.id() + ":photo-assessment").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        capture.record(assessment.failure(), incidentId);
        return new DispatchDecision(workOrder.id(), DispatchStatus.TECHNICIAN_REVIEW_REQUIRED, true);
    }

    @FunctionalInterface
    public interface FailureCapture {
        void record(String exception, String idempotencyKey);
    }

    public record WorkOrder(String id, String photoReference) {
        public WorkOrder {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("work order id is required");
            if (photoReference == null || photoReference.isBlank()) throw new IllegalArgumentException("photo reference is required");
        }
    }

    public record PhotoAssessment(String equipmentCondition, String failure) {
        public static PhotoAssessment accepted(String condition) {
            return new PhotoAssessment(condition, null);
        }
        public static PhotoAssessment failed(String exception) {
            return new PhotoAssessment(null, Objects.requireNonNull(exception));
        }
    }

    public record DispatchDecision(String workOrderId, DispatchStatus status, boolean technicianFollowUp) {}

    public enum DispatchStatus {
        READY_FOR_DISPATCH,
        TECHNICIAN_REVIEW_REQUIRED
    }
}
