package com.arpay.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lightweight health probe endpoint.
 * Responds to GET / and GET /health without requiring an API key.
 * Used by Coolify, Docker health checks, and load-balancer probes.
 */
@RestController
public class HealthController {

    @GetMapping({"/", "/health"})
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "arpay-notifications"));
    }
}

