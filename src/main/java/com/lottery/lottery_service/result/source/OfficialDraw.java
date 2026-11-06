package com.lottery.lottery_service.result.source;

import com.lottery.lottery_service.result.domain.WinningNumbers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 검증을 통과한 공식 결과 한 회차.
 *
 * @param roundNo 회차
 * @param winning 당첨 번호와 보너스
 * @param firstPrizePerGame 1등 1게임 금액. 없으면 {@code null}
 * @param firstWinnerCount 1등 당첨 게임 수. 없으면 {@code null}
 * @param sourceHash 받은 원문의 SHA-256
 */
public record OfficialDraw(
    int roundNo,
    WinningNumbers winning,
    Long firstPrizePerGame,
    Long firstWinnerCount,
    byte[] sourceHash) {

  /** 의미 요약. 회차, 당첨 번호, 보너스가 같으면 같다. 금액은 넣지 않는다(금액만 바뀌는 것은 새 게시본이 아니다). */
  public byte[] semanticHash() {
    return sha256(roundNo + "|" + winning.main().key() + "|" + winning.bonus().value());
  }

  static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
