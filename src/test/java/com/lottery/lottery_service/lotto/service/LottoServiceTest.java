package com.lottery.lottery_service.lotto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;

import com.lottery.lottery_service.lotto.dto.request.PurchaseLottoRequest;
import com.lottery.lottery_service.lotto.dto.response.LottoRecordResponse;
import com.lottery.lottery_service.lotto.entity.LottoRecord;
import com.lottery.lottery_service.lotto.repository.LottoRecordRepository;
import com.lottery.lottery_service.lotto.validation.pipeline.LottoValidationPipeline;
import com.lottery.lottery_service.member.entity.Member;
import com.lottery.lottery_service.member.repository.MemberRepository;
import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.validation.pipeline.LottoValidationResult; 
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * LottoService의 도메인 정책을 검증하는 테스트 클래스.
 *
 * <p>이 테스트 클래스의 역할:
 * <ul>
 *   <li>HTTP/보안/JSON 직렬화는 보지 않는다. (그건 LottoControllerWebTest의 책임)</li>
 *   <li>서비스가 repository/pipeline을 어떻게 조합해서 도메인 규칙을 수행하는지 검증한다.</li>
 *   <li>특히 구매 저장 로직의 update-or-insert 분기와 번호 정규화 정책을 고정한다.</li>
 * </ul>
 *
 * <p>현재 LottoService는 추천 생성, 추천 저장, 구매 저장, 조회, validation pipeline 호출 책임을 함께 가진다.
 * 그중 이번 1차 테스트 범위는 랜덤이 없고 분기가 명확한 구매 저장/조회 로직부터 시작한다.
 */
@ExtendWith(MockitoExtension.class)
class LottoServiceTest {

  @Mock
  private LottoRecordRepository lottoRecordRepository;

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private LottoValidationPipeline validationPipeline;

  @InjectMocks
  private LottoService lottoService;

  /**
   * 기존 동일 번호 기록이 존재하고 아직 purchased=false 라면,
   * 새 레코드를 만들지 않고 기존 레코드의 purchased 값을 true로 갱신해야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   * <ul>
   *   <li>memberRepository.getReferenceById(memberId)를 통해 회원 참조를 가져온다.</li>
   *   <li>round + normalized numbers 기준으로 기존 기록을 조회한다.</li>
   *   <li>기존 기록이 있고 purchased=false 면 save(existing)를 호출한다.</li>
   *   <li>새 레코드는 생성하지 않는다.</li>
   * </ul>
   */
  @Test
  @DisplayName("기존 기록이 있으면 purchased 값을 true로 갱신한다")
  void addPurchasedRecords_existingRecord_updatesPurchasedFlag() {
    // given
    Long memberId = 1L;
    Member member = Member.newMember("test@example.com", "tester", null);

    PurchaseLottoRequest request = new PurchaseLottoRequest();
    request.setRound("1112");
    request.setNumbersList(List.of("6, 1, 3, 2, 5, 4")); // 서비스 내부에서 "1 2 3 4 5 6"으로 정규화되어야 함

    LottoRecord existing = LottoRecord.builder()
        .member(member)
        .round(1112)
        .numbers("1 2 3 4 5 6")
        .recommendedAt(LocalDateTime.now().minusDays(1))
        .manual(false)
        .purchased(false)
        .source("BASIC")
        .build();

    given(memberRepository.getReferenceById(memberId)).willReturn(member);
    given(lottoRecordRepository.findByMemberAndRoundAndNumbers(member, 1112, "1 2 3 4 5 6"))
        .willReturn(Optional.of(existing));

    // when
    lottoService.addPurchasedRecords(memberId, request);

    // then
    assertThat(existing.isPurchased()).isTrue();
    verify(lottoRecordRepository).save(existing);
  }

