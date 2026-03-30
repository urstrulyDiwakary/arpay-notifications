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

import java.io.IOException;
import java.io.InputStream;

@Configuration
@Slf4j
public class FirebaseConfig {

    @Value("${firebase.enabled:false}")
    private boolean firebaseEnabled;

    @Value("${firebase.service-account-key-path:classpath:firebase/firebase-service-account.json}")
    private String serviceAccountKeyPath;

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

            Resource resource;
            if (serviceAccountKeyPath.startsWith("classpath:")) {
                resource = new ClassPathResource(serviceAccountKeyPath.replace("classpath:", ""));
            } else {
                resource = new FileSystemResource(serviceAccountKeyPath);
            }

            if (!resource.exists()) {
                log.warn("Firebase service account key not found at: {}. FCM push disabled.", serviceAccountKeyPath);
                return null;
            }

            try (InputStream is = resource.getInputStream()) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(is);
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("Firebase Admin SDK initialized successfully.");
                return FirebaseMessaging.getInstance();
            }
        } catch (IOException e) {
            log.error("Failed to initialize Firebase Admin SDK: {}. FCM push disabled.", e.getMessage());
            return null;
        }
    }
}
