package com.lottery.lottery_service.common.secret;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 서버 관리 명령으로 한 키 묶음의 저장 값을 현재 버전으로 다시 암호화한다(03 6.6, C-30). 키 설정에 새 버전을 더하고 현재 버전으로 지정한 뒤 실행한다. 예:
 *
 * <pre>
 * java -jar app.jar --lottery.secrets.reencrypt.ring=contact
 * </pre>
 *
 * 옛 버전으로 남은 값이 없으면 0으로, 남았으면 1로, 모르는 키 묶음이면 2로 끝난다. 0으로 끝난 뒤에만 옛 버전 키를 지운다. 웹 요청으로는 실행할 수 없다.
 */
@Component
@ConditionalOnProperty(prefix = "lottery.secrets.reencrypt", name = "ring")
public class KeyReencryptCommand implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(KeyReencryptCommand.class);

  private final List<KeyReencryptor> reencryptors;
  private final Environment env;
  private final ConfigurableApplicationContext context;

  /** 명령을 만든다. */
  public KeyReencryptCommand(
      List<KeyReencryptor> reencryptors, Environment env, ConfigurableApplicationContext context) {
    this.reencryptors = reencryptors;
    this.env = env;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    int code = execute(env.getRequiredProperty("lottery.secrets.reencrypt.ring"));
    System.exit(SpringApplication.exit(context, () -> code));
  }

  /** 그 키 묶음을 다시 암호화하고 종료 코드를 돌려준다. 로그에는 수만 남긴다. */
  public int execute(String ring) {
    KeyReencryptor target =
        reencryptors.stream().filter(r -> r.ring().equals(ring)).findFirst().orElse(null);
    if (target == null) {
      log.error(
          "unknown key ring for reencryption; known: {}",
          reencryptors.stream().map(KeyReencryptor::ring).sorted().toList());
      return 2;
    }
    KeyReencryptor.Result result = target.reencrypt();
    log.info(
        "key ring {} reencrypted: {} values, {} still on old versions",
        ring,
        result.reencrypted(),
        result.remaining());
    return result.remaining() == 0 ? 0 : 1;
  }
}
