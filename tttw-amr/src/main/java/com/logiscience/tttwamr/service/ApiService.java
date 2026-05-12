package com.logiscience.tttwamr.service;

import com.logiscience.tttwamr.model.OutboundApiResponse;
import com.logiscience.tttwamr.model.OutboundItemRequest;
import com.logiscience.tttwamr.model.OutboundRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ApiService {
    private final RestClient restClient;
    private final String AmrApiUrl;
    private final Logger logger = LoggerFactory.getLogger(ApiService.class);

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public ApiService(RestClient restClient,
                      @Value("${outbound.api.url}") String AmrApiUrl, NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.restClient = restClient;
        this.AmrApiUrl = AmrApiUrl;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    public void SendOutbound(Long outboundId, String custCode, Long areaValue) {
        // check whether if ob exist in amr_doc_master for syncType
        String syncType="I";
        String checkExistSql = "SELECT count(*) FROM amr_doc_master WHERE outbound_id = :outboundId and status = 1";
        MapSqlParameterSource outboundIdParam = new MapSqlParameterSource()
                .addValue("outboundId", outboundId);
        Integer existAmrCount = namedParameterJdbcTemplate.queryForObject(checkExistSql,outboundIdParam, Integer.class);
        if(existAmrCount >0){
            //already sent to AMR
            syncType="U";
        }

        String outboundItemRequestSql = "select o.job_no as orderNo, :custCode as custCode, ow.owner_code as targetCustCode, " +
                "p.product_attr1 as productCode, o.etd as etd, o.optional_item2 as runNo, opd.uid as pid, " +
                "sum(opd.pack_qty)  as pidAmount, od.spq as pkgQty, sum(opd.qty) as amount, :syncType as syncType " +
                "from outbound o " +
                "join owner ow on ow.id= o.owner_id " +
                "join outbound_detail od on od.outbound_id = o.id " +
                "join product p on p.id= od.product_id and p.owner_id= o.owner_id " +
                "join outbound_picking_detail opd on o.id = opd.outbound_id and opd.outbound_detail_id = od.id " +
                "join warehouse_location wl on wl.id = opd.location_id " +
                "where o.id in(:id) and wl.warehouse_area_id = :area " +
                "group by o.job_no,ow.owner_code, p.product_attr1,o.etd,opd.uid,od.spq,o.optional_item2 ";

        MapSqlParameterSource outboundIdParams = new MapSqlParameterSource();
        outboundIdParams.addValue("id", outboundId);
        outboundIdParams.addValue("custCode", custCode);
        outboundIdParams.addValue("area", areaValue);
        outboundIdParams.addValue("syncType", syncType);

        List<OutboundItemRequest> sendOutboundList = namedParameterJdbcTemplate.query(
                    outboundItemRequestSql,
                    outboundIdParams,
                    new DataClassRowMapper<>(OutboundItemRequest.class)
            );


        OutboundRequest request = new OutboundRequest(sendOutboundList);

        OutboundApiResponse response = restClient.post()
                .uri(AmrApiUrl)
                .body(request)
                .retrieve()
                .body(OutboundApiResponse.class);

        //remove FIFO conditions in outbound_picking_detail:
        String removeOpdFifoSql = "SELECT opd.id FROM outbound_picking_detail opd" +
                                  "join warehouse_location wl on wl.id = opd.location_id WHERE outbound_id = :id and wl. warehouse_area_id = :area";
        List<Long> opdIdList= namedParameterJdbcTemplate.queryForList(removeOpdFifoSql, outboundIdParams, Long.class);
        if(opdIdList.size() == 0){
            logger.warn("Outbound picking details not found for outbound_id: {}. Skipping FIFO condition removal.", outboundId);
            return;
        }
        for (Long opdId : opdIdList) {
            String updateRemoveFifoSql = "UPDATE outbound_picking_detail SET in_date=null ,production_date=null, expiry_date=null, lot_no=null, po_no=null " +
                    " WHERE outbound_id = :outboundId and id= :id";
            MapSqlParameterSource opdParams = new MapSqlParameterSource();
            outboundIdParams.addValue("outboundId", outboundId);
            outboundIdParams.addValue("id", opdId);
            namedParameterJdbcTemplate.update(updateRemoveFifoSql, opdParams);
        }
        logger.info("FIFO conditions already remove in outbound picking detail. Outbound job: {}",outboundId);

        // for log
        String jobNoSql = "SELECT id, job_no FROM outbound WHERE id IN (:id)";
        Map<Long, String> outboundIdToJobNo = namedParameterJdbcTemplate.query(jobNoSql, outboundIdParams,
                        (rs, rowNum) -> Map.entry(rs.getLong("id"), rs.getString("job_no")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

        String checkOpdCreatedDateSql = "SELECT date_created FROM outbound_picking_detail WHERE outbound_id = :outboundId order by date_created desc limit 1";
        LocalDateTime opdCreatedDate = namedParameterJdbcTemplate.queryForObject(checkOpdCreatedDateSql, outboundIdParam, LocalDateTime.class);

        //process response
        if (response.messages().equals("Y")) {
            //check ob already exist or not
            if (existAmrCount > 0){
                String updateAdmSuccessSql = "UPDATE amr_doc_master SET status = 1, fail_message = NULL, date_created=:opdCreatedDate WHERE outbound_id = :outboundId";
                MapSqlParameterSource updateAdmSuccessParams = new MapSqlParameterSource()
                        .addValue("opdCreatedDate", opdCreatedDate)
                        .addValue("outboundId", outboundId);
                namedParameterJdbcTemplate.update(updateAdmSuccessSql, updateAdmSuccessParams);
            }
            else {
                //if not exist then insert new record
                String insertAdmSuccessSql = "INSERT INTO amr_doc_master (outbound_id, status, fail_message, date_created) VALUES (:outboundId, 1, NULL, :opdCreatedDate)";
                MapSqlParameterSource insertAdmSuccessParams = new MapSqlParameterSource()
                        .addValue("outboundId", outboundId)
                        .addValue("opdCreatedDate", opdCreatedDate);
                namedParameterJdbcTemplate.update(insertAdmSuccessSql, insertAdmSuccessParams);
            }

        } else if (response.messages().equals("N")) {
            String jobNo = outboundIdToJobNo.getOrDefault(outboundId, String.valueOf(outboundId));
            //check ob already exist or not
            if (existAmrCount == 0) {
                String insertAdmFailSql = "INSERT INTO amr_doc_master (outbound_id, status, fail_message, date_created) VALUES (:outboundId, 0, :failMessage, :opdCreatedDate)";
                MapSqlParameterSource insertAdmFailparams = new MapSqlParameterSource()
                        .addValue("outboundId", outboundId)
                        .addValue("failMessage", response.code())
                        .addValue("opdCreatedDate", opdCreatedDate);
                namedParameterJdbcTemplate.update(insertAdmFailSql, insertAdmFailparams);
                logger.error("Outbound response error. Please check outbound job: {}, error messages: {}", jobNo, response.code());
            }else{
                //if exist, update the failMessage
                String updateAdmFailSql = "Update amr_doc_master set status = 0, fail_message= :failMessage where outbound_id= :outboundId";
                MapSqlParameterSource updateAdmFailparams = new MapSqlParameterSource()
                        .addValue("outboundId", outboundId)
                        .addValue("failMessage", response.code());
                namedParameterJdbcTemplate.update(updateAdmFailSql, updateAdmFailparams);
                logger.error("Outbound response error. Please check outbound job: {}, error messages: {}", jobNo, response.code());
            }

        }
    }
}
