package dev.infrai.fieldservice;

import dev.infrai.fieldservice.FieldServiceAgentLoop.DispatchDecision;
import dev.infrai.fieldservice.FieldServiceAgentLoop.PhotoAssessment;
import dev.infrai.fieldservice.FieldServiceAgentLoop.WorkOrder;

public final class DispatchFailureExample {
    private DispatchFailureExample() {}

    public static void main(String[] args) {
        InfraiErrorsClient errors = new InfraiErrorsClient(ServiceConfig.fromEnvironment());
        FieldServiceAgentLoop loop = new FieldServiceAgentLoop(
                (exception, key) -> errors.capture(exception, key));

        WorkOrder workOrder = new WorkOrder("WO-1842", "photos/WO-1842/panel.jpg");
        PhotoAssessment result = PhotoAssessment.failed(
                "PhotoAssessmentException: panel label could not be classified");
        DispatchDecision decision = loop.review(workOrder, result);

        System.out.printf("workOrder=%s status=%s technicianFollowUp=%s%n",
                decision.workOrderId(), decision.status(), decision.technicianFollowUp());
    }
}
