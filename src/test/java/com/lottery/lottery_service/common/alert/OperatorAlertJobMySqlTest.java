package com.lottery.lottery_service.common.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.job.JobProperties;
import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.job.JobState;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.support.MutableClock;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 운영자 알림 job을 MySQL에서 확인한다. */
class OperatorAlertJobMySqlTest extends MySqlContainerSupport {

  private static DataSource dataSource;

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
  private final List<String> sent = new ArrayList<>();
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager tm;
  private JobStore jobs;

  @BeforeAll
  static void migrate() throws Exception {
    dataSource = migratedDatabase("operator_alert");
  }

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update("DELETE FROM job");
    tm = new DataSourceTransactionManager(dataSource);
    jobs = new JobStore(jdbc, tm, clock, new JobProperties(1, Duration.ofMinutes(5)));
    jobs.enqueue("EVALUATE_RESULT", "result:1", "{}", clock.instant());
    jobs.fail(jobs.claim(List.of("EVALUATE_RESULT")).orElseThrow(), "SOURCE_DOWN", clock.instant());
  }

  @Test
  @DisplayName("DEAD가 된 job의 운영자 알림은 작업 종류와 마지막 오류만 담아 한 번 보낸다")
  void sendsDeadJobAlert() {
    JobRunner runner =
        new JobRunner(jobs, List.of(new OperatorAlertJobHandler(sent::add, jdbc)), tm, clock);

    assertThat(runner.runOnce()).contains(JobState.SUCCEEDED);
    assertThat(runner.runOnce()).isEmpty();
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0)).contains("EVALUATE_RESULT").contains("SOURCE_DOWN");
  }

  @Test
  @DisplayName(
      "Discord 전송이 실패하면 그 알림 job만 실패로 남고(시도 상한 1이라 DEAD, 상한 안이면 다시 시도) 다른 job에는 영향이 없다(T42)")
  void webhookFailureRetries() {
    jobs.enqueue("OTHER", "x", "{}", clock.instant());
    OperatorAlertSender down =
        content -> {
          throw new IllegalStateException("discord down");
        };

    assertThat(
            new JobRunner(jobs, List.of(new OperatorAlertJobHandler(down, jdbc)), tm, clock)
                .runOnce())
        .contains(JobState.DEAD);
    assertThat(jdbc.queryForObject("SELECT state FROM job WHERE job_type = 'OTHER'", String.class))
        .isEqualTo("READY");
  }

  @Test
  @DisplayName("슬롯 실패 알림은 회차, 슬롯 시각(서울), 원인, 대상 수, 건수를 담고 마지막 슬롯이면 다시 보내기를 안내한다")
  void slotFailedFormat() throws Exception {
    String text =
        new OperatorAlertJobHandler(sent::add, jdbc)
            .format(
                new ObjectMapper()
                    .readTree(
                        "{\"kind\":\"SLOT_FAILED\",\"roundNo\":1200,"
                            + "\"slotAt\":\"2026-11-14T13:00:00Z\",\"targets\":12,"
                            + "\"cause\":\"PARTIAL_FAILURE\",\"failed\":3,\"unknown\":1,\"last\":true}"));

    assertThat(text)
        .startsWith("[결과 알림 슬롯 실패] 1200회 11월 14일 22:00 슬롯, 원인 PARTIAL_FAILURE, 대상 12명, 미발송 3건")
        .contains("1200회")
        .contains("PARTIAL_FAILURE")
        .contains("3건")
        .contains("다시 보내기");
  }

  @Test
  @DisplayName("예산 카운터 불일치는 달과 카운터·메시지 합계를 원 단위로, 정리 지연은 회차·건수·이유를 담는다")
  void budgetAndCleanupFormat() throws Exception {
    OperatorAlertJobHandler handler = new OperatorAlertJobHandler(sent::add, jdbc);
    ObjectMapper mapper = new ObjectMapper();

    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"BUDGET_MISMATCH\",\"month\":\"2026-11\",\"counterReserved\":0,"
                        + "\"counterSpent\":143,\"messageReserved\":0,\"messageSpent\":0}")))
        .contains("2026-11")
        .contains("사용 14.3원")
        .contains("메시지 합계 예약 0.0원");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"CLEANUP_DELAYED\",\"roundNo\":1200,\"reason\":\"UNEVALUATED\","
                        + "\"count\":2}")))
        .contains("1200회")
        .contains("2건")
        .contains("UNEVALUATED");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"BUDGET_THRESHOLD\",\"month\":\"2026-12\",\"used\":240000,"
                        + "\"limit\":300000}")))
        .contains("사용 24000.0원")
        .contains("남은 6000.0원");
    assertThat(
            handler.format(mapper.readTree("{\"kind\":\"ADMIN_MFA_LOCKED\",\"lockMinutes\":15}")))
        .startsWith("[관리자 인증 잠금]")
        .contains("15분");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"RESULT_CORRECTED\",\"roundNo\":1200,\"changed\":1,"
                        + "\"newlyTargeted\":2}")))
        .contains("1200회")
        .contains("바뀐 회원 1명")
        .contains("새로 대상이 된 회원 2명")
        .contains("정정 알림은 보내지 않습니다");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"MANUAL_RESEND\",\"roundNo\":1200,\"actor\":\"OPERATOR\","
                        + "\"sent\":3,\"failed\":1,\"unknown\":0}")))
        .contains("1200회")
        .contains("실행 OPERATOR")
        .contains("접수 3건")
        .contains("실패 1건");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"DELETION_JOURNAL_FAILED\","
                        + "\"requestId\":\"00000000-0000-4000-8000-000000000001\"}")))
        .contains("삭제 요청 00000000-0000-4000-8000-000000000001")
        .doesNotContain("[운영자 알림]");
    assertThat(
            handler.format(
                mapper.readTree("{\"kind\":\"RESULT_MANUAL_REGISTERED\",\"roundNo\":1200}")))
        .contains("1200회")
        .doesNotContain("[운영자 알림]");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"MESSAGE_UNKNOWN_LONG\",\"count\":3,"
                        + "\"oldest\":\"2026-11-14T12:30:00Z\"}")))
        .contains("3건")
        .contains("2026-11-14T12:30:00Z");
  }

  @Test
  @DisplayName("만료·격리·관리자 변경 알림 문구와 모르는 종류의 기본 문구")
  void expiryQuarantineAndAdminFormat() throws Exception {
    OperatorAlertJobHandler handler = new OperatorAlertJobHandler(sent::add, jdbc);
    ObjectMapper mapper = new ObjectMapper();

    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"EXPIRY\",\"name\":\"solapi-sender\","
                        + "\"expiresOn\":\"2026-12-01\",\"daysLeft\":7}")))
        .isEqualTo("[인증서·연동 만료] solapi-sender 만료일 2026-12-01(남은 7일).");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"EXPIRY\",\"name\":\"tls\","
                        + "\"expiresOn\":\"2026-11-01\",\"daysLeft\":-3}")))
        .isEqualTo("[인증서·연동 만료] tls 만료일 2026-11-01(이미 만료됨).");
    assertThat(
            handler.format(mapper.readTree("{\"kind\":\"RESULT_QUARANTINED\",\"roundNo\":1200}")))
        .isEqualTo("[결과 격리] 1200회 결과가 게시본과 달라 격리했습니다. 승인이 필요합니다.");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"RESULT_QUARANTINED\",\"roundNo\":1200,\"changed\":[\"NUMBERS\",\"BONUS\"]}")))
        .isEqualTo("[결과 격리] 1200회 결과가 게시본과 달라 격리했습니다. 바뀐 항목: 당첨 번호, 보너스 번호. 승인이 필요합니다.");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"ADMIN_CHANGE\",\"action\":\"ADMIN_GRANT\",\"role\":\"OPERATOR\"}")))
        .startsWith("[관리자 권한 변경] 권한 부여·역할 변경(역할 OPERATOR).");
    assertThat(
            handler.format(
                mapper.readTree("{\"kind\":\"ADMIN_CHANGE\",\"action\":\"ADMIN_MFA_RESET\"}")))
        .startsWith("[관리자 권한 변경] 2차 인증 초기화.");
    assertThat(handler.format(mapper.readTree("{\"kind\":\"ADMIN_CHANGE\",\"action\":\"OTHER\"}")))
        .contains("알 수 없는 변경");
    assertThat(
            handler.format(
                mapper.readTree(
                    "{\"kind\":\"SECURITY_SIGNAL\",\"signal\":\"TOO_MANY_REQUESTS\",\"count\":1200,"
                        + "\"topPath\":\"/api/v1/lotto/check\"}")))
        .isEqualTo("[보안 신호] 10분 동안 요청 제한(429) 1200건. 많이 걸린 경로 `/api/v1/lotto/check`.");
    assertThat(handler.format(mapper.readTree("{\"kind\":\"NEW_KIND\"}")))
        .isEqualTo("[운영자 알림] NEW_KIND");
    assertThat(handler.format(mapper.readTree("{}"))).isEqualTo("[운영자 알림] UNKNOWN");
  }

  @Test
  @DisplayName("웹훅 주소가 없으면 보낼 때 실패한다")
  void unconfiguredWebhook() {
    assertThatThrownBy(() -> new DiscordWebhookSender("").send("x"))
        .isInstanceOf(IllegalStateException.class);
  }
}
