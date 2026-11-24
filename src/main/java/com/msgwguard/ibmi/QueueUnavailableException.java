package com.msgwguard.ibmi;

/**
 * 作业消息队列暂时不可访问（典型：MSGW 作业在扫描与读取之间已结束，
 * 队列随作业消失，IBM i 返回 CPF2403 "Message queue xxx in QSYS not found"）。
 *
 * 这类情况不是规则处理失败，守卫应跳过该作业：不刷 ERROR 告警、不累计失败次数、不触发熔断。
 */
public class QueueUnavailableException extends Exception {

    private final String jobKey;
    private final String queuePath;

    public QueueUnavailableException(String jobKey, String queuePath, Throwable cause) {
        super(buildMessage(jobKey, queuePath, cause), cause);
        this.jobKey = jobKey;
        this.queuePath = queuePath;
    }

    public String getJobKey() {
        return jobKey;
    }

    public String getQueuePath() {
        return queuePath;
    }

    private static String buildMessage(String jobKey, String queuePath, Throwable cause) {
        String reason = cause == null ? "" : cause.getMessage();
        if (reason == null || reason.trim().length() == 0) {
            reason = cause == null ? "" : cause.toString();
        }
        return "消息队列暂不可访问，跳过该作业（作业可能已结束或被回复）。job="
                + jobKey + " queue=" + queuePath
                + (reason == null || reason.trim().length() == 0 ? "" : " reason=" + reason);
    }
}
