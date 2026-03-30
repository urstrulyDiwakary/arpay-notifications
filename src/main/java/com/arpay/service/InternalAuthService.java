package com.arpay.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal authentication service for service-to-service communication.
 * Validates API keys for internal API endpoints.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternalAuthService {
    
    @Value("${internal.api.keys:arpay-internal-key-change-in-production}")
    private String apiKeysConfig;
    
    @Value("${internal.api.header:X-API-Key}")
    private String apiKeyHeader;
    
    private Set<String> validApiKeys;
    
    /**
     * Check if the provided API key is valid
     */
    public boolean isValidApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        
        // Lazy initialization of API keys set
        if (validApiKeys == null) {
            initializeApiKeys();
        }
        
        return validApiKeys.contains(apiKey);
    }
    
    /**
     * Get the configured API key header name
     */
    public String getApiKeyHeader() {
        return apiKeyHeader;
    }
    
    /**
     * Initialize the set of valid API keys from configuration
     */
    private synchronized void initializeApiKeys() {
        if (validApiKeys != null) {
            return;
        }
        
        List<String> keys = Arrays.stream(apiKeysConfig.split(","))
            .map(String::trim)
            .filter(k -> !k.isBlank())
            .collect(Collectors.toList());
        
        validApiKeys = Set.copyOf(keys);
        
        log.info("Initialized {} internal API keys", validApiKeys.size());
        
        // Warn if using default key
        if (validApiKeys.contains("arpay-internal-key-change-in-production")) {
            log.warn("WARNING: Using default internal API key! Change INTERNAL_API_KEYS environment variable in production.");
        }
    }
}
