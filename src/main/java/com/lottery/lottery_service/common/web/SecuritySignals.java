package com.lottery.lottery_service.common.web;

import com.lottery.lottery_service.common.error.ErrorCode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 보안 신호(07 2.1절)를 센다. 429, CSRF 거절, 출처 검사 거절을 종류별로 세고, 많이 걸린 경로를 함께 기억한다. IP는 받지 않는다. 인스턴스 1개 운영을
 * 가정해 메모리에 둔다(03 6.5). 주기 작업이 {@link #drain}으로 꺼내 기준을 넘으면 운영자에게 알린다.
 */
@Component
public class SecuritySignals {

  /** 신호 종류. */
  public enum Kind {
    /** 429 응답. */
    TOO_MANY_REQUESTS,
    /** CSRF 토큰 거절. */
    CSRF_REJECTED,
    /** 출처 검사 거절. */
    ORIGIN_REJECTED
  }

  /** 종류마다 기억하는 경로 수 상한. 넘는 경로는 {@link #OTHER_PATH}로 센다. */
  static final int MAX_PATHS = 50;

  /** 상한을 넘었거나 형식이 맞지 않는 경로를 묶는 이름. */
  public static final String OTHER_PATH = "(기타)";

  private static final Pattern ID_SEGMENT =
      Pattern.compile(
          "^(\\d+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");
  private static final Pattern SAFE_PATH = Pattern.compile("^/[a-z0-9/_{}-]{0,80}$");

  private final Map<Kind, Map<String, Long>> counts = new EnumMap<>(Kind.class);

  /** 오류 코드가 보안 신호면 센다. */
  public void record(ErrorCode code, String path) {
    kindOf(code).ifPresent(kind -> record(kind, path));
  }

  /** 신호 하나를 센다. 경로의 숫자·UUID 칸은 {@code {id}}로 바꾸고, 형식이 맞지 않으면 기타로 센다. */
  public synchronized void record(Kind kind, String path) {
    Map<String, Long> paths = counts.computeIfAbsent(kind, k -> new HashMap<>());
    String route = route(path);
    if (!paths.containsKey(route) && paths.size() >= MAX_PATHS) {
      route = OTHER_PATH;
    }
    paths.merge(route, 1L, Long::sum);
  }

  /** 지금까지 센 값을 꺼내고 비운다. */
  public synchronized Map<Kind, Summary> drain() {
    Map<Kind, Summary> result = new EnumMap<>(Kind.class);
    counts.forEach(
        (kind, paths) -> {
          long total = paths.values().stream().mapToLong(Long::longValue).sum();
          String top =
              paths.entrySet().stream()
                  .max(
                      Map.Entry.<String, Long>comparingByValue()
                          .thenComparing(Map.Entry.comparingByKey()))
                  .map(Map.Entry::getKey)
                  .orElse(OTHER_PATH);
          result.put(kind, new Summary(total, top));
        });
    counts.clear();
    return result;
  }

  static Optional<Kind> kindOf(ErrorCode code) {
    if (code == ErrorCode.CSRF_REJECTED) {
      return Optional.of(Kind.CSRF_REJECTED);
    }
    if (code == ErrorCode.ORIGIN_REJECTED) {
      return Optional.of(Kind.ORIGIN_REJECTED);
    }
    if (code.status() == HttpStatus.TOO_MANY_REQUESTS) {
      return Optional.of(Kind.TOO_MANY_REQUESTS);
    }
    return Optional.empty();
  }

  static String route(String path) {
    if (path == null) {
      return OTHER_PATH;
    }
    StringBuilder out = new StringBuilder();
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty()) {
        continue;
      }
      out.append('/').append(ID_SEGMENT.matcher(segment).matches() ? "{id}" : segment);
    }
    String route = out.length() == 0 ? "/" : out.toString();
    return SAFE_PATH.matcher(route).matches() ? route : OTHER_PATH;
  }

  /**
   * 한 종류의 합계.
   *
   * @param count 건수
   * @param topPath 가장 많이 걸린 경로(숫자·UUID는 {@code {id}})
   */
  public record Summary(long count, String topPath) {}
}
