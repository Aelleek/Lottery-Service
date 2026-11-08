package com.lottery.lottery_service.notification.verify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhoneMaskTest {

  @Test
  @DisplayName("국내 휴대폰 번호는 010-****-1234처럼 가운데를 가린다")
  void domesticMobile() {
    assertThat(PhoneMask.mask("+821012345678")).isEqualTo("010-****-5678");
    assertThat(PhoneMask.mask("+82111234567")).isEqualTo("011-****-4567");
  }

  @Test
  @DisplayName("그 밖의 번호는 끝 4자리만, 짧거나 형식이 틀리면 모두 가린다")
  void others() {
    assertThat(PhoneMask.mask("+14155550123")).isEqualTo("****0123");
    assertThat(PhoneMask.mask("+12")).isEqualTo("****");
    assertThat(PhoneMask.mask("+82-10-1234")).isEqualTo("****");
    assertThat(PhoneMask.mask(null)).isEqualTo("****");
  }
}
