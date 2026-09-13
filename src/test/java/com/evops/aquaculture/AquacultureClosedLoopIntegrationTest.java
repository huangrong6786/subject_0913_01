package com.evops.aquaculture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 深海网箱养殖批次与投饵监测闭环集成测试：
 * 批次建立 -> 投饵计划 -> 传感器/读数 -> 投饵记录/落账 -> 存活率 -> 出网/验收 -> 关联查询，
 * 并覆盖唯一业务键、状态流转、已落账/已验收禁删等保护规则。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AquacultureClosedLoopIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String cageNo = "CAGE-T-01";

    private TestRestTemplate auth() {
        return restTemplate.withBasicAuth("bootstrap", "bootstrap");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Map<String, Object> body() {
        return new LinkedHashMap<>();
    }

    private ResponseEntity<JsonNode> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return auth().exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> put(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return auth().exchange(url(path), HttpMethod.PUT,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> put(String path) {
        return auth().exchange(url(path), HttpMethod.PUT, HttpEntity.EMPTY, JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return auth().getForEntity(url(path), JsonNode.class);
    }

    private ResponseEntity<JsonNode> delete(String path) {
        return auth().exchange(url(path), HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class);
    }

    private Long createBatch(String batchNo, int count) {
        Map<String, Object> body = body();
        body.put("batchNo", batchNo);
        body.put("cageNo", cageNo);
        body.put("species", "大黄鱼");
        body.put("fingerlingCount", count);
        body.put("averageWeightG", new BigDecimal("5.20"));
        body.put("stockingTime", LocalDateTime.now().minusDays(30).toString());
        ResponseEntity<JsonNode> resp = post("/api/fish-batches", body);
        assertTrue(resp.getBody().get("success").asBoolean(), resp::toString);
        return resp.getBody().get("data").get("id").asLong();
    }

    @BeforeEach
    void healthIsAnonymous() {
        ResponseEntity<JsonNode> resp = restTemplate.getForEntity(url("/api/health"), JsonNode.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().get("success").asBoolean());
    }

    @Test
    void unauthenticatedRequestIsRejected() {
        ResponseEntity<JsonNode> resp = restTemplate.getForEntity(url("/api/fish-batches"), JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void fullClosedLoop() {
        // ---------- 1. 建立鱼苗批次 ----------
        Long batchId = createBatch("FB-LOOP-001", 10000);

        // 唯一业务键：重复批次编号被拒绝
        ResponseEntity<JsonNode> dup = post("/api/fish-batches", duplicateBatchBody("FB-LOOP-001"));
        assertFalse(dup.getBody().get("success").asBoolean());
        assertNotNull(dup.getBody().get("message").asText());

        // 参数校验：投苗数量必须为正
        Map<String, Object> bad = duplicateBatchBody("FB-LOOP-BAD");
        bad.put("fingerlingCount", 0);
        ResponseEntity<JsonNode> badResp = post("/api/fish-batches", bad);
        assertFalse(badResp.getBody().get("success").asBoolean());

        // 无任何业务记录的批次可以正常删除
        Long cleanBatchId = createBatch("FB-CLEAN-001", 100);
        assertTrue(delete("/api/fish-batches/" + cleanBatchId).getBody().get("success").asBoolean());
        assertFalse(get("/api/fish-batches/" + cleanBatchId).getBody().get("success").asBoolean());

        // 状态流转 BREEDING -> MONITORING
        ResponseEntity<JsonNode> toMonitoring = put("/api/fish-batches/" + batchId + "/status",
                java.util.Collections.singletonMap("targetStatus", "MONITORING"));
        assertTrue(toMonitoring.getBody().get("success").asBoolean());
        assertEquals("MONITORING", toMonitoring.getBody().get("data").get("status").asText());

        // 非法状态值被拒绝
        ResponseEntity<JsonNode> badStatus = put("/api/fish-batches/" + batchId + "/status",
                java.util.Collections.singletonMap("targetStatus", "WAT"));
        assertFalse(badStatus.getBody().get("success").asBoolean());

        // ---------- 2. 投饵计划 ----------
        Map<String, Object> plan = body();
        plan.put("planNo", "FP-LOOP-001");
        plan.put("batchId", batchId);
        plan.put("feedType", "配合饲料");
        plan.put("dailyAmountKg", new BigDecimal("120.00"));
        plan.put("feedFrequency", 3);
        plan.put("startDate", LocalDate.now().minusDays(20).toString());
        plan.put("endDate", LocalDate.now().plusDays(60).toString());
        ResponseEntity<JsonNode> planResp = post("/api/feeding-plans", plan);
        assertTrue(planResp.getBody().get("success").asBoolean(), planResp::toString);
        Long planId = planResp.getBody().get("data").get("id").asLong();
        assertEquals(cageNo, planResp.getBody().get("data").get("cageNo").asText());

        // 计划编号唯一
        assertFalse(post("/api/feeding-plans", plan).getBody().get("success").asBoolean());

        // 结束早于开始被拒绝
        Map<String, Object> badPlan = new LinkedHashMap<>(plan);
        badPlan.put("planNo", "FP-BAD");
        badPlan.put("endDate", LocalDate.now().minusDays(40).toString());
        assertFalse(post("/api/feeding-plans", badPlan).getBody().get("success").asBoolean());

        // 计划暂停 -> 暂停状态下投饵被拒绝
        assertTrue(put("/api/feeding-plans/" + planId + "/status",
                java.util.Collections.singletonMap("targetStatus", "SUSPENDED"))
                .getBody().get("success").asBoolean());
        Map<String, Object> recordWhileSuspended = feedingBody("FR-0", batchId, planId, "10.00");
        assertFalse(post("/api/feeding-records", recordWhileSuspended).getBody().get("success").asBoolean());

        // 恢复生效
        assertTrue(put("/api/feeding-plans/" + planId + "/status",
                java.util.Collections.singletonMap("targetStatus", "ACTIVE"))
                .getBody().get("success").asBoolean());

        // FINISHED 不能回到 ACTIVE
        assertTrue(put("/api/feeding-plans/" + planId + "/status",
                java.util.Collections.singletonMap("targetStatus", "FINISHED"))
                .getBody().get("success").asBoolean());
        assertFalse(put("/api/feeding-plans/" + planId + "/status",
                java.util.Collections.singletonMap("targetStatus", "ACTIVE"))
                .getBody().get("success").asBoolean());

        // ---------- 3. 传感器与读数 ----------
        Map<String, Object> sensor = body();
        sensor.put("sensorNo", "SEN-DO-001");
        sensor.put("cageNo", cageNo);
        sensor.put("sensorType", "DISSOLVED_OXYGEN");
        sensor.put("metricUnit", "mg/L");
        sensor.put("depthM", new BigDecimal("8.50"));
        sensor.put("installTime", LocalDateTime.now().minusDays(25).toString());
        ResponseEntity<JsonNode> sensorResp = post("/api/sensors", sensor);
        assertTrue(sensorResp.getBody().get("success").asBoolean(), sensorResp::toString);
        Long sensorId = sensorResp.getBody().get("data").get("id").asLong();

        // 传感器编号唯一
        assertFalse(post("/api/sensors", sensor).getBody().get("success").asBoolean());

        // 上报两条读数
        assertTrue(post("/api/sensors/readings",
                readingBody("RD-001", sensorId, "6.80", LocalDateTime.now().minusHours(2)))
                .getBody().get("success").asBoolean());
        assertTrue(post("/api/sensors/readings",
                readingBody("RD-002", sensorId, "7.10", LocalDateTime.now().minusHours(1)))
                .getBody().get("success").asBoolean());
        // 读数流水号唯一
        assertFalse(post("/api/sensors/readings",
                readingBody("RD-002", sensorId, "7.10", LocalDateTime.now().minusHours(1)))
                .getBody().get("success").asBoolean());

        // 维护中的传感器不能上报读数；切回 ONLINE 后恢复
        assertTrue(put("/api/sensors/" + sensorId + "/status",
                java.util.Collections.singletonMap("targetStatus", "MAINTENANCE"))
                .getBody().get("success").asBoolean());
        assertFalse(post("/api/sensors/readings",
                readingBody("RD-003", sensorId, "7.00", LocalDateTime.now()))
                .getBody().get("success").asBoolean());
        assertTrue(put("/api/sensors/" + sensorId + "/status",
                java.util.Collections.singletonMap("targetStatus", "ONLINE"))
                .getBody().get("success").asBoolean());
        assertTrue(post("/api/sensors/readings",
                readingBody("RD-003", sensorId, "7.00", LocalDateTime.now()))
                .getBody().get("success").asBoolean());

        // 有读数的传感器不能删除
        assertFalse(delete("/api/sensors/" + sensorId).getBody().get("success").asBoolean());

        // ---------- 4. 投饵记录与落账保护 ----------
        // 未落账记录可以删除
        Long transientRecordId = post("/api/feeding-records",
                feedingBody("FR-DEL-001", batchId, null, "15.00"))
                .getBody().get("data").get("id").asLong();
        assertTrue(delete("/api/feeding-records/" + transientRecordId).getBody().get("success").asBoolean());

        Long recordId1 = post("/api/feeding-records",
                feedingBody("FR-LOOP-001", batchId, null, "100.00"))
                .getBody().get("data").get("id").asLong();
        Long recordId2 = post("/api/feeding-records",
                feedingBody("FR-LOOP-002", batchId, null, "80.00"))
                .getBody().get("data").get("id").asLong();

        // 落账
        assertTrue(put("/api/feeding-records/" + recordId1 + "/post")
                .getBody().get("success").asBoolean());
        // 重复落账被拒绝
        assertFalse(put("/api/feeding-records/" + recordId1 + "/post")
                .getBody().get("success").asBoolean());
        // 已落账记录不能删除
        assertFalse(delete("/api/feeding-records/" + recordId1).getBody().get("success").asBoolean());
        // 未落账的第二条可删除
        assertTrue(delete("/api/feeding-records/" + recordId2).getBody().get("success").asBoolean());

        // ---------- 5. 存活率报告 ----------
        Map<String, Object> report = body();
        report.put("reportNo", "SR-LOOP-001");
        report.put("batchId", batchId);
        report.put("aliveCount", 9500);
        report.put("reportTime", LocalDateTime.now().minusDays(10).toString());
        ResponseEntity<JsonNode> reportResp = post("/api/survival-reports", report);
        assertTrue(reportResp.getBody().get("success").asBoolean(), reportResp::toString);
        // 存活率自动计算 = 9500/10000 = 0.9500
        assertEquals(0, new BigDecimal("0.9500")
                .compareTo(reportResp.getBody().get("data").get("survivalRate").decimalValue()));

        // 报告编号唯一
        assertFalse(post("/api/survival-reports", report).getBody().get("success").asBoolean());
        // 存活数超过投苗数被拒绝
        Map<String, Object> badReport = new LinkedHashMap<>(report);
        badReport.put("reportNo", "SR-BAD");
        badReport.put("aliveCount", 20000);
        assertFalse(post("/api/survival-reports", badReport).getBody().get("success").asBoolean());

        // 最新一期存活率覆盖
        Map<String, Object> report2 = body();
        report2.put("reportNo", "SR-LOOP-002");
        report2.put("batchId", batchId);
        report2.put("aliveCount", 9200);
        report2.put("reportTime", LocalDateTime.now().toString());
        assertTrue(post("/api/survival-reports", report2).getBody().get("success").asBoolean());

        // ---------- 6. 出网批次与验收保护 ----------
        // 待验收出网单可删除
        Long pendingId = post("/api/harvest-batches",
                harvestBody("HB-TMP-001", batchId, 100, "300.00"))
                .getBody().get("data").get("id").asLong();
        assertTrue(delete("/api/harvest-batches/" + pendingId).getBody().get("success").asBoolean());

        Long harvestId = post("/api/harvest-batches",
                harvestBody("HB-LOOP-001", batchId, 9000, "27000.00"))
                .getBody().get("data").get("id").asLong();

        // 出网数量超过投苗数量被拒绝
        assertFalse(post("/api/harvest-batches",
                harvestBody("HB-BAD", batchId, 99999, "1.00"))
                .getBody().get("success").asBoolean());

        // 验收
        ResponseEntity<JsonNode> acceptResp = put("/api/harvest-batches/" + harvestId + "/accept");
        assertTrue(acceptResp.getBody().get("success").asBoolean(), acceptResp::toString);
        assertEquals("ACCEPTED", acceptResp.getBody().get("data").get("status").asText());
        assertNotNull(acceptResp.getBody().get("data").get("acceptedTime").asText());
        // 重复验收被拒绝
        assertFalse(put("/api/harvest-batches/" + harvestId + "/accept")
                .getBody().get("success").asBoolean());
        // 已验收不能删除
        assertFalse(delete("/api/harvest-batches/" + harvestId).getBody().get("success").asBoolean());

        // 验收联动鱼苗批次 -> HARVESTED；HARVESTED 不能回退到 MONITORING
        assertEquals("HARVESTED", get("/api/fish-batches/" + batchId)
                .getBody().get("data").get("status").asText());
        assertFalse(put("/api/fish-batches/" + batchId + "/status",
                java.util.Collections.singletonMap("targetStatus", "MONITORING"))
                .getBody().get("success").asBoolean());
        // 存在已验收出网单的批次不能删除
        assertFalse(delete("/api/fish-batches/" + batchId).getBody().get("success").asBoolean());

        // ---------- 7. 网箱关联查询（此时批次1为 HARVESTED，仍计入网箱总览） ----------
        // 用一个仍在养的新批次验证投饵量聚合
        Long activeBatchId = createBatch("FB-LOOP-002", 5000);
        post("/api/feeding-records", feedingBody("FR-AGG-001", activeBatchId, null, "40.00"));
        Long agg2 = post("/api/feeding-records", feedingBody("FR-AGG-002", activeBatchId, null, "60.00"))
                .getBody().get("data").get("id").asLong();
        put("/api/feeding-records/" + agg2 + "/post");
        // 存在已落账投饵记录的批次不能删除
        assertFalse(delete("/api/fish-batches/" + activeBatchId).getBody().get("success").asBoolean());
        // 未在第二个网箱放任何数据
        ResponseEntity<JsonNode> overview = get("/api/cages/" + cageNo + "/overview");
        assertTrue(overview.getBody().get("success").asBoolean(), overview::toString);
        JsonNode data = overview.getBody().get("data");
        // 网箱已落账合计 = 100(FR-LOOP-001) + 60(FR-AGG-002)
        assertEquals(0, new BigDecimal("160.00")
                .compareTo(data.get("postedFeedAmountKg").decimalValue()));

        boolean foundActiveBatch = false;
        for (JsonNode b : data.get("batches")) {
            if ("FB-LOOP-002".equals(b.get("batchNo").asText())) {
                foundActiveBatch = true;
                assertEquals(0, new BigDecimal("60.00")
                        .compareTo(b.get("postedFeedAmountKg").decimalValue()));
            }
        }
        assertTrue(foundActiveBatch, "网箱总览应包含在养批次 FB-LOOP-002");

        // 传感器最新读数为 RD-003 的 7.00
        JsonNode sensorNode = data.get("sensors").get(0);
        assertEquals("SEN-DO-001", sensorNode.get("sensorNo").asText());
        assertEquals(0, new BigDecimal("7.0000")
                .compareTo(sensorNode.get("latestValue").decimalValue()));

        // 出网批次列表含已验收单
        boolean foundHarvest = false;
        for (JsonNode h : data.get("harvests")) {
            if ("HB-LOOP-001".equals(h.get("harvestNo").asText())) {
                foundHarvest = true;
                assertEquals("ACCEPTED", h.get("status").asText());
            }
        }
        assertTrue(foundHarvest);

        // 批次明细关联查询：投饵记录、存活率报告、出网批次齐备
        JsonNode detail = get("/api/fish-batches/" + batchId + "/detail").getBody().get("data");
        assertTrue(detail.get("feedingRecords").size() >= 1);
        assertEquals(2, detail.get("survivalReports").size());
        assertEquals(1, detail.get("harvests").size());
        assertEquals(0, new BigDecimal("100.00")
                .compareTo(detail.get("postedFeedAmountKg").decimalValue()));

        // 关闭归档：HARVESTED -> CLOSED；关闭后批次不再出现在网箱在养总览，投饵量合计回落
        assertTrue(put("/api/fish-batches/" + batchId + "/status",
                java.util.Collections.singletonMap("targetStatus", "CLOSED"))
                .getBody().get("success").asBoolean());
        JsonNode overviewAfterClose = get("/api/cages/" + cageNo + "/overview").getBody().get("data");
        assertEquals(0, new BigDecimal("60.00")
                .compareTo(overviewAfterClose.get("postedFeedAmountKg").decimalValue()));
        for (JsonNode b : overviewAfterClose.get("batches")) {
            assertFalse("FB-LOOP-001".equals(b.get("batchNo").asText()),
                    "已关闭批次不应出现在网箱总览");
        }

        // 按网箱/状态过滤查询
        ResponseEntity<JsonNode> byCage = get("/api/fish-batches?cageNo=" + cageNo);
        assertTrue(byCage.getBody().get("data").size() >= 2);
        ResponseEntity<JsonNode> readings = get("/api/sensors/readings?sensorId=" + sensorId);
        assertEquals(3, readings.getBody().get("data").size());
    }

    // ---------- helpers ----------

    private Map<String, Object> duplicateBatchBody(String batchNo) {
        Map<String, Object> body = body();
        body.put("batchNo", batchNo);
        body.put("cageNo", cageNo);
        body.put("species", "大黄鱼");
        body.put("fingerlingCount", 10000);
        body.put("stockingTime", LocalDateTime.now().toString());
        return body;
    }

    private Map<String, Object> feedingBody(String recordNo, Long batchId, Long planId, String amount) {
        Map<String, Object> body = body();
        body.put("recordNo", recordNo);
        body.put("batchId", batchId);
        if (planId != null) {
            body.put("planId", planId);
        }
        body.put("feedType", "配合饲料");
        body.put("amountKg", new BigDecimal(amount));
        body.put("feedingTime", LocalDateTime.now().toString());
        return body;
    }

    private Map<String, Object> readingBody(String readingNo, Long sensorId, String value, LocalDateTime time) {
        Map<String, Object> body = body();
        body.put("readingNo", readingNo);
        body.put("sensorId", sensorId);
        body.put("metricValue", new BigDecimal(value));
        body.put("readingTime", time.toString());
        return body;
    }

    private Map<String, Object> harvestBody(String harvestNo, Long batchId, int count, String weight) {
        Map<String, Object> body = body();
        body.put("harvestNo", harvestNo);
        body.put("fishBatchId", batchId);
        body.put("harvestCount", count);
        body.put("totalWeightKg", new BigDecimal(weight));
        body.put("harvestTime", LocalDateTime.now().toString());
        return body;
    }
}
