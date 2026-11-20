package com.lottery.lottery_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.admin.web.AdminChangeController;
import com.lottery.lottery_service.notification.web.PreferenceController;
import com.lottery.lottery_service.recommendation.web.RecommendationController;
import com.lottery.lottery_service.record.web.PurchaseController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class StrictJsonConfigTest {

  private final ObjectMapper mapper = mapper();

  private static ObjectMapper mapper() {
    Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
    StrictJsonConfig.apply(builder);
    return builder.build();
  }

  @Test
  @DisplayName("계약대로 쓴 본문은 그대로 읽는다")
  void readsContractBody() throws Exception {
    PurchaseController.Input input =
        mapper.readValue(
            "{\"round\":1250,\"entries\":[{\"clientItemId\":\"a\",\"numbers\":[1,2,3,4,5,6]}]}",
            PurchaseController.Input.class);

    assertThat(input.round()).isEqualTo(1250);
    assertThat(input.entries().get(0).numbers()).containsExactly(1, 2, 3, 4, 5, 6);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"round\":1250,\"entries\":[],\"memberId\":7}",
        "{\"round\":1250,\"entries\":[{\"clientItemId\":\"a\",\"numbers\":[1,2,3,4,5,6.9]}]}",
        "{\"round\":1250.0,\"entries\":[]}",
        "{\"round\":\"1250\",\"entries\":[]}",
        "{\"round\":1250,\"entries\":[{\"clientItemId\":\"a\",\"numbers\":[\"1\",2,3,4,5,6]}]}",
        "{\"round\":1250,\"round\":1251,\"entries\":[]}",
        "{\"round\":1250,\"entries\":[]} {\"round\":1251}",
        "{\"round\":1250,\"entries\":[{\"clientItemId\":\"a\",\"numbers\":[1,2,3,4,5,6],\"x\":1}]}"
      })
  @DisplayName("모르는 필드, 정수 자리의 소수·문자열, 같은 키 반복, 뒤에 남은 값은 읽지 않는다")
  void rejectsOffContractPurchaseBody(String body) {
    assertThatThrownBy(() -> mapper.readValue(body, PurchaseController.Input.class))
        .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class);
  }

  @Test
  @DisplayName("문자열 자리의 숫자, 참거짓 자리의 문자열도 읽지 않는다")
  void rejectsScalarCoercion() {
    assertThatThrownBy(
            () ->
                mapper.readValue(
                    "{\"round\":1250,\"scheduleVersion\":3}", RecommendationController.Input.class))
        .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class);
    assertThatThrownBy(
            () ->
                mapper.readValue(
                    "{\"enabled\":\"false\",\"expectedVersion\":\"0\"}",
                    PreferenceController.Input.class))
        .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"numbers\":[1,2,3,4,5,6.5],\"bonus\":7,\"expectedPublicationVersion\":\"0\",\"reason\":\"r\"}",
        "{\"numbers\":[1,2,3,4,5,6],\"bonus\":\"7\",\"expectedPublicationVersion\":\"0\",\"reason\":\"r\"}",
        "{\"numbers\":[1,2,3,4,5,6],\"bonus\":7,\"expectedPublicationVersion\":0,\"reason\":\"r\"}",
        "{\"numbers\":[1,2,3,4,5,6],\"bonus\":7,\"expectedPublicationVersion\":\"0\",\"reason\":\"r\",\"adminId\":1}"
      })
  @DisplayName("관리자 결과 직접 등록도 계약 밖 본문은 읽지 않는다")
  void rejectsOffContractManualResult(String body) {
    assertThatThrownBy(() -> mapper.readValue(body, AdminChangeController.ManualResultInput.class))
        .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class);
  }

  @Test
  @DisplayName("빠진 필드와 null은 null로 읽어 컨트롤러 검사가 맡는다")
  void missingAndNullStayNull() throws Exception {
    RecommendationController.Input input =
        mapper.readValue("{\"round\":1250,\"include\":null}", RecommendationController.Input.class);

    assertThat(input.scheduleVersion()).isNull();
    assertThat(input.include()).isNull();
  }
}
