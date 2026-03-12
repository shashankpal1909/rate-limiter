package com.shashank.rate_limiter.util;

import org.springframework.stereotype.Component;

@Component
public class KeyGenerator {
    public String generateKey(String clientId) {
        return "rate:" + clientId + ":" + (System.currentTimeMillis() / 1000 / 60);
    }
}
