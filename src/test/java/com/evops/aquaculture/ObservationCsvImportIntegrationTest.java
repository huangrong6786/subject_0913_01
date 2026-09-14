package com.evops.aquaculture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观测数据 CSV 批量导入集成测试：
 * 混合行（合法/重复/缺列/坏数值/坏传感器）逐行隔离、文件校验和+业务键两级幂等、
 * 已落账（锁定）/已验收数据不得覆盖、校验和防损坏、表头与解析边界。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ObservationCsvImportIntegrationTest {

    private static final String HEADER =
            "voyage_no,cage_no,observed_at,feed_amount_kg,survival_rate,sensor_value,sensor_unit,source_device";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TestRestTemplate auth() {
        return restTemplate.withBasicAuth("bootstrap", "bootstrap");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ---------------- 基础工具 ----------------

    private ResponseEntity<JsonNode> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return auth().exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> put(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return auth().exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> put(String path) {
        return auth().exchange(url(path), HttpMethod.PUT, HttpEntity.EMPTY, JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return auth().getForEntity(url(path), JsonNode.class);
    }

    private Long createBatch(String batchNo, String cageNo, int count) {
        Map<String, Object> body = new LinkedHashMap<>();
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

    private Long createSensor(String sensorNo, String cageNo, String unit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sensorNo", sensorNo);
        body.put("cageNo", cageNo);
        body.put("sensorType", "DISSOLVED_OXYGEN");
        body.put("metricUnit", unit);
        body.put("depthM", new BigDecimal("15.0"));
        body.put("installTime", LocalDateTime.now().minusDays(20).toString());
        ResponseEntity<JsonNode> resp = post("/api/sensors", body);
        assertTrue(resp.getBody().get("success").asBoolean(), resp::toString);
        return resp.getBody().get("data").get("id").asLong();
    }

    private void setSensorStatus(Long sensorId, String targetStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetStatus", targetStatus);
        ResponseEntity<JsonNode> resp = put("/api/sensors/" + sensorId + "/status", body);
        assertTrue(resp.getBody().get("success").asBoolean(), resp::toString);
    }

    private ResponseEntity<JsonNode> upload(String content, String fileName, String checksum) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        String path = "/api/observations/import" + (checksum == null ? "" : "?checksum=" + checksum);
        return auth().exchange(url(path), HttpMethod.POST, new HttpEntity<>(form, headers), JsonNode.class);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode failureAt(JsonNode result, int index) {
        return result.get("failures").get(index);
    }

    // ---------------- 混合行逐行隔离 ----------------

    @Test
    void mixedRowsAreIsolatedWithDetailedErrors() {
        createBatch("FB-IMP-001", "CAGE-I-01", 10000);
        createSensor("S-OK-01", "CAGE-I-01", "mg/L");
        Long offlineId = createSensor("S-OFF-01", "CAGE-I-01", "mg/L");
        setSensorStatus(offlineId, "OFFLINE");
        createSensor("S-TMP-01", "CAGE-I-01", "℃");

        String csv = HEADER + "\n"
                // L2 合法全量行 → SUCCESS
                + "VOY-1,CAGE-I-01,2026-09-10T08:00:00,12.50,0.9500,6.8,mg/L,S-OK-01\n"
                // L3 合法纯传感器行 → SUCCESS
                + "VOY-1,CAGE-I-01,2026-09-10T09:00:00,,,7.1,mg/L,S-OK-01\n"
                // L4 与 L2 完全重复 → SKIPPED
                + "VOY-1,CAGE-I-01,2026-09-10T08:00:00,12.50,0.9500,6.8,mg/L,S-OK-01\n"
                // L5 同键改投饵量 → UPDATED
                + "VOY-1,CAGE-I-01,2026-09-10T08:00:00,15.00,0.9500,6.8,mg/L,S-OK-01\n"
                // L6 缺列（7 列）→ FAILED source_device
                + "VOY-1,CAGE-I-01,2026-09-10T10:00:00,12.5,0.95,6.8,mg/L\n"
                // L7 坏数值投饵量 → FAILED feed_amount_kg
                + "VOY-1,CAGE-I-01,2026-09-10T11:00:00,abc,0.95,,,\n"
                // L8 未来观测时间 → FAILED observed_at
                + "VOY-1,CAGE-I-01,2099-01-01T00:00:00,12.5,,,,\n"
                // L9 来源设备不存在 → FAILED source_device
                + "VOY-1,CAGE-I-01,2026-09-10T12:00:00,,,6.9,mg/L,S-GHOST\n"
                // L10 离线传感器 → FAILED（不阻塞其他行）
                + "VOY-1,CAGE-I-01,2026-09-10T13:00:00,,,6.9,mg/L,S-OFF-01\n"
                // L11 单位与设备不符 → FAILED sensor_unit
                + "VOY-1,CAGE-I-01,2026-09-10T14:00:00,,,25.0,mg/L,S-TMP-01\n"
                // L12 存活率超范围 → FAILED survival_rate
                + "VOY-1,CAGE-I-01,2026-09-10T15:00:00,,1.5,,,\n"
                // L13 网箱无在养批次 → FAILED cage_no
                + "VOY-1,CAGE-NOEXIST,2026-09-10T16:00:00,10,,,,\n"
                // L14 列数超出（9 列）→ FAILED _row
                + "VOY-1,CAGE-I-01,2026-09-10T17:00:00,10,,,,,EXTRA\n";

        ResponseEntity<JsonNode> resp = upload(csv, "obs-mixed.csv", null);
        assertTrue(resp.getBody().get("success").asBoolean(), resp::toString);
        JsonNode data = resp.getBody().get("data");

        assertEquals("PARTIAL", data.get("status").asText());
        assertEquals(13, data.get("totalRows").asInt());
        assertEquals(2, data.get("successCount").asInt());
        assertEquals(1, data.get("updatedCount").asInt());
        assertEquals(1, data.get("skippedCount").asInt());
        assertEquals(9, data.get("failedCount").asInt());
        assertEquals(3, data.get("shardCount").asInt(), "13 行按 5 行/片应切 3 片");

        // 失败明细：原始行号、字段、原值、原因齐全
        JsonNode failures = data.get("failures");
        assertEquals(9, failures.size());
        assertEquals(6, failureAt(data, 0).get("lineNo").asInt());
        assertEquals("source_device", failureAt(data, 0).get("fieldName").asText());
        assertTrue(failureAt(data, 0).get("errorReason").asText().contains("列数"));
        assertEquals(7, failureAt(data, 1).get("lineNo").asInt());
        assertEquals("feed_amount_kg", failureAt(data, 1).get("fieldName").asText());
        assertEquals("abc", failureAt(data, 1).get("rawValue").asText());
        assertEquals(8, failureAt(data, 2).get("lineNo").asInt());
        assertEquals("observed_at", failureAt(data, 2).get("fieldName").asText());
        assertEquals(9, failureAt(data, 3).get("lineNo").asInt());
        assertTrue(failureAt(data, 3).get("errorReason").asText().contains("不存在"));
        assertEquals(10, failureAt(data, 4).get("lineNo").asInt());
        assertTrue(failureAt(data, 4).get("errorReason").asText().contains("OFFLINE"));
        assertEquals(11, failureAt(data, 5).get("lineNo").asInt());
        assertEquals("sensor_unit", failureAt(data, 5).get("fieldName").asText());
        assertEquals(12, failureAt(data, 6).get("lineNo").asInt());
        assertEquals("survival_rate", failureAt(data, 6).get("fieldName").asText());
        assertEquals(13, failureAt(data, 7).get("lineNo").asInt());
        assertEquals("cage_no", failureAt(data, 7).get("fieldName").asText());
        assertEquals(14, failureAt(data, 8).get("lineNo").asInt());
        assertEquals("_row", failureAt(data, 8).get("fieldName").asText());

        // 行明细接口可按结果过滤
        String importNo = data.get("importNo").asText();
        JsonNode failedRows = get("/api/observations/import/" + importNo + "/rows?outcome=FAILED&limit=50")
                .getBody().get("data");
        assertEquals(9, failedRows.size());
        assertTrue(failedRows.get(1).get("rawLine").asText().contains("abc"));
        JsonNode successRows = get("/api/observations/import/" + importNo + "/rows?outcome=SUCCESS&limit=50")
                .getBody().get("data");
        assertEquals(2, successRows.size());

        // 领域联动：投饵记录（被 L5 更新为 15.00，未落账）、存活率报告（存活数自动换算）、传感器读数
        JsonNode records = get("/api/feeding-records?cageNo=CAGE-I-01").getBody().get("data");
        assertEquals(1, records.size());
        assertEquals(0, new BigDecimal("15.00").compareTo(records.get(0).get("amountKg").decimalValue()));
        assertEquals(0, records.get(0).get("posted").asInt());
        assertEquals("IMPORT-CSV", records.get(0).get("feedType").asText());

        JsonNode reports = get("/api/survival-reports?cageNo=CAGE-I-01").getBody().get("data");
        assertEquals(1, reports.size());
        assertEquals(0, new BigDecimal("0.9500").compareTo(reports.get(0).get("survivalRate").decimalValue()));
        assertEquals(9500, reports.get(0).get("aliveCount").asInt());

        JsonNode readings = get("/api/sensors/readings?cageNo=CAGE-I-01").getBody().get("data");
        assertEquals(2, readings.size());

        JsonNode observations = get("/api/observations?cageNo=CAGE-I-01").getBody().get("data");
        assertEquals(2, observations.size());
    }

    // ---------------- 文件级幂等（航次断网重传同一文件） ----------------

    @Test
    void sameFileReuploadIsIdempotent() {
        createBatch("FB-IMP-002", "CAGE-I-02", 8000);
        createSensor("S-OK-02", "CAGE-I-02", "mg/L");

        String csv = HEADER + "\n"
                + "VOY-2,CAGE-I-02,2026-09-11T06:00:00,10.00,0.9600,7.0,mg/L,S-OK-02\n"
                + "VOY-2,CAGE-I-02,2026-09-11T07:00:00,11.00,0.9610,7.2,mg/L,S-OK-02\n";

        JsonNode first = upload(csv, "voyage2.csv", null).getBody().get("data");
        assertEquals("SUCCESS", first.get("status").asText());
        assertEquals(2, first.get("successCount").asInt());
        String importNo = first.get("importNo").asText();

        // 断网恢复后重复上传同一文件：返回首次结果，不产生任何重复数据
        JsonNode second = upload(csv, "voyage2.csv", null).getBody().get("data");
        assertEquals(importNo, second.get("importNo").asText());
        assertTrue(second.get("message").asText().contains("校验和"), second::toString);
        assertEquals(2, second.get("successCount").asInt());

        assertEquals(2, get("/api/observations?voyageNo=VOY-2").getBody().get("data").size());
        assertEquals(2, get("/api/feeding-records?cageNo=CAGE-I-02").getBody().get("data").size());
        assertEquals(2, get("/api/sensors/readings?cageNo=CAGE-I-02").getBody().get("data").size());
    }

    // ---------------- 修正文件 upsert；已落账（锁定）不得覆盖 ----------------

    @Test
    void correctedFileUpsertsButPostedRecordIsLocked() {
        createBatch("FB-IMP-003", "CAGE-I-03", 9000);
        createBatch("FB-IMP-004", "CAGE-I-04", 9000);

        String csvV1 = HEADER + "\n"
                + "VOY-3,CAGE-I-03,2026-09-12T08:00:00,10.00,,,,\n"
                + "VOY-3,CAGE-I-04,2026-09-12T08:00:00,20.00,,,,\n";
        JsonNode first = upload(csvV1, "voyage3.csv", null).getBody().get("data");
        assertEquals(2, first.get("successCount").asInt());

        // CAGE-I-03 的投饵记录落账 → 该观测被锁定
        JsonNode records = get("/api/feeding-records?cageNo=CAGE-I-03").getBody().get("data");
        assertEquals(1, records.size());
        Long recordId = records.get(0).get("id").asLong();
        assertTrue(put("/api/feeding-records/" + recordId + "/post").getBody().get("success").asBoolean());

        // 修正文件（新校验和）：两行都改投饵量
        String csvV2 = HEADER + "\n"
                + "VOY-3,CAGE-I-03,2026-09-12T08:00:00,99.90,,,,\n"
                + "VOY-3,CAGE-I-04,2026-09-12T08:00:00,25.50,,,,\n";
        JsonNode second = upload(csvV2, "voyage3-fix.csv", null).getBody().get("data");
        assertEquals("PARTIAL", second.get("status").asText());
        assertEquals(1, second.get("updatedCount").asInt(), "未锁定行应更新");
        assertEquals(1, second.get("failedCount").asInt(), "已落账行应拒绝覆盖");
        JsonNode failure = failureAt(second, 0);
        assertEquals(2, failure.get("lineNo").asInt());
        assertTrue(failure.get("errorReason").asText().contains("已落账"), failure::toString);
        assertTrue(failure.get("businessKey").asText().contains("CAGE-I-03"));

        // 锁定行数据保持原值；未锁定行已更新
        JsonNode obs03 = get("/api/observations?cageNo=CAGE-I-03").getBody().get("data");
        assertEquals(0, new BigDecimal("10.00").compareTo(obs03.get(0).get("feedAmountKg").decimalValue()));
        JsonNode obs04 = get("/api/observations?cageNo=CAGE-I-04").getBody().get("data");
        assertEquals(0, new BigDecimal("25.50").compareTo(obs04.get(0).get("feedAmountKg").decimalValue()));
        JsonNode records04 = get("/api/feeding-records?cageNo=CAGE-I-04").getBody().get("data");
        assertEquals(0, new BigDecimal("25.50").compareTo(records04.get(0).get("amountKg").decimalValue()));
    }

    // ---------------- 已验收出网不得覆盖 ----------------

    @Test
    void acceptedHarvestBlocksOverwrite() {
        Long batchId = createBatch("FB-IMP-005", "CAGE-I-05", 5000);

        String csvV1 = HEADER + "\n"
                + "VOY-4,CAGE-I-05,2026-09-13T08:00:00,10.00,0.9000,,,\n";
        assertEquals(1, upload(csvV1, "voyage4.csv", null).getBody().get("data")
                .get("successCount").asInt());

        // 出网并验收 → 网箱数据冻结
        Map<String, Object> harvest = new LinkedHashMap<>();
        harvest.put("harvestNo", "HB-IMP-005");
        harvest.put("fishBatchId", batchId);
        harvest.put("harvestCount", 4500);
        harvest.put("totalWeightKg", new BigDecimal("9000.00"));
        harvest.put("harvestTime", LocalDateTime.now().minusHours(1).toString());
        JsonNode created = post("/api/harvest-batches", harvest).getBody().get("data");
        assertTrue(put("/api/harvest-batches/" + created.get("id").asLong() + "/accept")
                .getBody().get("success").asBoolean());

        String csvV2 = HEADER + "\n"
                + "VOY-4,CAGE-I-05,2026-09-13T08:00:00,55.50,0.8000,,,\n";
        JsonNode second = upload(csvV2, "voyage4-fix.csv", null).getBody().get("data");
        assertEquals(1, second.get("failedCount").asInt());
        assertTrue(failureAt(second, 0).get("errorReason").asText().contains("已验收"), second::toString);

        JsonNode observations = get("/api/observations?cageNo=CAGE-I-05").getBody().get("data");
        assertEquals(0, new BigDecimal("10.00").compareTo(observations.get(0).get("feedAmountKg").decimalValue()));
    }

    // ---------------- 文件校验和防损坏 ----------------

    @Test
    void checksumGuardRejectsCorruptUpload() {
        createBatch("FB-IMP-006", "CAGE-I-06", 6000);

        String csv = HEADER + "\n"
                + "VOY-5,CAGE-I-06,2026-09-13T09:00:00,10.00,,,,\n";

        JsonNode rejected = upload(csv, "voyage5.csv", "0000deadbeef").getBody();
        assertFalse(rejected.get("success").asBoolean());
        assertTrue(rejected.get("message").asText().contains("校验和不匹配"), rejected::toString);

        JsonNode accepted = upload(csv, "voyage5.csv", sha256(csv)).getBody();
        assertTrue(accepted.get("success").asBoolean(), accepted::toString);
        assertEquals(1, accepted.get("data").get("successCount").asInt());
    }

    // ---------------- 表头与解析边界 ----------------

    @Test
    void headerAndParserEdges() {
        createBatch("FB-IMP-007", "CAGE-I-07", 7000);
        createSensor("S-OK-07", "CAGE-I-07", "mg/L");

        // 表头非法 → 文件级失败，不产生分片
        JsonNode badHeader = upload("a,b,c\n1,2,3\n", "bad-header.csv", null).getBody().get("data");
        assertEquals("FAILED", badHeader.get("status").asText());
        assertTrue(badHeader.get("message").asText().contains("表头"), badHeader::toString);
        assertEquals(0, badHeader.get("shardCount").asInt());

        // 只有表头 → 文件级失败
        JsonNode headerOnly = upload(HEADER + "\n", "header-only.csv", null).getBody().get("data");
        assertEquals("FAILED", headerOnly.get("status").asText());
        assertTrue(headerOnly.get("message").asText().contains("无数据行"), headerOnly::toString);

        // 空文件 → 请求级拒绝
        JsonNode empty = upload("", "empty.csv", null).getBody();
        assertFalse(empty.get("success").asBoolean());
        assertTrue(empty.get("message").asText().contains("为空"), empty::toString);

        // BOM + 引号包裹字段（含空引号）→ 正常解析
        String quoted = "\uFEFF" + HEADER + "\n"
                + "\"VOY-6\",\"CAGE-I-07\",\"2026-09-13T10:00:00\",\"12.50\",\"\",\"6.8\",\"mg/L\",\"S-OK-07\"\n";
        JsonNode parsed = upload(quoted, "quoted.csv", null).getBody().get("data");
        assertEquals("SUCCESS", parsed.get("status").asText(), parsed::toString);
        assertEquals(1, parsed.get("successCount").asInt());

        // 观测时间为空/格式非法 → 行级失败
        String badTime = HEADER + "\n"
                + "VOY-6,CAGE-I-07,not-a-time,12.50,,,,\n";
        JsonNode timeFailed = upload(badTime, "bad-time.csv", null).getBody().get("data");
        assertEquals(1, timeFailed.get("failedCount").asInt());
        assertEquals("observed_at", failureAt(timeFailed, 0).get("fieldName").asText());
        assertEquals("not-a-time", failureAt(timeFailed, 0).get("rawValue").asText());
    }
}
