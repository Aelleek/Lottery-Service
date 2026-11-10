package com.lottery.lottery_service.result.source;

import com.lottery.lottery_service.result.domain.WinningNumbers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 검증을 통과한 공식 결과 한 회차.
 *
 * @param roundNo 회차
 * @param winning 당첨 번호와 보너스
 * @param upperPrizes 1~3등 금액과 당첨 수(등수 순서)
 * @param sourceHash 받은 원문의 SHA-256
 */
public record OfficialDraw(
    int roundNo, WinningNumbers winning, List<RankPrize> upperPrizes, byte[] sourceHash) {

  /** 결과 API에서 받는 등수(1~3등). 4·5등은 고정 금액이다. */
  public static final int UPPER_RANKS = 3;

  /** 등수 순서를 맞춘다. */
  public OfficialDraw {
    if (upperPrizes.size() != UPPER_RANKS) {
      throw new IllegalArgumentException("upper prizes must cover ranks 1-3");
    }
    for (int i = 0; i < UPPER_RANKS; i++) {
      if (upperPrizes.get(i).rank() != i + 1) {
        throw new IllegalArgumentException("upper prizes must be in rank order");
      }
    }
    upperPrizes = List.copyOf(upperPrizes);
  }

  /** 1등 금액·당첨 수만 아는 결과를 만든다. 2·3등은 확인 중이다. */
  public OfficialDraw(
      int roundNo,
      WinningNumbers winning,
      Long firstPrizePerGame,
      Long firstWinnerCount,
      byte[] sourceHash) {
    this(
        roundNo,
        winning,
        List.of(
            new RankPrize(1, firstPrizePerGame, firstWinnerCount),
            new RankPrize(2, null, null),
            new RankPrize(3, null, null)),
        sourceHash);
  }

  /** 등수(1~3)의 금액과 당첨 수. */
  public RankPrize prize(int rank) {
    return upperPrizes.get(rank - 1);
  }

  /** 1등 1게임 금액. 없으면 {@code null}. */
  public Long firstPrizePerGame() {
    return prize(1).prizePerGame();
  }

  /** 1등 당첨 게임 수. 없으면 {@code null}. */
  public Long firstWinnerCount() {
    return prize(1).winnerCount();
  }

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
