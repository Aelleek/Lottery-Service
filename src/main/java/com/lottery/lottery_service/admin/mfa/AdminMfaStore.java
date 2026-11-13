package com.lottery.lottery_service.admin.mfa;

import static com.lottery.lottery_service.common.db.DbValues.instant;
import static com.lottery.lottery_service.common.db.DbValues.utc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import com.lottery.lottery_service.common.job.JobStore;
import com.lottery.lottery_service.common.secret.SecretKeys;
import com.lottery.lottery_service.common.secret.VersionedCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 다중 요소 인증(D-047, 03 6.3). 인증 앱 비밀값은 {@code admin-mfa} 키로 암호화해 {@code admin_grant}에 둔다.
 *
 * <ul>
 *   <li>코드는 30초 단위 6자리이고 앞뒤 한 단위까지 받는다. 마지막으로 받은 단위 이하의 코드는 다시 받지 않는다.
 *   <li>5번 틀리면 15분 잠그고 운영자 알림 job을 남긴다 [미확인]. 알림에는 회원 ID를 넣지 않는다.
 *   <li>복구 코드 10개는 등록할 때 한 번만 돌려주고 해시만 저장한다. 쓰면 그 해시를 지운다.
 * </ul>
 */
@Component
public class AdminMfaStore {

  /** 연속 실패 한도. */
  public static final int MAX_FAILURES = 5;

  /** 잠금 시간. */
  public static final Duration LOCK = Duration.ofMinutes(15);

  /** 복구 코드 수. */
  public static final int RECOVERY_CODES = 10;

  private static final int SECRET_BYTES = 20;
  private static final int RECOVERY_BYTES = 10;
  private static final String ISSUER = "Lottery Service";
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final SecretKeys secrets;
  private final JobStore jobs;
  private final Clock clock;
  private final ObjectMapper json = new ObjectMapper();
  private final SecureRandom random = new SecureRandom();

