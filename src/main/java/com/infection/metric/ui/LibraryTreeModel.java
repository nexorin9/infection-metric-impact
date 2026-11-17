package com.infection.metric.ui;

import com.infection.metric.metriclib.MetricStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 口径库树纯逻辑模型：按 indicator_code 分组，组内列出各版本与状态徽标。
 *
 * <p>支持按状态过滤与关键字搜索（命中指标编码 / 标题 / 版本号）；空态与加载态给出明确引导文案。
 * 本类不创建任何 Swing 组件，headless 测试直接断言分组、过滤、搜索与空态。</p>
 */
public final class LibraryTreeModel {

    /** 空库引导：先导入上级口径包或新建口径。 */
    public static final String EMPTY_HINT = "暂无本地口径条目。请先导入上级口径包或新建口径。";
    /** 加载态进度提示。 */
    public static final String LOADING_HINT = "正在加载口径库，请稍候…";
    /** 过滤 / 搜索后无匹配的提示。 */
    public static final String NO_MATCH_HINT = "没有符合当前状态过滤或搜索条件的口径，请调整筛选条件。";

    private final List<UiMetricItem> all;
    private Set<MetricStatus> statusFilter = Collections.emptySet();
    private String keyword = "";

    public LibraryTreeModel(List<UiMetricItem> items) {
        this.all = List.copyOf(items == null ? List.of() : items);
    }

    public boolean isEmpty() { return all.isEmpty(); }
    public int totalCount() { return all.size(); }
    public String emptyHint() { return EMPTY_HINT; }
    public String loadingHint() { return LOADING_HINT; }
    public String noMatchHint() { return NO_MATCH_HINT; }

    /** 设置状态过滤（空表示不过滤）；null 元素被忽略。 */
    public void setStatusFilter(MetricStatus... statuses) {
        Set<MetricStatus> s = new LinkedHashSet<>();
        if (statuses != null) {
            for (MetricStatus st : statuses) {
                if (st != null) s.add(st);
            }
        }
        this.statusFilter = s;
    }

    public void clearStatusFilter() { this.statusFilter = Collections.emptySet(); }
    public void setKeyword(String kw) { this.keyword = kw == null ? "" : kw.trim(); }
    public void clearFilters() { clearStatusFilter(); setKeyword(""); }

    /** 全量分组（不受过滤影响）。 */
    public List<Group> groups() { return group(all); }

    /** 按单一状态过滤后的分组。 */
    public List<Group> groupsByStatus(MetricStatus status) {
        return group(filtered(Set.of(status), ""));
    }

    /** 按一组状态过滤后的分组。 */
    public List<Group> groupsByStatus(Set<MetricStatus> statuses) {
        return group(filtered(statuses, ""));
    }

    /** 按关键字搜索后的分组（命中编码 / 标题 / 版本号）。 */
    public List<Group> search(String kw) {
        return group(filtered(Collections.emptySet(), kw == null ? "" : kw.trim()));
    }

    /** 应用当前状态过滤 + 关键字后的可见分组，供树视图渲染。 */
    public List<Group> visibleGroups() {
        return group(filtered(statusFilter, keyword));
    }

    public int visibleCount() {
        return filtered(statusFilter, keyword).size();
    }

    private List<UiMetricItem> filtered(Set<MetricStatus> statuses, String kw) {
        List<UiMetricItem> out = new ArrayList<>();
        for (UiMetricItem it : all) {
            if (statuses != null && !statuses.isEmpty() && !statuses.contains(it.getStatus())) {
                continue;
            }
            if (!kw.isEmpty() && !hit(it, kw)) {
                continue;
            }
            out.add(it);
        }
        return out;
    }

    private static boolean hit(UiMetricItem it, String kw) {
        String lower = kw.toLowerCase();
        return it.getIndicatorCode().toLowerCase().contains(lower)
                || it.getTitle().toLowerCase().contains(lower)
                || it.getDefinitionVersion().toLowerCase().contains(lower);
    }

    private static List<Group> group(List<UiMetricItem> items) {
        Map<String, List<UiMetricItem>> byCode = new TreeMap<>();
        for (UiMetricItem it : items) {
            byCode.computeIfAbsent(it.getIndicatorCode(), k -> new ArrayList<>()).add(it);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<UiMetricItem>> e : byCode.entrySet()) {
            List<UiMetricItem> versions = e.getValue();
            versions.sort((a, b) -> a.getDefinitionVersion().compareTo(b.getDefinitionVersion()));
            groups.add(new Group(e.getKey(), List.copyOf(versions)));
        }
        return groups;
    }

    /** 状态徽标文本，直接用状态枚举名（DRAFT / PENDING_REVIEW / ...）。 */
    public static String statusBadgeText(MetricStatus status) {
        return status == null ? "DRAFT" : status.name();
    }

    /** 窄栏视图用的中文短标；模型层仍以枚举名为准，避免长枚举名在树里被截断。 */
    public static String statusBadgeShort(MetricStatus status) {
        if (status == null) return "草稿";
        return switch (status) {
            case DRAFT -> "草稿";
            case PENDING_REVIEW -> "待复核";
            case PUBLISHED -> "已发布";
            case REJECTED -> "已驳回";
            case RETIRED -> "已停用";
        };
    }

    /** 状态徽标语义色（取自 M8-A token），五种状态各有区分。 */
    public static int statusBadgeColor(MetricStatus status) {
        if (status == null) return UiTokens.TEXT_MUTED;
        return switch (status) {
            case DRAFT -> UiTokens.TEXT_MUTED;
            case PENDING_REVIEW -> UiTokens.WARNING;
            case PUBLISHED -> UiTokens.SUCCESS;
            case REJECTED -> UiTokens.DANGER;
            case RETIRED -> UiTokens.BORDER;
        };
    }

    /** 一个 indicator_code 分组，含该指标的全部版本。 */
    public static final class Group {
        private final String code;
        private final List<UiMetricItem> items;
        public Group(String code, List<UiMetricItem> items) {
            this.code = code;
            this.items = items;
        }
        public String getCode() { return code; }
        public List<UiMetricItem> getItems() { return items; }
        public int getVersionCount() { return items.size(); }
    }
}
