package com.lottery.lottery_service.common.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Spring HTTP 클라이언트(RestTemplate, RestClient)가 {@link BoundedHttp}로 외부를 부르게 하는 요청 팩토리(03 6.4). 연결부터
 * 본문 끝까지의 전체 시간 상한과 응답 크기 상한을 두고, 주소 이동(3xx)은 따르지 않는다.
 */
public final class BoundedClientHttpRequestFactory implements ClientHttpRequestFactory {

  private static final Set<String> RESTRICTED =
      Set.of("connection", "content-length", "expect", "host", "upgrade");

  private final HttpClient http;
  private final int maxBytes;
  private final Duration total;

  /**
   * 팩토리를 만든다.
   *
   * @param maxBytes 응답 본문 크기 상한
   * @param total 연결부터 본문 끝까지의 시간 상한. 연결 시간 제한도 이 값이다
   */
  public BoundedClientHttpRequestFactory(int maxBytes, Duration total) {
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(total)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    this.maxBytes = maxBytes;
    this.total = total;
  }

  @Override
  public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
    return new Request(uri, method);
  }

  private final class Request extends AbstractClientHttpRequest {

    private final URI uri;
    private final HttpMethod method;
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    Request(URI uri, HttpMethod method) {
      this.uri = uri;
      this.method = method;
    }

    @Override
    public HttpMethod getMethod() {
      return method;
    }

    @Override
    public URI getURI() {
      return uri;
    }

    @Override
    protected OutputStream getBodyInternal(HttpHeaders headers) {
      return body;
    }

    @Override
    protected ClientHttpResponse executeInternal(HttpHeaders headers) throws IOException {
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(uri)
              .timeout(total)
              .method(method.name(), HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
      headers.forEach(
          (name, values) -> {
            if (!RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
              values.forEach(value -> builder.header(name, value));
            }
          });
      try {
        return new Response(BoundedHttp.send(http, builder.build(), maxBytes, total));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted", e);
      }
    }
  }

  private static final class Response implements ClientHttpResponse {

    private final BoundedHttp.Response response;
    private final HttpHeaders headers = new HttpHeaders();

    Response(BoundedHttp.Response response) {
      this.response = response;
      response.headers().map().forEach(headers::addAll);
    }

    @Override
    public HttpStatusCode getStatusCode() {
      return HttpStatusCode.valueOf(response.status());
    }

    @Override
    public String getStatusText() {
      return "";
    }

    @Override
    public HttpHeaders getHeaders() {
      return headers;
    }

    @Override
    public InputStream getBody() {
      return new ByteArrayInputStream(response.body());
    }

    @Override
    public void close() {
      // 본문은 이미 다 읽어 메모리에 있다.
    }
  }
}
