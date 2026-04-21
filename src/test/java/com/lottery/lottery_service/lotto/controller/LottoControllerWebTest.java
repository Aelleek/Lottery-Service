package com.lottery.lottery_service.lotto.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.auth.CustomOAuth2UserService;
import com.lottery.lottery_service.config.SecurityConfig;
import com.lottery.lottery_service.lotto.dto.LottoSet;
import com.lottery.lottery_service.lotto.dto.request.PurchaseLottoRequest;
import com.lottery.lottery_service.lotto.dto.response.LottoRecordResponse;
import com.lottery.lottery_service.lotto.service.LottoService;
import com.lottery.lottery_service.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * LottoController의 웹 계층 계약을 검증하는 테스트 클래스.
 *
 * <p>이 테스트 클래스의 핵심 역할은 아래 4가지를 확인하는 것이다.
 *
 * <ol>
 *   <li>요청 URL과 HTTP Method가 현재 컨트롤러 계약과 일치하는지
 *   <li>회원 전용 API가 인증 principal(memberId 포함)을 요구하는지
 *   <li>비회원 API가 로그인 없이도 접근 가능한지
 *   <li>컨트롤러가 요청을 해석한 뒤 올바른 서비스 메서드로 위임하는지
 * </ol>
 *
 * <p>중요:
 *
 * <ul>
 *   <li>이 클래스는 서비스 내부 로직(랜덤 번호 생성, 저장 정책, validation rule)을 검증하지 않는다.
 *   <li>그런 로직은 별도의 LottoServiceTest, LottoValidationPipelineTest에서 검증해야 한다.
 *   <li>여기서는 오직 "웹 계층 계약"만 본다.
 * </ul>
 */
