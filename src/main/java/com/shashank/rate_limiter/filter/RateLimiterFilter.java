package com.shashank.rate_limiter.filter;

import java.io.IOException;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shashank.rate_limiter.dto.RateLimitResponse;
import com.shashank.rate_limiter.dto.RateLimiterResult;
import com.shashank.rate_limiter.service.RateLimiterService;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RateLimiterFilter implements Filter {

    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String clientId = httpRequest.getHeader("X-API-KEY");
        if (clientId == null || clientId.isBlank()) {
            clientId = httpRequest.getRemoteAddr();
        }

        try {
            RateLimiterResult result = rateLimiterService.isAllowed(clientId);

            if (result.isAllowed()) {
                httpResponse.setHeader("X-RateLimit-Limit", String.valueOf(result.getMaxRequests()));
                httpResponse.setHeader("X-RateLimit-Remaining", String.valueOf(result.getRemainingRequests()));
                httpResponse.setHeader("X-RateLimit-Reset", String.valueOf(result.getWindowSeconds()));
                chain.doFilter(request, response);
            } else {
                httpResponse.setStatus(429);
                httpResponse.setHeader("Retry-After", String.valueOf(result.getWindowSeconds()));
                httpResponse.setContentType("application/json");
                objectMapper.writeValue(httpResponse.getWriter(), new RateLimitResponse("rate limit exceeded"));
                return;
            }
        } catch (RuntimeException e) {
            httpResponse.setStatus(503);
            httpResponse.setContentType("application/json");
            objectMapper.writeValue(httpResponse.getWriter(), new RateLimitResponse("service unavailable"));
            return;
        }
    }
}
