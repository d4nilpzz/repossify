package dev.d4nilpzz.middleware;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

public class RateLimitMiddleware {

    private final Cache<String, RequestCounter> requestCounters;
    private final int maxRequests;
    private final long windowMs;

    public RateLimitMiddleware(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
        this.requestCounters = Caffeine.newBuilder()
                .expireAfterWrite(windowMs, TimeUnit.MILLISECONDS)
                .build();
    }

    public Handler getHandler() {
        return ctx -> {
            String ip = ctx.ip();
            long now = System.currentTimeMillis();

            RequestCounter counter = requestCounters.get(ip, k -> new RequestCounter(now));
            synchronized (counter) {
                if (now - counter.startTime > windowMs) {
                    counter.startTime = now;
                    counter.count = 0;
                }

                if (counter.count >= maxRequests) {
                    ctx.status(429).result("Too many requests");
                    return;
                }

                counter.count++;
            }
        };
    }

    private static class RequestCounter {
        long startTime;
        int count;

        RequestCounter(long startTime) {
            this.startTime = startTime;
            this.count = 0;
        }
    }
}

