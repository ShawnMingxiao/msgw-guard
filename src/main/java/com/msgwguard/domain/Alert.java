package com.msgwguard.domain;

public class Alert {

    public String id;
    public String timestamp;
    public String level;
    public String job;
    public String message;
    public boolean acknowledged;

    public Alert() {
    }

    public Alert(String level, String job, String message) {
        this.id = java.util.UUID.randomUUID().toString();
        this.timestamp = com.msgwguard.util.Time.now();
        this.level = level;
        this.job = job;
        this.message = message;
    }
}
