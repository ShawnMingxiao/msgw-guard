package com.msgwguard.domain;

public class JobSnapshot {

    public String jobName;
    public String user;
    public String jobNumber;
    public String subsystem;
    public String status = "MSGW";
    public String detectedAt;

    public JobSnapshot() {
    }

    public JobSnapshot(String jobName, String user, String jobNumber, String subsystem) {
        this.jobName = jobName;
        this.user = user;
        this.jobNumber = jobNumber;
        this.subsystem = subsystem;
    }

    public String key() {
        return (jobName == null ? "" : jobName.trim())
                + "/" + (user == null ? "" : user.trim())
                + "/" + (jobNumber == null ? "" : jobNumber.trim());
    }

    @Override
    public String toString() {
        return key();
    }
}
