package com.infection.metric.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.infection.metric.common.Hashes;

import java.util.LinkedHashMap;
import java.util.Map;

/** 读取端点本地缓存：键 = 端点 + 查询参数 + 数据源版本，避免重复取数。 */
public final class LocalReadCache {
    private final Map<String, JsonNode> cache = new LinkedHashMap<>();
    private int hits;
    private int misses;

    public String key(String endpoint, Map<String, String> query, String sourceVersion) {
        StringBuilder sb = new StringBuilder(endpoint);
        new java.util.TreeMap<>(query).forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        sb.append('|').append(sourceVersion);
        return Hashes.sha256(sb.toString());
    }

    public boolean has(String key) { return cache.containsKey(key); }
    public JsonNode get(String key) { return cache.get(key); }
    public void put(String key, JsonNode value) { cache.put(key, value); }

    /** 命中直接返回；未命中才执行取数函数。 */
    public JsonNode getOrFetch(String key, java.util.function.Supplier<JsonNode> fetcher) {
        JsonNode cached = cache.get(key);
        if (cached != null) { hits++; return cached; }
        misses++;
        JsonNode fresh = fetcher.get();
        cache.put(key, fresh);
        return fresh;
    }

    public int hitCount() { return hits; }
    public int missCount() { return misses; }
    public int size() { return cache.size(); }
    public void clear() { cache.clear(); }
}
