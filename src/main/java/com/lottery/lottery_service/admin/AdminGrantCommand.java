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
 * 서버 관리 명령으로 관리자 권한을 주고 끝낸다(C-33). 예:
 *
 * <pre>
 * java -jar app.jar --lottery.admin.grant.member=회원외부ID --lottery.admin.grant.role=ADMIN \
 *   --lottery.admin.grant.reason="첫 관리자 지정"
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
    UUID member = UUID.fromString(env.getRequiredProperty("lottery.admin.grant.member"));
    AdminRole role =
        AdminRole.valueOf(
            env.getProperty("lottery.admin.grant.role", "ADMIN").toUpperCase(Locale.ROOT));
    String reason = env.getRequiredProperty("lottery.admin.grant.reason");
    grants.grantByCommand(member, role, reason);
    System.exit(SpringApplication.exit(context, () -> 0));
  }
}
