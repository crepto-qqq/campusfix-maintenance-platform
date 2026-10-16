package com.campusfix.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config/maps")
@CrossOrigin(
        origins = {"http://localhost:5500", "http://127.0.0.1:5500"},
        allowCredentials = "true"
)
public class MapsConfigurationController {

    private final String apiKey;

    public MapsConfigurationController(
            @Value("${campusfix.google-maps.api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @GetMapping
    public MapsConfiguration getConfiguration() {
        return new MapsConfiguration(!apiKey.isBlank(), apiKey);
    }

    public record MapsConfiguration(boolean enabled, String apiKey) {
    }
}