@WebMvcTest(controllers = LottoController.class)
@Import(SecurityConfig.class)
class LottoControllerWebTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  /**
   * 컨트롤러가 의존하는 실제 서비스는 웹 테스트의 대상이 아니므로 mock 처리한다.
   *
   * <p>이 mock을 통해 "컨트롤러가 어떤 인자를 서비스로 넘겼는지"와 "서비스 결과를 응답으로 어떻게 직렬화하는지"만 검증한다.
   */
  @MockBean private LottoService lottoService;

  /**
   * LottoController는 현재 생성자 주입으로 MemberRepository를 받는다.
   *
   * <p>실제 테스트 메서드에서 직접 사용하지 않더라도, 컨트롤러 빈 생성에 필요하므로 mock bean으로 등록해야 한다.
   */
  @MockBean private MemberRepository memberRepository;

  /**
   * SecurityConfig는 oauth2Login 설정에서 CustomOAuth2UserService를 필요로 한다.
   *
   * <p>웹 테스트에서는 실제 OAuth2 사용자 로딩 로직을 검증하지 않으므로 mock 처리한다.
   */
  @MockBean private CustomOAuth2UserService customOAuth2UserService;

  /**
   * 회원 추천 API 성공 케이스.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>현재 회원 추천 URL이 /api/lotto/recommendations 인지
   *   <li>인증 principal 안의 memberId가 서비스로 전달되는지
   *   <li>서비스가 반환한 LottoSet 5개가 JSON 배열로 응답되는지
   *   <li>각 세트가 numbers 필드를 통해 직렬화되는지
   * </ul>
   *
   * <p>이 테스트가 보장하지 않는 것:
   *
   * <ul>
   *   <li>번호 생성 알고리즘의 정확성
   *   <li>validation rule의 PASS/FAIL 동작
   *   <li>DB 저장 성공 여부
   * </ul>
   */
  @Test
  @DisplayName("회원 추천 성공: 로그인 사용자는 로또 번호 5세트를 반환받는다")
  void recommendForMember_success() throws Exception {
    List<LottoSet> sets =
        List.of(
            new LottoSet(List.of(1, 2, 3, 4, 5, 6)),
            new LottoSet(List.of(7, 8, 9, 10, 11, 12)),
            new LottoSet(List.of(13, 14, 15, 16, 17, 18)),
            new LottoSet(List.of(19, 20, 21, 22, 23, 24)),
            new LottoSet(List.of(25, 26, 27, 28, 29, 30)));

    // 컨트롤러가 memberId=1L, source=BASIC을 서비스로 넘겼을 때의 결과를 미리 정의한다.
    given(lottoService.recommendAndSaveForMember(1L, "BASIC")).willReturn(sets);

    mockMvc
        .perform(
            post("/api/lotto/recommendations")
                // 회원 API는 principal 내부의 memberId를 읽으므로, 테스트에서도 이 값을 직접 넣어줘야 한다.
                .with(oauth2Login().attributes(attributes -> attributes.put("memberId", 1L)))
                .param("source", "BASIC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5))
        .andExpect(jsonPath("$[0].numbers.length()").value(6));

    // 컨트롤러가 서비스에 정확한 인자로 위임했는지 마지막에 검증한다.
    verify(lottoService).recommendAndSaveForMember(1L, "BASIC");
  }

  /**
   * 회원 추천 API 비인증 접근 차단 케이스.
   *
   * <p>이 테스트의 목적은 "회원 전용 API는 로그인 없이 호출할 수 없다"는 보안 계약을 고정하는 것이다.
   *
   * <p>참고: Spring Security 설정에 따라 비인증 응답은 302 redirect 또는 401이 될 수 있다. 현재 설정에서는 일반적으로 3xx redirect가
   * 나올 가능성이 높다. 만약 실제 실행 결과가 401이라면 이 기대값만 맞춰 바꾸면 된다.
   */
  @Test
  @DisplayName("회원 추천 실패: 비인증 사용자는 접근할 수 없다")
  void recommendForMember_unauthenticated() throws Exception {
    mockMvc
        .perform(post("/api/lotto/recommendations").param("source", "BASIC"))
        .andExpect(status().is3xxRedirection());
  }

  /**
   * 회원 추천 내역 조회 성공 케이스.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>현재 조회 URL이 /api/lotto/members/me/recommendations 인지
   *   <li>principal의 memberId가 서비스로 전달되는지
   *   <li>LottoRecordResponse가 JSON 배열 구조로 직렬화되는지
   * </ul>
   *
   * <p>즉 이 테스트는 과거 path variable 기반 계약이 아니라, "로그인 사용자 자신의 추천 내역을 조회한다"는 현재 계약을 고정한다.
   */
  @Test
  @DisplayName("회원 추천 내역 조회 성공: 현재 로그인 사용자의 추천 내역을 반환한다")
  void getMyRecommendations_success() throws Exception {
    List<LottoRecordResponse> histories =
        List.of(
            LottoRecordResponse.builder()
                .round(1112)
                .numbers("1 2 3 4 5 6")
                .recommendedAt(LocalDateTime.of(2026, 4, 21, 20, 0))
                .manual(false)
                .purchased(false)
                .source("BASIC")
                .build());

    given(lottoService.getRecommendationsForMember(1L)).willReturn(histories);

    mockMvc
        .perform(
            get("/api/lotto/members/me/recommendations")
                .with(oauth2Login().attributes(attributes -> attributes.put("memberId", 1L))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].round").value(1112))
        .andExpect(jsonPath("$[0].numbers").value("1 2 3 4 5 6"))
        .andExpect(jsonPath("$[0].source").value("BASIC"));

    verify(lottoService).getRecommendationsForMember(1L);
  }

  /**
   * 회원 추천 내역 조회 비인증 접근 차단 케이스.
   *
   * <p>회원의 저장된 추천 기록은 개인정보/개인 이력 성격이 있으므로, 로그인 없이 접근되면 안 된다는 점을 보장한다.
   */
  @Test
  @DisplayName("회원 추천 내역 조회 실패: 비인증 사용자는 접근할 수 없다")
  void getMyRecommendations_unauthenticated() throws Exception {
    mockMvc
        .perform(get("/api/lotto/members/me/recommendations"))
        .andExpect(status().is3xxRedirection());
  }

  /**
   * 회원 구매 번호 저장 성공 케이스.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>현재 구매 저장 URL이 /api/lotto/purchases 인지
   *   <li>JSON 요청 본문이 PurchaseLottoRequest로 바인딩되는지
   *   <li>principal의 memberId와 request body가 서비스로 위임되는지
   *   <li>성공 시 200 OK를 반환하는지
   * </ul>
   *
   * <p>이 테스트는 "실제로 DB에 저장됐는지"를 보지 않는다. 그건 서비스 테스트에서 검증한다.
   */
  @Test
  @DisplayName("회원 구매 번호 저장 성공")
  void purchase_success() throws Exception {
    PurchaseLottoRequest request = new PurchaseLottoRequest();
    request.setRound("1112");
    request.setNumbersList(List.of("1, 2, 3, 4, 5, 6"));

    // void 메서드이므로 아무 예외 없이 끝나도록 설정한다.
    doNothing().when(lottoService).addPurchasedRecords(eq(1L), any(PurchaseLottoRequest.class));

    mockMvc
        .perform(
            post("/api/lotto/purchases")
                .with(oauth2Login().attributes(attributes -> attributes.put("memberId", 1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk());

    verify(lottoService).addPurchasedRecords(eq(1L), any(PurchaseLottoRequest.class));
  }

  /**
   * 회원 구매 번호 저장 비인증 접근 차단 케이스.
   *
   * <p>구매 번호 저장은 개인 계정 데이터 변경이므로, 로그인 없이 호출되면 안 된다는 점을 보장한다.
   */
  @Test
  @DisplayName("회원 구매 번호 저장 실패: 비인증 사용자는 접근할 수 없다")
  void purchase_unauthenticated() throws Exception {
    PurchaseLottoRequest request = new PurchaseLottoRequest();
    request.setRound("1112");
    request.setNumbersList(List.of("1, 2, 3, 4, 5, 6"));

    mockMvc
        .perform(
            post("/api/lotto/purchases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().is3xxRedirection());
  }

  /**
   * 회원 구매 번호 저장 요청 검증 실패 케이스.
   *
   * <p>현재 PurchaseLottoRequest는 다음 제약을 가진다.
   *
   * <ul>
   *   <li>round: 비어 있으면 안 됨
   *   <li>numbersList: null 불가, 최소 1개 이상
   *   <li>numbersList 각 문자열: "1~45 숫자 6개를 쉼표로 구분한 문자열"이어야 함
   * </ul>
   *
   * <p>따라서 잘못된 요청이 들어오면 컨트롤러에 진입하더라도 서비스 호출 전 단계에서 400이 나와야 한다.
   */
  @Test
  @DisplayName("회원 구매 번호 저장 실패: 잘못된 요청 본문이면 400을 반환한다")
  void purchase_invalidRequest() throws Exception {
    PurchaseLottoRequest request = new PurchaseLottoRequest();
    request.setRound("");
    request.setNumbersList(List.of("1, 2, 3"));

    mockMvc
        .perform(
            post("/api/lotto/purchases")
                .with(oauth2Login().attributes(attributes -> attributes.put("memberId", 1L)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  /**
   * 비회원 추천 성공 케이스.
   *
   * <p>이 테스트는 "guest 추천 API는 로그인 없이 접근 가능해야 한다"는 정책을 고정한다. 즉 이 테스트가 통과하려면 SecurityConfig에서
   * /api/lotto/guests/recommendations 경로가 permitAll로 열려 있어야 한다.
   *
   * <p>이 테스트가 보장하는 것:
   *
   * <ul>
   *   <li>guest 추천 URL이 /api/lotto/guests/recommendations 인지
   *   <li>로그인 principal 없이도 200 OK가 가능한지
   *   <li>서비스 반환 결과가 로또 번호 5세트로 직렬화되는지
   * </ul>
   */
  @Test
  @DisplayName("비회원 추천 성공: 로그인 없이 로또 번호 5세트를 반환한다")
  void recommendForGuest_success() throws Exception {
    List<LottoSet> sets =
        List.of(
            new LottoSet(List.of(1, 2, 3, 4, 5, 6)),
            new LottoSet(List.of(7, 8, 9, 10, 11, 12)),
            new LottoSet(List.of(13, 14, 15, 16, 17, 18)),
            new LottoSet(List.of(19, 20, 21, 22, 23, 24)),
            new LottoSet(List.of(25, 26, 27, 28, 29, 30)));

    given(lottoService.recommendAndSaveForGuest("BASIC")).willReturn(sets);

    mockMvc
        .perform(post("/api/lotto/guests/recommendations").param("source", "BASIC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(5))
        .andExpect(jsonPath("$[0].numbers.length()").value(6));

    verify(lottoService).recommendAndSaveForGuest("BASIC");
  }
}
