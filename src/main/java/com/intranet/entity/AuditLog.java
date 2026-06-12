package com.intranet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log", indexes = {
    @Index(name = "idx_audit_user_id", columnList = "userId"),
    @Index(name = "idx_audit_entity", columnList = "entityType, entityId"),
    @Index(name = "idx_audit_action", columnList = "action"),
    @Index(name = "idx_audit_created_at", columnList = "createdAt"),
    @Index(name = "idx_audit_target_user", columnList = "targetUserId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── WHO performed the action ──
    private Long userId;

    private String userName;

    private String userEmail;

    // ── WHAT action was performed ──
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditAction action;

    @Column(nullable = false, length = 100)
    private String entityType;

    private Long entityId;

    // ── DETAILS ──
    @Column(length = 500)
    private String description;

    @Column(columnDefinition = "TEXT")
    private String requestBody;

    @Column(columnDefinition = "TEXT")
    private String oldValue;

    @Column(columnDefinition = "TEXT")
    private String newValue;

    // ── WHERE / HOW ──
    private String endpoint;

    @Column(length = 10)
    private String httpMethod;

    @Column(length = 50)
    private String ipAddress;

    // ── STATUS ──
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private AuditStatus status = AuditStatus.SUCCESS;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    // ── CONTEXT ──
    private Long targetUserId;

    // ── WHEN ──
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
