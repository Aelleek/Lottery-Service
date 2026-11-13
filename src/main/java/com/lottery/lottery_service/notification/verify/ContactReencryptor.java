package com.lottery.lottery_service.notification.verify;

import com.lottery.lottery_service.common.secret.KeyReencryptor;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 연락처 암호화 키({@code contact})로 만든 수신 번호 암호문을 현재 버전으로 다시 암호화한다(03 6.6). 대상은 연락처, 인증 요청 대상, 메시지에 고정한
 * 수신 번호 사본이다. 키 버전은 컬럼이 아니라 암호문 앞에 적힌 값으로 판단한다. 읽은 뒤 암호문이 바뀐 행(번호 변경, 정리)은 건드리지 않고 남은 수에 센다. 풀 수 없는
 * 행(변조, 다른 회원 행으로 옮겨진 값, 이미 지운 키 버전)은 건너뛰고 행 ID만 로그에 남기며 남은 수에 센다.
 */
@Slf4j
@Component
public class ContactReencryptor implements KeyReencryptor {

  private static final int BATCH = 500;

  private static final List<Target> TARGETS =
      List.of(
          new Target("member_contact", "member_id", "address_ciphertext", "key_version = ?"),
          new Target("verification_challenge", "id", "target_ciphertext", null),
          new Target(
              "delivery_message",
              "id",
              "recipient_ciphertext",
              "key_version = IF(key_version IS NULL, NULL, ?)"));

  private final JdbcTemplate jdbc;
  private final ContactCipher cipher;

  /** 재암호화기를 만든다. */
  public ContactReencryptor(JdbcTemplate jdbc, ContactCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  @Override
  public String ring() {
    return "contact";
  }

  @Override
  public Result reencrypt() {
    long changed = 0;
    for (Target target : TARGETS) {
      changed += scan(target, true);
    }
    long remaining = 0;
    for (Target target : TARGETS) {
      remaining += scan(target, false);
    }
    return new Result(changed, remaining);
  }

  private long scan(Target t, boolean write) {
    long count = 0;
    long after = Long.MIN_VALUE;
    while (true) {
      List<Row> rows =
          jdbc.query(
              "SELECT "
                  + t.id()
                  + ", member_id, "
                  + t.cipher()
                  + " FROM "
                  + t.table()
                  + " WHERE "
                  + t.id()
                  + " > ? AND "
                  + t.cipher()
                  + " IS NOT NULL ORDER BY "
                  + t.id()
                  + " LIMIT "
                  + BATCH,
              (rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getBytes(3)),
              after);
      for (Row row : rows) {
        if (!cipher.isCurrent(row.sealed())) {
          count += write ? update(t, row) : 1;
        }
      }
      if (rows.size() < BATCH) {
        return count;
      }
      after = rows.get(rows.size() - 1).id();
    }
  }

  private int update(Target t, Row row) {
    byte[] fresh;
    try {
      fresh = cipher.reencrypt(row.memberId(), row.sealed());
    } catch (IllegalStateException | IllegalArgumentException e) {
      log.warn("cannot reencrypt {} row id={}; left on its old version", t.table(), row.id());
      return 0;
    }
    String set = t.cipher() + " = ?" + (t.versionSet() == null ? "" : ", " + t.versionSet());
    String sql =
        "UPDATE "
            + t.table()
            + " SET "
            + set
            + " WHERE "
            + t.id()
            + " = ? AND "
            + t.cipher()
            + " = ?";
    return t.versionSet() == null
        ? jdbc.update(sql, fresh, row.id(), row.sealed())
        : jdbc.update(sql, fresh, ContactCipher.versionOf(fresh), row.id(), row.sealed());
  }

  private record Target(String table, String id, String cipher, String versionSet) {}

  private record Row(long id, long memberId, byte[] sealed) {}
}
