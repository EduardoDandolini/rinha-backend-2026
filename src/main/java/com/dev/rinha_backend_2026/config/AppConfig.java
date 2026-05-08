package com.dev.rinha_backend_2026.config;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.util.Map;

@Configuration
public class AppConfig {

    @Bean
    public NormalizationConfig normalizationConfig(ObjectMapper mapper) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/normalization.json")) {
            return mapper.readValue(is, NormalizationConfig.class);
        }
    }

    @Bean
    public Map<String, Float> mccRisk(ObjectMapper mapper) throws Exception {
        JavaType type = mapper.getTypeFactory().constructMapType(Map.class, String.class, Float.class);
        try (InputStream is = getClass().getResourceAsStream("/mcc_risk.json")) {
            return mapper.readValue(is, type);
        }
    }
}
