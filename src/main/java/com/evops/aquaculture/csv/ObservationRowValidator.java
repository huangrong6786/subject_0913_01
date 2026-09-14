package com.evops.aquaculture.csv;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 观测数据行校验器：字段、单位（精度）、时间、业务键格式校验。
 * 全部校验失败以 {@link RowValidationException} 抛出，由导入服务逐行隔离落账。
 */
public final class ObservationRowValidator {

    /** 航次号/网箱号/设备号：字母数字中划线 underscore，最长 32（业务键格式约束）。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    private static final List<DateTimeFormatter> TIME_FORMATS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

    /** 观测时间允许的最大未来偏移（容忍采集端时钟偏差）。 */
    private static final int FUTURE_TOLERANCE_MINUTES = 10;

    private static final BigDecimal MAX_FEED_AMOUNT = new BigDecimal("99999.99");
    private static final BigDecimal MAX_SENSOR_VALUE = new BigDecimal("99999999.9999");

    private ObservationRowValidator() {
    }

    /**
     * 校验并转换一行（列数已由调用方核对）。
     *
     * @throws RowValidationException 任一字段校验失败（携带字段名/原值/原因）
     */
    public static ValidatedObsRow validate(String[] cols, int lineNo, String rawLine) {
        String voyageNo = requireKey(cols[0], "voyage_no", "航次号");
        String cageNo = requireKey(cols[1], "cage_no", "网箱号");
        LocalDateTime observedAt = parseTime(cols[2]);

        BigDecimal feedAmount = parseDecimal(cols[3], "feed_amount_kg", "投饵量", 2);
        if (feedAmount != null) {
            if (feedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RowValidationException("feed_amount_kg", cols[3], "投饵量必须大于0");
            }
            if (feedAmount.compareTo(MAX_FEED_AMOUNT) > 0) {
                throw new RowValidationException("feed_amount_kg", cols[3],
                        "投饵量超出允许范围（最大 " + MAX_FEED_AMOUNT + " kg）");
            }
        }

        BigDecimal survivalRate = parseDecimal(cols[4], "survival_rate", "存活率", 4);
        if (survivalRate != null
                && (survivalRate.compareTo(BigDecimal.ZERO) < 0
                        || survivalRate.compareTo(BigDecimal.ONE) > 0)) {
            throw new RowValidationException("survival_rate", cols[4], "存活率必须在 0~1 之间");
        }

        BigDecimal sensorValue = parseDecimal(cols[5], "sensor_value", "传感器读数", 4);
        if (sensorValue != null) {
            if (sensorValue.compareTo(BigDecimal.ZERO) < 0) {
                throw new RowValidationException("sensor_value", cols[5], "传感器读数不能为负");
            }
            if (sensorValue.compareTo(MAX_SENSOR_VALUE) > 0) {
                throw new RowValidationException("sensor_value", cols[5],
                        "传感器读数超出允许范围（最大 " + MAX_SENSOR_VALUE + "）");
            }
        }

        String sensorUnit = trimToNull(cols[6]);
        String sourceDevice = trimToNull(cols[7]);
        if (sensorValue != null) {
            if (sourceDevice == null) {
                throw new RowValidationException("source_device", null, "存在传感器读数时来源设备不能为空");
            }
            if (!KEY_PATTERN.matcher(sourceDevice).matches()) {
                throw new RowValidationException("source_device", sourceDevice,
                        "来源设备编号仅允许字母/数字/中划线/下划线且不超过32字符");
            }
            if (sensorUnit == null) {
                throw new RowValidationException("sensor_unit", null, "存在传感器读数时读数单位不能为空");
            }
            if (sensorUnit.length() > 16) {
                throw new RowValidationException("sensor_unit", sensorUnit, "读数单位长度不能超过16字符");
            }
        }

        if (feedAmount == null && survivalRate == null && sensorValue == null) {
            throw new RowValidationException("_row", null, "行内无任何观测数据（投饵量/存活率/传感器读数均为空）");
        }

        return new ValidatedObsRow(lineNo, rawLine, voyageNo, cageNo, observedAt,
                feedAmount, survivalRate, sensorValue, sensorUnit, sourceDevice);
    }

    private static String requireKey(String raw, String field, String label) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new RowValidationException(field, raw, label + "不能为空");
        }
        if (!KEY_PATTERN.matcher(value).matches()) {
            throw new RowValidationException(field, raw,
                    label + "仅允许字母/数字/中划线/下划线且不超过32字符");
        }
        return value;
    }

    private static LocalDateTime parseTime(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new RowValidationException("observed_at", raw, "观测时间不能为空");
        }
        for (DateTimeFormatter formatter : TIME_FORMATS) {
            try {
                // 采样时刻按秒归一：业务键须经受住“解析→入库→读出”往返，
                // 亚秒精度在不同存储精度下会造成键漂移。
                LocalDateTime time = LocalDateTime.parse(value, formatter)
                        .truncatedTo(ChronoUnit.SECONDS);
                if (time.isAfter(LocalDateTime.now().plusMinutes(FUTURE_TOLERANCE_MINUTES))) {
                    throw new RowValidationException("observed_at", raw, "观测时间不能晚于当前时间");
                }
                return time;
            } catch (DateTimeParseException ignored) {
                // 尝试下一种格式
            }
        }
        throw new RowValidationException("observed_at", raw,
                "观测时间格式非法，支持 ISO-8601（如 2026-09-10T08:00:00）");
    }

    private static BigDecimal parseDecimal(String raw, String field, String label, int maxScale) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        BigDecimal number;
        try {
            number = new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new RowValidationException(field, raw, label + "不是合法数值: " + value);
        }
        int scale = number.signum() == 0 ? 0 : number.stripTrailingZeros().scale();
        if (scale > maxScale) {
            throw new RowValidationException(field, raw, label + "最多支持 " + maxScale + " 位小数");
        }
        return number;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
