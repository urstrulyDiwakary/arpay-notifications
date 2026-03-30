package com.arpay.config;

import org.springframework.context.annotation.Configuration;

/**
 * CORS configuration has been moved to SecurityConfig.java
 * to ensure it's properly applied within the Spring Security filter chain.
 */
@Configuration
public class CorsConfig {
    // Bean moved to SecurityConfig.corsConfigurationSource()
}
