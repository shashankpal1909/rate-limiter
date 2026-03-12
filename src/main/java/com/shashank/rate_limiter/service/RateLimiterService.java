package com.shashank.rate_limiter.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.shashank.rate_limiter.dto.RateLimiterResult;
import com.shashank.rate_limiter.util.KeyGenerator;

@Service
public class RateLimiterService {

    @Autowired
    private RedisTemplate<String, String> template;
    @Autowired
    private KeyGenerator keyGenerator;

    @Value("${rate.limit.max-requests}")
    private int maxRequests;

    @Value("${rate.limit.window-seconds}")
    private int windowSeconds;

    public RateLimiterResult isAllowed(String clientId) {
        String key = keyGenerator.generateKey(clientId);
        Long count = template.opsForValue().increment(key);

        if (count == null) {
            throw new RuntimeException("Redis failure");
        }

        if (count == 1) {
            template.expire(key, Duration.ofSeconds(windowSeconds));
        }

        RateLimiterResult result = new RateLimiterResult();
        result.setAllowed(count <= maxRequests);
        result.setMaxRequests(maxRequests);
        result.setRemainingRequests(Math.max(0, maxRequests - count.intValue()));
        result.setWindowSeconds(windowSeconds);

        return result;
    }

}
