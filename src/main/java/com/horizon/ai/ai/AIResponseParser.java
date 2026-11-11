package com.horizon.ai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.horizon.ai.analyzer.HealthReport;
import com.horizon.ai.optimizer.ActionValidator.ProposedAction;
import com.horizon.ai.utils.Severity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chuyển phản hồi AI thành HealthReport có kiểm tra schema.
 * Phản hồi sai định dạng bị từ chối hoàn toàn; không có "đoán" nội dung.
 */
public final class AIResponseParser {

    private static final int MAX_LIST = 20;
    private static final int MAX_TEXT = 600;

    private final ObjectMapper mapper;

    public AIResponseParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public HealthReport parse(String content, long nowMillis) throws IOException {
        JsonNode root = mapper.readTree(content);
        if (!root.isObject()) {
            throw new IOException("Phản hồi AI không phải JSON object");
        }
        Severity overall = Severity.parse(root.path("severity").asText(null), null);
        if (overall == null) {
            throw new IOException("Thiếu hoặc sai trường severity");
        }
        String summary = clip(root.path("summary").asText(""));

        List<String> causes = strings(root.path("causes"));
        List<String> recs = strings(root.path("recommendations"));

        Map<String, Boolean> confirmed = new HashMap<>();
        for (JsonNode m : iterable(root.path("machines"))) {
            String id = m.path("id").asText(null);
            if (id != null && m.path("causal").isBoolean()) {
                confirmed.put(clip(id), m.path("causal").asBoolean());
            }
        }

        List<ProposedAction> actions = new ArrayList<>();
        for (JsonNode a : iterable(root.path("actions"))) {
            String type = a.path("type").asText(null);
            if (type == null) {
                continue;
            }
            String machineId = a.path("machine_id").isMissingNode() || a.path("machine_id").isNull()
                    ? null : clip(a.path("machine_id").asText());
            actions.add(new ProposedAction(clip(type), machineId, clip(a.path("reason").asText(""))));
            if (actions.size() >= MAX_LIST) {
                break;
            }
        }

        return new HealthReport(overall, "AI", summary, causes, recs, actions,
                Map.copyOf(confirmed), nowMillis);
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : iterable(arr)) {
            if (n.isTextual()) {
                out.add(clip(n.asText()));
            }
            if (out.size() >= MAX_LIST) {
                break;
            }
        }
        return out;
    }

    private static Iterable<JsonNode> iterable(JsonNode n) {
        return n.isArray() ? n : List.of();
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "…" : s;
    }
}
