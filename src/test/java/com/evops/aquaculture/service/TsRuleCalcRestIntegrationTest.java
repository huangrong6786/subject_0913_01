package com.evops.aquaculture.service;

import com.evops.aquaculture.entity.FishBatch;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时序规则计算 REST 层回归：Basic 认证、统一响应结构、
 * 规则维护/潮次登记/观测登记/重算/明细查询全链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class TsRuleCalcRestIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private FishBatchMapper fishBatchMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        jdbcTemplate.update("DELETE FROM t_ts_calc_detail");
        jdbcTemplate.update("DELETE FROM t_ts_calc_result");
        jdbcTemplate.update("DELETE FROM t_feed_observation");
        jdbcTemplate.update("DELETE FROM t_tide_session");
        jdbcTemplate.update("DELETE FROM t_ts_rule");
        jdbcTemplate.update("DELETE FROM t_cage_sea_area");
    }

    @Test
    void fullChain_authValidationAndUnifiedResponse() {
        // 未认证被拒绝
        assertEquals(HttpStatus.UNAUTHORIZED, restTemplate.postForEntity(
                base + "/api/ts-rules", body(), JsonNode.class).getStatusCode());
        TestRestTemplate api = restTemplate.withBasicAuth("bootstrap", "bootstrap");

        // 网箱海区档案
        ResponseEntity<JsonNode> area = api.postForEntity(base + "/api/cage-sea-areas",
                body("cageNo", "CAGE-TS-REST", "seaArea", "东海一区", "timeZone", "Asia/Shanghai"),
                JsonNode.class);
        assertTrue(area.getBody().get("success").asBoolean());

        // 非法时区被拒绝
        ResponseEntity<JsonNode> badZone = api.postForEntity(base + "/api/cage-sea-areas",
                body("cageNo", "CAGE-TS-BAD", "seaArea", "东海一区", "timeZone", "Mars/Olympus"),
                JsonNode.class);
        assertFalse(badZone.getBody().get("success").asBoolean());

        // 新建规则（草稿 v1）→ 启用
        ResponseEntity<JsonNode> rule = api.postForEntity(base + "/api/ts-rules",
                body("ruleCode", "REST-FLAT", "ruleType", "FLAT",
                        "startMinute", 0, "endMinute", 1440, "coefficient", 1.0),
                JsonNode.class);
        assertTrue(rule.getBody().get("success").asBoolean());
        long ruleId = rule.getBody().get("data").get("id").asLong();
        assertEquals(1, rule.getBody().get("data").get("versionNo").asInt());
        assertEquals("DRAFT", rule.getBody().get("data").get("status").asText());
        ResponseEntity<JsonNode> enabled = api.postForEntity(
                base + "/api/ts-rules/" + ruleId + "/enable", null, JsonNode.class);
        assertEquals("ENABLED", enabled.getBody().get("data").get("status").asText());

        // 启用后原地修改被拒绝（统一失败响应）
        ResponseEntity<JsonNode> modify = api.exchange(
                org.springframework.http.RequestEntity.put(base + "/api/ts-rules/" + ruleId)
                        .body(body("ruleType", "FLAT", "startMinute", 0, "endMinute", 720,
                                "coefficient", 1.0)),
                JsonNode.class);
        assertFalse(modify.getBody().get("success").asBoolean());
        assertTrue(modify.getBody().get("message").asText().contains("不能原地修改"));

        // 非法区间（空区间）创建即拒绝
        ResponseEntity<JsonNode> badInterval = api.postForEntity(base + "/api/ts-rules",
                body("ruleCode", "REST-BAD", "ruleType", "FLAT",
                        "startMinute", 600, "endMinute", 600, "coefficient", 1.0),
                JsonNode.class);
        assertFalse(badInterval.getBody().get("success").asBoolean());

        // 区间重叠启用被拒绝
        ResponseEntity<JsonNode> overlap = api.postForEntity(base + "/api/ts-rules",
                body("ruleCode", "REST-OVERLAP", "ruleType", "PEAK",
                        "startMinute", 100, "endMinute", 200, "coefficient", 1.0),
                JsonNode.class);
        long overlapId = overlap.getBody().get("data").get("id").asLong();
        ResponseEntity<JsonNode> overlapEnable = api.postForEntity(
                base + "/api/ts-rules/" + overlapId + "/enable", null, JsonNode.class);
        assertFalse(overlapEnable.getBody().get("success").asBoolean());
        assertTrue(overlapEnable.getBody().get("message").asText().contains("重叠"));

        // 批次 + 潮次 + 观测
        Long batchId = newBatch("CAGE-TS-REST");
        ResponseEntity<JsonNode> tide = api.postForEntity(base + "/api/tide-sessions",
                body("tideNo", "TIDE-REST-1", "cageNo", "CAGE-TS-REST",
                        "startAtUtc", "2026-09-13T06:00:00", "endAtUtc", "2026-09-13T12:00:00"),
                JsonNode.class);
        assertTrue(tide.getBody().get("success").asBoolean());
        assertEquals("2026-09-13", tide.getBody().get("data").get("businessDate").asText());
        assertEquals("Asia/Shanghai", tide.getBody().get("data").get("timeZone").asText());

        ResponseEntity<JsonNode> obs = api.postForEntity(base + "/api/feed-observations",
                body("obsNo", "OBS-REST-1", "batchId", batchId,
                        "observedAtUtc", "2026-09-13T08:00:00", "feedAmountKg", 10.0),
                JsonNode.class);
        assertTrue(obs.getBody().get("success").asBoolean());

        // 重算：DONE + 规则版本快照
        ResponseEntity<JsonNode> calc = api.postForEntity(base + "/api/ts-calc/recalculate",
                body("batchId", batchId, "businessDate", "2026-09-13"), JsonNode.class);
        JsonNode calcData = calc.getBody().get("data");
        assertEquals("DONE", calcData.get("status").asText());
        assertEquals(1, calcData.get("obsMatched").asInt());
        assertEquals(10.0, calcData.get("totalWeightedKg").asDouble());
        assertEquals(1, calcData.get("ruleSnapshot").size());
        assertEquals("REST-FLAT", calcData.get("ruleSnapshot").get(0).get("ruleCode").asText());
        assertEquals(1, calcData.get("ruleSnapshot").get(0).get("versionNo").asInt());
        long resultId = calcData.get("resultId").asLong();

        // 结果与明细查询：明细含潮次快照与规则版本快照，当地时刻按海区时区归算
        ResponseEntity<JsonNode> details = api.getForEntity(
                base + "/api/ts-calc/results/" + resultId + "/details", JsonNode.class);
        JsonNode detailList = details.getBody().get("data");
        assertEquals(1, detailList.size());
        JsonNode detail = detailList.get(0);
        assertEquals("TIDE-REST-1", detail.get("tideNo").asText());
        assertEquals(1, detail.get("ruleVersion").asInt());
        assertEquals("16:00:00", detail.get("obsLocalTime").asText(), "UTC 08:00 归算为 UTC+8 当地 16:00");

        ResponseEntity<JsonNode> list = api.getForEntity(
                base + "/api/ts-calc/results?batchId=" + batchId + "&businessDate=2026-09-13",
                JsonNode.class);
        assertEquals(1, list.getBody().get("data").size());

        // 未登记海区档案的网箱批次：计算被拒绝并提示
        Long orphanBatchId = newBatch("CAGE-TS-ORPHAN");
        ResponseEntity<JsonNode> orphan = api.postForEntity(base + "/api/ts-calc/recalculate",
                body("batchId", orphanBatchId, "businessDate", "2026-09-13"), JsonNode.class);
        assertFalse(orphan.getBody().get("success").asBoolean());
        assertTrue(orphan.getBody().get("message").asText().contains("海区时区"));
    }

    private Long newBatch(String cageNo) {
        FishBatch batch = new FishBatch();
        batch.setBatchNo("FB-REST-" + System.nanoTime());
        batch.setCageNo(cageNo);
        batch.setSpecies("鲈鱼");
        batch.setFingerlingCount(8000);
        batch.setStatus("MONITORING");
        batch.setStockingTime(LocalDateTime.now().minusDays(15));
        fishBatchMapper.insert(batch);
        return batch.getId();
    }

    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            body.put((String) keyValues[i], keyValues[i + 1]);
        }
        return body;
    }
}
