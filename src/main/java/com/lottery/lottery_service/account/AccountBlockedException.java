package com.lottery.lottery_service.account;

/** 탈퇴 처리 중이거나 탈퇴한 회원이라 로그인을 받지 않는다. */
public class AccountBlockedException extends RuntimeException {

  /** 차단된 회원 ID로 예외를 만든다. */
  public AccountBlockedException(long memberId) {
    super("member is not active: id=" + memberId);
  }
}
