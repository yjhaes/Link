package com.example.shortlink.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

public class ValidMinutesDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw JsonMappingException.from(parser, "validMinutes must be an integer.");
        }

        try {
            return parser.getBigIntegerValue().intValueExact();
        } catch (ArithmeticException exception) {
            throw JsonMappingException.from(parser, "validMinutes is outside the integer range.", exception);
        }
    }
}
