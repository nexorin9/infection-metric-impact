package com.infection.metric.connect;

import com.infection.metric.common.OutboundRequest;
import com.infection.metric.common.OutboundResponse;
import com.infection.metric.common.OutboundTransport;

import java.util.ArrayList;
import java.util.List;

/** 契约测试替身：记录请求形状，按预设序列返回响应。产品代码未针对替身特化。 */
final class EndpointTestFakes {

    static final class RecordingTransport implements OutboundTransport {
        final List<OutboundRequest> requests = new ArrayList<>();
        final List<String> fullUrls = new ArrayList<>();
        private final List<OutboundResponse> scripted;
        private int cursor = 0;

        RecordingTransport(OutboundResponse... scripted) { this.scripted = List.of(scripted); }

        @Override public OutboundResponse send(OutboundRequest request) {
            requests.add(request);
            if (fullUrls != null) fullUrls.add(request.fullUrl());
            if (scripted.isEmpty()) return OutboundResponse.of(200, "{}");
            OutboundResponse r = scripted.get(Math.min(cursor, scripted.size() - 1));
            cursor++;
            return r;
        }
        OutboundRequest last() { return requests.get(requests.size() - 1); }
        int count() { return requests.size(); }
    }

    static OutboundClient.RawResult raw(int status, String body) {
        return OutboundClient.RawResult.ok(status, body);
    }

    private EndpointTestFakes() {}
}
