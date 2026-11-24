package com.msgwguard.domain;

public class Settings {

    public String adapter = "simulator";
    public int pollIntervalMs = 10000;
    public boolean dryRun = false;
    public String defaultAction = Rule.Action.ALERT_ONLY.name();
    public String defaultReply = "I";
    public String ignoreReply = "I";
    public String cancelReply = "C";
    public String retryReply = "R";
    public int maxRetries = 3;
    public int failureThreshold = 5;
    public boolean allowDestructive = false;

    public String ibmiHost;
    public String ibmiUser;
    public String ibmiPassword;
    public String ibmiSubsystem;
    public String messageQueuePathTemplate = "/QSYS.LIB/{job}.MSGQ";

    public Settings() {
    }
}
