package com.lottery.lottery_service.admin;

import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 서버 관리 명령으로 관리자 권한을 주거나 회수하거나 다중 요소 인증을 초기화하고 끝낸다(C-33). {@code action}은 {@code grant}(기본), {@code
 * revoke}, {@code reset-mfa}다. 예:
 *
 * <pre>
 * java -jar app.jar --lottery.admin.grant.member=회원외부ID --lottery.admin.grant.role=ADMIN \
 *   --lottery.admin.grant.reason="첫 관리자 지정"
 * java -jar app.jar --lottery.admin.grant.member=회원외부ID --lottery.admin.grant.action=reset-mfa \
 *   --lottery.admin.grant.reason="하나뿐인 ADMIN의 기기 분실"
 * </pre>
 *
 * 이 값이 없으면 동작하지 않는다. 웹 요청으로는 실행할 수 없다.
 */
@Component
@ConditionalOnProperty(prefix = "lottery.admin.grant", name = "member")
public class AdminGrantCommand implements ApplicationRunner {

  private final AdminGrants grants;
  private final Environment env;
  private final ConfigurableApplicationContext context;

  /** 명령을 만든다. */
  public AdminGrantCommand(
      AdminGrants grants, Environment env, ConfigurableApplicationContext context) {
    this.grants = grants;
    this.env = env;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    execute();
    System.exit(SpringApplication.exit(context, () -> 0));
  }

  /** 설정 값대로 부여·회수·초기화 중 하나를 한다. 모르는 동작이면 아무것도 바꾸지 않고 예외. */
  void execute() {
    UUID member = UUID.fromString(env.getRequiredProperty("lottery.admin.grant.member"));
    String reason = env.getRequiredProperty("lottery.admin.grant.reason");
    String action = env.getProperty("lottery.admin.grant.action", "grant").toLowerCase(Locale.ROOT);
    switch (action) {
      case "grant" -> grants.grantByCommand(
          member,
          AdminRole.valueOf(
              env.getProperty("lottery.admin.grant.role", "ADMIN").toUpperCase(Locale.ROOT)),
          reason);
      case "revoke" -> grants.revokeByCommand(member, reason);
      case "reset-mfa" -> grants.resetMfaByCommand(member, reason);
      default -> throw new IllegalArgumentException("unknown admin grant action");
    }
  }
}
