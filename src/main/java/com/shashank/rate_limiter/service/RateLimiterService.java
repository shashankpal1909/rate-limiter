package com.shashank.rate_limiter.service;

import java.time.Duration;
import java.util.Collections;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.shashank.rate_limiter.dto.RateLimiterResult;
import com.shashank.rate_limiter.util.KeyGenerator;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class RateLimiterService {

    /**
     * Token Bucket algorithm implemented as an atomic Redis Lua script.
     *
     * Each client has a Redis Hash with two fields:
     *   tokens     – current token count (floating-point stored as string)
     *   last_refill – epoch-ms of the last refill calculation
     *
     * ARGV[1] capacity     – bucket size (max burst), integer
     * ARGV[2] refill_rate  – tokens added per second (= capacity / window), float
     * ARGV[3] now          – current epoch-ms from the caller
     *
     * Returns a colon-delimited string: "allowed:remaining:retry_after_ms"
     *   allowed       – 1 if the request is permitted, 0 if denied
     *   remaining     – tokens left after this request
     *   retry_after_ms – ms until the next token arrives (0 when allowed)
     */
    private static final DefaultRedisScript<String> TOKEN_BUCKET_SCRIPT;

    static {
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setScriptText("""
                local key          = KEYS[1]
                local capacity     = tonumber(ARGV[1])
                local refill_rate  = tonumber(ARGV[2])
                local now          = tonumber(ARGV[3])

                local data       = redis.call('HMGET', key, 'tokens', 'last_refill')
                local tokens     = tonumber(data[1])
                local last_refill = tonumber(data[2])

                -- First request for this client: start with a full bucket
                if tokens == nil then
                    tokens      = capacity
                    last_refill = now
                end

                -- Refill: compute how many tokens have accumulated since last call
                local elapsed_ms = math.max(0, now - last_refill)
                local new_tokens = math.floor(elapsed_ms / 1000 * refill_rate)
                if new_tokens > 0 then
                    tokens      = math.min(capacity, tokens + new_tokens)
                    last_refill = now
                end

                local allowed        = 0
                local retry_after_ms = 0

                if tokens >= 1 then
                    tokens  = tokens - 1
                    allowed = 1
                else
                    -- How many ms until the next token refills?
                    retry_after_ms = math.ceil(1000 / refill_rate)
                end

                -- TTL: time to drain a full bucket + 1s buffer so Redis doesn't evict active keys
                local ttl = math.ceil(capacity / refill_rate) + 1
                redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)
                redis.call('EXPIRE', key, ttl)

                return tostring(allowed) .. ':' .. tostring(math.floor(tokens)) .. ':' .. tostring(retry_after_ms)
                """);
        TOKEN_BUCKET_SCRIPT.setResultType(String.class);
    }

    private final RedisTemplate<String, String> template;
    private final KeyGenerator keyGenerator;

    @Value("${rate.limit.max-requests}")
    private int maxRequests;

    @Value("${rate.limit.window-seconds}")
    private int windowSeconds;

    @Value("${rate.limit.circuit-breaker.failure-rate-threshold:50}")
    private float failureRateThreshold;

    @Value("${rate.limit.circuit-breaker.sliding-window-size:20}")
    private int slidingWindowSize;

    @Value("${rate.limit.circuit-breaker.minimum-number-of-calls:10}")
    private int minimumNumberOfCalls;

    @Value("${rate.limit.circuit-breaker.wait-duration-open-seconds:30}")
    private int waitDurationOpenSeconds;

    @Value("${rate.limit.circuit-breaker.permitted-calls-half-open:3}")
    private int permittedCallsHalfOpen;

    private CircuitBreaker redisCircuitBreaker;

    @PostConstruct
    void initCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationOpenSeconds))
                .permittedNumberOfCallsInHalfOpenState(permittedCallsHalfOpen)
                .build();

        this.redisCircuitBreaker = CircuitBreaker.of("redis-rate-limiter", config);
        this.redisCircuitBreaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                log.warn("circuit breaker transitioned to OPEN: {}", event.getStateTransition());
                return;
            }
            log.info("circuit breaker state transition: {}", event.getStateTransition());
        });
    }

    public RateLimiterResult isAllowed(String clientId) {
        String key = keyGenerator.generateKey(clientId);
        double refillRate = (double) maxRequests / windowSeconds; // tokens per second
        long nowMs = System.currentTimeMillis();

        Supplier<String> redisSupplier = () -> executeTokenBucketScript(key, refillRate, nowMs);
        Supplier<String> protectedSupplier = CircuitBreaker.decorateSupplier(redisCircuitBreaker, redisSupplier);

        try {
            String response = protectedSupplier.get();
            return parseResult(response);
        } catch (CallNotPermittedException e) {
            log.warn("circuit breaker is OPEN for client={} - using fail-open fallback", fingerprint(clientId));
            return buildFailOpenResult();
        } catch (RuntimeException e) {
            log.warn("redis rate-limit evaluation failed for client={} - using fail-open fallback", fingerprint(clientId), e);
            return buildFailOpenResult();
        }
    }

    private String executeTokenBucketScript(String key, double refillRate, long nowMs) {
        String response = template.execute(
                TOKEN_BUCKET_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(maxRequests),
                String.valueOf(refillRate),
                String.valueOf(nowMs));

        if (response == null) {
            throw new RuntimeException("Redis failure");
        }

        return response;
    }

    private RateLimiterResult parseResult(String response) {
        String[] parts = response.split(":");
        if (parts.length != 3) {
            throw new RuntimeException("Unexpected Redis response format: " + response);
        }

        boolean allowed = "1".equals(parts[0]);
        int remaining = Integer.parseInt(parts[1]);
        int retryAfterMs = Integer.parseInt(parts[2]);

        RateLimiterResult result = new RateLimiterResult();
        result.setAllowed(allowed);
        result.setMaxRequests(maxRequests);
        result.setRemainingRequests(remaining);
        result.setWindowSeconds(windowSeconds);
        result.setRetryAfterSeconds(allowed ? 0 : Math.max(1, (int) Math.ceil(retryAfterMs / 1000.0)));
        result.setDegraded(false);

        return result;
    }

    private RateLimiterResult buildFailOpenResult() {
        RateLimiterResult result = new RateLimiterResult();
        result.setAllowed(true);
        result.setMaxRequests(maxRequests);
        result.setRemainingRequests(maxRequests);
        result.setWindowSeconds(windowSeconds);
        result.setRetryAfterSeconds(0);
        result.setDegraded(true);
        return result;
    }

    private String fingerprint(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return "unknown";
        }
        return Integer.toHexString(Math.abs(clientId.hashCode()));
    }

}
