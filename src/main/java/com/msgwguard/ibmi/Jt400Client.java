package com.msgwguard.ibmi;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.Job;
import com.ibm.as400.access.JobList;
import com.ibm.as400.access.MessageQueue;
import com.ibm.as400.access.QueuedMessage;
import com.msgwguard.domain.InquiryMessage;
import com.msgwguard.domain.JobSnapshot;
import com.msgwguard.domain.Settings;
import com.msgwguard.util.Hex;
import com.msgwguard.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Jt400Client implements IbmIClient {

    private static final Logger log = LoggerFactory.getLogger(Jt400Client.class);
    private static final int MAX_MESSAGES = 50;

    private final Settings settings;
    private final AS400 system;

    public Jt400Client(Settings settings) {
        this.settings = settings;
        this.system = new AS400(
                nullToEmpty(settings.ibmiHost),
                nullToEmpty(settings.ibmiUser),
                nullToEmpty(settings.ibmiPassword));
    }

    @Override
    public String describe() {
        return "JTOpen 11.2 → " + nullToEmpty(settings.ibmiHost)
                + " / user=" + nullToEmpty(settings.ibmiUser)
                + " / subsystem=" + (settings.ibmiSubsystem == null ? "*ALL" : settings.ibmiSubsystem);
    }

    @Override
    public boolean testConnection() throws Exception {
        boolean connected = system.validateSignon();
        log.info("JTOpen 连接测试 host={} user={} connected={}", settings.ibmiHost, settings.ibmiUser, connected);
        return connected;
    }

    @Override
    public List<JobSnapshot> listMsgwJobs() throws Exception {
        JobList list = new JobList(system);
        list.addJobSelectionCriteria(JobList.SELECTION_PRIMARY_JOB_STATUS_ACTIVE, Boolean.TRUE);
        list.addJobSelectionCriteria(JobList.SELECTION_ACTIVE_JOB_STATUS,
                Job.ACTIVE_JOB_STATUS_WAIT_MESSAGE);
        // 让 JobList 一次性把 subsystem 属性随列表取回，避免对每个 job 单独远程调用 getSubsystem()。
        list.addJobAttributeToRetrieve(Job.SUBSYSTEM);
        list.load();
        int length = list.getLength();
        Job[] jobs = length == 0 ? new Job[0] : list.getJobs(0, length);

        List<JobSnapshot> result = new ArrayList<JobSnapshot>();
        String wantedSubsystem = settings.ibmiSubsystem == null ? "" : settings.ibmiSubsystem.trim();
        for (Job job : jobs) {
            String subsystem = job.getSubsystem();
            if (!subsystemMatches(wantedSubsystem, subsystem)) {
                continue;
            }
            JobSnapshot snapshot = new JobSnapshot(job.getName(), job.getUser(), job.getNumber(), subsystem);
            snapshot.status = "MSGW";
            snapshot.detectedAt = Time.now();
            result.add(snapshot);
        }
        log.info("JTOpen 扫描 MSGW：原始 {} 个，subsystem 过滤[{}]后 {} 个", length, wantedSubsystem, result.size());
        return result;
    }

    /** 只读诊断：不做回复，用于排查为什么没有发现 job。 */
    public Map<String, Object> diagnose() throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("adapter", "jt400");
        result.put("host", nullToEmpty(settings.ibmiHost));
        result.put("user", nullToEmpty(settings.ibmiUser));
        result.put("subsystem", nullToEmpty(settings.ibmiSubsystem));
        result.put("messageQueueTemplate", nullToEmpty(settings.messageQueuePathTemplate));
        try {
            result.put("connected", system.validateSignon());
        } catch (Exception e) {
            result.put("connected", false);
            result.put("connectionError", describe(e));
            return result;
        }

        JobList all = new JobList(system);
        all.addJobSelectionCriteria(JobList.SELECTION_PRIMARY_JOB_STATUS_ACTIVE, Boolean.TRUE);
        all.load();
        int activeCount = all.getLength();
        result.put("activeJobCount", activeCount);

        Job[] activeJobs = activeCount == 0 ? new Job[0] : all.getJobs(0, activeCount);
        Map<String, Integer> statusCounts = new TreeMap<String, Integer>();
        List<Map<String, Object>> sample = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < activeJobs.length; i++) {
            String status = nullToEmpty(activeJobs[i].getStatus());
            Integer count = statusCounts.get(status);
            statusCounts.put(status, count == null ? 1 : count + 1);
            if (i < 20) {
                sample.add(describeJob(activeJobs[i], status));
            }
        }
        result.put("statusCounts", statusCounts);
        result.put("sampleActiveJobs", sample);

        JobList msgw = new JobList(system);
        msgw.addJobSelectionCriteria(JobList.SELECTION_PRIMARY_JOB_STATUS_ACTIVE, Boolean.TRUE);
        msgw.addJobSelectionCriteria(JobList.SELECTION_ACTIVE_JOB_STATUS,
                Job.ACTIVE_JOB_STATUS_WAIT_MESSAGE);
        msgw.load();
        int msgwCount = msgw.getLength();
        result.put("msgwJobCount", msgwCount);

        Job[] msgwJobs = msgwCount == 0 ? new Job[0] : msgw.getJobs(0, msgwCount);
        List<Map<String, Object>> matched = new ArrayList<Map<String, Object>>();
        for (Job job : msgwJobs) {
            if (subsystemMatches(settings.ibmiSubsystem, job.getSubsystem())) {
                matched.add(describeJob(job, "MSGW"));
            }
        }
        result.put("matchedMsgwJobs", matched);
        result.put("messageQueuePathHint", nullToEmpty(settings.messageQueuePathTemplate)
                .replace("{job}", "<jobName>")
                .replace("{user}", "<user>")
                .replace("{number}", "<jobNumber>"));
        return result;
    }

    private Map<String, Object> describeJob(Job job, String status) throws Exception {
        Map<String, Object> one = new LinkedHashMap<String, Object>();
        one.put("job", nullToEmpty(job.getName()) + "/" + nullToEmpty(job.getUser()) + "/" + nullToEmpty(job.getNumber()));
        one.put("subsystem", nullToEmpty(job.getSubsystem()));
        one.put("status", status);
        return one;
    }

    private String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().length() == 0 ? e.toString() : message;
    }

    @Override
    public InquiryMessage readInquiry(JobSnapshot job) throws Exception {
        MessageQueue queue = openQueue(job);
        queue.setSelectMessagesNeedReply(true);
        queue.load();
        int length = queue.getLength();
        if (length == 0) {
            return null;
        }
        QueuedMessage[] messages = queue.getMessages(0, Math.min(length, MAX_MESSAGES));
        for (QueuedMessage message : messages) {
            InquiryMessage inquiry = new InquiryMessage();
            inquiry.messageId = message.getID();
            inquiry.text = message.getMessage();
            inquiry.secondLevelText = message.getMessageHelp();
            inquiry.severity = message.getSeverity();
            inquiry.messageKeyHex = Hex.toHex(message.getKey());
            inquiry.timestamp = Time.now();
            if (inquiry.text != null && inquiry.text.trim().length() > 0) {
                return inquiry;
            }
        }
        return null;
    }

    @Override
    public void reply(JobSnapshot job, InquiryMessage message, String replyText) throws Exception {
        byte[] key = Hex.fromHex(message == null ? null : message.messageKeyHex);
        if (key == null) {
            throw new IllegalStateException("消息缺少 message key，无法精确定位待回答消息");
        }
        MessageQueue queue = openQueue(job);
        queue.reply(key, replyText, true);
        log.info("JTOpen 已回复 job={} messageKey={} reply={}", job.key(), Hex.toHex(key), replyText);
    }

    @Override
    public void endJob(JobSnapshot job) throws Exception {
        Job target = new Job(system, job.jobName, job.user, job.jobNumber);
        target.end(0);
    }

    private MessageQueue openQueue(JobSnapshot job) throws Exception {
        String template = settings.messageQueuePathTemplate;
        if (template == null || template.trim().length() == 0) {
            template = "/QSYS.LIB/{job}.MSGQ";
        }
        String path = template
                .replace("{job}", nullToEmpty(job.jobName))
                .replace("{user}", nullToEmpty(job.user))
                .replace("{number}", nullToEmpty(job.jobNumber));
        return new MessageQueue(system, path);
    }

    private boolean subsystemMatches(String wanted, String actual) {
        if (wanted == null || wanted.trim().length() == 0 || "*".equals(wanted.trim()) || "*ALL".equalsIgnoreCase(wanted.trim())) {
            return true;
        }
        String w = unqualified(wanted);
        String a = unqualified(actual);
        if (w.endsWith("*")) {
            return a.regionMatches(true, 0, w.substring(0, w.length() - 1), 0, w.length() - 1);
        }
        return w.equalsIgnoreCase(a);
    }

    private String unqualified(String value) {
        String v = nullToEmpty(value).trim();
        int pos = v.lastIndexOf('/');
        return pos >= 0 ? v.substring(pos + 1) : v;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
