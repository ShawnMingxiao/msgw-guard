package com.msgwguard.web;

import com.msgwguard.domain.AuditRecord;
import com.msgwguard.domain.Decision;
import com.msgwguard.domain.InquiryMessage;
import com.msgwguard.domain.JobSnapshot;
import com.msgwguard.domain.Rule;
import com.msgwguard.domain.RuleTestRequest;
import com.msgwguard.domain.Settings;
import com.msgwguard.engine.RuleEngine;
import com.msgwguard.ibmi.ClientFactory;
import com.msgwguard.ibmi.IbmIClient;
import com.msgwguard.ibmi.SimulatorClient;
import com.msgwguard.service.GuardService;
import com.msgwguard.store.JsonStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired private GuardService guard;
    @Autowired private JsonStore store;
    @Autowired private RuleEngine engine;
    @Autowired private ClientFactory clientFactory;
    @Autowired private SimulatorClient simulator;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return guard.status();
    }

    @PostMapping("/guard/start")
    public Map<String, Object> start() {
        guard.start();
        return ok();
    }

    @PostMapping("/guard/stop")
    public Map<String, Object> stop() {
        guard.stop();
        return ok();
    }

    @PostMapping("/guard/scan")
    public Map<String, Object> scan() {
        guard.scanOnce();
        return ok();
    }

    @PostMapping("/guard/reset-circuit")
    public Map<String, Object> resetCircuit() {
        guard.resetCircuit();
        return ok();
    }

    @PostMapping("/guard/clear-processed")
    public Map<String, Object> clearProcessed() {
        guard.clearProcessed();
        return ok();
    }

    @GetMapping("/guard/alerts")
    public List<?> alerts(@RequestParam(defaultValue = "false") boolean onlyUnacknowledged) {
        return guard.alerts(onlyUnacknowledged);
    }

    @PostMapping("/guard/alerts/{id}/ack")
    public Map<String, Object> acknowledge(@PathVariable String id) {
        Map<String, Object> result = ok();
        result.put("found", guard.acknowledge(id));
        return result;
    }

    @PostMapping("/guard/jobs/resume")
    public Map<String, Object> resumeJob(@RequestBody Map<String, String> body) {
        Map<String, Object> result = ok();
        result.put("found", guard.resumeJob(body.get("jobKey")));
        return result;
    }

    @GetMapping("/jobs")
    public List<JobSnapshot> jobs() {
        return guard.currentJobs();
    }

    @GetMapping("/rules")
    public List<Rule> rules() {
        return store.loadRules();
    }

    @PostMapping("/rules")
    public Rule addRule(@RequestBody Rule rule) {
        List<Rule> rules = store.loadRules();
        if (rule.id == null || rule.id.trim().length() == 0) {
            rule.id = UUID.randomUUID().toString();
        }
        rules.add(rule);
        store.saveRules(rules);
        return rule;
    }

    @PutMapping("/rules/{id}")
    public Map<String, Object> updateRule(@PathVariable String id, @RequestBody Rule rule) {
        List<Rule> rules = store.loadRules();
        boolean found = false;
        for (int i = 0; i < rules.size(); i++) {
            if (id.equals(rules.get(i).id)) {
                rule.id = id;
                rules.set(i, rule);
                found = true;
                break;
            }
        }
        if (found) {
            store.saveRules(rules);
        }
        Map<String, Object> result = ok();
        result.put("found", found);
        return result;
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable String id) {
        List<Rule> rules = store.loadRules();
        Iterator<Rule> it = rules.iterator();
        boolean found = false;
        while (it.hasNext()) {
            if (id.equals(it.next().id)) {
                it.remove();
                found = true;
            }
        }
        if (found) {
            store.saveRules(rules);
        }
        Map<String, Object> result = ok();
        result.put("found", found);
        return result;
    }

    @PostMapping("/rules/test")
    public Map<String, Object> testRule(@RequestBody RuleTestRequest request) {
        JobSnapshot job = new JobSnapshot(request.jobName, request.user, null, request.subsystem);
        InquiryMessage message = new InquiryMessage(request.messageId, request.text);
        Decision decision = engine.decide(store.loadRules(), store.loadSettings(), job, message);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("decision", decision);
        result.put("replyText", decision.replyText);
        return result;
    }

    @GetMapping("/audit")
    public List<AuditRecord> audit(@RequestParam(defaultValue = "100") int limit,
                                   @RequestParam(required = false) String job,
                                   @RequestParam(required = false) String action) {
        return store.readAudit(Math.max(1, Math.min(limit, 1000)), job, action);
    }

    @DeleteMapping("/audit")
    public Map<String, Object> clearAudit() {
        store.clearAudit();
        return ok();
    }

    @GetMapping("/settings")
    public Settings settings() {
        return store.loadSettings();
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Settings settings) {
        store.saveSettings(settings);
        clientFactory.invalidate();
        guard.clearDryRunSeen();
        guard.restartWithNewInterval();
        return ok();
    }

    @PostMapping("/simulator/inject")
    public JobSnapshot inject(@RequestBody Map<String, String> body) {
        return simulator.inject(
                value(body, "jobName", "NIGHTLY_DAILY"),
                value(body, "user", "BATCH"),
                value(body, "jobNumber", "000001"),
                value(body, "subsystem", "QUSRWRK"),
                value(body, "messageId", "CPA3708"),
                value(body, "text", "Object not found in library QTEMP"));
    }

    @PostMapping("/simulator/clear")
    public Map<String, Object> clearSimulator() {
        simulator.clear();
        return ok();
    }

    @PostMapping("/ibmi/test")
    public Map<String, Object> testConnection() {
        Settings settings = store.loadSettings();
        IbmIClient client = clientFactory.get(settings);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try {
            result.put("success", client.testConnection());
            result.put("description", client.describe());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            result.put("description", client.describe());
        }
        return result;
    }

    private String value(Map<String, String> body, String key, String defaultValue) {
        String value = body == null ? null : body.get(key);
        return value == null || value.trim().length() == 0 ? defaultValue : value;
    }

    private Map<String, Object> ok() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        return result;
    }
}
