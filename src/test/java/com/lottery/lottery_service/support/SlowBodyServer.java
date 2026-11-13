package com.lottery.lottery_service.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/** 응답 헤더를 바로 보낸 뒤 본문을 0.2초에 1바이트씩 흘리는 가짜 외부 서버. 응답 시간 상한 테스트에 쓴다. */
public final class SlowBodyServer {

  private SlowBodyServer() {}

  /** 주어진 길이의 본문을 천천히 보내는 서버를 띄운다. 끝나면 {@code stop(0)}으로 닫는다. */
  public static HttpServer start(int length) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, length);
          try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < length; i++) {
              out.write(' ');
              out.flush();
              Thread.sleep(200);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (IOException e) {
            exchange.close();
          }
        });
    server.start();
    return server;
  }
}
