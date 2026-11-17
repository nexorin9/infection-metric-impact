package com.infection.metric.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 模型调用审计表（任务46）：只追加、不改写、不删除；可按 indicator_code 反查全部模型调用。
 *
 * <p>五类错误（E_LLM_001..005）每次调用都会写入一条；降级动作另写一条带
 * {@code degraded=true} 的记录。每条记录含模型名、提示版本、输入哈希、耗时、
 * 是否降级、错误码。</p>
 */
public final class CallAudit {

    private final List<CallMetadata> entries = new ArrayList<>();

    public synchronized void record(CallMetadata metadata) {
        entries.add(Objects.requireNonNull(metadata, "审计记录不可为 null"));
    }

    /** 按指标编码反查该指标的全部模型调用（保持写入顺序）。 */
    public synchronized List<CallMetadata> findByIndicatorCode(String indicatorCode) {
        List<CallMetadata> out = new ArrayList<>();
        for (CallMetadata m : entries) {
            if (Objects.equals(m.getIndicatorCode(), indicatorCode)) {
                out.add(m);
            }
        }
        return List.copyOf(out);
    }

    /** 按错误码筛选（便于「每类错误都有审计」自检）。 */
    public synchronized List<CallMetadata> withErrorCode(String errorCode) {
        List<CallMetadata> out = new ArrayList<>();
        for (CallMetadata m : entries) {
            if (Objects.equals(m.getErrorCode(), errorCode)) {
                out.add(m);
            }
        }
        return List.copyOf(out);
    }

    public synchronized List<CallMetadata> all() { return List.copyOf(entries); }

    public synchronized int size() { return entries.size(); }
}
