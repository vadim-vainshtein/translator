package dev.translator.config;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one nonblocking upload and keeps its admission slot through request completion. */
final class RequestBodyUpload implements AsyncListener, ReadListener {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final AsyncContext async;
    private final int limit;
    private final Semaphore uploads;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final byte[] chunk = new byte[8192];
    private ServletInputStream input;

    RequestBodyUpload(
            HttpServletRequest request,
            HttpServletResponse response,
            AsyncContext async,
            int limit,
            Semaphore uploads) {
        this.request = request;
        this.response = response;
        this.async = async;
        this.limit = limit;
        this.uploads = uploads;
    }

    void start() throws IOException {
        async.addListener(this);
        input = request.getInputStream();
        input.setReadListener(this);
    }

    @Override
    public void onDataAvailable() throws IOException {
        while (!finished.get() && input.isReady() && !input.isFinished()) {
            int count = input.read(chunk);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                return;
            }
            if (count > limit - body.size()) {
                fail(413, "REQUEST_TOO_LARGE", "Request body exceeds the configured limit.");
                return;
            }
            body.write(chunk, 0, count);
        }
    }

    @Override
    public void onAllDataRead() {
        if (finished.compareAndSet(false, true)) {
            request.setAttribute(RequestSizeFilter.BODY, body.toByteArray());
            // The upload deadline ends here; provider work has its own timeout.
            async.dispatch();
        }
    }

    @Override
    public void onError(Throwable throwable) {
        try {
            fail(400, "INVALID_REQUEST", "Request body could not be read.");
        } catch (IOException ignored) {
            // The client may already be disconnected.
        }
    }

    @Override
    public void onComplete(AsyncEvent event) {
        if (released.compareAndSet(false, true)) {
            uploads.release();
        }
    }

    @Override
    public void onTimeout(AsyncEvent event) throws IOException {
        fail(408, "REQUEST_TIMEOUT", "Request body was not received in time.");
    }

    @Override
    public void onError(AsyncEvent event) throws IOException {
        fail(400, "INVALID_REQUEST", "Request body could not be read.");
    }

    @Override
    public void onStartAsync(AsyncEvent event) {}

    private void fail(int status, String code, String message) throws IOException {
        if (finished.compareAndSet(false, true)) {
            try {
                RequestSizeFilter.reject(response, status, code, message);
            } finally {
                async.complete();
            }
        }
    }
}
