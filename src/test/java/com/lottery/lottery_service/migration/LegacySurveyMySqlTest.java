package com.lottery.lottery_service.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 기존 DB 조사 쿼리({@code scripts/migration/legacy-survey.sql})를 표본 DB에 돌려 지표마다 개수가 맞는지 확인한다. */
class LegacySurveyMySqlTest extends MySqlContainerSupport {

  private static final String DB = "legacy_survey";
  private static final Path SURVEY = Path.of("scripts/migration/legacy-survey.sql");

  private static Map<String, String> metrics;

  @BeforeAll
  static void runSurvey() throws Exception {
    recreateDatabase(DB);
    try (Connection c = openConnection(DB)) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy/legacy-schema.sql"));
      ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy/legacy-sample.sql"));
      c.setReadOnly(true);
      metrics = new LinkedHashMap<>();
      try (Statement st = c.createStatement();
          ResultSet rs = st.executeQuery(Files.readString(SURVEY))) {
        while (rs.next()) {
          metrics.put(rs.getString("metric"), rs.getString("value"));
        }
      }
    }
  }

  @Test
  @DisplayName("DB 버전과 시간대 설정을 보고한다")
  void reportsVersionAndTimeZones() {
    assertThat(metrics.get("db_version")).startsWith("8.4");
    assertThat(metrics).containsKeys("global_time_zone", "session_time_zone", "system_time_zone");
  }

  @Test
  @DisplayName("번호 문자열의 빈 값, 형식 오류, 범위 밖, 같은 번호 반복, 정렬 안 됨을 따로 센다")
  void classifiesNumberProblems() {
    assertThat(metrics)
        .containsEntry("record_total", "9")
        .containsEntry("record_numbers_null", "1")
        .containsEntry("record_numbers_bad_format", "1")
        .containsEntry("record_numbers_out_of_range", "1")
        .containsEntry("record_numbers_repeated_value", "1")
        .containsEntry("record_numbers_valid", "5")
        .containsEntry("record_numbers_unsorted", "1");
  }

  @Test
  @DisplayName("같은 회원·회차·조합의 중복을 순서와 상관없이 찾고, 주인 없는 중복은 따로 센다")
  void findsDuplicateCombinations() {
    assertThat(metrics)
        .containsEntry("record_duplicate_groups_member", "1")
        .containsEntry("record_duplicate_extra_rows_member", "1")
        .containsEntry("record_duplicate_groups_no_owner", "1");
  }

  @Test
  @DisplayName("회원 NULL, 비회원 표시 불일치, 회차 이상, 고정 회차 1112, 추천 시각 NULL, 구매 표시를 센다")
  void countsOwnerRoundAndFlags() {
    assertThat(metrics)
        .containsEntry("record_member_null", "3")
        .containsEntry("record_guest_flag_mismatch", "1")
        .containsEntry("record_round_not_positive", "1")
        .containsEntry("record_round_1112", "5")
        .containsEntry("record_recommended_at_null", "1")
        .containsEntry("record_purchased", "1")
        .containsEntry("record_manual", "1")
        .containsEntry("record_source:AUTO", "5")
        .containsEntry("record_source:NULL", "1");
  }

  @Test
  @DisplayName("이메일 연결 충돌은 다른 회원의 이메일과 같은 제공자 이메일만 센다(대소문자 무시)")
  void countsEmailLinkConflicts() {
    assertThat(metrics)
        .containsEntry("member_total", "3")
        .containsEntry("member_email_present", "2")
        .containsEntry("member_email_verified", "0")
        .containsEntry("oauth_total", "3")
        .containsEntry("oauth_members_with_multiple_accounts", "1")
        .containsEntry("oauth_email_conflict", "1");
  }

  @Test
  @DisplayName("과거 결과의 행 수, 최대 회차, 빠진 회차 수를 센다")
  void countsWinnerData() {
    assertThat(metrics)
        .containsEntry("winner_total", "3")
        .containsEntry("winner_max_round", "4")
        .containsEntry("winner_round_gaps", "1");
  }

  @Test
  @DisplayName("결과에 이메일이나 번호 원문이 없다")
  void exposesNoPersonalDataOrNumbers() {
    for (Map.Entry<String, String> e : metrics.entrySet()) {
      assertThat(e.getKey() + "=" + e.getValue())
          .doesNotContain("@")
          .doesNotContain("1 2 3")
          .doesNotContain("7 8 9");
    }
  }
}
