package com.logiscience.tttwamr.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 最外層的 Request 物件
 */
public record OutboundRequest(
        @JsonProperty("outbound")
        List<OutboundItemRequest> outboundItemRequest
) {}


