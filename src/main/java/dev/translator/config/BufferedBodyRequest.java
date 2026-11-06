package dev.translator.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class BufferedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BufferedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new BodyInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    private static final class BodyInputStream extends ServletInputStream {
        private final ByteArrayInputStream stream;

        private BodyInputStream(byte[] body) {
            stream = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return stream.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return stream.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return stream.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException();
        }
    }
}
