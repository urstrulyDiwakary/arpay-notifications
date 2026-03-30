package com.arpay.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDlqEntryDTO {
    private UUID id;
    private UUID eventId;
    private String errorMessage;
    private Integer retryCount;
    private Boolean resolved;
}
