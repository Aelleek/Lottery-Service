package com.lottery.lottery_service.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeContractTest {

  private static final Path OPENAPI = Path.of("docs/contracts/openapi.yaml");
  private static final Pattern ENTRY =
      Pattern.compile("- const: ([A-Z_]+)\\s+description: (\\d{3})");

  @Test
  @DisplayName("ErrorCode는 openapi Error.code 목록과 순서까지 같고, 주 HTTP 상태가 설명의 첫 숫자와 같다")
  void matchesOpenApi() throws IOException {
    String yaml = Files.readString(OPENAPI);
    int start = yaml.indexOf("    Error:");
    int end = yaml.indexOf("        message:", start);
    String block = yaml.substring(start, end);

    List<String> names = new ArrayList<>();
    List<Integer> statuses = new ArrayList<>();
    Matcher m = ENTRY.matcher(block);
    while (m.find()) {
      names.add(m.group(1));
      statuses.add(Integer.parseInt(m.group(2)));
    }

    assertThat(names)
        .containsExactlyElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    for (ErrorCode code : ErrorCode.values()) {
      assertThat(code.status())
          .as(code.name())
          .isEqualTo(HttpStatus.valueOf(statuses.get(code.ordinal())));
    }
  }
}
