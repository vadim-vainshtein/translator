package dev.translator.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.filter.OncePerRequestFilter;

/** Buffers bounded bodies without holding a servlet worker while the client uploads. */
public class RequestSizeFilter extends OncePerRequestFilter {
    private static final String BODY = RequestSizeFilter.class.getName() + ".body";
    private final int limit;
    private final long timeoutMillis;
    private final Semaphore uploads;

    public RequestSizeFilter(TranslatorProperties properties) {
        limit = properties.limits().maxRequestBytes();
        timeoutMillis = properties.limits().bodyTimeout().toMillis();
        uploads = new Semaphore(properties.limits().concurrentBodies());
    }

    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(BODY) instanceof byte[] body) {
            chain.doFilter(buffered(request, body), response);
            return;
        }
        if (!"POST".equals(request.getMethod()) || !("/v1/translations".equals(request.getServletPath())
                || request.getServletPath().startsWith("/v1/auth/"))) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            reject(response, 413, "REQUEST_TOO_LARGE", "Request body exceeds the configured limit.");
            return;
        }
        if (!uploads.tryAcquire()) {
            reject(response, 503, "UPLOAD_CAPACITY", "Too many request bodies are being received.");
            return;
        }
        AsyncContext async;
        try { async = request.startAsync(request, response); }
        catch (RuntimeException exception) { uploads.release(); throw exception; }
        async.setTimeout(timeoutMillis);
        var finished = new AtomicBoolean();
        var released = new AtomicBoolean();
        async.addListener(new AsyncListener() {
            @Override public void onComplete(AsyncEvent event) { release(); }
            @Override public void onTimeout(AsyncEvent event) throws IOException { fail(408, "REQUEST_TIMEOUT", "Request body was not received in time."); }
            @Override public void onError(AsyncEvent event) throws IOException { fail(400, "INVALID_REQUEST", "Request body could not be read."); }
            @Override public void onStartAsync(AsyncEvent event) {}
            private void release() { if (released.compareAndSet(false, true)) uploads.release(); }
            private void fail(int status, String code, String message) throws IOException {
                if (finished.compareAndSet(false, true)) {
                    try { reject(response, status, code, message); }
                    finally { async.complete(); }
                }
            }
        });
        ServletInputStream input = request.getInputStream();
        input.setReadListener(new ReadListener() {
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();
            private final byte[] chunk = new byte[8192];
            @Override public void onDataAvailable() throws IOException {
                while (!finished.get() && input.isReady() && !input.isFinished()) {
                    int count = input.read(chunk);
                    if (count < 0) break;
                    if (count == 0) return;
                    if (count > limit - body.size()) {
                        if (finished.compareAndSet(false, true)) {
                            try { reject(response, 413, "REQUEST_TOO_LARGE", "Request body exceeds the configured limit."); }
                            finally { async.complete(); }
                        }
                        return;
                    }
                    body.write(chunk, 0, count);
                }
            }
            @Override public void onAllDataRead() {
                if (finished.compareAndSet(false, true)) {
                    request.setAttribute(BODY, body.toByteArray());
                    // The upload deadline ends here; provider work has its own timeout.
                    async.dispatch();
                }
            }
            @Override public void onError(Throwable throwable) {
                if (finished.compareAndSet(false, true)) {
                    try { reject(response, 400, "INVALID_REQUEST", "Request body could not be read."); }
                    catch (IOException ignored) { /* The client may already be disconnected. */ }
                    finally { async.complete(); }
                }
            }
        });
    }

    private static void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Connection", "close");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private static HttpServletRequest buffered(HttpServletRequest request, byte[] body) {
        return new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var stream = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return stream.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) { return stream.read(bytes, offset, length); }
                    @Override public boolean isFinished() { return stream.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        };
    }
}
