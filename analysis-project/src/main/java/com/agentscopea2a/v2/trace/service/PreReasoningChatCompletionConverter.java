package com.agentscopea2a.v2.trace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/** Converts a captured PRE_REASONING trace event into an OpenAI-compatible request body. */
@Service
public class PreReasoningChatCompletionConverter {

    public ObjectNode convert(JsonNode event) {
        if (event == null || !"PRE_REASONING".equalsIgnoreCase(event.path("type").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be PRE_REASONING");
        }
        String model = event.path("model_name").asText("");
        if (model.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model_name is required");
        }

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        appendSystemMessage(messages, event.get("system_message"));

        JsonNode inputs = event.get("input_messages");
        if (inputs != null && inputs.isArray()) {
            for (JsonNode input : inputs) appendMessage(messages, input);
        }
        body.put("stream", false);
        return body;
    }

    private void appendSystemMessage(ArrayNode messages, JsonNode systemMessage) {
        String content = extractText(systemMessage);
        if (!content.isBlank()) addMessage(messages, "system", content);
    }

    private void appendMessage(ArrayNode messages, JsonNode message) {
        if (message == null || !message.isObject()) return;
        String role = message.path("role").asText("").toLowerCase();
        if (role.isBlank()) return;
        String content = extractText(message);
        if (!content.isBlank()) addMessage(messages, role, content);
    }

    private void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode converted = messages.addObject();
        converted.put("role", role);
        converted.put("content", content);
    }

    private String extractText(JsonNode message) {
        if (message == null || message.isNull()) return "";
        JsonNode content = message.has("content") ? message.get("content") : message;
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (!content.isArray()) return content.path("text").asText("");

        List<String> parts = new ArrayList<>();
        for (JsonNode block : content) {
            if (block.isTextual()) {
                parts.add(block.asText());
                continue;
            }
            String text = block.path("text").asText("");
            if (text.isBlank()) text = block.path("thinking").asText("");
            if (!text.isBlank()) parts.add(text);
        }
        return String.join("\n", parts);
    }
}
