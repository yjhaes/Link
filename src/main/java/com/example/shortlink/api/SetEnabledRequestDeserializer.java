package com.example.shortlink.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/** Validates the complete state request without changing other endpoints' JSON settings. */
public class SetEnabledRequestDeserializer extends JsonDeserializer<SetEnabledRequest> {
    @Override
    public SetEnabledRequest deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode body = parser.getCodec().readTree(parser);
        if (!body.isObject()) {
            throw JsonMappingException.from(parser, "Request body must be an object.");
        }
        JsonNode enabled = body.get("enabled");
        if (enabled != null && !enabled.isNull() && !enabled.isBoolean()) {
            throw JsonMappingException.from(parser, "enabled must be a boolean.");
        }
        if (parser.nextToken() != null) {
            throw JsonMappingException.from(
                    parser, "Request body must contain exactly one JSON object.");
        }
        return new SetEnabledRequest(
                enabled == null || enabled.isNull() ? null : enabled.booleanValue());
    }
}
