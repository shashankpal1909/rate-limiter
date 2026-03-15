package com.shashank.rate_limiter.filter;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.rate_limiter.dto.RateLimitResponse;
import com.shashank.rate_limiter.dto.RateLimiterResult;
import com.shashank.rate_limiter.service.RateLimiterService;
import com.shashank.rate_limiter.util.ClientIdResolver;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimiterFilter implements Filter {

    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;
    private final ClientIdResolver clientIdResolver;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String clientId = clientIdResolver.resolve(httpRequest);

        try {
            RateLimiterResult result = rateLimiterService.isAllowed(clientId);

            if (result.isAllowed()) {
                httpResponse.setHeader("X-RateLimit-Limit", String.valueOf(result.getMaxRequests()));
                httpResponse.setHeader("X-RateLimit-Remaining", String.valueOf(result.getRemainingRequests()));
                httpResponse.setHeader("X-RateLimit-Reset", String.valueOf(result.getWindowSeconds()));
                if (result.isDegraded()) {
                    httpResponse.setHeader("X-RateLimit-Policy", "degraded-fail-open");
                }
                chain.doFilter(request, response);
            } else {
                log.info("rate limit blocked: client={}, path={}, remaining={}, retryAfterSeconds={}",
                        fingerprint(clientId),
                        httpRequest.getRequestURI(),
                        result.getRemainingRequests(),
                        result.getRetryAfterSeconds());
                httpResponse.setStatus(429);
                httpResponse.setHeader("Retry-After", String.valueOf(result.getRetryAfterSeconds()));
                httpResponse.setContentType("application/json");
                objectMapper.writeValue(httpResponse.getWriter(), new RateLimitResponse("rate limit exceeded"));
                return;
            }
        } catch (RuntimeException e) {
            log.error("rate limiter hard failure: client={}, path={}", fingerprint(clientId), httpRequest.getRequestURI(), e);
            httpResponse.setStatus(503);
            httpResponse.setContentType("application/json");
            objectMapper.writeValue(httpResponse.getWriter(), new RateLimitResponse("service unavailable"));
            return;
        }
    }

    private String fingerprint(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return "unknown";
        }
        return Integer.toHexString(Math.abs(clientId.hashCode()));
    }
}
