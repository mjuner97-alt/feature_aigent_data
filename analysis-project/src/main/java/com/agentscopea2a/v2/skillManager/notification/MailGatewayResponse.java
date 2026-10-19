package com.agentscopea2a.v2.skillManager.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;

/** Validate an explicit business acknowledgement rather than treating HTTP 200 as success. */
public final class MailGatewayResponse {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MailGatewayResponse() {}

    public static void requireAccepted(String response, String successCodes) {
        try {
            JsonNode root = JSON.readTree(response);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("missing acknowledgement");
            JsonNode success = root.get("success");
            JsonNode code = root.get("code");
            if (success != null && (!success.isBoolean() || !success.booleanValue()))
                throw new IllegalArgumentException("success is not true");
            if (code != null && Arrays.stream(successCodes.split(",")).map(String::trim)
                    .noneMatch(value -> value.equals(code.asText())))
                throw new IllegalArgumentException("rejected business code: " + code.asText());
            if (success == null && code == null) throw new IllegalArgumentException("unknown acknowledgement format");
        } catch (Exception e) {
            throw new Rejected("MailGatewayRejected: " + e.getMessage(), response);
        }
    }

    public static final class Rejected extends RuntimeException {
        private final String response;
        public Rejected(String message, String response) { super(message); this.response = response; }
        public String response() { return response; }
    }
}
