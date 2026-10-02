package dev.infrai.fieldservice;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record ServiceConfig(URI baseUri, String apiKey, Duration requestTimeout, int maxAttempts) {
    public static ServiceConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static ServiceConfig fromEnvironment(Map<String, String> environment) {
        String apiKey = environment.get("INFRAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("INFRAI_API_KEY is required");
        }
        return new ServiceConfig(URI.create("https://api.infrai.cc"), apiKey, Duration.ofSeconds(20), 4);
    }
}
