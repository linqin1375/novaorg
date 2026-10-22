package io.github.tangbailin.novaorg.api;

import io.github.tangbailin.novaorg.audit.TraceContext;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a trace id to every inbound request.
 *
 * <p>An upstream caller may supply its own id via {@code X-Trace-Id}; otherwise one is generated.
 * Either way it is echoed back in the response header, so a reviewer can take an id from the
 * browser network tab and grep the audit log for exactly that request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = "tr-" + UUID.randomUUID().toString().substring(0, 12);
        }
        response.setHeader(HEADER, traceId);
        TraceContext.start(traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }
}
