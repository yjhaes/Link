package com.example.shortlink.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

public class EnabledDeserializer extends JsonDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_TRUE && parser.currentToken() != JsonToken.VALUE_FALSE) {
            throw JsonMappingException.from(parser, "enabled must be a boolean.");
        }
        return parser.getBooleanValue();
    }
}
