package com.lottery.lottery_service.admin.mfa;

import com.lottery.lottery_service.common.secret.KeyReencryptor;
import com.lottery.lottery_service.common.secret.KeyRing;
import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.common.secret.VersionedCipher;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 관리자 인증 앱 비밀값을 현재 {@code admin-mfa} 키 버전으로 다시 암호화한다(03 6.6). 읽은 뒤 비밀값이 바뀐 행(재등록, 초기화)은 건드리지 않고 남은
 * 수에 센다. 풀 수 없는 비밀값(변조, 이미 지운 키 버전)은 건너뛰고 회원 ID만 로그에 남기며 남은 수에 센다.
 */
@Slf4j
@Component
public class AdminMfaReencryptor implements KeyReencryptor {

  private final JdbcTemplate jdbc;
  private final SecretKeys secrets;

  /** 재암호화기를 만든다. */
  public AdminMfaReencryptor(JdbcTemplate jdbc, SecretKeys secrets) {
    this.jdbc = jdbc;
    this.secrets = secrets;
  }

  @Override
  public String ring() {
    return "admin-mfa";
  }

  @Override
  public Result reencrypt() {
    KeyRing ring = secrets.adminMfa();
    long changed = 0;
    for (Row row : old(ring.currentVersion())) {
      byte[] plain;
      try {
        plain =
            VersionedCipher.decrypt(
                ring, row.version(), row.sealed(), AdminMfaStore.aad(row.memberId()));
      } catch (IllegalStateException | IllegalArgumentException e) {
        log.warn(
            "cannot reencrypt admin mfa secret member={}; left on its old version", row.memberId());
        continue;
      }
      VersionedCipher.Sealed fresh =
          VersionedCipher.encrypt(ring, plain, AdminMfaStore.aad(row.memberId()));
      changed +=
          jdbc.update(
              "UPDATE admin_grant SET mfa_secret = ?, mfa_key_version = ?"
                  + " WHERE member_id = ? AND mfa_key_version = ? AND mfa_secret = ?",
              fresh.sealed(),
              fresh.version(),
              row.memberId(),
              row.version(),
              row.sealed());
    }
    return new Result(changed, old(ring.currentVersion()).size());
  }

  private List<Row> old(int current) {
    return jdbc.query(
        "SELECT member_id, mfa_key_version, mfa_secret FROM admin_grant"
            + " WHERE mfa_secret IS NOT NULL AND mfa_key_version <> ?",
        (rs, n) -> new Row(rs.getLong(1), rs.getInt(2), rs.getBytes(3)),
        current);
  }

  private record Row(long memberId, int version, byte[] sealed) {}
}
