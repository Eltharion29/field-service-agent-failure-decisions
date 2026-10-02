package dev.infrai.fieldservice;

import java.util.ArrayList;
import java.util.List;

import dev.infrai.fieldservice.FieldServiceAgentLoop.DispatchDecision;
import dev.infrai.fieldservice.FieldServiceAgentLoop.DispatchStatus;
import dev.infrai.fieldservice.FieldServiceAgentLoop.PhotoAssessment;
import dev.infrai.fieldservice.FieldServiceAgentLoop.WorkOrder;

public final class FieldServiceAgentLoopTest {
    public static void main(String[] args) {
        List<String> captures = new ArrayList<>();
        FieldServiceAgentLoop loop = new FieldServiceAgentLoop(
                (exception, key) -> captures.add(exception + "|" + key));

        DispatchDecision first = loop.review(
                new WorkOrder("WO-1842", "photos/WO-1842/panel.jpg"),
                PhotoAssessment.failed("PhotoAssessmentException: unreadable label"));
        DispatchDecision retry = loop.review(
                new WorkOrder("WO-1842", "photos/WO-1842/panel.jpg"),
                PhotoAssessment.failed("PhotoAssessmentException: unreadable label"));

        check(first.status() == DispatchStatus.TECHNICIAN_REVIEW_REQUIRED,
                "failed photo assessment must pause dispatch");
        check(first.technicianFollowUp(), "failed photo assessment must request follow-up");
        check(captures.size() == 2, "each failed loop iteration must be observable");
        check(captures.get(0).substring(captures.get(0).indexOf('|'))
                        .equals(captures.get(1).substring(captures.get(1).indexOf('|'))),
                "retries for one work order must reuse the idempotency key");
        System.out.println("PASS: photo failure pauses dispatch and schedules technician review");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
