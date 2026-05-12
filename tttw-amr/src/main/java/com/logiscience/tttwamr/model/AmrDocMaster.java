package com.logiscience.tttwamr.model;

import java.time.LocalDateTime;

/**
 * 對應 amr_doc_master 表的實體類
 */
public class AmrDocMaster {

    private Long id;
    private Long outboundId;
    private Integer status;
    private String failMessage;
    private LocalDateTime dateCreated;

    public AmrDocMaster() {
    }

    public AmrDocMaster(Long id, Long outboundId, Integer status, String failMessage, LocalDateTime dateCreated) {
        this.id = id;
        this.outboundId = outboundId;
        this.status = status;
        this.failMessage = failMessage;
        this.dateCreated = dateCreated;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOutboundId() {
        return outboundId;
    }

    public void setOutboundId(Long outboundId) {
        this.outboundId = outboundId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getFailMessage() {
        return failMessage;
    }

    public void setFailMessage(String failMessage) {
        this.failMessage = failMessage;
    }

    public LocalDateTime getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(LocalDateTime dateCreated) {
        this.dateCreated = dateCreated;
    }
}

