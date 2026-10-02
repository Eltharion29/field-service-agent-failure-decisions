package dev.infrai.fieldservice;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

public final class InfraiErrorsClient {
    private final ServiceConfig config;
    private final HttpClient http;

    public InfraiErrorsClient(ServiceConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build());
    }

    InfraiErrorsClient(ServiceConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /** infrai.errors.capture maps to this explicit REST call. */
    public CaptureReceipt capture(String exception, String idempotencyKey) {
        String body = "{\"exception\":\"" + jsonEscape(exception) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(config.baseUri().resolve("/v1/errors/capture"))
                .timeout(config.requestTimeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .method("POST", HttpRequest.BodyPublishers.ofString(body))
                .build();

        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            HttpResponse<String> response = send(request);
            Envelope envelope = Envelope.decode(response.body());
            if (response.statusCode() == 429 && attempt < config.maxAttempts()) {
                sleep(retryDelay(response, attempt));
                continue;
            }
            if (response.statusCode() >= 500) {
                throw new InfraiTransportException("Infrai request returned HTTP " + response.statusCode());
            }
            if (!envelope.ok()) {
                throw new InfraiRequestException(envelope.error(), response.statusCode());
            }
            return new CaptureReceipt(response.statusCode(), envelope.data(), envelope.metadata());
        }
        throw new InfraiTransportException("Retry budget exhausted");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new InfraiTransportException("Could not reach error capture", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraiTransportException("Error capture was interrupted", e);
        }
    }

    private static Duration retryDelay(HttpResponse<?> response, int attempt) {
        Optional<String> value = response.headers().firstValue("Retry-After");
        if (value.isPresent()) {
            String raw = value.get().trim();
            try {
                return Duration.ofSeconds(Math.max(0, Long.parseLong(raw)));
            } catch (NumberFormatException ignored) {
                try {
                    return Duration.between(ZonedDateTime.now(),
                            ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)).isNegative()
                            ? Duration.ZERO
                            : Duration.between(ZonedDateTime.now(),
                                    ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME));
                } catch (RuntimeException invalidDate) {
                    // Fall through to bounded exponential backoff.
                }
            }
        }
        return Duration.ofMillis(250L * (1L << Math.min(attempt - 1, 5)));
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraiTransportException("Retry wait was interrupted", e);
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }

    record Envelope(boolean ok, String data, String error, String metadata) {
        static Envelope decode(String json) {
            if (json == null || json.isBlank() || json.charAt(0) != '{') {
                throw new InfraiTransportException("Response was not a JSON envelope");
            }
            Boolean ok = booleanMember(json, "ok");
            if (ok == null) throw new InfraiTransportException("Response envelope has no ok field");
            return new Envelope(ok, rawMember(json, "data"), rawMember(json, "error"), rawMember(json, "metadata"));
        }

        private static Boolean booleanMember(String json, String name) {
            String value = rawMember(json, name);
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
            return null;
        }

        private static String rawMember(String json, String name) {
            int key = json.indexOf("\"" + name + "\"");
            if (key < 0) return null;
            int colon = json.indexOf(':', key + name.length() + 2);
            if (colon < 0) return null;
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            if (start >= json.length()) return null;
            char opening = json.charAt(start);
            if (opening == '{' || opening == '[') {
                char closing = opening == '{' ? '}' : ']';
                int depth = 0;
                boolean quoted = false;
                boolean escaped = false;
                for (int i = start; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (quoted) {
                        if (escaped) escaped = false;
                        else if (c == '\\') escaped = true;
                        else if (c == '"') quoted = false;
                    } else if (c == '"') quoted = true;
                    else if (c == opening) depth++;
                    else if (c == closing && --depth == 0) return json.substring(start, i + 1);
                }
                return null;
            }
            int end = start;
            boolean quoted = opening == '"';
            if (quoted) {
                end++;
                boolean escaped = false;
                while (end < json.length()) {
                    char c = json.charAt(end++);
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') break;
                }
            } else {
                while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            }
            return json.substring(start, end).trim();
        }
    }

    public record CaptureReceipt(int httpStatus, String data, String metadata) {}

    public static final class InfraiRequestException extends RuntimeException {
        private final int httpStatus;
        InfraiRequestException(String error, int httpStatus) {
            super(error == null ? "Infrai rejected the capture request" : error);
            this.httpStatus = httpStatus;
        }
        public int httpStatus() { return httpStatus; }
    }

    public static final class InfraiTransportException extends RuntimeException {
        InfraiTransportException(String message) { super(message); }
        InfraiTransportException(String message, Throwable cause) { super(message, cause); }
    }
}
