package com.lottery.lottery_service.notification.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lottery.lottery_service.account.AccountStore;
import com.lottery.lottery_service.auth.MemberPrincipal;
import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.notification.verify.ContactCipher;
import com.lottery.lottery_service.support.MySqlContainerSupport;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 알림 설정 조회가 가린 번호와 같은 두 버전을 주는지 확인한다(D-059). */
@SpringBootTest
@AutoConfigureMockMvc
class PreferenceWebMySqlTest extends MySqlContainerSupport {

  private static final String DB = "preference_web";

  @Autowired private MockMvc mvc;
  @Autowired private AccountStore accounts;
  @Autowired private ContactCipher cipher;
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
  @DisplayName("연락처가 없으면 꺼진 기본값, 있으면 010-****-5678로 가리고 version과 contactVersion이 같다")
  void preference() throws Exception {
    MemberPrincipal member =
        MemberPrincipal.of(accounts.login(OAuthProvider.KAKAO, "pref"), OAuthProvider.KAKAO);
    mvc.perform(get("/api/v1/me/notification-preference").with(oauth2Login().oauth2User(member)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.maskedPhoneNumber").doesNotExist());

    jdbc.update(
        "INSERT INTO member_contact (member_id, address_ciphertext, key_version, verified_at, version)"
            + " VALUES (?, ?, 1, NOW(6), 3)",
        member.memberId(),
        cipher.encrypt(member.memberId(), "+821012345678"));
    mvc.perform(get("/api/v1/me/notification-preference").with(oauth2Login().oauth2User(member)))
        .andExpect(jsonPath("$.maskedPhoneNumber").value("010-****-5678"))
        .andExpect(jsonPath("$.version").value("3"))
        .andExpect(jsonPath("$.contactVersion").value("3"))
        .andExpect(jsonPath("$.verified").value(true));
    mvc.perform(get("/api/v1/me/notification-preference")).andExpect(status().isUnauthorized());
  }
}
