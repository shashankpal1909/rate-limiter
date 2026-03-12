package com.shashank.rate_limiter.dto;

import lombok.Data;

@Data
public class RateLimiterResult {
    private boolean allowed;
    private int maxRequests;
    private int remainingRequests;
    private int windowSeconds;
}