package com.arpay.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for device token registration and management
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeviceTokenDTO {
    
    private UUID id;
    private UUID userId;
    private String deviceToken;
    private DeviceType deviceType;
    private String appVersion;
    private String platformVersion;
    private String appIdentifier;
    private Boolean isActive;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    
    public enum DeviceType {
        ANDROID,
        IOS,
        WEB
    }
    
    /**
     * Convert from entity
     */
    public static DeviceTokenDTO fromEntity(com.arpay.entity.UserDeviceToken entity) {
        DeviceTokenDTO dto = new DeviceTokenDTO();
        dto.setId(entity.getId());
        dto.setUserId(entity.getUserId());
        dto.setDeviceToken(entity.getDeviceToken());
        dto.setDeviceType(entity.getDeviceType() != null ? 
            DeviceType.valueOf(entity.getDeviceType().name()) : null);
        dto.setAppVersion(entity.getAppVersion());
        dto.setPlatformVersion(entity.getPlatformVersion());
        dto.setAppIdentifier(entity.getAppIdentifier());
        dto.setIsActive(entity.getIsActive());
        dto.setLastUsedAt(entity.getLastUsedAt());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
