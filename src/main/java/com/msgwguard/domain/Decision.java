package com.msgwguard.domain;

public class Decision {

    public String action;
    public String replyText;
    public boolean matched;
    public String matchedRuleId;
    public String matchedRuleName;
    public double score;
    public String reason;
    public String decidedAt;

    public Decision() {
    }

    public static Decision alert(String reason) {
        Decision d = new Decision();
        d.action = Rule.Action.ALERT_ONLY.name();
        d.matched = false;
        d.reason = reason;
        return d;
    }
}
