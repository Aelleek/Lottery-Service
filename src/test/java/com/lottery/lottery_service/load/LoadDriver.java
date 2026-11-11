package com.lottery.lottery_service.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 07 3절의 부하 시험을 돌리는 도구. 비회원 사용자 여럿이 정해진 초당 요청 수로 조회(현재 회차, 회차 결과, 내 기록)와 기본 추천 저장을 섞어 보내고, 종류별 p95와
 * 서버 오류율을 센다.
 *
 * <p>사용자마다 {@code X-Forwarded-For}에 다른 IP를 넣을 수 있다. 앱이 그 연결을 믿을 앞단으로 설정한 경우에만(07 5절 5번) 사용자별로 요청
 * 제한이 세어진다. 앞단 없이 한 IP로 보내면 IP당 한도(03 6.5)에 먼저 걸린다.
 *
 * <p>로컬에서는 {@code ./gradlew loadTest}가 MySQL 컨테이너로 앱을 띄워 돌린다. 운영과 같은 환경에서는 {@code ./gradlew loadRun
 * -Dload.baseUrl=https://...}로 돌린다.
 */
public final class LoadDriver {

  /** 요청 종류. */
  public enum Kind {
    /** 현재 회차 조회. */
    CONTEXT,
    /** 지난 회차 결과 조회. */
    RESULT,
    /** 비회원 내 기록 조회. */
    RECORDS,
    /** 명령 토큰 발급. */
    COMMAND_TOKEN,
    /** 기본 추천 저장. */
    RECOMMEND
  }

  /**
   * 부하 설정.
   *
   * @param baseUrl 앱 주소(끝에 {@code /} 없이)
   * @param users 동시 사용자 수
   * @param requestsPerSecond 전체 초당 동작 수. 추천 한 번은 요청 둘(토큰, 저장)이다
   * @param duration 측정 시간
   * @param forwardedPrefix 사용자 IP로 쓸 앞 세 자리(예: {@code 198.18.0}). {@code null}이면 헤더를 넣지 않는다
   * @param recommendPercent 동작 중 추천 비율(0~100)
   */
  public record Settings(
      String baseUrl,
      int users,
      int requestsPerSecond,
      Duration duration,
      String forwardedPrefix,
      int recommendPercent) {

    /** 시스템 속성 {@code load.*}에서 읽는다. 없으면 07 3절 값(100명, 초당 20)이다. */
    public static Settings fromSystemProperties(String defaultBaseUrl) {
      return new Settings(
          System.getProperty("load.baseUrl", defaultBaseUrl),
          Integer.getInteger("load.users", 100),
          Integer.getInteger("load.rps", 20),
          Duration.ofSeconds(Integer.getInteger("load.seconds", 60)),
          System.getProperty("load.forwardedPrefix", "198.18.0"),
          Integer.getInteger("load.recommendPercent", 20));
    }
  }

  /**
   * 종류별 결과.
   *
   * @param count 보낸 요청 수
   * @param serverErrors 5xx이거나 응답을 받지 못한 수
   * @param limited 429 수
   * @param clientErrors 429를 뺀 4xx 수. 도구나 앱의 계약이 어긋났다는 뜻이다
   * @param p95Millis 응답 시간 p95(밀리초)
   * @param maxMillis 가장 긴 응답 시간(밀리초)
   */
  public record KindReport(
      int count, int serverErrors, int limited, int clientErrors, long p95Millis, long maxMillis) {}

  /**
   * 전체 결과.
   *
   * @param kinds 종류별 결과
   * @param elapsed 실제 걸린 시간
   */
  public record Report(Map<Kind, KindReport> kinds, Duration elapsed) {

    /** 모든 요청 수. */
    public int total() {
      return kinds.values().stream().mapToInt(KindReport::count).sum();
    }

    /** 서버 오류율(0~1). */
    public double serverErrorRate() {
      int total = total();
      return total == 0
          ? 0
          : kinds.values().stream().mapToInt(KindReport::serverErrors).sum() / (double) total;
    }

    /** 조회 요청(현재 회차, 결과, 기록) 중 가장 큰 p95(밀리초). */
    public long readP95Millis() {
      return List.of(Kind.CONTEXT, Kind.RESULT, Kind.RECORDS).stream()
          .mapToLong(k -> kinds.get(k).p95Millis())
          .max()
          .orElse(0);
    }

    /** 사람이 읽을 요약. */
    public String summary() {
      StringBuilder out = new StringBuilder();
      out.append(
          String.format(
              Locale.ROOT,
              "load: %d requests in %ds (%.1f/s), server error rate %.2f%%%n",
              total(),
              elapsed.toSeconds(),
              total() / Math.max(1.0, elapsed.toMillis() / 1000.0),
              serverErrorRate() * 100));
      kinds.forEach(
          (kind, r) ->
              out.append(
                  String.format(
                      Locale.ROOT,
                      "  %-13s n=%5d 5xx=%3d 429=%3d 4xx=%3d p95=%5dms max=%5dms%n",
                      kind,
                      r.count(),
                      r.serverErrors(),
                      r.limited(),
                      r.clientErrors(),
                      r.p95Millis(),
                      r.maxMillis())));
      return out.toString();
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Settings settings;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final Map<Kind, List<long[]>> samples = new EnumMap<>(Kind.class);

  /** 설정으로 만든다. */
  public LoadDriver(Settings settings) {
    this.settings = settings;
    for (Kind kind : Kind.values()) {
      samples.put(kind, new ArrayList<>());
    }
  }

  /** 시스템 속성으로 정한 주소에 부하를 걸고 요약을 출력한다. */
  public static void main(String[] args) throws Exception {
    Report report = new LoadDriver(Settings.fromSystemProperties("http://127.0.0.1:8080")).run();
    System.out.print(report.summary());
  }

  /** 사용자를 준비하고 측정 시간 동안 부하를 건다. */
  public Report run() throws IOException, InterruptedException {
    JsonNode context = getJson(settings.baseUrl() + "/api/v1/lotto/context", null, null);
    int round = context.path("currentRound").asInt();
    String scheduleVersion = context.path("scheduleVersion").asText();
    if (!context.path("recommendationOpen").asBoolean()) {
      throw new IllegalStateException("recommendation is not open for round " + round);
    }

    List<User> users = new ArrayList<>();
    for (int i = 0; i < settings.users(); i++) {
      users.add(issue(i));
    }

    long intervalNanos =
        TimeUnit.SECONDS.toNanos(settings.users()) / Math.max(1, settings.requestsPerSecond());
    long start = System.nanoTime();
    long end = start + settings.duration().toNanos();
    ExecutorService pool = Executors.newFixedThreadPool(settings.users());
    for (int i = 0; i < users.size(); i++) {
      User user = users.get(i);
      long offset = intervalNanos * i / users.size();
      pool.execute(() -> loop(user, round, scheduleVersion, start + offset, intervalNanos, end));
    }
    pool.shutdown();
    if (!pool.awaitTermination(settings.duration().toSeconds() + 120, TimeUnit.SECONDS)) {
      pool.shutdownNow();
    }
    return report(Duration.ofNanos(System.nanoTime() - start));
  }

  private void loop(
      User user, int round, String scheduleVersion, long first, long interval, long end) {
    long next = first;
    int step = 0;
    while (next < end) {
      long wait = next - System.nanoTime();
      if (wait > 0) {
        LockSupport.parkNanos(wait);
      }
      act(user, step++, round, scheduleVersion);
      next += interval;
    }
  }

  private void act(User user, int step, int round, String scheduleVersion) {
    int pick = Math.floorMod(user.index() * 31 + step * 17, 100);
    if (pick < settings.recommendPercent()) {
      recommend(user, round, scheduleVersion);
      return;
    }
    switch (step % 3) {
      case 0 -> timed(Kind.CONTEXT, get("/api/v1/lotto/context", user));
      case 1 -> timed(Kind.RESULT, get("/api/v1/lotto/rounds/" + (round - 1) + "/result", user));
      default -> timed(Kind.RECORDS, get("/api/v1/guest/lotto/records?round=" + round, user));
    }
  }

  private void recommend(User user, int round, String scheduleVersion) {
    String tokenBody =
        "{\"operation\":\"RECOMMEND\",\"round\":"
            + round
            + ",\"scheduleVersion\":\""
            + scheduleVersion
            + "\"}";
    HttpResponse<String> token =
        timed(Kind.COMMAND_TOKEN, post("/api/v1/command-tokens", user, tokenBody, null));
    if (token == null || token.statusCode() != 200) {
      return;
    }
    String commandToken;
    try {
      commandToken = JSON.readTree(token.body()).path("token").asText();
    } catch (IOException e) {
      return;
    }
    timed(
        Kind.RECOMMEND,
        post(
            "/api/v1/lotto/recommendations",
            user,
            "{\"round\":"
                + round
                + ",\"scheduleVersion\":\""
                + scheduleVersion
                + "\",\"include\":[],\"exclude\":[]}",
            commandToken));
  }

  private User issue(int index) throws IOException, InterruptedException {
    String ip = ipOf(index);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/api/v1/guest-credentials"))
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.noBody());
    if (ip != null) {
      request.header("X-Forwarded-For", ip).header("X-Forwarded-Proto", "https");
    }
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200 && response.statusCode() != 201) {
      throw new IllegalStateException("guest credential status " + response.statusCode());
    }
    String cookie =
        response.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.contains("lottery_guest="))
            .map(c -> c.substring(0, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no guest cookie"));
    return new User(index, ip, cookie, JSON.readTree(response.body()).path("csrfToken").asText());
  }

  private String ipOf(int index) {
    if (settings.forwardedPrefix() == null || settings.forwardedPrefix().isBlank()) {
      return null;
    }
    return settings.forwardedPrefix() + "." + (index % 250 + 1);
  }

  private Call get(String path, User user) {
    return () -> http.send(base(path, user).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private Call post(String path, User user, String body, String commandToken) {
    return () -> {
      HttpRequest.Builder request =
          base(path, user)
              .header("Content-Type", "application/json")
              .header("X-CSRF-TOKEN", user.csrf())
              .POST(HttpRequest.BodyPublishers.ofString(body));
      if (commandToken != null) {
        request
            .header("X-Command-Token", commandToken)
            .header("Idempotency-Key", UUID.randomUUID().toString());
      }
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    };
  }

  private HttpRequest.Builder base(String path, User user) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(settings.baseUrl() + path))
            .timeout(Duration.ofSeconds(10))
            .header("Cookie", user.cookie());
    if (user.ip() != null) {
      request.header("X-Forwarded-For", user.ip()).header("X-Forwarded-Proto", "https");
    }
    return request;
  }

  private HttpResponse<String> timed(Kind kind, Call call) {
    long started = System.nanoTime();
    HttpResponse<String> response = null;
    int status;
    try {
      response = call.send();
      status = response.statusCode();
    } catch (IOException e) {
      status = -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      status = -1;
    }
    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    List<long[]> list = samples.get(kind);
    synchronized (list) {
      list.add(new long[] {millis, status});
    }
    return response;
  }

  private JsonNode getJson(String url, String cookie, String ip)
      throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
    if (cookie != null) {
      request.header("Cookie", cookie);
    }
    if (ip != null) {
      request.header("X-Forwarded-For", ip);
    }
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("status " + response.statusCode() + " for " + url);
    }
    return JSON.readTree(response.body());
  }

  private Report report(Duration elapsed) {
    Map<Kind, KindReport> kinds = new EnumMap<>(Kind.class);
    samples.forEach(
        (kind, list) -> {
          List<long[]> copy;
          synchronized (list) {
            copy = new ArrayList<>(list);
          }
          long[] millis = copy.stream().mapToLong(s -> s[0]).sorted().toArray();
          int errors = (int) copy.stream().filter(s -> s[1] < 0 || s[1] >= 500).count();
          int limited = (int) copy.stream().filter(s -> s[1] == 429).count();
          int client =
              (int) copy.stream().filter(s -> s[1] >= 400 && s[1] < 500 && s[1] != 429).count();
          long p95 =
              millis.length == 0
                  ? 0
                  : millis[Math.min(millis.length - 1, (int) Math.ceil(millis.length * 0.95) - 1)];
          long max = millis.length == 0 ? 0 : millis[millis.length - 1];
          kinds.put(kind, new KindReport(copy.size(), errors, limited, client, p95, max));
        });
    return new Report(kinds, elapsed);
  }

  private record User(int index, String ip, String cookie, String csrf) {}

  @FunctionalInterface
  private interface Call {
    HttpResponse<String> send() throws IOException, InterruptedException;
  }
}