  /**
   * 기존 동일 번호 기록이 없다면,
   * 서비스는 새 구매 레코드를 생성해 저장해야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   * <ul>
   *   <li>입력 번호 문자열이 canonical 포맷("1 2 3 4 5 6")으로 정규화된다.</li>
   *   <li>새 레코드는 manual=true, purchased=true, source="manual" 이어야 한다.</li>
   *   <li>member, round, numbers 필드가 정확히 채워져야 한다.</li>
   * </ul>
   */
  @Test
  @DisplayName("기존 기록이 없으면 새 수동 구매 기록을 저장한다")
  void addPurchasedRecords_noExistingRecord_savesNewManualPurchasedRecord() {
    // given
    Long memberId = 1L;
    Member member = Member.newMember("test@example.com", "tester", null);

    PurchaseLottoRequest request = new PurchaseLottoRequest();
    request.setRound("1112");
    request.setNumbersList(List.of("6, 1, 3, 2, 5, 4"));

    given(memberRepository.getReferenceById(memberId)).willReturn(member);
    given(lottoRecordRepository.findByMemberAndRoundAndNumbers(member, 1112, "1 2 3 4 5 6"))
        .willReturn(Optional.empty());

    ArgumentCaptor<LottoRecord> captor = ArgumentCaptor.forClass(LottoRecord.class);

    // when
    lottoService.addPurchasedRecords(memberId, request);

    // then
    verify(lottoRecordRepository).save(captor.capture());

    LottoRecord saved = captor.getValue();
    assertThat(saved.getMember()).isEqualTo(member);
    assertThat(saved.getRound()).isEqualTo(1112);
    assertThat(saved.getNumbers()).isEqualTo("1 2 3 4 5 6");
    assertThat(saved.isManual()).isTrue();
    assertThat(saved.isPurchased()).isTrue();
    assertThat(saved.getSource()).isEqualTo("manual");
    assertThat(saved.getRecommendedAt()).isNotNull();
  }

