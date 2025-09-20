package com.lottery.lottery_service.lotto.validation.rule;

import com.lottery.lottery_service.lotto.dto.LottoSet;

/**
 * 모든 "번호 유효성 검증 규칙"의 공통 계약(인터페이스).
 *
 * 설계 원칙:
 * - 룰은 "판정"만 담당한다(단일 책임).
 * - 상태를 가지지 않는다(stateless). 외부 상태 변경 금지(부작용 없음).
 * - 파이프라인은 이 계약(인터페이스)에만 의존한다 → 구현체 추가/교체/테스트가 쉬움(다형성).
 * - 필요한 데이터 접근은 캐시/컨텍스트를 통해 "읽기"만 한다.
 * - 스프링 DI에서 List<LottoValidationRule>로 주입하면 모든 구현체가 자동 수집된다.
 *
 * 반환/예외 규약
 * - validate(...)가 true면 "통과", false면 "불허".
 * - 시스템 상태 이상(예: 캐시가 비초기화 상태)처럼 비즈니스가 아닌 장애 상황은 예외로 던져
 *   파이프라인이 fail-closed 정책(즉시 중단)을 적용하도록 한다.
 */
public interface LottoValidationRule {

    /**
     * 룰의 고유 식별자(내부 로그/메트릭에서 사용).
     * - 예: "NOT_EQUAL_TO_PAST_FIRST_PRIZE"
     * - 사람이 읽기 쉬운 포맷을 권장(스네이크/케밥 등 팀 컨벤션 따르기)
     */
    String id();

    /**
     * 이 룰에서 "실패"가 발생했을 때 기록할 내부 사유 코드.
     * - 기본은 id()와 동일하게 두되, 필요 시 구현체에서 의미 있는 코드로 오버라이드.
     * - 예: "EQUALS_PAST_FIRST_PRIZE"
     */
    default String reasonOnFail() {
        return id();
    }

    /**
     * 룰 활성화 여부.
     * - 환경설정 기반으로 on/off를 제어할 때 사용.
     * - 지금은 항상 true를 반환해도 무방하지만, 확장성을 위해 기본 훅 제공.
     */
    default boolean enabled() {
        return true;
    }

    /**
     * 검증 수행(핵심). true = PASS(허용), false = FAIL(불허).
     *
     * @param set 추천된 번호 1세트(숫자 6개)
     * @return true(통과) / false(불허)
     * @throws RuntimeException 시스템 상태 이상(캐시 미초기화 등) 시 예외로 올려 파이프라인이 중단되도록 함
     */
    boolean validate(LottoSet set);
}