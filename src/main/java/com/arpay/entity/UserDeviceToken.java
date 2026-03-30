package com.arpay.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * User device token entity - supports multiple devices per user.
 * Replaces the single deviceToken field in User entity.
 */
@Entity
@Table(name = "user_device_tokens", 
       indexes = {
           @Index(name = "idx_user_tokens_user_id", columnList = "user_id"),
           @Index(name = "idx_user_tokens_active", columnList = "user_id, is_active"),
           @Index(name = "idx_user_tokens_token", columnList = "device_token")
       })
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    
    @Column(name = "device_token", nullable = false, length = 500)
    private String deviceToken;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", length = 20)
    private DeviceType deviceType;
    
    @Column(name = "app_version", length = 50)
    private String appVersion;
    
    @Column(name = "platform_version", length = 50)
    private String platformVersion;
    
    @Column(name = "app_identifier", length = 100)
    private String appIdentifier;
    
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
    
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
    
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;
    
    @Column(name = "invalidation_reason", length = 100)
    private String invalidationReason;
    
    @Column(name = "fcm_error_message", columnDefinition = "TEXT")
    private String fcmErrorMessage;
    
    @Column(name = "fcm_error_count")
    private Integer fcmErrorCount = 0;
    
    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastUsedAt == null) {
            lastUsedAt = LocalDateTime.now();
        }
        if (isActive == null) {
            isActive = true;
        }
        if (fcmErrorCount == null) {
            fcmErrorCount = 0;
        }
    }
    
    /**
     * Mark this token as invalid due to FCM error
     */
    public void markInvalid(String reason, String errorMessage) {
        this.isActive = false;
        this.invalidatedAt = LocalDateTime.now();
        this.invalidationReason = reason;
        this.fcmErrorMessage = errorMessage;
    }
    
    /**
     * Increment FCM error count
     */
    public void incrementFcmErrorCount() {
        this.fcmErrorCount = this.fcmErrorCount + 1;
    }
    
    /**
     * Reset FCM error count on successful delivery
     */
    public void resetFcmErrorCount() {
        this.fcmErrorCount = 0;
    }
    
    /**
     * Update last used timestamp
     */
    public void touch() {
        this.lastUsedAt = LocalDateTime.now();
    }
    
    public enum DeviceType {
        ANDROID,
        IOS,
        WEB
    }
}
