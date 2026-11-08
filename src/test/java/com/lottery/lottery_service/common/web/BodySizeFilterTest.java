package com.lottery.lottery_service.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lottery.lottery_service.common.error.BusinessException;
import com.lottery.lottery_service.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

class BodySizeFilterTest {

  private final List<BusinessException> rejected = new ArrayList<>();
  private final BodySizeFilter filter =
      new BodySizeFilter(
          (request, response, handler, ex) -> {
            rejected.add((BusinessException) ex);
            return new ModelAndView();
          });

  @Test
  @DisplayName("Content-Length가 64KB를 넘으면 VALIDATION_FAILED로 거부하고 다음으로 넘기지 않는다")
  void rejectsDeclaredLength() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/lotto/check");
    request.setContent(new byte[BodySizeFilter.MAX_BYTES + 1]);
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isNull();
    assertThat(rejected)
        .extracting(BusinessException::getCode)
        .containsExactly(ErrorCode.VALIDATION_FAILED);
  }

  @Test
  @DisplayName("길이를 알리지 않은 본문은 64KB까지만 읽힌다")
  void limitsUndeclaredLength() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/lotto/check") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    request.setContent(new byte[BodySizeFilter.MAX_BYTES + 10]);
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
    assertThatThrownBy(() -> passed.getInputStream().readAllBytes())
        .isInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("64KB 이하 본문은 그대로 읽힌다")
  void passesSmallBody() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/lotto/check");
    request.setContent(new byte[BodySizeFilter.MAX_BYTES]);
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
    assertThat(passed.getInputStream().readAllBytes()).hasSize(BodySizeFilter.MAX_BYTES);
  }
}
