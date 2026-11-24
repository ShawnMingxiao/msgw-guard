package com.msgwguard.domain;

public class InquiryMessage {

    public String messageId;
    public String text;
    public String secondLevelText;
    /** hex encoded IBM i message key, used by MessageQueue.reply(byte[], ...) */
    public String messageKeyHex;
    public int severity;
    public String timestamp;

    public InquiryMessage() {
    }

    public InquiryMessage(String messageId, String text) {
        this.messageId = messageId;
        this.text = text;
    }
}
