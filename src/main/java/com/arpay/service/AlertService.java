package com.arpay.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Alert service - sends notifications to Slack/email when issues detected.
 * 
 * Simple integration - no complex monitoring stack needed.
 */
@Service
@Slf4j
public class AlertService {
    
    private final RestTemplate restTemplate;
    
    @Value("${alerting.slack.webhook-url:}")
    private String slackWebhookUrl;
    
    @Value("${alerting.enabled:true}")
    private boolean alertingEnabled;
    
    @Value("${spring.application.name:arpay-notifications}")
    private String applicationName;
    
    public AlertService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }
    
    /**
     * Send alert to configured channels (Slack, email, etc.)
     */
    public void sendAlert(String title, Map<String, Object> alerts) {
        if (!alertingEnabled) {
            log.info("Alerting disabled - would send: {}", title);
            return;
        }
        
        // Log alert locally (always works)
        logAlertToConsole(title, alerts);
        
        // Send to Slack if configured
        if (slackWebhookUrl != null && !slackWebhookUrl.isEmpty()) {
            sendSlackAlert(title, alerts);
        }
        
        // TODO: Add email alerting if needed
    }
    
    /**
     * Log alert to console (picked up by log monitoring tools)
     */
    private void logAlertToConsole(String title, Map<String, Object> alerts) {
        log.error("🚨 ALERT: {}", title);
        alerts.forEach((key, value) -> log.error("   - {}: {}", key, value));
    }
    
    /**
     * Send alert to Slack webhook
     */
    private void sendSlackAlert(String title, Map<String, Object> alerts) {
        try {
            Map<String, Object> payload = buildSlackPayload(title, alerts);
            
            restTemplate.postForObject(slackWebhookUrl, payload, String.class);
            
            log.info("Slack alert sent: {}", title);
        } catch (Exception e) {
            log.error("Failed to send Slack alert: {}", e.getMessage());
        }
    }
    
    /**
     * Build Slack message payload
     */
    private Map<String, Object> buildSlackPayload(String title, Map<String, Object> alerts) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("text", "🚨 " + applicationName + ": " + title);
        
        // Build attachments
        StringBuilder details = new StringBuilder();
        alerts.forEach((key, value) -> {
            details.append("• ").append(key).append(": ").append(value).append("\n");
        });
        
        Map<String, Object> attachment = new HashMap<>();
        attachment.put("color", "danger");
        attachment.put("text", details.toString());
        attachment.put("footer", applicationName);
        attachment.put("ts", System.currentTimeMillis() / 1000);
        
        payload.put("attachments", new Object[] { attachment });
        
        return payload;
    }
}