  /**
   * 잘못된 번호 문자열이 들어오면 서비스 내부 normalizeNumbers(...)에서 예외가 발생해야 한다.
   *
   * <p>현재 normalizeNumbers는 다음을 검증한다.
   * <ul>
   *   <li>정확히 6개 숫자인지</li>
   *   <li>1~45 범위인지</li>
   *   <li>중복이 없는지</li>
   * </ul>
   *
   * <p>이 테스트는 private 메서드를 직접 호출하지 않고,
   * 공개 서비스 메서드 addPurchasedRecords(...)를 통해 간접 검증한다.
   */
  @Test
  @DisplayName("잘못된 번호 문자열이면 예외를 던지고 저장하지 않는다")
  void addPurchasedRecords_invalidNumbers_throwsException() {
    // given
    Long memberId = 1L;
    Member member = Member.newMember("test@example.com", "tester", null);

    PurchaseLottoRequest request = new PurchaseLottoRequest();
    request.setRound("1112");
    request.setNumbersList(List.of("1, 2, 3, 4, 5, 5")); // 중복 번호

    given(memberRepository.getReferenceById(memberId)).willReturn(member);

    // when & then
    assertThatThrownBy(() -> lottoService.addPurchasedRecords(memberId, request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("중복");

    verify(lottoRecordRepository, never()).save(any());
  }

  /**
   * 회원 추천 내역 조회는 repository 결과를 그대로 DTO로 매핑해 반환해야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   * <ul>
   *   <li>findAllByMemberIdOrderByRoundDesc(memberId)를 호출하는지</li>
   *   <li>LottoRecordResponse.from(...) 규약에 맞춰 필드가 복사되는지</li>
   * </ul>
   */
  @Test
  @DisplayName("회원 추천 내역을 응답 DTO로 변환해 반환한다")
  void getRecommendationsForMember_recordsExist_returnsMappedResponses() {
    // given
    Long memberId = 1L;

    LottoRecord first = LottoRecord.builder()
        .round(1112)
        .numbers("1 2 3 4 5 6")
        .recommendedAt(LocalDateTime.of(2026, 4, 21, 21, 0))
        .manual(false)
        .purchased(false)
        .source("BASIC")
        .build();

    LottoRecord second = LottoRecord.builder()
        .round(1111)
        .numbers("7 8 9 10 11 12")
        .recommendedAt(LocalDateTime.of(2026, 4, 20, 21, 0))
        .manual(true)
        .purchased(true)
        .source("manual")
        .build();

    given(lottoRecordRepository.findAllByMemberIdOrderByRoundDesc(memberId))
        .willReturn(List.of(first, second));

    // when
    List<LottoRecordResponse> result = lottoService.getRecommendationsForMember(memberId);

    // then
    assertThat(result).hasSize(2);

    assertThat(result.get(0).getRound()).isEqualTo(1112);
    assertThat(result.get(0).getNumbers()).isEqualTo("1 2 3 4 5 6");
    assertThat(result.get(0).isManual()).isFalse();
    assertThat(result.get(0).isPurchased()).isFalse();
    assertThat(result.get(0).getSource()).isEqualTo("BASIC");

    assertThat(result.get(1).getRound()).isEqualTo(1111);
    assertThat(result.get(1).getNumbers()).isEqualTo("7 8 9 10 11 12");
    assertThat(result.get(1).isManual()).isTrue();
    assertThat(result.get(1).isPurchased()).isTrue();
    assertThat(result.get(1).getSource()).isEqualTo("manual");

    verify(lottoRecordRepository).findAllByMemberIdOrderByRoundDesc(memberId);
  }

  /**
    * 기존 기록이 이미 purchased=true 상태라면,
    * 서비스는 중복 저장이나 재갱신을 하지 않고 그냥 지나가야 한다.
    
    * <p>이 테스트가 보장하는 것:
    * <ul>
    *   <li>동일 member + round + numbers 기록이 이미 존재한다.</li>
    *   <li>그 기록이 이미 purchased=true 이면 save(...)를 다시 호출하지 않는다.</li>
    *   <li>즉 구매 저장 로직이 idempotent하게 동작한다.</li>
    * </ul>
    */
    @Test
    @DisplayName("기존 기록이 이미 구매 상태면 다시 저장하지 않는다")
    void addPurchasedRecords_existingPurchasedRecord_doesNotSaveAgain() {
    // given
    Long memberId = 1L;
    Member member = Member.newMember("test@example.com", "tester", null);

    PurchaseLottoRequest request = new PurchaseLottoRequest();
    request.setRound("1112");
    request.setNumbersList(List.of("6, 1, 3, 2, 5, 4"));

    LottoRecord existing = LottoRecord.builder()
        .member(member)
        .round(1112)
        .numbers("1 2 3 4 5 6")
        .recommendedAt(LocalDateTime.now().minusDays(1))
        .manual(false)
        .purchased(true)
        .source("BASIC")
        .build();

    given(memberRepository.getReferenceById(memberId)).willReturn(member);
    given(lottoRecordRepository.findByMemberAndRoundAndNumbers(member, 1112, "1 2 3 4 5 6"))
        .willReturn(Optional.of(existing));

    // when
    lottoService.addPurchasedRecords(memberId, request);

    // then
    assertThat(existing.isPurchased()).isTrue();
    verify(lottoRecordRepository, never()).save(any(LottoRecord.class));
    }

    /**
     * guest 추천은 현재 서비스 구현상:
     * <ul>
     *   <li>번호 5세트를 생성하고</li>
     *   <li>guest=true, member=null, manual=false, purchased=false 로 저장하며</li>
     *   <li>source는 입력값을 그대로 쓰고</li>
     *   <li>round는 현재 하드코딩된 1112를 사용한다.</li>
     * </ul>
     *
     * <p>랜덤 숫자 자체를 검증하는 테스트가 아니라,
     * "guest 저장 정책"이 현재 구현대로 적용되는지 검증한다.
     */
    @Test
    @DisplayName("비회원 추천은 guest 전용 정책으로 5건 저장한다")
    @SuppressWarnings("unchecked")
    void recommendAndSaveForGuest_savesGuestRecords() {
    // given
    ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);

    // when
    List<LottoSet> result = lottoService.recommendAndSaveForGuest("BASIC");

    // then
    assertThat(result).hasSize(5);
    assertThat(result).allSatisfy(set -> assertThat(set.getNumbers()).hasSize(6));

    verify(lottoRecordRepository).saveAll(captor.capture());

    List<LottoRecord> savedRecords = (List<LottoRecord>) captor.getValue();
    assertThat(savedRecords).hasSize(5);

    assertThat(savedRecords).allSatisfy(record -> {
        assertThat(record.getMember()).isNull();
        assertThat(record.isGuest()).isTrue();
        assertThat(record.isManual()).isFalse();
        assertThat(record.isPurchased()).isFalse();
        assertThat(record.getSource()).isEqualTo("BASIC");
        assertThat(record.getRound()).isEqualTo(1112);
        assertThat(record.getRecommendedAt()).isNotNull();
    });
    }

    /**
     * 회원 추천은 내부적으로 후보를 1세트씩 만들고 validationPipeline을 통과한 세트만 모아 저장한다.
     *
     * <p>현재 구현은 Random을 내부에서 직접 생성하므로,
     * 이 테스트에서는 service spy를 사용해 generateLottoNumbersSet(1)의 반환값을 제어한다.
     *
     * <p>이 테스트가 보장하는 것:
     * <ul>
     *   <li>FAIL 후보는 저장 대상에서 제외된다.</li>
     *   <li>PASS 후보만 최종 저장된다.</li>
     *   <li>최종 저장 건수는 desired=5를 만족한다.</li>
     *   <li>현재 round 하드코딩 값 1112가 저장된다.</li>
     * </ul>
     */
    @Test
    @DisplayName("회원 추천은 validation 통과 세트만 저장한다")
    @SuppressWarnings("unchecked")
    void recommendAndSaveForMember_validationPassOnly_savesFilteredSets() {
    // given
    Long memberId = 1L;
    Member member = Member.newMember("test@example.com", "tester", null);

    LottoSet fail1 = new LottoSet(List.of(1, 2, 3, 4, 5, 6));
    LottoSet pass1 = new LottoSet(List.of(7, 8, 9, 10, 11, 12));
    LottoSet pass2 = new LottoSet(List.of(13, 14, 15, 16, 17, 18));
    LottoSet fail2 = new LottoSet(List.of(19, 20, 21, 22, 23, 24));
    LottoSet pass3 = new LottoSet(List.of(25, 26, 27, 28, 29, 30));
    LottoSet pass4 = new LottoSet(List.of(31, 32, 33, 34, 35, 36));
    LottoSet pass5 = new LottoSet(List.of(37, 38, 39, 40, 41, 42));

    LottoService spyService = spy(new LottoService(
        lottoRecordRepository,
        memberRepository,
        validationPipeline
    ));

    // recommendAndSaveForMember()가 generateLottoNumbersSet(1)을 반복 호출하므로 순차 반환값을 제어한다.
    doReturn(List.of(fail1))
        .doReturn(List.of(pass1))
        .doReturn(List.of(pass2))
        .doReturn(List.of(fail2))
        .doReturn(List.of(pass3))
        .doReturn(List.of(pass4))
        .doReturn(List.of(pass5))
        .when(spyService).generateLottoNumbersSet(1);

    given(validationPipeline.validate(fail1))
        .willReturn(LottoValidationResult.fail("RULE_A", "FAIL_A", List.of("RULE_A")));
    given(validationPipeline.validate(pass1))
        .willReturn(LottoValidationResult.pass(List.of("RULE_A", "RULE_B")));
    given(validationPipeline.validate(pass2))
        .willReturn(LottoValidationResult.pass(List.of("RULE_A", "RULE_B")));
    given(validationPipeline.validate(fail2))
        .willReturn(LottoValidationResult.fail("RULE_C", "FAIL_C", List.of("RULE_A", "RULE_C")));
    given(validationPipeline.validate(pass3))
        .willReturn(LottoValidationResult.pass(List.of("RULE_A", "RULE_B")));
    given(validationPipeline.validate(pass4))
        .willReturn(LottoValidationResult.pass(List.of("RULE_A", "RULE_B")));
    given(validationPipeline.validate(pass5))
        .willReturn(LottoValidationResult.pass(List.of("RULE_A", "RULE_B")));

    given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

    ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);

    // when
    List<LottoSet> result = spyService.recommendAndSaveForMember(memberId, "BASIC");

    // then
    assertThat(result).containsExactly(pass1, pass2, pass3, pass4, pass5);

    verify(lottoRecordRepository).saveAll(captor.capture());

    List<LottoRecord> savedRecords = (List<LottoRecord>) captor.getValue();
    assertThat(savedRecords).hasSize(5);

    assertThat(savedRecords).extracting(LottoRecord::getNumbers)
        .containsExactly(
            "7 8 9 10 11 12",
            "13 14 15 16 17 18",
            "25 26 27 28 29 30",
            "31 32 33 34 35 36",
            "37 38 39 40 41 42"
        );

    assertThat(savedRecords).allSatisfy(record -> {
        assertThat(record.getMember()).isEqualTo(member);
        assertThat(record.isGuest()).isFalse();
        assertThat(record.isManual()).isFalse();
        assertThat(record.isPurchased()).isFalse();
        assertThat(record.getSource()).isEqualTo("BASIC");
        assertThat(record.getRound()).isEqualTo(1112);
    });

    verify(memberRepository).findById(memberId);
    verify(validationPipeline, times(7)).validate(any(LottoSet.class));
    }
}