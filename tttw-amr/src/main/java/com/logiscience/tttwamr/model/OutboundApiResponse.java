package com.logiscience.tttwamr.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OutboundApiResponse(
        @JsonProperty("messages") String messages,
        @JsonProperty("code") String code
) {
}
