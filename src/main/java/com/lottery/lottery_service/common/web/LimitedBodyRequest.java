package com.lottery.lottery_service.common.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** 본문을 정해진 바이트까지만 읽게 하는 요청. 넘으면 읽기가 {@link IOException}으로 실패한다. */
class LimitedBodyRequest extends HttpServletRequestWrapper {

  private final long max;
  private ServletInputStream stream;

  LimitedBodyRequest(HttpServletRequest request, long max) {
    super(request);
    this.max = max;
  }

  @Override
  public BufferedReader getReader() throws IOException {
    String encoding = getCharacterEncoding();
    Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
    return new BufferedReader(new InputStreamReader(getInputStream(), charset));
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    if (stream == null) {
      stream = new Limited(super.getInputStream(), max);
    }
    return stream;
  }

  private static final class Limited extends ServletInputStream {

    private final ServletInputStream in;
    private final long max;
    private long read;

    Limited(ServletInputStream in, long max) {
      this.in = in;
      this.max = max;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = in.read(buffer, offset, length);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(int n) throws IOException {
      read += n;
      if (read > max) {
        throw new IOException("request body too large");
      }
    }

    @Override
    public boolean isFinished() {
      return in.isFinished();
    }

    @Override
    public boolean isReady() {
      return in.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      in.setReadListener(listener);
    }
  }
}
