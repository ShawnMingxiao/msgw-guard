package com.msgwguard.ibmi;

import com.msgwguard.domain.InquiryMessage;
import com.msgwguard.domain.JobSnapshot;

import java.util.List;

public interface IbmIClient {

    String describe();

    boolean testConnection() throws Exception;

    List<JobSnapshot> listMsgwJobs() throws Exception;

    InquiryMessage readInquiry(JobSnapshot job) throws Exception;

    void reply(JobSnapshot job, InquiryMessage message, String replyText) throws Exception;

    void endJob(JobSnapshot job) throws Exception;
}
