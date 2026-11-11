package com.lottery.lottery_service.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.lottery.lottery_service.common.job.JobRunner;
import com.lottery.lottery_service.common.number.LottoNumber;
import com.lottery.lottery_service.common.number.LottoNumbers;
import com.lottery.lottery_service.result.domain.WinningNumbers;
import com.lottery.lottery_service.result.publish.ResultPublisher;
import com.lottery.lottery_service.result.source.OfficialDraw;
import com.lottery.lottery_service.round.RoundStore;
import com.lottery.lottery_service.round.domain.RoundSchedule;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 07 3절 부하 목표를 로컬 MySQL 컨테이너에서 잰다. 앞단 뒤에 있는 것처럼 {@code 127.0.0.1}을 믿을 앞단으로 두고 사용자마다 다른 IP를 보낸다. 운영
 * 환경의 측정을 대신하지 않는다(07 5절 4번). {@code ./gradlew loadTest}로만 돈다.
 */
@Tag("load")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "server.forward-headers-strategy=native",
      "server.tomcat.remoteip.internal-proxies=127\\\\.0\\\\.0\\\\.1"
    })
class LocalLoadMySqlTest extends MySqlContainerSupport {

  private static final String DB = "local_load";
  private static final int ROUND = 1400;

  @LocalServerPort private int port;
  @Autowired private RoundStore rounds;
  @Autowired private ResultPublisher publisher;
  @Autowired private JobRunner jobs;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws SQLException {
    recreateDatabase(DB);
    registry.add("spring.datasource.url", () -> databaseUrl(DB));
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.flyway.enabled", () -> "true");
    registry.add("spring.session.jdbc.initialize-schema", () -> "never");
    registry.add("lottery.api-v1.enabled", () -> "true");
  }

  @Test
  @DisplayName("사용자 100명·초당 20동작에서 조회 p95 1초, 추천 저장 p95 2초, 서버 오류율 1% 미만, 요청 제한·계약 오류 0")
  void meetsOperationTargets() throws Exception {
    Instant now = rounds.dbNow();
    rounds.register(
        new RoundSchedule(
            ROUND - 1,
            now.minus(Duration.ofDays(8)),
            now.minus(Duration.ofHours(2)),
            now.minus(Duration.ofHours(1)),
            1));
    rounds.register(
        new RoundSchedule(
            ROUND,
            now.minus(Duration.ofHours(1)),
            now.plus(Duration.ofDays(6)),
            now.plus(Duration.ofDays(7)),
            1));
    assertThat(rounds.sealDue()).contains(ROUND - 1);
    publisher.receive(
        new OfficialDraw(
            ROUND - 1,
            new WinningNumbers(LottoNumbers.of(3, 9, 17, 24, 33, 41), LottoNumber.of(12)),
            2_000_000_000L,
            10L,
            new byte[32]));
    int ran = 0;
    while (ran < 50 && jobs.runOnce().isPresent()) {
      ran++;
    }

    LoadDriver.Report report =
        new LoadDriver(LoadDriver.Settings.fromSystemProperties("http://127.0.0.1:" + port)).run();
    System.out.print(report.summary());

    assertThat(report.readP95Millis()).isLessThanOrEqualTo(1000);
    assertThat(report.kinds().get(LoadDriver.Kind.RECOMMEND).p95Millis()).isLessThanOrEqualTo(2000);
    assertThat(report.serverErrorRate()).isLessThan(0.01);
    assertThat(report.kinds().values())
        .allSatisfy(
            r -> {
              assertThat(r.limited()).isZero();
              assertThat(r.clientErrors()).isZero();
            });
    assertThat(report.kinds().get(LoadDriver.Kind.RECOMMEND).count()).isPositive();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lotto_record WHERE round_no = ?", Long.class, ROUND))
        .isEqualTo(succeeded(report.kinds().get(LoadDriver.Kind.RECOMMEND)) * 5L);
  }

  private static long succeeded(LoadDriver.KindReport report) {
    return report.count() - report.serverErrors() - report.limited() - report.clientErrors();
  }
}
