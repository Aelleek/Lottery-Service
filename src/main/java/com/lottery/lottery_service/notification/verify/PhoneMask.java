package com.lottery.lottery_service.notification.verify;

/**
 * 화면에 보여 줄 수신 번호를 가린다(D-059). 국내 휴대폰 번호는 가운데를 가린 {@code 010-****-1234} 형식이고, 그 밖의 번호는 끝 4자리만 남긴다.
 * 입력은 openapi {@code VerificationInput.phoneNumber}와 같은 국제 형식({@code +821012345678})이다.
 */
public final class PhoneMask {

  private PhoneMask() {}

  /** 가린 번호. 형식이 맞지 않으면 끝 4자리만 남긴 값, 4자리보다 짧으면 모두 가린 값이다. */
  public static String mask(String international) {
    if (international == null || international.isEmpty()) {
      return "****";
    }
    String digits = international.startsWith("+") ? international.substring(1) : international;
    if (!digits.chars().allMatch(Character::isDigit) || digits.length() < 4) {
      return "****";
    }
    if (digits.startsWith("821") && (digits.length() == 11 || digits.length() == 12)) {
      String domestic = "0" + digits.substring(2);
      return domestic.substring(0, 3) + "-****-" + domestic.substring(domestic.length() - 4);
    }
    return "****" + digits.substring(digits.length() - 4);
  }
}
