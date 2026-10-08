package com.agentscopea2a.v2.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MetricIdsDeserializer extends StdDeserializer<String> {
    public MetricIdsDeserializer() { super(String.class); }
    @Override public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode n = p.getCodec().readTree(p);
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return n.asText();
        if (!n.isArray()) return (String) ctxt.handleUnexpectedToken(String.class, p);
        List<String> ids = new ArrayList<>();
        for (JsonNode item : n) if (item.isIntegralNumber() && item.asLong() > 0) ids.add(String.valueOf(item.asLong()));
        return String.join(",", ids);
    }
}
