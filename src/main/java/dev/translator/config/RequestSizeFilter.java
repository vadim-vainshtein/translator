package dev.translator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeFilter extends OncePerRequestFilter {
    private final int limit;
    public RequestSizeFilter(TranslatorProperties properties) { limit = properties.limits().maxRequestBytes(); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"POST".equals(request.getMethod()) || !"/v1/translations".equals(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"REQUEST_TOO_LARGE\",\"message\":\"Request body exceeds the configured limit.\"}");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var stream = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return stream.read(); }
                    @Override public int read(byte[] bytes, int offset, int length) { return stream.read(bytes, offset, length); }
                    @Override public boolean isFinished() { return stream.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(jakarta.servlet.ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
        }, response);
    }
}
