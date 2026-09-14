package com.evops.aquaculture.service;

import com.evops.aquaculture.csv.DataLine;
import com.evops.aquaculture.entity.ObsImportBatch;
import com.evops.aquaculture.entity.ObsImportShard;
import com.evops.aquaculture.mapper.CageObservationMapper;
import com.evops.aquaculture.mapper.FeedingRecordMapper;
import com.evops.aquaculture.mapper.FishBatchMapper;
import com.evops.aquaculture.mapper.HarvestBatchMapper;
import com.evops.aquaculture.mapper.ObsImportBatchMapper;
import com.evops.aquaculture.mapper.ObsImportRowMapper;
import com.evops.aquaculture.mapper.ObsImportShardMapper;
import com.evops.aquaculture.mapper.SensorReadingMapper;
import com.evops.aquaculture.mapper.SurvivalReportMapper;
import com.evops.aquaculture.mapper.UnderwaterSensorMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入分片失败重试回归：分片级异常只标记本片 FAILED 不连坐其他分片；
 * 重试端点与断网重传都能续传失败分片；僵死 RUNNING 分片可被接管。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ObsImportShardRetryTest {

    private static final String HEADER =
            "voyage_no,cage_no,observed_at,feed_amount_kg,survival_rate,sensor_value,sensor_unit,source_device";

    /** 注入失败的分片序号；-1 表示不注入。 */
    private static final AtomicInteger FAIL_SHARD_INDEX = new AtomicInteger(-1);
    /** 剩余注入失败次数。 */
    private static final AtomicInteger FAILURES_LEFT = new AtomicInteger(0);

    @TestConfiguration
    static class FlakyImportConfig {
        /** 子类覆盖分片处理 seam：对指定分片注入一次性分片级异常。 */
        @Bean
        @Primary
        ObsImportService flakyObsImportService(ObsImportBatchMapper batchMapper,
                                               ObsImportShardMapper shardMapper,
                                               ObsImportRowMapper rowMapper,
                                               CageObservationMapper observationMapper,
                                               UnderwaterSensorMapper sensorMapper,
                                               FishBatchMapper fishBatchMapper,
                                               HarvestBatchMapper harvestBatchMapper,
                                               FeedingRecordMapper feedingRecordMapper,
                                               SurvivalReportMapper survivalReportMapper,
                                               SensorReadingMapper sensorReadingMapper,
                                               ObsImportRowProcessor rowProcessor,
                                               SqlSessionFactory sqlSessionFactory) {
            return new ObsImportService(batchMapper, shardMapper, rowMapper, observationMapper,
                    sensorMapper, fishBatchMapper, harvestBatchMapper, feedingRecordMapper,
                    survivalReportMapper, sensorReadingMapper, rowProcessor, sqlSessionFactory) {
                @Override
                protected void processShard(ObsImportBatch batch, ObsImportShard shard,
                                            List<DataLine> dataLines) {
                    if (shard.getShardIndex() == FAIL_SHARD_INDEX.get()
                            && FAILURES_LEFT.getAndDecrement() > 0) {
                        throw new IllegalStateException("模拟分片处理中断（存储闪断）");
                    }
                    super.processShard(batch, shard, dataLines);
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetFault() {
        FAIL_SHARD_INDEX.set(-1);
        FAILURES_LEFT.set(0);
    }

    private TestRestTemplate auth() {
        return restTemplate.withBasicAuth("bootstrap", "bootstrap");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<JsonNode> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return auth().exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return auth().getForEntity(url(path), JsonNode.class);
    }

    private void createBatch(String batchNo, String cageNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchNo", batchNo);
        body.put("cageNo", cageNo);
        body.put("species", "鲈鱼");
        body.put("fingerlingCount", 50000);
        body.put("stockingTime", LocalDateTime.now().minusDays(40).toString());
        ResponseEntity<JsonNode> resp = post("/api/fish-batches", body);
        assertTrue(resp.getBody().get("success").asBoolean(), resp::toString);
    }

    private ResponseEntity<JsonNode> upload(String content, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return auth().exchange(url("/api/observations/import"), HttpMethod.POST,
                new HttpEntity<>(form, headers), JsonNode.class);
    }

    private String buildCsv(String voyage, String cage, int rows) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        LocalDateTime base = LocalDateTime.now().minusDays(5);
        for (int i = 0; i < rows; i++) {
            csv.append(voyage).append(',').append(cage).append(',')
                    .append(base.plusHours(i).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .append(",12.50,0.9500,,,\n");
        }
        return csv.toString();
    }

    @Test
    void failedShardIsIsolatedAndRetryable() {
        createBatch("FB-RETRY-01", "CAGE-R-01");
        String csv = buildCsv("VOY-R1", "CAGE-R-01", 12);

        // 12 行（分片大小 5 → 3 片），第 2 片（shardIndex=1）注入一次分片级失败
        FAIL_SHARD_INDEX.set(1);
        FAILURES_LEFT.set(1);
        JsonNode first = upload(csv, "retry.csv").getBody().get("data");

        assertEquals("PARTIAL", first.get("status").asText(), first::toString);
        assertEquals(3, first.get("shardCount").asInt());
        assertEquals(7, first.get("successCount").asInt(), "第 1、3 片的 7 行应成功");
        JsonNode shards = first.get("shards");
        assertEquals("SUCCESS", shards.get(0).get("status").asText());
        assertEquals("FAILED", shards.get(1).get("status").asText());
        assertNotNull(shards.get(1).get("errorMessage"));
        assertEquals("SUCCESS", shards.get(2).get("status").asText());
        String importNo = first.get("importNo").asText();

        Integer obsAfterFirst = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_cage_observation WHERE voyage_no = 'VOY-R1'", Integer.class);
        assertEquals(7, obsAfterFirst);

        // 重试端点续传失败分片：已入账行幂等跳过/不重复，分片 attempts 累加
        ResponseEntity<JsonNode> retryResp = auth().exchange(
                url("/api/observations/import/" + importNo + "/retry"),
                HttpMethod.POST, HttpEntity.EMPTY, JsonNode.class);
        JsonNode retried = retryResp.getBody().get("data");
        assertEquals("SUCCESS", retried.get("status").asText(), retried::toString);
        assertEquals(12, retried.get("successCount").asInt());
        assertEquals(12, retried.get("totalRows").asInt());
        assertEquals(1, retried.get("shards").get(1).get("attempts").asInt());
        assertEquals("SUCCESS", retried.get("shards").get(1).get("status").asText());

        Integer obsAfterRetry = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_cage_observation WHERE voyage_no = 'VOY-R1'", Integer.class);
        assertEquals(12, obsAfterRetry);
        Integer feedingRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_feeding_record fr JOIN t_fish_batch fb ON fr.batch_id = fb.id "
                        + "WHERE fb.cage_no = 'CAGE-R-01'", Integer.class);
        assertEquals(12, feedingRecords, "重试不产生重复投饵记录");

        // 断网重传同一文件：幂等短路，返回首次处理结果
        JsonNode reupload = upload(csv, "retry.csv").getBody().get("data");
        assertEquals(importNo, reupload.get("importNo").asText());
        assertEquals(12, reupload.get("successCount").asInt());
        Integer obsFinal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_cage_observation WHERE voyage_no = 'VOY-R1'", Integer.class);
        assertEquals(12, obsFinal);
    }

    @Test
    void staleRunningShardIsTakenOverByRetry() {
        createBatch("FB-RETRY-02", "CAGE-R-02");
        String csv = buildCsv("VOY-R2", "CAGE-R-02", 8);

        // 正常导入 8 行（2 片）
        JsonNode first = upload(csv, "stale.csv").getBody().get("data");
        assertEquals("SUCCESS", first.get("status").asText(), first::toString);
        String importNo = first.get("importNo").asText();

        // 模拟执行方崩溃：第 2 片被置为僵死 RUNNING（started_at 早于租约阈值）
        jdbcTemplate.update("UPDATE t_obs_import_shard SET status = 'RUNNING', "
                + "started_at = DATEADD('SECOND', -3600, CURRENT_TIMESTAMP), "
                + "error_message = '模拟崩溃遗留' WHERE import_id = "
                + "(SELECT id FROM t_obs_import_batch WHERE import_no = ?) AND shard_index = 1", importNo);
        jdbcTemplate.update("UPDATE t_obs_import_batch SET status = 'RUNNING', "
                + "started_at = DATEADD('SECOND', -3600, CURRENT_TIMESTAMP) WHERE import_no = ?", importNo);

        // 重传同一文件：僵死分片被接管续传
        JsonNode resumed = upload(csv, "stale.csv").getBody().get("data");
        assertEquals(importNo, resumed.get("importNo").asText());
        assertEquals("SUCCESS", resumed.get("status").asText(), resumed::toString);
        assertEquals(8, resumed.get("totalRows").asInt());
        assertEquals(8, resumed.get("successCount").asInt()
                + resumed.get("skippedCount").asInt()
                + resumed.get("updatedCount").asInt());
        assertEquals(0, resumed.get("failedCount").asInt());
        assertTrue(resumed.get("shards").get(1).get("attempts").asInt() >= 2);
    }
}
