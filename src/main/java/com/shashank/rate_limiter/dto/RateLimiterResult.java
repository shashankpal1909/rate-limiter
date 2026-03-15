package com.shashank.rate_limiter.dto;

import lombok.Data;

@Data
public class RateLimiterResult {
    private boolean allowed;
    private int maxRequests;
    private int remainingRequests;
    private int windowSeconds;
    /** Seconds until the next token is available; 0 when the request is allowed. */
    private int retryAfterSeconds;
    /** True when Redis was bypassed due to fail-open fallback. */
    private boolean degraded;
}