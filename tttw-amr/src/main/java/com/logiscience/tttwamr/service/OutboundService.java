package com.logiscience.tttwamr.service;

import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.logiscience.tttwamr.model.AmrDocMaster;

/**
 * 負責處理與 Outbound 相關的排程與業務邏輯，
 * 例如：每 5 分鐘呼叫一次 APIService.SendOutbound()。
 */
@Service
@EnableScheduling
public class OutboundService {

    private static final Logger logger = LoggerFactory.getLogger(OutboundService.class);

    private final ApiService apiService;

    private final JdbcTemplate jdbcTemplate;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    private final String outboundCustCode;
    private final String areaKey;
    private final Integer companyId;

    public OutboundService(ApiService apiService, JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedParameterJdbcTemplate,
                           @Value("${amr.companyCode}") String outboundCustCode, @Value("${amr.AreaKey}") String areaKey,
                           @Value("${amr.companyId}") Integer companyId) {
        this.apiService = apiService;
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.outboundCustCode = outboundCustCode;
        this.areaKey = areaKey;
        this.companyId = companyId;
    }

    /**
     * 每 5 分鐘執行一次 outbound 處理。
     */
    @Scheduled(cron = "${amr.scheduling.cron0}")
    public void processSendOutbound() {
        // 1) select outbound status in pending picking~packed
        String outboundSql = "select id, whs_id from outbound where status in (3,4,5,6,7)";
        List<Map<String, Object>> outboundRows = jdbcTemplate.queryForList(outboundSql);

        // 2) find system_control settings
        String scSql = "select whs_id, value from system_control where key = :areaKey and company_id = :companyId";
        MapSqlParameterSource scParams = new MapSqlParameterSource()
                .addValue("areaKey", areaKey)
                .addValue("companyId", companyId);
        List<Map<String, Object>> scRows = namedParameterJdbcTemplate.queryForList(scSql, scParams);

        // 3) run all system_control settings by WHS
        for (Map<String, Object> sc : scRows) {
            Object whsO = sc.get("whs_id");
            Long whsId = (whsO == null) ? null : ((Number) whsO).longValue();
            String areaStr = (String) sc.get("value");
            String s = areaStr == null ? null : areaStr.trim();
            Long areaValue = null;
            if (s != null && !s.isEmpty() && s.matches("\\d+")) {
                areaValue = Long.valueOf(s);
            }

            if (whsId == null || areaValue == null) {
                logger.warn("Skip invalid system_control row: {}", sc);
                continue;
            }

            // 4) select outbounds in this WHS
            List<Long> outboundListByWhs = outboundRows.stream()
                    .filter(r -> {
                        Object x = r.get("whs_id");
                        Long rowWhsId = (x == null) ? null : ((Number) x).longValue();
                        return whsId.equals(rowWhsId);
                    })
                    .map(r -> {
                        Object x = r.get("id");
                        return (x == null) ? null : ((Number) x).longValue();
                    })
                    .filter(Objects::nonNull)
                    .toList();

            if (outboundListByWhs.isEmpty()) {
                continue;
            }

            // outbound locate in agv area
            String opdSql =
                    "select opd.outbound_id " +
                            "from outbound_picking_detail opd " +
                            "join warehouse_location wl on wl.id = opd.location_id " +
                            "join outbound o on o.id = opd.outbound_id " +
                            "where opd.outbound_id in (:ids) " +
                            "  and o.whs_id = :whs " +
                            "  and wl.warehouse_area_id = :area " +
                            "group by opd.outbound_id " +
                            "order by min(opd.date_created), opd.outbound_id";

            MapSqlParameterSource outboundListParams = new MapSqlParameterSource();
            outboundListParams.addValue("ids", outboundListByWhs);
            outboundListParams.addValue("whs", whsId);
            outboundListParams.addValue("area", areaValue);
            List<Long> outboundLocInAmrList = namedParameterJdbcTemplate.queryForList(opdSql, outboundListParams, Long.class);

            // save outboundId
            Set<Long> sendOutboundIds = new LinkedHashSet<>();

            // find whether if exist in amr_doc_master
            String findExistOutboundSql = "select id,outbound_id as outboundId,status,fail_message as failMessage,date_created as dateCreated " +
                    " from amr_doc_master where outbound_id in (:ids)";
            MapSqlParameterSource outboundListInAmrParams = new MapSqlParameterSource();
            outboundListInAmrParams.addValue("ids", outboundLocInAmrList);
            List<AmrDocMaster> existAmrOutboundList = namedParameterJdbcTemplate.query(
                    findExistOutboundSql,
                    outboundListInAmrParams,
                    new BeanPropertyRowMapper<AmrDocMaster>(AmrDocMaster.class)
            );

            //select all opd max(date_created) and compare 1 by 1 later
            String opdMaxDateSql = "SELECT outbound_id, MAX(date_created) as max_date FROM outbound_picking_detail " +
                    "WHERE outbound_id IN (:ids) GROUP BY outbound_id";
            Map<Long, LocalDateTime> opdMaxDateMap = namedParameterJdbcTemplate.query(
                    opdMaxDateSql, outboundListInAmrParams,
                    rs -> {
                        Map<Long, LocalDateTime> map = new HashMap<>();
                        while (rs.next()) {
                            Timestamp ts = rs.getTimestamp("max_date");
                            map.put(rs.getLong("outbound_id"), ts != null ? ts.toLocalDateTime() : null);
                        }
                        return map;
                    });

            //use Map to get ID convenient
            Map<Long, AmrDocMaster> amrMasterMap = existAmrOutboundList.stream()
                    .collect(Collectors.toMap(AmrDocMaster::getOutboundId, m -> m));


            for (Long candidateId : outboundLocInAmrList) {
                AmrDocMaster targetMaster = amrMasterMap.get(candidateId);
                //If not exist
                if (targetMaster == null) {
                    sendOutboundIds.add(candidateId);
                    continue;
                } else {
                    //If already exist
                    Integer status = targetMaster.getStatus();
                    LocalDateTime admCreatedDate = targetMaster.getDateCreated();
                    LocalDateTime maxOpdCreatedDate = opdMaxDateMap.get(candidateId);
                    // different date time, maybe undo previously
                    if (!Objects.equals(admCreatedDate, maxOpdCreatedDate)) {
                        sendOutboundIds.add(candidateId);
                    } else if (status == 0) {
                        sendOutboundIds.add(candidateId);
                    }
                }
            }
            //call api service
            if (!sendOutboundIds.isEmpty()) {
                for (Long id : sendOutboundIds) {
                    apiService.SendOutbound(id, outboundCustCode, areaValue);
                }
            }
        }
    }
}





