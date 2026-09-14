package com.evops.aquaculture.csv;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 通过字段/单位/时间校验的一行观测数据。 */
public class ValidatedObsRow {

    private final int lineNo;
    private final String rawLine;
    private final String voyageNo;
    private final String cageNo;
    private final LocalDateTime observedAt;
    private final BigDecimal feedAmountKg;
    private final BigDecimal survivalRate;
    private final BigDecimal sensorValue;
    private final String sensorUnit;
    private final String sourceDevice;

    public ValidatedObsRow(int lineNo, String rawLine, String voyageNo, String cageNo,
                           LocalDateTime observedAt, BigDecimal feedAmountKg, BigDecimal survivalRate,
                           BigDecimal sensorValue, String sensorUnit, String sourceDevice) {
        this.lineNo = lineNo;
        this.rawLine = rawLine;
        this.voyageNo = voyageNo;
        this.cageNo = cageNo;
        this.observedAt = observedAt;
        this.feedAmountKg = feedAmountKg;
        this.survivalRate = survivalRate;
        this.sensorValue = sensorValue;
        this.sensorUnit = sensorUnit;
        this.sourceDevice = sourceDevice;
    }

    /** 幂等业务键：航次号|网箱号|采样时刻。 */
    public String businessKey() {
        return businessKeyOf(voyageNo, cageNo, observedAt);
    }

    public static String businessKeyOf(String voyageNo, String cageNo, LocalDateTime observedAt) {
        return voyageNo + "|" + cageNo + "|" + observedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /** 是否携带投饵量/存活率（需要网箱存在在养批次）。 */
    public boolean needsBatch() {
        return feedAmountKg != null || survivalRate != null;
    }

    /** 是否携带传感器读数。 */
    public boolean hasSensor() {
        return sensorValue != null;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getRawLine() {
        return rawLine;
    }

    public String getVoyageNo() {
        return voyageNo;
    }

    public String getCageNo() {
        return cageNo;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public BigDecimal getFeedAmountKg() {
        return feedAmountKg;
    }

    public BigDecimal getSurvivalRate() {
        return survivalRate;
    }

    public BigDecimal getSensorValue() {
        return sensorValue;
    }

    public String getSensorUnit() {
        return sensorUnit;
    }

    public String getSourceDevice() {
        return sourceDevice;
    }
}
