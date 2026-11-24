package com.msgwguard.engine;

import com.msgwguard.domain.Decision;
import com.msgwguard.domain.InquiryMessage;
import com.msgwguard.domain.JobSnapshot;
import com.msgwguard.domain.Rule;
import com.msgwguard.domain.Settings;
import com.msgwguard.util.Time;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class RuleEngine {

    public Decision decide(List<Rule> rules, Settings settings, JobSnapshot job, InquiryMessage message) {
        List<Rule> sorted = new ArrayList<Rule>();
        if (rules != null) {
            for (Rule rule : rules) {
                if (rule != null && rule.enabled) {
                    sorted.add(rule);
                }
            }
        }
        Collections.sort(sorted, new Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                return Integer.compare(a.priority, b.priority);
            }
        });

        for (Rule rule : sorted) {
            if (!inScope(rule, job)) {
                continue;
            }
            String target = fieldValue(rule.matchField, job, message);
            if (matches(rule, target, message)) {
                return buildDecision(rule, settings);
            }
        }

        Decision decision = Decision.alert("没有规则命中，使用默认动作 " + settings.defaultAction);
        decision.replyText = replyFor(settings, actionOf(settings.defaultAction), settings.defaultReply);
        decision.decidedAt = Time.now();
        return decision;
    }

    private boolean inScope(Rule rule, JobSnapshot job) {
        if (rule.scope == null || rule.scope == Rule.Scope.GLOBAL) {
            return true;
        }
        if (!matchesPattern(rule.jobPattern, job.jobName)) {
            return false;
        }
        if (!matchesPattern(rule.userPattern, job.user)) {
            return false;
        }
        if (!matchesPattern(rule.subsystemPattern, job.subsystem)) {
            return false;
        }
        return true;
    }

    private boolean matchesPattern(String pattern, String value) {
        if (pattern == null || pattern.trim().length() == 0 || "*".equals(pattern.trim())) {
            return true;
        }
        return wildcard(value == null ? "" : value, pattern);
    }

    private String fieldValue(Rule.MatchField field, JobSnapshot job, InquiryMessage message) {
        if (field == null) {
            field = Rule.MatchField.MESSAGE_TEXT;
        }
        switch (field) {
            case MESSAGE_ID:
                return message == null || message.messageId == null ? "" : message.messageId;
            case JOB_NAME:
                return job == null || job.jobName == null ? "" : job.jobName;
            case USER:
                return job == null || job.user == null ? "" : job.user;
            case SUBSYSTEM:
                return job == null || job.subsystem == null ? "" : job.subsystem;
            case ALL_TEXT:
                StringBuilder sb = new StringBuilder();
                if (job != null) {
                    sb.append(nullToEmpty(job.jobName)).append(' ').append(nullToEmpty(job.subsystem)).append(' ');
                }
                if (message != null) {
                    sb.append(nullToEmpty(message.messageId)).append(' ')
                            .append(nullToEmpty(message.text)).append(' ')
                            .append(nullToEmpty(message.secondLevelText));
                }
                return sb.toString();
            case MESSAGE_TEXT:
            default:
                return message == null || message.text == null ? "" : message.text;
        }
    }

    private boolean matches(Rule rule, String target, InquiryMessage message) {
        List<String> patterns = rule.patterns;
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        boolean any = false;
        boolean all = true;
        for (String pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            boolean hit;
            try {
                hit = matchOne(rule.matcher, target, pattern, rule.caseSensitive, rule.fuzzyThreshold);
            } catch (PatternSyntaxException e) {
                hit = false;
            }
            any = any || hit;
            all = all && hit;
        }
        return rule.matchMode == Rule.MatchMode.ALL ? all : any;
    }

    private boolean matchOne(Rule.Matcher matcher, String target, String pattern,
                             boolean caseSensitive, double fuzzyThreshold) {
        Rule.Matcher m = matcher == null ? Rule.Matcher.CONTAINS : matcher;
        String t = caseSensitive ? nullToEmpty(target) : nullToEmpty(target).toLowerCase();
        String p = caseSensitive ? pattern : pattern.toLowerCase();
        switch (m) {
            case NOT_CONTAINS:
                return !t.contains(p);
            case EQUALS:
                return t.trim().equals(p.trim());
            case WILDCARD:
                return wildcard(t, p);
            case REGEX:
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                return Pattern.compile(pattern, flags).matcher(nullToEmpty(target)).find();
            case FUZZY:
                return similarity(t, p) >= fuzzyThreshold;
            case CONTAINS:
            default:
                return t.contains(p);
        }
    }

    private Decision buildDecision(Rule rule, Settings settings) {
        Decision decision = new Decision();
        decision.action = rule.action == null ? Rule.Action.ALERT_ONLY.name() : rule.action.name();
        decision.replyText = replyFor(settings, rule.action, rule.customReply);
        decision.matched = true;
        decision.matchedRuleId = rule.id;
        decision.matchedRuleName = rule.name;
        decision.score = 1.0;
        decision.reason = "命中规则：" + rule.name + "（优先级 " + rule.priority + "）";
        decision.decidedAt = Time.now();
        return decision;
    }

    private String replyFor(Settings settings, Rule.Action action, String customReply) {
        if (action == null) {
            return null;
        }
        switch (action) {
            case IGNORE:
                return settings.ignoreReply;
            case CANCEL:
                return settings.cancelReply;
            case RETRY:
                return settings.retryReply;
            case CUSTOM:
                return customReply;
            case ALERT_ONLY:
            case END_JOB:
            default:
                return null;
        }
    }

    private Rule.Action actionOf(String name) {
        if (name == null) {
            return Rule.Action.ALERT_ONLY;
        }
        try {
            return Rule.Action.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Rule.Action.ALERT_ONLY;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** wildcard match supporting '*' (any chars) and '?' (single char) */
    public static boolean wildcard(String value, String pattern) {
        if (value == null || pattern == null) {
            return false;
        }
        int n = value.length();
        int m = pattern.length();
        boolean[][] dp = new boolean[m + 1][n + 1];
        dp[0][0] = true;
        for (int i = 1; i <= m; i++) {
            if (pattern.charAt(i - 1) == '*') {
                dp[i][0] = dp[i - 1][0];
            }
        }
        for (int i = 1; i <= m; i++) {
            char pc = pattern.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                if (pc == '*') {
                    dp[i][j] = dp[i - 1][j] || dp[i][j - 1];
                } else if (pc == '?' || pc == value.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                }
            }
        }
        return dp[m][n];
    }

    /** normalized Levenshtein similarity in [0,1] */
    public static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        int la = a.length();
        int lb = b.length();
        if (la == 0 || lb == 0) {
            return 0.0;
        }
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= lb; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= la; i++) {
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        int max = Math.max(la, lb);
        return 1.0 - ((double) dp[la][lb] / (double) max);
    }
}
