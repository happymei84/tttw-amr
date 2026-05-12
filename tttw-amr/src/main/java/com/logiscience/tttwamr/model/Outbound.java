package com.logiscience.tttwamr.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;


public class Outbound {

    String jobNo; //OB
    Long outboundId; //OB
    Long warehouseId;
    int status; //OB
    String customerCode; //OB id map to cus
    String productCode; //OD
    LocalDate etd; //OB
    String uid; //OPD
    BigDecimal packQty; //OD
    BigDecimal spq; //OD
    String locationCode; //OPD
    LocalDateTime dateCreated; //OPD
}
