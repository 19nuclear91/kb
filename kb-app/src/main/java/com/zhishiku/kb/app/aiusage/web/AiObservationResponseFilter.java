package com.zhishiku.kb.app.aiusage.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 在鉴权之前设置缓存策略；采集失败只累计无标签计数。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AiObservationResponseFilter extends OncePerRequestFilter {
    private final Counter failures;
    public AiObservationResponseFilter(MeterRegistry registry) {
        failures = registry.counter("kb.ai.observation.ingest_failures");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/admin/ai/") && !path.startsWith("/internal/ai-observability/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        boolean failed = false;
        try { chain.doFilter(request, response); }
        catch (IOException | ServletException | RuntimeException error) { failed = true; throw error; }
        finally {
            if (request.getRequestURI().startsWith("/internal/ai-observability/") && (failed || response.getStatus() >= 400)) failures.increment();
        }
    }
}
