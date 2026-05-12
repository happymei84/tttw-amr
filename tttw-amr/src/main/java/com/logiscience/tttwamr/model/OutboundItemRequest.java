package com.logiscience.tttwamr.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * å°æ?object arrayä¸­ç?æ¬ä?
 */
public record OutboundItemRequest(
        @JsonProperty("Order_no") String orderNo,
        @JsonProperty("Cust_code") String custCode,
        @JsonProperty("Taget_cust_code") String targetCustCode,
        @JsonProperty("Product_code") String productCode,
        @JsonProperty("ETD") LocalDate etd,
        @JsonProperty("RunNo") String runNo,
        @JsonProperty("PID") String pid,
        @JsonProperty("PIDAmount") Integer pidAmount,
        @JsonProperty("PkgQty") Integer pkgQty,
        @JsonProperty("Amount") Integer amount,
        @JsonProperty("Sync_type") String syncType
) {
}
