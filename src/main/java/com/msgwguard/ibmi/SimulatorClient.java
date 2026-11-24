package com.msgwguard.ibmi;

import com.msgwguard.domain.InquiryMessage;
import com.msgwguard.domain.JobSnapshot;
import com.msgwguard.util.Time;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SimulatorClient implements IbmIClient {

    private static class Entry {
        JobSnapshot job;
        InquiryMessage message;
        boolean handled;
        boolean ended;
    }

    private final Map<String, Entry> jobs = new ConcurrentHashMap<String, Entry>();

    @Override
    public String describe() {
        return "模拟器（本地内存，不连接 IBM i）";
    }

    @Override
    public boolean testConnection() {
        return true;
    }

    public JobSnapshot inject(String jobName, String user, String jobNumber,
                              String subsystem, String messageId, String messageText) {
        JobSnapshot job = new JobSnapshot(jobName, user, jobNumber, subsystem);
        job.detectedAt = Time.now();
        InquiryMessage message = new InquiryMessage(messageId, messageText);
        message.messageKeyHex = "00000001";
        message.severity = 20;
        message.timestamp = Time.now();

        Entry entry = new Entry();
        entry.job = job;
        entry.message = message;
        jobs.put(job.key(), entry);
        return job;
    }

    public void clear() {
        jobs.clear();
    }

    @Override
    public List<JobSnapshot> listMsgwJobs() {
        List<JobSnapshot> result = new ArrayList<JobSnapshot>();
        for (Entry entry : jobs.values()) {
            if (!entry.handled && !entry.ended) {
                result.add(entry.job);
            }
        }
        return result;
    }

    @Override
    public InquiryMessage readInquiry(JobSnapshot job) {
        Entry entry = jobs.get(job.key());
        return entry == null ? null : entry.message;
    }

    @Override
    public void reply(JobSnapshot job, InquiryMessage message, String replyText) {
        Entry entry = jobs.get(job.key());
        if (entry != null) {
            entry.handled = true;
        }
    }

    @Override
    public void endJob(JobSnapshot job) {
        Entry entry = jobs.get(job.key());
        if (entry != null) {
            entry.ended = true;
        }
    }
}
