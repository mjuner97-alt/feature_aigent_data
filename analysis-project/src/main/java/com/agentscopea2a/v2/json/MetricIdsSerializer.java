package com.agentscopea2a.v2.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;

public class MetricIdsSerializer extends StdSerializer<String> {
    public MetricIdsSerializer() { super(String.class); }
    @Override public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeStartArray();
        if (value != null && !value.isBlank()) for (String id : value.split(",")) if (!id.isBlank()) gen.writeNumber(Long.parseLong(id.trim()));
        gen.writeEndArray();
    }
}
