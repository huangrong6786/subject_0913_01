package com.evops.aquaculture.csv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 观测数据 CSV 解析器（RFC-4180 风格子集）。
 * 支持：UTF-8 BOM、CRLF、双引号包裹字段（内嵌逗号与 "" 转义）。
 * 不支持引号内换行（观测数据行为单行记录）。
 *
 * 固定表头（顺序敏感）：
 * voyage_no,cage_no,observed_at,feed_amount_kg,survival_rate,sensor_value,sensor_unit,source_device
 */
public class ObservationCsvParser {

    public static final String[] EXPECTED_HEADER = {
            "voyage_no", "cage_no", "observed_at", "feed_amount_kg",
            "survival_rate", "sensor_value", "sensor_unit", "source_device"};

    public static final int COLUMN_COUNT = EXPECTED_HEADER.length;

    /**
     * 从物理行列表中抽取数据行：第 1 行必须是表头，空行被剔除。
     *
     * @throws IllegalArgumentException 表头缺失或与期望不符（文件级失败）
     */
    public List<DataLine> extractDataLines(List<String> physicalLines) {
        if (physicalLines == null || physicalLines.isEmpty()) {
            throw new IllegalArgumentException("文件为空：缺少表头行，期望表头 " + expectedHeaderText());
        }
        String headerLine = stripBom(physicalLines.get(0));
        String[] header = parseLine(headerLine);
        if (header.length != COLUMN_COUNT || !headerMatches(header)) {
            throw new IllegalArgumentException("表头与期望不符，期望 " + expectedHeaderText()
                    + "，实际 " + String.join(",", header));
        }
        List<DataLine> dataLines = new ArrayList<>();
        for (int i = 1; i < physicalLines.size(); i++) {
            String text = physicalLines.get(i);
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            dataLines.add(new DataLine(i + 1, text));
        }
        return dataLines;
    }

    public String expectedHeaderText() {
        return String.join(",", EXPECTED_HEADER);
    }

    private boolean headerMatches(String[] header) {
        for (int i = 0; i < COLUMN_COUNT; i++) {
            if (!EXPECTED_HEADER[i].equals(header[i] == null ? null : header[i].trim())) {
                return false;
            }
        }
        return true;
    }

    private static String stripBom(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }

    /**
     * 解析一行 CSV 为字段数组（已去除各字段首尾空白）。
     * 引号包裹的字段可含逗号；"" 表示字面双引号。
     */
    public String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int len = line.length();
        for (int i = 0; i < len; i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else if (c == '"' && current.length() == 0) {
                inQuotes = true;
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        String[] result = fields.toArray(new String[0]);
        for (int i = 0; i < result.length; i++) {
            result[i] = result[i] == null ? null : result[i].trim();
        }
        return result;
    }

    @Override
    public String toString() {
        return "ObservationCsvParser" + Arrays.toString(EXPECTED_HEADER);
    }
}
