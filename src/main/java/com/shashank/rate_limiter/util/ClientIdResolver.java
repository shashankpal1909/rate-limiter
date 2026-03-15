package com.shashank.rate_limiter.util;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves a stable client identifier from the incoming HTTP request.
 *
 * Priority order (highest to lowest trust):
 *   1. X-API-Key header        — explicit, stable, scoped identity
 *   2. X-Forwarded-For header  — first IP in the chain (set by a trusted proxy)
 *   3. X-Real-IP header        — single real IP forwarded by reverse proxies (nginx, etc.)
 *   4. request.getRemoteAddr() — last-resort direct connection IP
 *
 * Identifiers are prefixed ("apikey:" / "ip:") to avoid Redis key collisions
 * between an API key that happens to look like an IP address.
 */
@Component
public class ClientIdResolver {

    private static final String HEADER_API_KEY        = "X-API-Key";
    private static final String HEADER_FORWARDED_FOR  = "X-Forwarded-For";
    private static final String HEADER_REAL_IP        = "X-Real-IP";

    public String resolve(HttpServletRequest request) {
        // 1. Explicit API key — most trustworthy, stable across IPs
        String apiKey = request.getHeader(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return "apikey:" + apiKey.trim();
        }

        // 2. X-Forwarded-For — may contain a comma-separated chain of IPs added by
        //    successive proxies: "client, proxy1, proxy2". The left-most entry is the
        //    original client IP. We take only that first element so the whole string is
        //    never used verbatim as a Redis key.
        String xForwardedFor = request.getHeader(HEADER_FORWARDED_FOR);
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String firstIp = xForwardedFor.split(",")[0].trim();
            if (!firstIp.isEmpty()) {
                return "ip:" + firstIp;
            }
        }

        // 3. X-Real-IP — set by nginx / other reverse proxies as a single clean IP
        String xRealIp = request.getHeader(HEADER_REAL_IP);
        if (xRealIp != null && !xRealIp.isBlank()) {
            return "ip:" + xRealIp.trim();
        }

        // 4. Direct connection address — fallback when no proxy headers are present
        return "ip:" + request.getRemoteAddr();
    }
}
