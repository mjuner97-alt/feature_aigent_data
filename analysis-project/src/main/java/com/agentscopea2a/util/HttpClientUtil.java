package com.agentscopea2a.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 通知 HTTP 客户端的最小实现。
 */
public final class HttpClientUtil {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpClientUtil() {
    }

    public static String postWithHeaders(String url, Map<String, Object> body,
                                          Map<String, String> headers)
            throws IOException, InterruptedException {
        final String requestJson;
        try {
            requestJson = JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IOException("Unable to serialize notification request", e);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson));
        headers.forEach(request::header);
        HttpResponse<String> response = CLIENT.send(
                request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Notification request failed with HTTP "
                    + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }
}
