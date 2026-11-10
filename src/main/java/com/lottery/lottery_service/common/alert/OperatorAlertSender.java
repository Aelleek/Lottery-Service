package com.lottery.lottery_service.common.alert;

/** 운영자 알림 전송 경로. 운영에서는 Discord 웹훅이다(D-030). */
public interface OperatorAlertSender {

  /**
   * 알림 한 건을 보낸다.
   *
   * @throws RuntimeException 보내지 못했을 때. 알림 job이 다시 시도한다
   */
  void send(String content);
}
