package com.msgwguard.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.msgwguard.domain.AuditRecord;
import com.msgwguard.domain.Rule;
import com.msgwguard.domain.Settings;
import com.msgwguard.util.Time;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class JsonStore {

    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Value("${msgw.guard.data-dir:./data}")
    private String dataDirProperty;

    private Path dataDir;
    private Path rulesFile;
    private Path settingsFile;
    private Path auditFile;

    @Autowired
    public JsonStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() throws IOException {
        dataDir = Paths.get(dataDirProperty).toAbsolutePath().normalize();
        Files.createDirectories(dataDir);
        rulesFile = dataDir.resolve("rules.json");
        settingsFile = dataDir.resolve("settings.json");
        auditFile = dataDir.resolve("audit.jsonl");
        if (!Files.exists(rulesFile)) {
            saveRules(defaultRules());
        }
        if (!Files.exists(settingsFile)) {
            saveSettings(new Settings());
        }
    }

    public Path getDataDir() {
        return dataDir;
    }

    public List<Rule> loadRules() {
        lock.readLock().lock();
        try {
            if (!Files.exists(rulesFile)) {
                return new ArrayList<Rule>();
            }
            String json = new String(Files.readAllBytes(rulesFile), StandardCharsets.UTF_8);
            if (json.trim().length() == 0) {
                return new ArrayList<Rule>();
            }
            return mapper.readValue(json, new TypeReference<List<Rule>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load rules from " + rulesFile, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveRules(List<Rule> rules) {
        lock.writeLock().lock();
        try {
            atomicWrite(rulesFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rules));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save rules to " + rulesFile, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Settings loadSettings() {
        lock.readLock().lock();
        try {
            if (!Files.exists(settingsFile)) {
                return new Settings();
            }
            String json = new String(Files.readAllBytes(settingsFile), StandardCharsets.UTF_8);
            if (json.trim().length() == 0) {
                return new Settings();
            }
            Settings settings = mapper.readValue(json, Settings.class);
            return settings == null ? new Settings() : settings;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load settings from " + settingsFile, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveSettings(Settings settings) {
        lock.writeLock().lock();
        try {
            atomicWrite(settingsFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save settings to " + settingsFile, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void appendAudit(AuditRecord record) {
        lock.writeLock().lock();
        try {
            String line = mapper.writeValueAsString(record);
            Files.write(auditFile, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append audit to " + auditFile, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<AuditRecord> readAudit(int limit, String job, String action) {
        lock.readLock().lock();
        try {
            if (!Files.exists(auditFile)) {
                return Collections.emptyList();
            }
            List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
            List<AuditRecord> result = new ArrayList<AuditRecord>();
            for (int i = lines.size() - 1; i >= 0 && result.size() < limit; i--) {
                String line = lines.get(i).trim();
                if (line.length() == 0) {
                    continue;
                }
                AuditRecord record;
                try {
                    record = mapper.readValue(line, AuditRecord.class);
                } catch (Exception ignore) {
                    continue;
                }
                if (job != null && job.trim().length() > 0
                        && (record.job == null || !record.job.toLowerCase().contains(job.trim().toLowerCase()))) {
                    continue;
                }
                if (action != null && action.trim().length() > 0
                        && (record.decision == null || !record.decision.equalsIgnoreCase(action.trim()))) {
                    continue;
                }
                result.add(record);
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read audit from " + auditFile, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clearAudit() {
        lock.writeLock().lock();
        try {
            if (Files.exists(auditFile)) {
                Files.delete(auditFile);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clear audit", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Rule> defaultRules() {
        List<Rule> rules = new ArrayList<Rule>();
        rules.add(Rule.sample("对象不存在则忽略", 10, Rule.Matcher.CONTAINS,
                Rule.MatchField.MESSAGE_TEXT, Rule.Action.IGNORE,
                "object not found", "not found", "不存在"));
        rules.add(Rule.sample("MPI1001 则取消", 20, Rule.Matcher.EQUALS,
                Rule.MatchField.MESSAGE_ID, Rule.Action.CANCEL, "MPI1001"));
        rules.add(Rule.sample("权限问题仅告警", 30, Rule.Matcher.CONTAINS,
                Rule.MatchField.ALL_TEXT, Rule.Action.ALERT_ONLY,
                "permission denied", "authority", "权限不足"));
        Rule fixed = Rule.sample("夜间 job 固定回复 I", 5, Rule.Matcher.WILDCARD,
                Rule.MatchField.JOB_NAME, Rule.Action.CUSTOM, "NIGHTLY*");
        fixed.scope = Rule.Scope.JOB;
        fixed.jobPattern = "NIGHTLY*";
        fixed.customReply = "I";
        fixed.note = "job 名以 NIGHTLY 开头时，无论消息内容如何都回复 I";
        rules.add(fixed);
        return rules;
    }
}
