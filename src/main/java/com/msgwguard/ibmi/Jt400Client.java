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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
    private volatile String lastDiscoveryMode = "未扫描";
    private volatile String lastDiscoveryError = "";

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
        try {
            List<JobSnapshot> result = listMsgwJobsViaSql();
            lastDiscoveryMode = "SQL (QSYS2.ACTIVE_JOB_INFO)";
            lastDiscoveryError = "";
            log.info("SQL 扫描 MSGW 完成：subsystem[{}] 命中 {} 个", configuredSubsystem(), result.size());
            return result;
        } catch (Exception e) {
            lastDiscoveryError = describe(e);
            log.warn("SQL 发现失败，回退 JobList（可能较慢）：{}", lastDiscoveryError);
        }
        return listMsgwJobsViaJobList();
    }

    /** 服务端筛选：只取指定 subsystem 且状态为 MSGW 的 job。 */
    private List<JobSnapshot> listMsgwJobsViaSql() throws Exception {
        String sql = "SELECT JOB_NAME_SHORT, JOB_USER, JOB_NUMBER, SUBSYSTEM, JOB_STATUS "
                + "FROM TABLE(" + activeJobInfoTable() + ") X "
                + "WHERE X.JOB_STATUS = 'MSGW'" + subsystemLikeClause() + " "
                + "FETCH FIRST 500 ROWS ONLY";
        List<JobSnapshot> result = new ArrayList<JobSnapshot>();
        Connection connection = openJdbc();
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                ResultSet rs = statement.executeQuery();
                while (rs.next()) {
                    JobSnapshot job = new JobSnapshot(
                            nullToEmpty(rs.getString("JOB_NAME_SHORT")),
                            nullToEmpty(rs.getString("JOB_USER")),
                            nullToEmpty(rs.getString("JOB_NUMBER")),
                            nullToEmpty(rs.getString("SUBSYSTEM")));
                    job.status = "MSGW";
                    job.detectedAt = Time.now();
                    result.add(job);
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
        return result;
    }

    private List<JobSnapshot> listMsgwJobsViaJobList() throws Exception {
        JobList list = new JobList(system);
        list.addJobSelectionCriteria(JobList.SELECTION_PRIMARY_JOB_STATUS_ACTIVE, Boolean.TRUE);
        list.addJobSelectionCriteria(JobList.SELECTION_ACTIVE_JOB_STATUS,
                Job.ACTIVE_JOB_STATUS_WAIT_MESSAGE);
        list.addJobAttributeToRetrieve(Job.SUBSYSTEM);
        list.load();
        int length = list.getLength();
        Job[] jobs = length == 0 ? new Job[0] : list.getJobs(0, length);

        List<JobSnapshot> result = new ArrayList<JobSnapshot>();
        String wantedSubsystem = configuredSubsystem();
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
        lastDiscoveryMode = "JobList (客户端过滤，较慢)";
        log.info("JobList 回退扫描：原始 {} 个，subsystem 过滤[{}]后 {} 个", length, wantedSubsystem, result.size());
        return result;
    }

    /** 只读诊断：不做回复，用于排查为什么没有发现 job。优先走 SQL，失败时回退 JobList。 */
    public Map<String, Object> diagnose() throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("adapter", "jt400");
        result.put("host", nullToEmpty(settings.ibmiHost));
        result.put("user", nullToEmpty(settings.ibmiUser));
        result.put("subsystem", nullToEmpty(settings.ibmiSubsystem));
        result.put("messageQueueTemplate", nullToEmpty(settings.messageQueuePathTemplate));
        result.put("lastDiscoveryMode", lastDiscoveryMode);
        result.put("lastDiscoveryError", lastDiscoveryError);
        try {
            result.put("connected", system.validateSignon());
        } catch (Exception e) {
            result.put("connected", false);
            result.put("connectionError", describe(e));
            return result;
        }

        try {
            diagnoseViaSql(result);
            result.put("discoveryMode", "SQL (QSYS2.ACTIVE_JOB_INFO)");
            return result;
        } catch (Exception e) {
            result.put("sqlError", describe(e));
        }

        diagnoseViaJobList(result);
        result.put("discoveryMode", "JobList (SQL 失败回退)");
        return result;
    }

    private void diagnoseViaSql(Map<String, Object> result) throws Exception {
        String table = activeJobInfoTable();
        String where = subsystemLikeClause();
        Connection connection = openJdbc();
        try {
            Map<String, Integer> statusCounts = new TreeMap<String, Integer>();
            PreparedStatement countStatement = connection.prepareStatement(
                    "SELECT JOB_STATUS, COUNT(*) AS CNT FROM TABLE(" + table + ") X"
                            + (where.length() == 0 ? "" : " WHERE 1=1" + where)
                            + " GROUP BY JOB_STATUS");
            try {
                ResultSet rs = countStatement.executeQuery();
                while (rs.next()) {
                    statusCounts.put(nullToEmpty(rs.getString("JOB_STATUS")), rs.getInt("CNT"));
                }
            } finally {
                countStatement.close();
            }
            int activeCount = 0;
            for (Integer count : statusCounts.values()) {
                activeCount += count;
            }
            result.put("statusCounts", statusCounts);
            result.put("activeJobCount", activeCount);

            Integer msgwCount = statusCounts.get("MSGW");
            result.put("msgwJobCount", msgwCount == null ? 0 : msgwCount);
            result.put("sampleActiveJobs", querySampleJobs(connection, table, where, 20));
            result.put("matchedMsgwJobs", queryMsgwJobs(connection, table, where));
        } finally {
            connection.close();
        }
        result.put("messageQueuePathHint", nullToEmpty(settings.messageQueuePathTemplate)
                .replace("{job}", "<jobName>")
                .replace("{user}", "<user>")
                .replace("{number}", "<jobNumber>"));
    }

    private List<Map<String, Object>> querySampleJobs(Connection connection, String table,
                                                       String where, int limit) throws Exception {
        String sql = "SELECT JOB_NAME_SHORT, JOB_USER, JOB_NUMBER, SUBSYSTEM, JOB_STATUS "
                + "FROM TABLE(" + table + ") X"
                + (where.length() == 0 ? "" : " WHERE 1=1" + where)
                + " FETCH FIRST " + limit + " ROWS ONLY";
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("job", nullToEmpty(rs.getString("JOB_NAME_SHORT")) + "/"
                        + nullToEmpty(rs.getString("JOB_USER")) + "/"
                        + nullToEmpty(rs.getString("JOB_NUMBER")));
                one.put("subsystem", nullToEmpty(rs.getString("SUBSYSTEM")));
                one.put("status", nullToEmpty(rs.getString("JOB_STATUS")));
                list.add(one);
            }
        } finally {
            statement.close();
        }
        return list;
    }

    private List<Map<String, Object>> queryMsgwJobs(Connection connection, String table,
                                                     String where) throws Exception {
        String sql = "SELECT JOB_NAME_SHORT, JOB_USER, JOB_NUMBER, SUBSYSTEM, JOB_STATUS "
                + "FROM TABLE(" + table + ") X WHERE X.JOB_STATUS = 'MSGW'"
                + where + " FETCH FIRST 500 ROWS ONLY";
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                Map<String, Object> one = new LinkedHashMap<String, Object>();
                one.put("job", nullToEmpty(rs.getString("JOB_NAME_SHORT")) + "/"
                        + nullToEmpty(rs.getString("JOB_USER")) + "/"
                        + nullToEmpty(rs.getString("JOB_NUMBER")));
                one.put("subsystem", nullToEmpty(rs.getString("SUBSYSTEM")));
                one.put("status", "MSGW");
                list.add(one);
            }
        } finally {
            statement.close();
        }
        return list;
    }

    private void diagnoseViaJobList(Map<String, Object> result) throws Exception {
        JobList all = new JobList(system);
        all.addJobSelectionCriteria(JobList.SELECTION_PRIMARY_JOB_STATUS_ACTIVE, Boolean.TRUE);
        all.addJobAttributeToRetrieve(Job.SUBSYSTEM);
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
        msgw.addJobAttributeToRetrieve(Job.SUBSYSTEM);
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

    private String configuredSubsystem() {
        return settings.ibmiSubsystem == null ? "" : settings.ibmiSubsystem.trim();
    }

    private boolean hasWildcard(String value) {
        return value != null && (value.indexOf('*') >= 0 || value.indexOf('%') >= 0);
    }

    /** 精确 subsystem 用表函数参数下推；通配或全量时不加参数，改在 WHERE 中过滤。 */
    private String activeJobInfoTable() {
        String subsystem = configuredSubsystem();
        if (subsystem.length() == 0 || "*".equals(subsystem) || "*ALL".equalsIgnoreCase(subsystem)
                || hasWildcard(subsystem)) {
            return "QSYS2.ACTIVE_JOB_INFO()";
        }
        return "QSYS2.ACTIVE_JOB_INFO(SUBSYSTEM_LIST_FILTER => '" + sqlEscape(unqualified(subsystem)) + "')";
    }

    /** 仅通配模式需要；精确模式已经由 SUBSYSTEM_LIST_FILTER 在服务端过滤。 */
    private String subsystemLikeClause() {
        String subsystem = configuredSubsystem();
        if (!hasWildcard(subsystem)) {
            return "";
        }
        String pattern = unqualified(subsystem).replace('*', '%');
        return " AND X.SUBSYSTEM LIKE '" + sqlEscape(pattern) + "'";
    }

    private String sqlEscape(String value) {
        return nullToEmpty(value).replace("'", "''");
    }

    private Connection openJdbc() throws Exception {
        Class.forName("com.ibm.as400.access.AS400JDBCDriver");
        String url = "jdbc:as400://" + nullToEmpty(settings.ibmiHost);
        return DriverManager.getConnection(url, nullToEmpty(settings.ibmiUser), nullToEmpty(settings.ibmiPassword));
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
