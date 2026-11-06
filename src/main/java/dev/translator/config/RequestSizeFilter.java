package dev.translator.config;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;

/** Admits bounded bodies without holding a servlet worker while the client uploads. */
public class RequestSizeFilter extends OncePerRequestFilter {
    static final String BODY = RequestSizeFilter.class.getName() + ".body";
    private final int limit;
    private final long timeoutMillis;
    private final Semaphore uploads;

    public RequestSizeFilter(TranslatorProperties properties) {
        limit = properties.limits().maxRequestBytes();
        timeoutMillis = properties.limits().bodyTimeout().toMillis();
        uploads = new Semaphore(properties.limits().concurrentBodies());
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(BODY) instanceof byte[] body) {
            chain.doFilter(new BufferedBodyRequest(request, body), response);
            return;
        }
        if (!requiresBodyLimit(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (!admit(request, response)) {
            return;
        }
        AsyncContext async = startAsync(request, response);
        async.setTimeout(timeoutMillis);
        new RequestBodyUpload(request, response, async, limit, uploads).start();
    }

    private boolean requiresBodyLimit(HttpServletRequest request) {
        String path = request.getServletPath();
        return "POST".equals(request.getMethod())
                && ("/v1/translations".equals(path) || path.startsWith("/v1/auth/"));
    }

    private boolean admit(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (request.getContentLengthLong() > limit) {
            reject(
                    response,
                    413,
                    "REQUEST_TOO_LARGE",
                    "Request body exceeds the configured limit.");
            return false;
        }
        if (!uploads.tryAcquire()) {
            reject(response, 503, "UPLOAD_CAPACITY", "Too many request bodies are being received.");
            return false;
        }
        return true;
    }

    private AsyncContext startAsync(HttpServletRequest request, HttpServletResponse response) {
        try {
            return request.startAsync(request, response);
        } catch (RuntimeException exception) {
            uploads.release();
            throw exception;
        }
    }

    static void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Connection", "close");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
