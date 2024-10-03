package com.fsck.k9.mailstore;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/* TODO proper deserialization at some point */
public class JsonLdDeserializer {
    public JsonLd deserialize(String jsonString) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> data = objectMapper.readValue(jsonString, Map.class);
        JsonLd jsonLd= new JsonLd();
        jsonLd.setData(data);
        return jsonLd;
    }
}
