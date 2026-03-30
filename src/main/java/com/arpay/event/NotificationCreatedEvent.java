package com.arpay.event;

import com.arpay.entity.Notification;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Domain event fired when a notification is created.
 * Used for async queue publishing via transactional outbox pattern.
 */
@Getter
public class NotificationCreatedEvent extends ApplicationEvent {
    
    private final Notification notification;
    
    public NotificationCreatedEvent(Object source, Notification notification) {
        super(source);
        this.notification = notification;
    }
}
