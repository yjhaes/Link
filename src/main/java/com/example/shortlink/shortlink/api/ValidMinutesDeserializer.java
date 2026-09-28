package com.example.shortlink.shortlink.api;

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
        if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
            throw JsonMappingException.from(parser, "validMinutes must be a number.");
        }

        try {
            return parser.getDecimalValue().intValueExact();
        } catch (ArithmeticException exception) {
            throw JsonMappingException.from(parser, "validMinutes must be a whole number.", exception);
        }
    }
}