  /** 저장소를 만든다. */
  public AdminMfaStore(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      SecretKeys secrets,
      JobStore jobs,
      Clock clock) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.secrets = secrets;
    this.jobs = jobs;
    this.clock = clock;
  }

  /**
   * 인증 앱을 등록한다. 등록 확인({@link #confirm}) 전까지는 로그인에 쓰지 않는다. 확인을 마친 등록이 있으면 다시 등록할 수 없고, 초기화({@link
   * #reset})부터 해야 한다(C-33).
   *
   * @param memberId 권한이 있는 관리자 회원 ID
   * @return 인증 앱에 넣을 비밀값과 복구 코드. 다시 보여 주지 않는다
   * @throws BusinessException 권한이 없거나 이미 등록했으면 {@code FORBIDDEN}
   */
  public Enrollment enroll(long memberId) {
    byte[] secret = new byte[SECRET_BYTES];
    random.nextBytes(secret);
    List<String> codes = new ArrayList<>();
    List<String> hashes = new ArrayList<>();
    for (int i = 0; i < RECOVERY_CODES; i++) {
      byte[] raw = new byte[RECOVERY_BYTES];
      random.nextBytes(raw);
      String code = Totp.base32(raw).substring(0, 16);
      codes.add(code);
      hashes.add(recoveryHash(memberId, code));
    }
    VersionedCipher.Sealed sealed =
        VersionedCipher.encrypt(secrets.adminMfa(), secret, aad(memberId));
    int changed =
        jdbc.update(
            "UPDATE admin_grant SET mfa_secret = ?, mfa_key_version = ?, mfa_enrolled_at = NULL,"
                + " mfa_last_step = NULL, mfa_failed_count = 0, mfa_locked_until = NULL,"
                + " mfa_recovery_hashes = ? WHERE member_id = ? AND revoked_at IS NULL"
                + " AND mfa_enrolled_at IS NULL",
            sealed.sealed(),
            sealed.version(),
            write(hashes),
            memberId);
    if (changed != 1) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "이미 등록했거나 권한이 없습니다.");
    }
    String base32 = Totp.base32(secret);
    String uri =
        "otpauth://totp/"
            + ISSUER.replace(" ", "%20")
            + "?secret="
            + base32
            + "&issuer="
            + ISSUER.replace(" ", "%20")
            + "&digits=6&period=30";
    return new Enrollment(base32, uri, List.copyOf(codes));
  }

  /** 등록한 인증 앱의 첫 코드를 확인해 등록을 마친다. */
  public Outcome confirm(long memberId, String code) {
    return check(memberId, code, true);
  }

  /** 관리자 세션을 시작할 때 인증 앱 코드를 확인한다. */
  public Outcome verify(long memberId, String code) {
    return check(memberId, code, false);
  }

  /** 복구 코드를 확인한다. 맞으면 그 코드를 지워 다시 쓸 수 없게 한다. 실패는 인증 앱 코드와 같이 센다. */
  public Outcome verifyRecovery(long memberId, String code) {
    return tx.execute(
        status -> {
          Optional<Row> row = lock(memberId);
          if (row.isEmpty() || row.get().enrolledAt() == null) {
            return Outcome.NOT_ENROLLED;
          }
          Instant now = clock.instant();
          if (row.get().locked(now)) {
            return Outcome.LOCKED;
          }
          List<String> hashes = new ArrayList<>(row.get().recoveryHashes());
          String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
          if (normalized.length() == 16 && hashes.remove(recoveryHash(memberId, normalized))) {
            jdbc.update(
                "UPDATE admin_grant SET mfa_recovery_hashes = ?, mfa_failed_count = 0"
                    + " WHERE member_id = ?",
                write(hashes),
                memberId);
            return Outcome.OK;
          }
          return fail(memberId, row.get(), now);
        });
  }

  /** 지금 잠겨 있으면 잠금이 풀리는 시각. 잠금 응답의 다시 시도할 시각에 쓴다. */
  public Optional<Instant> lockedUntil(long memberId) {
    Instant now = clock.instant();
    return jdbc
        .query(
            "SELECT mfa_locked_until FROM admin_grant WHERE member_id = ? AND revoked_at IS NULL",
            (rs, n) -> toInstant(rs.getObject(1, LocalDateTime.class)),
            memberId)
        .stream()
        .filter(until -> until != null && now.isBefore(until))
        .findFirst();
  }

  /**
   * 등록을 지운다. 기기를 잃었거나 복구 코드를 다 썼을 때 ADMIN이 사유와 감사 기록을 남기며 부른다(C-33).
   *
   * @return 바꾼 행 수. 회수되지 않은 관리자 권한이 없으면 0
   */
  public int reset(long memberId) {
    return jdbc.update(
        "UPDATE admin_grant SET mfa_secret = NULL, mfa_key_version = NULL, mfa_enrolled_at = NULL,"
            + " mfa_last_step = NULL, mfa_failed_count = 0, mfa_locked_until = NULL,"
            + " mfa_recovery_hashes = NULL WHERE member_id = ? AND revoked_at IS NULL",
        memberId);
  }

  private Outcome check(long memberId, String code, boolean enrolling) {
    return tx.execute(
        status -> {
          Optional<Row> found = lock(memberId);
          if (found.isEmpty()
              || found.get().secret() == null
              || (found.get().enrolledAt() == null) != enrolling) {
            return Outcome.NOT_ENROLLED;
          }
          Row row = found.get();
          Instant now = clock.instant();
          if (row.locked(now)) {
            return Outcome.LOCKED;
          }
          byte[] secret =
              VersionedCipher.decrypt(
                  secrets.adminMfa(), row.keyVersion(), row.secret(), aad(memberId));
          long current = Totp.step(now);
          if (code != null && code.matches("\\d{6}")) {
            for (long step = current - 1; step <= current + 1; step++) {
              boolean fresh = row.lastStep() == null || step > row.lastStep();
              if (fresh
                  && MessageDigest.isEqual(
                      Totp.code(secret, step).getBytes(StandardCharsets.US_ASCII),
                      code.getBytes(StandardCharsets.US_ASCII))) {
                jdbc.update(
                    "UPDATE admin_grant SET mfa_last_step = ?, mfa_failed_count = 0,"
                        + " mfa_enrolled_at = COALESCE(mfa_enrolled_at, ?) WHERE member_id = ?",
                    step,
                    utc(now),
                    memberId);
                return Outcome.OK;
              }
            }
          }
          return fail(memberId, row, now);
        });
  }

  private Outcome fail(long memberId, Row row, Instant now) {
    int failures = row.failedCount() + 1;
    if (failures >= MAX_FAILURES) {
      jdbc.update(
          "UPDATE admin_grant SET mfa_failed_count = 0, mfa_locked_until = ? WHERE member_id = ?",
          utc(now.plus(LOCK)),
          memberId);
      jobs.enqueue(
          "OPERATOR_ALERT",
          "admin-mfa-locked:" + UUID.randomUUID(),
          "{\"kind\":\"ADMIN_MFA_LOCKED\",\"lockMinutes\":" + LOCK.toMinutes() + "}",
          now);
      return Outcome.LOCKED;
    }
    jdbc.update(
        "UPDATE admin_grant SET mfa_failed_count = ? WHERE member_id = ?", failures, memberId);
    return Outcome.INVALID;
  }

  private Optional<Row> lock(long memberId) {
    return jdbc
        .query(
            "SELECT g.mfa_secret, g.mfa_key_version, g.mfa_enrolled_at, g.mfa_last_step,"
                + " g.mfa_failed_count, g.mfa_locked_until, g.mfa_recovery_hashes"
                + " FROM admin_grant g JOIN member m ON m.id = g.member_id"
                + " WHERE g.member_id = ? AND g.revoked_at IS NULL AND m.state = 'ACTIVE'"
                + " FOR UPDATE",
            (rs, n) ->
                new Row(
                    rs.getBytes(1),
                    (Integer) rs.getObject(2),
                    toInstant(rs.getObject(3, LocalDateTime.class)),
                    (Long) rs.getObject(4),
                    rs.getInt(5),
                    toInstant(rs.getObject(6, LocalDateTime.class)),
                    read(rs.getString(7))),
            memberId)
        .stream()
        .findFirst();
  }

  private static Instant toInstant(LocalDateTime value) {
    return value == null ? null : instant(value);
  }

  static byte[] aad(long memberId) {
    return ("admin-mfa|" + memberId).getBytes(StandardCharsets.UTF_8);
  }

  private static String recoveryHash(long memberId, String code) {
    try {
      return Base64.getEncoder()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest((memberId + "|" + code).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private String write(List<String> hashes) {
    try {
      return json.writeValueAsString(hashes);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<String> read(String value) {
    if (value == null) {
      return List.of();
    }
    try {
      return json.readValue(value, STRINGS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 확인 결과. */
  public enum Outcome {
    /** 맞음. */
    OK,
    /** 틀림. */
    INVALID,
    /** 잠김. 잠금이 풀린 뒤 다시 시도한다. */
    LOCKED,
    /** 등록이 없거나(확인이면) 아직 확인 전(로그인이면)이다. 권한이 없어도 이것이다. */
    NOT_ENROLLED
  }

  /**
   * 새 등록. 비밀값과 복구 코드는 이번 한 번만 보여 준다.
   *
   * @param secret 인증 앱에 넣을 Base32 비밀값
   * @param otpauthUri 인증 앱 등록 주소(QR로 보여 준다)
   * @param recoveryCodes 복구 코드 10개
   */
  public record Enrollment(String secret, String otpauthUri, List<String> recoveryCodes) {

    @Override
    public String toString() {
      return "Enrollment[hidden]";
    }
  }

  private record Row(
      byte[] secret,
      Integer keyVersion,
      Instant enrolledAt,
      Long lastStep,
      int failedCount,
      Instant lockedUntil,
      List<String> recoveryHashes) {

    boolean locked(Instant now) {
      return lockedUntil != null && now.isBefore(lockedUntil);
    }
  }
}
