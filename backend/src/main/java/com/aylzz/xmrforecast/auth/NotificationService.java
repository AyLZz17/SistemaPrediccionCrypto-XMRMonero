package com.aylzz.xmrforecast.auth;

import com.aylzz.xmrforecast.common.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bandeja de notificaciones. Toda lectura y toda marcacion se acotan al usuario
 * autenticado: no existe forma de tocar la bandeja de otro usuario por IDOR.
 */
@Service
public class NotificationService {

    public enum NotificationType {
        ACCOUNT,
        SECURITY,
        EXPERIMENT,
        PREDICTION,
        JOB,
        SYSTEM
    }

    public enum Severity {
        INFO,
        SUCCESS,
        WARNING,
        ERROR
    }

    private static final int MAX_BODY_LENGTH = 2000;

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(Long userId, NotificationType type, String title, String body, Severity severity) {
        if (userId == null) {
            return;
        }
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(type.name());
        notification.setTitle(truncate(title, 160));
        notification.setBody(body == null || body.length() <= MAX_BODY_LENGTH ? body
                : body.substring(0, MAX_BODY_LENGTH));
        notification.setSeverity(severity == null ? Notification.Severity.INFO
                : Notification.Severity.valueOf(severity.name()));
        repository.save(notification);
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
        return repository.findAllByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public Notification markAsRead(Long notificationId, Long userId) {
        Notification notification = repository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND",
                        "La notificacion no existe."));
        if (notification.getReadAt() == null) {
            notification.setReadAt(java.time.Instant.now());
            repository.save(notification);
        }
        return notification;
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}