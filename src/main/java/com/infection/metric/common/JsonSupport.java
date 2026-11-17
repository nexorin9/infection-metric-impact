package com.infection.metric.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 全局 JSON 读写口。落盘一律走 AtomicFiles 的原子写。 */
public final class JsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonSupport() {}

    public static ObjectMapper mapper() { return MAPPER; }
    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    public static ArrayNode array() { return MAPPER.createArrayNode(); }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new AppException("E_DATA_003", "JSON 解析失败：" + e.getMessage(), e);
        }
    }

    public static JsonNode read(Path path) {
        try {
            return MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AppException("E_DATA_003", "读取 JSON 文件失败：" + path + "：" + e.getMessage(), e);
        }
    }

    public static JsonNode readResource(String resource) {
        try (InputStream in = JsonSupport.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AppException("E_DATA_003", "内置资源缺失：" + resource);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new AppException("E_DATA_003", "读取内置资源失败：" + resource, e);
        }
    }

    public static String compact(JsonNode node) { return node.toString(); }

    public static String pretty(JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            throw new AppException("E_DATA_003", "JSON 序列化失败", e);
        }
    }

    public static void writeAtomic(Path path, JsonNode node) {
        AtomicFiles.writeString(path, pretty(node));
    }

    public static void writeAtomicCompact(Path path, JsonNode node) {
        AtomicFiles.writeString(path, compact(node));
    }

    public static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) out.add(n.asText());
        }
        return out;
    }

    public static ObjectNode deepCopyObject(ObjectNode src) { return src.deepCopy(); }
}
