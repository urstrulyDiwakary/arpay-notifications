package com.arpay.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

@Configuration
@Slf4j
public class FirebaseConfig {

    @Value("${firebase.enabled:false}")
    private boolean firebaseEnabled;

    /**
     * Path to the service account JSON file.
     * Used when the file is volume-mounted into the container.
     * Superseded by {@code serviceAccountJsonBase64} when that is set.
     */
    @Value("${firebase.service-account-key-path:classpath:firebase/firebase-service-account.json}")
    private String serviceAccountKeyPath;

    /**
     * Base64-encoded content of firebase-service-account.json.
     * <p>
     * Preferred method for Coolify deployments — no volume mount required.
     * To generate: {@code base64 -w 0 firebase-service-account.json}
     * Then paste the output as the {@code FIREBASE_SERVICE_ACCOUNT_JSON_BASE64} env var in Coolify.
     * <p>
     * Takes priority over {@code firebase.service-account-key-path} when set.
     */
    @Value("${firebase.service-account-json-base64:}")
    private String serviceAccountJsonBase64;

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        if (!firebaseEnabled) {
            log.info("Firebase FCM disabled (firebase.enabled=false). Push delivery will be skipped.");
            return null;
        }

        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                return FirebaseMessaging.getInstance();
            }

            GoogleCredentials credentials = loadCredentials();
            if (credentials == null) {
                return null;
            }

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("Firebase Admin SDK initialized successfully.");
            return FirebaseMessaging.getInstance();

        } catch (IOException e) {
            log.error("Failed to initialize Firebase Admin SDK: {}. FCM push disabled.", e.getMessage());
            return null;
        }
    }

    /**
     * Load credentials using the best available source:
     * <ol>
     *   <li>Base64 env var ({@code FIREBASE_SERVICE_ACCOUNT_JSON_BASE64}) — no file needed</li>
     *   <li>File path ({@code FIREBASE_SERVICE_ACCOUNT_KEY_PATH}) — volume-mounted JSON</li>
     * </ol>
     */
    private GoogleCredentials loadCredentials() throws IOException {
        // --- Option 1: base64 env var (Coolify-native, no volume needed) ---
        if (serviceAccountJsonBase64 != null && !serviceAccountJsonBase64.isBlank()) {
            log.info("Loading Firebase credentials from FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 env var.");
            try {
                byte[] decoded = Base64.getDecoder().decode(serviceAccountJsonBase64.trim());
                return GoogleCredentials.fromStream(new ByteArrayInputStream(decoded));
            } catch (IllegalArgumentException e) {
                log.error("FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 is not valid base64: {}. FCM push disabled.", e.getMessage());
                return null;
            }
        }

        // --- Option 2: file path (classpath or filesystem volume mount) ---
        Resource resource;
        if (serviceAccountKeyPath.startsWith("classpath:")) {
            resource = new ClassPathResource(serviceAccountKeyPath.replace("classpath:", ""));
        } else {
            resource = new FileSystemResource(serviceAccountKeyPath);
        }

        if (!resource.exists()) {
            log.error("Firebase service account key not found at: {}. " +
                      "Set FIREBASE_SERVICE_ACCOUNT_JSON_BASE64 or mount the file at that path. " +
                      "FCM push disabled.", serviceAccountKeyPath);
            return null;
        }

        log.info("Loading Firebase credentials from file: {}", serviceAccountKeyPath);
        try (InputStream is = resource.getInputStream()) {
            return GoogleCredentials.fromStream(is);
        }
    }
}
