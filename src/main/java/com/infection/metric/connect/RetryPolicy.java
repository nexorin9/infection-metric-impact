package com.infection.metric.connect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 指数退避重试：上限 3 次，记录每次重试次数与间隔；4xx 不重试。 */
public final class RetryPolicy {

    public static final int DEFAULT_MAX_RETRIES = 3;

    /** 测试可注入以避免真实等待。 */
    @FunctionalInterface
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private final int maxRetries;
    private final long baseDelayMillis;
    private final Sleeper sleeper;
    private final List<Long> intervals = new ArrayList<>();

    public RetryPolicy() { this(DEFAULT_MAX_RETRIES, 50L, millis -> Thread.sleep(millis)); }
    public RetryPolicy(int maxRetries) { this(maxRetries, 50L, millis -> Thread.sleep(millis)); }
    public RetryPolicy(int maxRetries, Sleeper sleeper) { this(maxRetries, 50L, sleeper); }

    public RetryPolicy(int maxRetries, long baseDelayMillis, Sleeper sleeper) {
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
        this.sleeper = sleeper;
    }

    public int getMaxRetries() { return maxRetries; }

    /** 第 n 次重试（从 1 起）的间隔：base * 2^(n-1)。 */
    public long delayFor(int attempt) {
        return baseDelayMillis * (1L << Math.max(0, attempt - 1));
    }

    public void waitBeforeRetry(int attempt) {
        long delay = delayFor(attempt);
        intervals.add(delay);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试等待被中断", e);
        }
    }

    public List<Long> getIntervals() { return Collections.unmodifiableList(intervals); }
    public int retryCount() { return intervals.size(); }
}
