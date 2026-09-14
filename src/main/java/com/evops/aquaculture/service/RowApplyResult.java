package com.evops.aquaculture.service;

import com.evops.aquaculture.entity.CageObservation;
import com.evops.aquaculture.enums.ObsRowOutcome;

/** 单行应用结果：成功/更新/跳过携带观测实体；失败携带字段、原值与原因。 */
class RowApplyResult {

    private final ObsRowOutcome outcome;
    private final CageObservation observation;
    private final String fieldName;
    private final String rawValue;
    private final String errorReason;

    private RowApplyResult(ObsRowOutcome outcome, CageObservation observation,
                           String fieldName, String rawValue, String errorReason) {
        this.outcome = outcome;
        this.observation = observation;
        this.fieldName = fieldName;
        this.rawValue = rawValue;
        this.errorReason = errorReason;
    }

    static RowApplyResult of(ObsRowOutcome outcome, CageObservation observation) {
        return new RowApplyResult(outcome, observation, null, null, null);
    }

    static RowApplyResult failed(String fieldName, String rawValue, String errorReason) {
        return new RowApplyResult(ObsRowOutcome.FAILED, null, fieldName, rawValue, errorReason);
    }

    ObsRowOutcome getOutcome() {
        return outcome;
    }

    CageObservation getObservation() {
        return observation;
    }

    String getFieldName() {
        return fieldName;
    }

    String getRawValue() {
        return rawValue;
    }

    String getErrorReason() {
        return errorReason;
    }
}
