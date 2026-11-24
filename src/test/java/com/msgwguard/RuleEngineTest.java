package com.msgwguard;

import com.msgwguard.domain.Decision;
import com.msgwguard.domain.InquiryMessage;
import com.msgwguard.domain.JobSnapshot;
import com.msgwguard.domain.Rule;
import com.msgwguard.domain.Settings;
import com.msgwguard.engine.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private Settings settings() {
        Settings settings = new Settings();
        settings.ignoreReply = "I";
        settings.cancelReply = "C";
        settings.retryReply = "R";
        settings.defaultAction = Rule.Action.ALERT_ONLY.name();
        return settings;
    }

    private JobSnapshot job() {
        return new JobSnapshot("NIGHTLY_DAILY", "BATCH", "000001", "QUSRWRK");
    }

    @Test
    public void containsMatchesCaseInsensitiveAndRepliesWithConfiguredText() {
        Settings settings = settings();
        settings.ignoreReply = "SKIP";
        Rule rule = Rule.sample("ignore not found", 10, Rule.Matcher.CONTAINS,
                Rule.MatchField.MESSAGE_TEXT, Rule.Action.IGNORE, "OBJECT NOT FOUND");
        Decision decision = engine.decide(Arrays.asList(rule), settings, job(),
                new InquiryMessage("CPA3708", "Object not found in library QTEMP"));
        assertTrue(decision.matched);
        assertEquals("SKIP", decision.replyText);
    }

    @Test
    public void priorityFirstMatchWins() {
        Rule high = Rule.sample("high", 1, Rule.Matcher.CONTAINS,
                Rule.MatchField.MESSAGE_TEXT, Rule.Action.CANCEL, "not found");
        Rule low = Rule.sample("low", 99, Rule.Matcher.CONTAINS,
                Rule.MatchField.MESSAGE_TEXT, Rule.Action.IGNORE, "not found");
        Decision decision = engine.decide(Arrays.asList(low, high), settings(), job(),
                new InquiryMessage("X", "not found"));
        assertEquals("high", decision.matchedRuleName);
        assertEquals("C", decision.replyText);
    }

    @Test
    public void jobScopedWildcardRuleAppliesRegardlessOfMessage() {
        Rule rule = Rule.sample("fixed job reply", 5, Rule.Matcher.WILDCARD,
                Rule.MatchField.JOB_NAME, Rule.Action.CUSTOM, "NIGHTLY*");
        rule.scope = Rule.Scope.JOB;
        rule.jobPattern = "NIGHTLY*";
        rule.customReply = "Y";
        Decision decision = engine.decide(Arrays.asList(rule), settings(), job(),
                new InquiryMessage("ANY", "some totally different message"));
        assertTrue(decision.matched);
        assertEquals("Y", decision.replyText);
    }

    @Test
    public void unmatchedUsesDefaultAlertAndDoesNotReply() {
        Decision decision = engine.decide(new ArrayList<Rule>(), settings(), job(),
                new InquiryMessage("ANY", "whatever"));
        assertFalse(decision.matched);
        assertEquals(Rule.Action.ALERT_ONLY.name(), decision.action);
        assertEquals(null, decision.replyText);
    }

    @Test
    public void regexAndFuzzyMatchersWork() {
        Rule regex = Rule.sample("regex", 1, Rule.Matcher.REGEX,
                Rule.MatchField.MESSAGE_ID, Rule.Action.IGNORE, "CP[A-Z]\\d+");
        assertTrue(engine.decide(Arrays.asList(regex), settings(), job(),
                new InquiryMessage("CPA1234", "text")).matched);

        Rule fuzzy = Rule.sample("fuzzy", 2, Rule.Matcher.FUZZY,
                Rule.MatchField.MESSAGE_TEXT, Rule.Action.IGNORE, "object not found");
        fuzzy.fuzzyThreshold = 0.7;
        assertTrue(engine.decide(Arrays.asList(fuzzy), settings(), job(),
                new InquiryMessage("X", "objct not found")).matched);
    }

    @Test
    public void wildcardAndSimilarityHelpers() {
        assertTrue(RuleEngine.wildcard("NIGHTLY_DAILY", "NIGHTLY*"));
        assertTrue(RuleEngine.wildcard("ABC", "A?C"));
        assertFalse(RuleEngine.wildcard("ABCD", "A?C"));
        assertTrue(RuleEngine.similarity("hello", "hello") == 1.0);
        assertTrue(RuleEngine.similarity("hello", "hallo") > 0.7);
    }
}
