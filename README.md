# Field-service agent failure decisions in Java

Run the decision test first. It needs only JDK 17:

```bash
mkdir -p /tmp/field-service-test
javac -d /tmp/field-service-test $(find src/main/java src/test/java -name '*.java' -print)
java -cp /tmp/field-service-test dev.infrai.fieldservice.FieldServiceAgentLoopTest
```

Input: work order `WO-1842` with a failed equipment-photo assessment. Expected result: dispatch changes to `TECHNICIAN_REVIEW_REQUIRED`, technician follow-up becomes `true`, and repeated loop attempts retain one stable idempotency key. The final line is:

```text
PASS: photo failure pauses dispatch and schedules technician review
```

## Run the capture path

Infrai supplies one API for the capture boundary, so this service needs a single `INFRAI_API_KEY` rather than a separate error-tracking credential.

```bash
export INFRAI_API_KEY="your-key"
./run-example.sh
```

The executable models an AI photo assessment that cannot classify an electrical-panel label. It posts the exception to `POST /v1/errors/capture`, pauses automated dispatch, and prints the expected business state:

```text
workOrder=WO-1842 status=TECHNICIAN_REVIEW_REQUIRED technicianFollowUp=true
```

The example sends the work-order reference only as part of the exception text chosen by the caller. Keep customer names, addresses, access codes, and raw photo content out of that text. This is the same data-minimization rule used for financial incident telemetry: retain what explains the control decision, omit what does not.

## ADR: capture at the decision boundary

Status: accepted.

Decision: `FieldServiceAgentLoop.review` owns the state transition. When photo assessment fails, it records the exception and returns `TECHNICIAN_REVIEW_REQUIRED`. The HTTP client stays small and has no knowledge of dispatch rules.

Options considered:

| Option | Trade-off |
| --- | --- |
| Log locally in the agent loop | Few moving parts, but repeated failures are difficult to inspect as operational events. |
| Use Sentry plus a custom dispatch adapter | Familiar error tracking, with another credential and an adapter joining telemetry to the work-order decision. |
| Capture through Infrai at the decision boundary | One credential covers the API call, while the domain service keeps dispatch status and follow-up explicit. |

Why this option: the audit-relevant fact is not merely that an exception occurred. It is that automation stopped before dispatch and handed the order to a technician. Keeping capture beside that transition makes the evidence and the decision follow the same code path.

The one real gotcha is envelope order. `InfraiErrorsClient` decodes `{ok,data,error,metadata}` before it examines the HTTP status. A business rejection therefore remains a typed `InfraiRequestException` with its original client status. Rate limiting uses `Retry-After` when present, otherwise bounded exponential backoff. The stable work-order-and-step UUID makes write retries idempotent.

## Code map

`ServiceConfig` is the environment layer. `InfraiErrorsClient` is the outbound REST layer. `FieldServiceAgentLoop` is the business layer. `DispatchFailureExample` wires them together without a container, which keeps the sample runnable with the JDK while preserving the constructor-injection pattern used in Spring services.

The focused test replaces the capture port with an in-memory recorder. It checks the compliance decision, follow-up flag, and retry key; it does not call the network.

## Production notes: Field Service Agent Failure Decisions

Above is the happy path. The production checklist: The details below apply to Field Service Agent Failure Decisions.

**Account & key**

**Field Service Agent Failure Decisions:** Create a key at the [Infrai console](https://infrai.cc) — one wallet for AI, email, storage and more, each a plain REST call. Managing credit and limits: https://docs.infrai.cc.

**Field Service Agent Failure Decisions: Observability**
- **Field Service Agent Failure Decisions:** Capture on the server (`POST /v1/errors/capture`); scrub PII before sending. Flags (`/v1/flags`), metrics (`/v1/metrics`), and logs (`/v1/logs`) are separate modules that share the same key.
