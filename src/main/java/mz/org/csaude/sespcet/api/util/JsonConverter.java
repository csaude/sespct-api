package mz.org.csaude.sespcet.api.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Converter(autoApply = false)
public class JsonConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attributes) {
        try {
            return attributes != null ? mapper.writeValueAsString(attributes) : null;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Erro ao converter mapa para JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String json) {
        try {
            return json != null ? mapper.readValue(json, HashMap.class) : null;
        } catch (IOException e) {
            throw new IllegalArgumentException("Erro ao converter JSON para mapa", e);
        }
    }
}
