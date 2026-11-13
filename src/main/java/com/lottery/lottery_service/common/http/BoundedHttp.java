package com.lottery.lottery_service.common.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 외부 응답을 전체 시간 상한과 크기 상한 안에서 받는다(03 6.4). {@link HttpRequest#timeout}은 응답 헤더까지만 보므로, 본문을 천천히 보내는
 * 상대에도 호출이 끝나도록 본문까지 포함한 상한을 따로 둔다.
 */
public final class BoundedHttp {

  private BoundedHttp() {}

  /**
   * 받은 응답.
   *
   * @param status 상태 코드
   * @param body 본문. 상한 이하
   */
  public record Response(int status, byte[] body) {}

  /** 본문이 크기 상한을 넘었다. 넘은 뒤로는 읽지 않는다. */
  public static final class TooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    /** 예외를 만든다. */
    public TooLargeException() {
      super("response body exceeds the size limit");
    }
  }

  /**
   * 요청을 보내고 본문까지 받는다.
   *
   * @param maxBytes 본문 크기 상한
   * @param total 연결부터 본문 끝까지의 시간 상한
   * @throws TooLargeException 본문이 상한을 넘으면
   * @throws HttpTimeoutException 시간 상한 안에 본문을 다 받지 못하면
   * @throws IOException 그 밖의 통신 실패
   */
  public static Response send(HttpClient http, HttpRequest request, int maxBytes, Duration total)
      throws IOException, InterruptedException {
    CompletableFuture<HttpResponse<byte[]>> future =
        http.sendAsync(request, info -> new Limited(maxBytes));
    try {
      HttpResponse<byte[]> response = future.get(total.toMillis(), TimeUnit.MILLISECONDS);
      return new Response(response.statusCode(), response.body());
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new HttpTimeoutException("response did not finish within the time limit");
    } catch (InterruptedException e) {
      future.cancel(true);
      throw e;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      while (cause instanceof CompletionException && cause.getCause() != null) {
        cause = cause.getCause();
      }
      if (cause instanceof IOException io) {
        throw io;
      }
      throw new IOException("request failed: " + cause.getClass().getSimpleName(), cause);
    }
  }

  private static final class Limited implements HttpResponse.BodySubscriber<byte[]> {

    private final int max;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    Limited(int max) {
      this.max = max;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      s.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      for (ByteBuffer buffer : items) {
        if (out.size() + buffer.remaining() > max) {
          subscription.cancel();
          result.completeExceptionally(new TooLargeException());
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        out.write(chunk, 0, chunk.length);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(out.toByteArray());
    }
  }
}
