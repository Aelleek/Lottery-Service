package com.lottery.lottery_service.account;

import static com.lottery.lottery_service.common.db.DbValues.bytes;
import static com.lottery.lottery_service.common.db.DbValues.utc;
import static com.lottery.lottery_service.common.db.DbValues.uuid;

import com.lottery.lottery_service.auth.OAuthProvider;
import com.lottery.lottery_service.stats.UsageEventStore;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 소셜 로그인을 회원과 주체로 잇는다. 회원은 (제공자, 제공자 사용자 ID)로만 찾고, 이메일로 다른 회원과 합치지 않는다(D-007).
 *
 * <p>처음 보는 소셜 계정이면 회원, 회원 주체, 소셜 계정 연결을 한 트랜잭션에서 만든다.
 */
@Component
public class AccountStore {

  private static final int MAX_PROVIDER_USER_ID_BYTES = 255;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  /** 저장소를 만든다. */
  public AccountStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * 소셜 로그인을 처리한다. 연결된 회원이 있으면 마지막 로그인 시각을 바꾸고, 없으면 새로 가입시킨다.
   *
   * @throws IllegalArgumentException 제공자 사용자 ID가 비었거나 너무 길 때
   * @throws AccountBlockedException 연결된 회원이 탈퇴 처리 중일 때
   */
  public MemberLogin login(OAuthProvider provider, String providerUserId) {
    byte[] key = providerKey(providerUserId);
    try {
      return tx.execute(status -> loginOrRegister(provider, key));
    } catch (DuplicateKeyException e) {
      return tx.execute(
          status ->
              existing(provider, key)
                  .orElseThrow(() -> new IllegalStateException("oauth account vanished", e)));
    }
  }

  private MemberLogin loginOrRegister(OAuthProvider provider, byte[] key) {
    return existing(provider, key).orElseGet(() -> register(provider, key));
  }

  private Optional<MemberLogin> existing(OAuthProvider provider, byte[] key) {
    List<MemberLogin> found =
        jdbc.query(
            "SELECT m.id, m.public_id, m.state, m.auth_version, s.id, s.public_id"
                + " FROM oauth_account o JOIN member m ON m.id = o.member_id"
                + " JOIN actor_subject s ON s.member_id = m.id"
                + " WHERE o.provider = ? AND o.provider_user_id = ?",
            (rs, n) -> {
              if (!"ACTIVE".equals(rs.getString(3))) {
                throw new AccountBlockedException(rs.getLong(1));
              }
              return new MemberLogin(
                  rs.getLong(1),
                  uuid(rs.getBytes(2)),
                  rs.getLong(5),
                  uuid(rs.getBytes(6)),
                  rs.getLong(4),
                  false);
            },
            provider.name(),
            key);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    MemberLogin login = found.get(0);
    int changed =
        jdbc.update(
            "UPDATE member SET last_login_at = ? WHERE id = ? AND state = 'ACTIVE'",
            utc(clock.instant()),
            login.memberId());
    if (changed != 1) {
      throw new AccountBlockedException(login.memberId());
    }
    return Optional.of(login);
  }

  private MemberLogin register(OAuthProvider provider, byte[] key) {
    Instant now = clock.instant();
    UUID memberPublicId = UUID.randomUUID();
    long memberId =
        insert(
            "INSERT INTO member (public_id, state, created_at, last_login_at)"
                + " VALUES (?, 'ACTIVE', ?, ?)",
            bytes(memberPublicId),
            utc(now),
            utc(now));
    UUID subjectPublicId = UUID.randomUUID();
    long subjectId =
        insert(
            "INSERT INTO actor_subject (public_id, member_id, kind, state)"
                + " VALUES (?, ?, 'MEMBER', 'ACTIVE')",
            bytes(subjectPublicId),
            memberId);
    jdbc.update(
        "INSERT INTO oauth_account (member_id, provider, provider_user_id, created_at)"
            + " VALUES (?, ?, ?, ?)",
        memberId,
        provider.name(),
        key,
        utc(now));
    new UsageEventStore(jdbc).recordWithoutSubject(UsageEventStore.MEMBER_REGISTERED, 1, now);
    return new MemberLogin(memberId, memberPublicId, subjectId, subjectPublicId, 0, true);
  }

  private long insert(String sql, Object... args) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        con -> {
          PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
          }
          return ps;
        },
        keys);
    return keys.getKey().longValue();
  }

  private static byte[] providerKey(String providerUserId) {
    if (providerUserId == null || providerUserId.isBlank()) {
      throw new IllegalArgumentException("provider user id is required");
    }
    byte[] key = providerUserId.getBytes(StandardCharsets.UTF_8);
    if (key.length > MAX_PROVIDER_USER_ID_BYTES) {
      throw new IllegalArgumentException("provider user id is too long");
    }
    return key;
  }
}
