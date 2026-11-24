package com.msgwguard.service;

import com.msgwguard.domain.AuditRecord;
import com.msgwguard.domain.Decision;
import com.msgwguard.domain.InquiryMessage;
import com.msgwguard.domain.JobSnapshot;
import com.msgwguard.domain.Rule;
import com.msgwguard.domain.Settings;
import com.msgwguard.domain.Alert;
import com.msgwguard.engine.RuleEngine;
import com.msgwguard.ibmi.ClientFactory;
import com.msgwguard.ibmi.IbmIClient;
import com.msgwguard.ibmi.QueueUnavailableException;
import com.msgwguard.store.JsonStore;
import com.msgwguard.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GuardService {

    private static final Logger log = LoggerFactory.getLogger(GuardService.class);

    @Autowired private JsonStore store;
    @Autowired private RuleEngine engine;
    @Autowired private ClientFactory clientFactory;

    private final AtomicInteger scans = new AtomicInteger();
    private final AtomicInteger replies = new AtomicInteger();
    private final AtomicInteger alerts = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private final Set<String> processed = ConcurrentHashMap.newKeySet();
    private final Set<String> dryRunSeen = ConcurrentHashMap.newKeySet();
    private final Set<String> stoppedJobs = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> jobLocks = new ConcurrentHashMap<String, Object>();
    private final List<Alert> alertList = new CopyOnWriteArrayList<Alert>();

    private final Object schedulerLock = new Object();
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile boolean circuitOpen = false;
    private volatile String statsDate = Time.now().substring(0, 10);

    @PostConstruct
    public void init() {
        start();
    }

    public void start() {
        synchronized (schedulerLock) {
            if (running) {
                return;
            }
            running = true;
            circuitOpen = false;
            consecutiveFailures.set(0);
            scheduleLocked(interval());
        }
    }

    public void stop() {
        synchronized (schedulerLock) {
            running = false;
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }

    public void restartWithNewInterval() {
        synchronized (schedulerLock) {
            if (!running) {
                return;
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            scheduleLocked(interval());
        }
    }

    private void scheduleLocked(long intervalMs) {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    scanOnce();
                } catch (Throwable t) {
                    recordFailure("轮询异常", t);
                }
            }
        }, 0, Math.max(1000, intervalMs), TimeUnit.MILLISECONDS);
    }

    private long interval() {
        try {
            int value = store.loadSettings().pollIntervalMs;
            return value > 0 ? value : 10000L;
        } catch (Exception e) {
            return 10000L;
        }
    }

    public void scanOnce() {
        Settings settings = store.loadSettings();
        if (circuitOpen) {
            return;
        }
        resetStatsIfNewDay();
        IbmIClient client = clientFactory.get(settings);
        try {
            List<JobSnapshot> jobs = client.listMsgwJobs();
            scans.incrementAndGet();
            if (jobs.isEmpty()) {
                log.info("扫描完成：未发现 MSGW job（adapter={}, subsystem={}）",
                        settings.adapter, nullToEmpty(settings.ibmiSubsystem));
            } else {
                log.info("扫描完成：发现 {} 个 MSGW job（adapter={}, subsystem={}）",
                        jobs.size(), settings.adapter, nullToEmpty(settings.ibmiSubsystem));
            }
            for (JobSnapshot job : jobs) {
                if (stoppedJobs.contains(job.key())) {
                    continue;
                }
                processJob(job, client, settings);
            }
            consecutiveFailures.set(0);
        } catch (Exception e) {
            log.error("扫描 MSGW job 失败", e);
            recordFailure("扫描 MSGW job 失败", e);
        }
    }

    private void processJob(JobSnapshot job, IbmIClient client, Settings settings) {
        Object lock = jobLocks.computeIfAbsent(job.key(), k -> new Object());
        synchronized (lock) {
            long started = System.currentTimeMillis();
            AuditRecord audit = new AuditRecord();
            boolean skipAudit = false;
            audit.id = UUID.randomUUID().toString();
            audit.timestamp = Time.now();
            audit.job = job.key();
            String error = null;
            try {
                InquiryMessage message = client.readInquiry(job);
                if (message == null) {
                    audit.executionStatus = "SKIPPED";
                    audit.decision = "NO_MESSAGE";
                    return;
                }
                audit.messageId = message.messageId;
                audit.textPreview = preview(message.text);

                String idempotencyKey = job.key() + "|" + nullToEmpty(message.messageKeyHex);
                if (processed.contains(idempotencyKey)) {
                    audit.executionStatus = "SKIPPED";
                    audit.decision = "ALREADY_PROCESSED";
                    return;
                }
                // Dry-run 只记录一次决策，不写入 processed；关闭 dry-run 后同一条消息仍会被真正处理。
                if (settings.dryRun && dryRunSeen.contains(idempotencyKey)) {
                    skipAudit = true;
                    return;
                }

                Decision decision = engine.decide(store.loadRules(), settings, job, message);
                audit.decision = decision.action;
                log.info("[{}] 规则决策 action={} reply={} reason={}",
                        job.key(), decision.action, decision.replyText, decision.reason);

                if (settings.dryRun) {
                    audit.executionStatus = "DRY_RUN";
                    audit.reasonSafe(decision.reason);
                    dryRunSeen.add(idempotencyKey);
                } else if (isNoReply(decision.action)) {
                    audit.executionStatus = "ALERT";
                    audit.alert = true;
                    audit.reasonSafe(decision.reason);
                    addAlert("WARN", job.key(), decision.reason);
                } else if (Rule.Action.END_JOB.name().equals(decision.action)) {
                    if (!settings.allowDestructive) {
                        audit.executionStatus = "ALERT";
                        audit.alert = true;
                        audit.reasonSafe("规则要求结束 job，但结束 job 总开关未开启，已降级为告警");
                        addAlert("WARN", job.key(), "规则要求结束 job，但总开关未开启，请人工确认");
                    } else {
                        executeWithRetry(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    client.endJob(job);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }, settings.maxRetries);
                        audit.executionStatus = "SUCCESS";
                        audit.alert = true;
                        stoppedJobs.add(job.key());
                        addAlert("ERROR", job.key(), "已按规则结束 job（END_JOB）");
                    }
                } else {
                    final String replyText = decision.replyText;
                    executeWithRetry(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                client.reply(job, message, replyText);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }, settings.maxRetries);
                    audit.executionStatus = "SUCCESS";
                    replies.incrementAndGet();
                    log.info("[{}] 已发送回复: {}", job.key(), replyText);
                    if (Rule.Action.CANCEL.name().equals(decision.action)) {
                        stoppedJobs.add(job.key());
                        audit.alert = true;
                        addAlert("ERROR", job.key(), "已按规则回复 CANCEL，守卫停止处理该 job，请人工确认后续调度");
                    }
                }
                processed.add(idempotencyKey);
            } catch (QueueUnavailableException e) {
                // 作业消息队列在扫描后被回复/结束而消失（CPF2403），属于正常的竞态。
                // 不计失败、不发告警、不熔断；仅记一条 SKIPPED 审计，避免错误刷屏。
                audit.executionStatus = "SKIPPED";
                audit.decision = "QUEUE_UNAVAILABLE";
                audit.reasonSafe(e.getMessage());
                log.info("[{}] 消息队列不可访问，已跳过：{}", job.key(), e.getMessage());
            } catch (Exception e) {
                log.error("[" + job.key() + "] 处理失败", e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                audit.executionStatus = "FAILED";
                audit.alert = true;
                audit.error = error;
                failures.incrementAndGet();
                addAlert("ERROR", job.key(), "处理失败：" + error);
            } finally {
                if (!skipAudit) {
                    audit.durationMs = System.currentTimeMillis() - started;
                    store.appendAudit(audit);
                }
            }
        }
    }

    private boolean isNoReply(String action) {
        return action == null || Rule.Action.ALERT_ONLY.name().equals(action) || "NO_MESSAGE".equals(action);
    }

    private void executeWithRetry(Runnable action, int maxRetries) {
        int attempts = Math.max(1, maxRetries);
        RuntimeException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                action.run();
                return;
            } catch (RuntimeException e) {
                last = e;
                if (i < attempts - 1) {
                    try {
                        Thread.sleep(300L * (i + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last == null ? new RuntimeException("执行失败") : last;
    }

    private void recordFailure(String reason, Throwable t) {
        failures.incrementAndGet();
        int count = consecutiveFailures.incrementAndGet();
        Settings settings = store.loadSettings();
        if (!circuitOpen && count >= settings.failureThreshold) {
            circuitOpen = true;
            addAlert("ERROR", "-", "连续失败 " + count + " 次，已熔断停止轮询；修复后请在概览页重置");
        }
    }

    private void addAlert(String level, String job, String message) {
        Alert alert = new Alert(level, job, message);
        alertList.add(alert);
        alerts.incrementAndGet();
        while (alertList.size() > 200) {
            alertList.remove(0);
        }
    }

    private void resetStatsIfNewDay() {
        String today = Time.now().substring(0, 10);
        if (!today.equals(statsDate)) {
            statsDate = today;
            scans.set(0);
            replies.set(0);
            alerts.set(0);
            failures.set(0);
        }
    }

    public Map<String, Object> status() {
        Settings settings = store.loadSettings();
        IbmIClient client = clientFactory.get(settings);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("running", running);
        result.put("circuitOpen", circuitOpen);
        result.put("adapter", settings.adapter);
        result.put("adapterDescription", client.describe());
        result.put("pollIntervalMs", settings.pollIntervalMs);
        result.put("dryRun", settings.dryRun);
        result.put("ibmiSubsystem", settings.ibmiSubsystem);
        result.put("todayScans", scans.get());
        result.put("todayReplies", replies.get());
        result.put("todayAlerts", alerts.get());
        result.put("todayFailures", failures.get());
        result.put("stoppedJobs", new ArrayList<String>(stoppedJobs));
        result.put("unacknowledgedAlerts", unacknowledgedCount());
        result.put("dataDir", store.getDataDir().toString());
        return result;
    }

    public List<Alert> alerts(boolean onlyUnacknowledged) {
        List<Alert> result = new ArrayList<Alert>();
        for (Alert alert : alertList) {
            if (!onlyUnacknowledged || !alert.acknowledged) {
                result.add(alert);
            }
        }
        Collections.reverse(result);
        return result;
    }

    private int unacknowledgedCount() {
        int count = 0;
        for (Alert alert : alertList) {
            if (!alert.acknowledged) {
                count++;
            }
        }
        return count;
    }

    public boolean acknowledge(String id) {
        for (Alert alert : alertList) {
            if (alert.id.equals(id)) {
                alert.acknowledged = true;
                return true;
            }
        }
        return false;
    }

    public boolean resumeJob(String jobKey) {
        return stoppedJobs.remove(jobKey);
    }

    public void resetCircuit() {
        circuitOpen = false;
        consecutiveFailures.set(0);
    }

    public void clearProcessed() {
        processed.clear();
        dryRunSeen.clear();
    }

    public void clearDryRunSeen() {
        dryRunSeen.clear();
    }

    public List<JobSnapshot> currentJobs() {
        Settings settings = store.loadSettings();
        try {
            return clientFactory.get(settings).listMsgwJobs();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String preview(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
