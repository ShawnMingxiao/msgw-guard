package com.msgwguard.domain;

public class AuditRecord {

    public String id;
    public String timestamp;
    public String job;
    public String messageId;
    public String textPreview;
    public String decision;
    public String executionStatus;
    public boolean alert;
    public String reason;
    public String error;
    public long durationMs;

    public AuditRecord() {
    }

    public void reasonSafe(String value) {
        this.reason = value;
    }
}
