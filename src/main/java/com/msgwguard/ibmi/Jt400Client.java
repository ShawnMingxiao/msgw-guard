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

import java.util.ArrayList;
import java.util.List;

public class Jt400Client implements IbmIClient {

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
        return system.isConnected();
    }

    @Override
    public List<JobSnapshot> listMsgwJobs() throws Exception {
        JobList list = new JobList(system);
        list.addJobSelectionCriteria(JobList.SELECTION_PRIMARY_JOB_STATUS_ACTIVE, Boolean.TRUE);
        list.addJobSelectionCriteria(JobList.SELECTION_ACTIVE_JOB_STATUS,
                Job.ACTIVE_JOB_STATUS_WAIT_MESSAGE);
        list.load();
        int length = list.getLength();
        Job[] jobs = length == 0 ? new Job[0] : list.getJobs(0, length);

        List<JobSnapshot> result = new ArrayList<JobSnapshot>();
        String wantedSubsystem = settings.ibmiSubsystem == null ? "" : settings.ibmiSubsystem.trim();
        for (Job job : jobs) {
            String subsystem = job.getSubsystem();
            if (wantedSubsystem.length() > 0 && !wantedSubsystem.equalsIgnoreCase(nullToEmpty(subsystem))) {
                continue;
            }
            JobSnapshot snapshot = new JobSnapshot(job.getName(), job.getUser(), job.getNumber(), subsystem);
            snapshot.detectedAt = Time.now();
            result.add(snapshot);
        }
        return result;
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
