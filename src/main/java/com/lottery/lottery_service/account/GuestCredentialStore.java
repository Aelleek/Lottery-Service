package com.lottery.lottery_service.account;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.utc;
import static com.lottery.lottery_service.common.db.DbValues.uuid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 비회원 자격을 발급하고 확인한다. DB에는 자격 원문의 SHA-256 해시만 둔다. 유효기간은 발급 후 37일이고(D-002), 자격을 바꿔도 같은 주체로 이어진다. */
@Component
public class GuestCredentialStore {

  /** 자격 유효기간. */
  public static final Duration VALIDITY = Duration.ofDays(37);

  private static final int TOKEN_BYTES = 32;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  /** 저장소를 만든다. */
  public GuestCredentialStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** 새 비회원 주체와 자격을 만든다. */
  public GuestCredential issue() {
    return tx.execute(
        status -> {
          UUID publicId = UUID.randomUUID();
          long subjectId = insertSubject(publicId);
          return insertCredential(subjectId, publicId, clock.instant());
        });
  }

  /**
   * 자격 원문이 가리키는 비회원 주체를 찾는다. 만료·폐기된 자격, 다른 계정에 귀속된 주체는 찾지 않는다.
   *
   * @return 유효하면 주체, 아니면 빈 값
   */
  public Optional<GuestSubject> resolve(String token) {
    if (token == null || token.isEmpty()) {
      return Optional.empty();
    }
    List<GuestSubject> found =
        jdbc.query(
            "SELECT s.id, s.public_id, c.expires_at FROM guest_credential c"
                + " JOIN actor_subject s ON s.id = c.subject_id"
                + " WHERE c.token_hash = ? AND c.revoked_at IS NULL AND c.expires_at > ?"
                + " AND s.kind = 'GUEST' AND s.state = 'ACTIVE'",
            (rs, n) ->
                new GuestSubject(
                    rs.getLong(1),
                    uuid(rs.getBytes(2)),
                    instant(rs.getObject(3, LocalDateTime.class))),
            hash(token),
            utc(clock.instant()));
    return found.stream().findFirst();
  }

  /**
   * 유효한 자격을 새 자격으로 바꾼다. 옛 자격은 폐기하고 같은 주체에 37일짜리 새 자격을 만든다.
   *
   * @return 새 자격. 옛 자격이 유효하지 않으면 빈 값
   */
  public Optional<GuestCredential> rotate(String token) {
    if (token == null || token.isEmpty()) {
      return Optional.empty();
    }
    return tx.execute(
        status -> {
          Instant now = clock.instant();
          List<long[]> found =
              jdbc.query(
                  "SELECT c.id, c.subject_id FROM guest_credential c"
                      + " JOIN actor_subject s ON s.id = c.subject_id"
                      + " WHERE c.token_hash = ? AND c.revoked_at IS NULL AND c.expires_at > ?"
                      + " AND s.kind = 'GUEST' AND s.state = 'ACTIVE' FOR UPDATE",
                  (rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)},
                  hash(token),
                  utc(now));
          if (found.isEmpty()) {
            return Optional.<GuestCredential>empty();
          }
          long credentialId = found.get(0)[0];
          long subjectId = found.get(0)[1];
          jdbc.update(
              "UPDATE guest_credential SET revoked_at = ? WHERE id = ?", utc(now), credentialId);
          UUID publicId =
              jdbc.queryForObject(
                  "SELECT public_id FROM actor_subject WHERE id = ?",
                  (rs, n) -> uuid(rs.getBytes(1)),
                  subjectId);
          return Optional.of(insertCredential(subjectId, publicId, now));
        });
  }

  private long insertSubject(UUID publicId) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement(
                  "INSERT INTO actor_subject (public_id, kind, state) VALUES (?, 'GUEST', 'ACTIVE')",
                  Statement.RETURN_GENERATED_KEYS);
          ps.setBytes(1, bytes(publicId));
          return ps;
        },
        keys);
    return keys.getKey().longValue();
  }

  private GuestCredential insertCredential(long subjectId, UUID publicId, Instant now) {
    String token = newToken();
    Instant expiresAt = now.plus(VALIDITY);
    jdbc.update(
        "INSERT INTO guest_credential (subject_id, token_hash, expires_at) VALUES (?, ?, ?)",
        subjectId,
        hash(token),
        utc(expiresAt));
    return new GuestCredential(token, subjectId, publicId, expiresAt);
  }

  private String newToken() {
    byte[] raw = new byte[TOKEN_BYTES];
    random.nextBytes(raw);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
