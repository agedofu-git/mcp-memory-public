package dev.memory.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestSizeFilter extends OncePerRequestFilter {
    private final int limit;
    public RequestSizeFilter(MemoryProperties properties) { limit = properties.maxRequestBytes(); }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/") || !java.util.Set.of("POST", "PATCH", "PUT").contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) { tooLarge(response); return; }
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) { tooLarge(response); return; }
        var input = new ByteArrayInputStream(bytes);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
        }, response);
    }
    private static void tooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Payload too large\",\"status\":413,\"detail\":\"Request exceeds byte limit.\"}");
    }
}
