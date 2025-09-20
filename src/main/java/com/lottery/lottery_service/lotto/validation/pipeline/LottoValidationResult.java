package com.lottery.lottery_service.lotto.validation.pipeline;

import java.util.Collections;
import java.util.List;

/**
 * [내부 모델] 검증 파이프라인의 최종 결과.
 *
 * 노출 정책
 * - 이 결과는 "내부 전용"으로 사용하며, 사용자 응답에는 노출하지 않는다.
 *
 * 포함 정보
 * - pass: 전체 통과 여부
 * - failedRuleId: 처음 실패를 야기한 룰 ID (통과 시 null)
 * - failedReasonCode: 실패 사유 코드(룰이 제공, 통과 시 null)
 * - executedRuleIds: 실행(시도)된 룰 ID의 순서 리스트
 *
 * 확장 여지
 * - 필요 시 경고(warn) 개념이나 추가 메타(소요시간 등)를 붙일 수 있다(내부용).
 */
public class LottoValidationResult {

    /** 전체 통과 여부 */
    private final boolean pass;

    /** 처음 실패를 유발한 룰의 ID(통과 시 null) */
    private final String failedRuleId;

    /** 실패 사유 코드(통과 시 null) */
    private final String failedReasonCode;

    /** 실행(시도)된 룰들의 ID 목록(실패 시 실패 지점까지 포함) */
    private final List<String> executedRuleIds;

    private LottoValidationResult(
            boolean pass,
            String failedRuleId,
            String failedReasonCode,
            List<String> executedRuleIds
    ) {
        this.pass = pass;
        this.failedRuleId = failedRuleId;
        this.failedReasonCode = failedReasonCode;
        // 불변 리스트로 보호(외부 수정 방지)
        this.executedRuleIds = executedRuleIds == null
                ? List.of()
                : Collections.unmodifiableList(executedRuleIds);
    }

    /** 모든 활성화된 룰을 통과한 경우 */
    public static LottoValidationResult pass(List<String> executedRuleIds) {
        return new LottoValidationResult(true, null, null, executedRuleIds);
    }

    /** 특정 룰에서 실패한 경우 */
    public static LottoValidationResult fail(
            String failedRuleId,
            String failedReasonCode,
            List<String> executedRuleIds
    ) {
        return new LottoValidationResult(false, failedRuleId, failedReasonCode, executedRuleIds);
    }

    public boolean isPass() {
        return pass;
    }

    public String getFailedRuleId() {
        return failedRuleId;
    }

    public String getFailedReasonCode() {
        return failedReasonCode;
    }

    public List<String> getExecutedRuleIds() {
        return executedRuleIds;
    }

    @Override
    public String toString() {
        return "LottoValidationResult{" +
                "pass=" + pass +
                ", failedRuleId='" + failedRuleId + '\'' +
                ", failedReasonCode='" + failedReasonCode + '\'' +
                ", executedRuleIds=" + executedRuleIds +
                '}';
    }
}