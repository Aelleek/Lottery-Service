package com.lottery.lottery_service.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/** 필터가 경로로 판단할 때 쓰는 경로. 화면 요청을 처리하는 쪽과 같게 퍼센트 인코딩을 풀고 세미콜론 내용을 뺀다. */
public final class RequestPaths {

  private static final UrlPathHelper HELPER = new UrlPathHelper();

  static {
    HELPER.setUrlDecode(true);
    HELPER.setRemoveSemicolonContent(true);
  }

  private RequestPaths() {}

  /** 컨텍스트 경로를 뺀, 디코딩한 요청 경로. 예: {@code /%61pi/v1/lotto/check}는 {@code /api/v1/lotto/check}다. */
  public static String of(HttpServletRequest request) {
    return HELPER.getPathWithinApplication(request);
  }
}
