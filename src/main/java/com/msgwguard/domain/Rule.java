package com.msgwguard.domain;

import java.util.ArrayList;
import java.util.List;

public class Rule {

    public String id;
    public String name;
    public boolean enabled = true;
    public int priority = 100;
    public Scope scope = Scope.GLOBAL;
    public String jobPattern;
    public String userPattern;
    public String subsystemPattern;
    public MatchField matchField = MatchField.MESSAGE_TEXT;
    public Matcher matcher = Matcher.CONTAINS;
    public List<String> patterns = new ArrayList<String>();
    public MatchMode matchMode = MatchMode.ANY;
    public boolean caseSensitive = false;
    public double fuzzyThreshold = 0.85;
    public Action action = Action.IGNORE;
    public String customReply;
    public String note;

    public enum Scope { GLOBAL, JOB }

    public enum MatchField { MESSAGE_TEXT, MESSAGE_ID, JOB_NAME, USER, SUBSYSTEM, ALL_TEXT }

    public enum Matcher { CONTAINS, NOT_CONTAINS, EQUALS, WILDCARD, REGEX, FUZZY }

    public enum MatchMode { ANY, ALL }

    public enum Action { IGNORE, CANCEL, RETRY, CUSTOM, ALERT_ONLY, END_JOB }

    public Rule() {
    }

    public static Rule sample(String name, int priority, Matcher matcher,
                              MatchField field, Action action, String... patterns) {
        Rule rule = new Rule();
        rule.id = java.util.UUID.randomUUID().toString();
        rule.name = name;
        rule.priority = priority;
        rule.matcher = matcher;
        rule.matchField = field;
        rule.action = action;
        for (String p : patterns) {
            rule.patterns.add(p);
        }
        if (action == Action.CUSTOM) {
            rule.customReply = "I";
        }
        return rule;
    }
}
