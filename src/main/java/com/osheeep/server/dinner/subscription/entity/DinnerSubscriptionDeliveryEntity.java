package com.osheeep.server.dinner.subscription.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("dinner_subscription_deliveries")
public class DinnerSubscriptionDeliveryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("recipient_id")
    private Long recipientId;
    @TableField("household_id")
    private Long householdId;
    private String scenario;
    @TableField("request_key")
    private String requestKey;
    private String outcome;
    private String status;
    @TableField("notification_type")
    private String notificationType;
    @TableField("reference_type")
    private String referenceType;
    @TableField("reference_id")
    private Long referenceId;
    @TableField("reference_version")
    private Long referenceVersion;
    @TableField("event_dedupe_key")
    private String eventDedupeKey;
    @TableField("attempt_count")
    private Integer attemptCount;
    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;
    @TableField("last_error_code")
    private Integer lastErrorCode;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    @TableField("sent_at")
    private LocalDateTime sentAt;
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(Long recipientId) {
        this.recipientId = recipientId;
    }

    public Long getHouseholdId() {
        return householdId;
    }

    public void setHouseholdId(Long householdId) {
        this.householdId = householdId;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(String notificationType) {
        this.notificationType = notificationType;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(Long referenceId) {
        this.referenceId = referenceId;
    }

    public Long getReferenceVersion() {
        return referenceVersion;
    }

    public void setReferenceVersion(Long referenceVersion) {
        this.referenceVersion = referenceVersion;
    }

    public String getEventDedupeKey() {
        return eventDedupeKey;
    }

    public void setEventDedupeKey(String eventDedupeKey) {
        this.eventDedupeKey = eventDedupeKey;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(LocalDateTime nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Integer getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(Integer lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
