package com.lottery.lottery_service.common.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretPropertiesTest {

  @Test
  @DisplayName("설정을 문자열로 바꿔도 키 값은 나오지 않고 버전만 보인다")
  void toStringHidesKeyValues() {
    String first = "c2VjcmV0LWtleS1vbmUtMzItYnl0ZXMtbG9uZy0hIQ==";
    String second = "c2VjcmV0LWtleS10d28tMzItYnl0ZXMtbG9uZy0hIQ==";
    SecretProperties.Ring ring =
        new SecretProperties.Ring(2, new TreeMap<>(Map.of(1, first, 2, second)));
    SecretProperties props = new SecretProperties(ring, ring);

    assertThat(ring).hasToString("Ring[current=2, versions=[1, 2]]");
    assertThat(props.toString()).doesNotContain(first, second).contains("versions=[1, 2]");
    assertThat(new SecretProperties.Ring(null, null))
        .hasToString("Ring[current=null, versions=null]");
  }
}
