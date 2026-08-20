package com.holzyn.actor.domain.memory.service;

import com.holzyn.actor.common.JsonUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆 AI 输出解析器（P4-1 长期记忆，纯逻辑可单测）。
 * <p>职责：把 AI 调用（memory_extract / memory_summarize 模板，json_object 模式）返回的文本
 * 解析为结构化记忆数据——抽取解析为「新事实数组」，摘要解析为「摘要文本」；
 * 兼容 Markdown 代码块/首尾说明文字（复用 JsonUtil），字段缺失时给默认值，非法内容安全丢弃。</p>
 * <p>所属模块：service/memory（记忆子域）</p>
 */
public final class MemoryExtractParse {

    /** Jackson 解析器（线程安全，静态复用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具类禁止实例化 */
    private MemoryExtractParse() {
    }

    /**
     * 解析记忆抽取 AI 输出：期望 JSON 数组 [{kind, content, importance}]。
     * <p>兼容对象包装（{"memories":[...]}）；content 缺失/空白条目丢弃；
     * kind 缺省 fact；importance 缺省 1 并钳制到 1~5。</p>
     *
     * @param aiText AI 输出文本（可为 null/非法/空数组）
     * @return 解析出的记忆条目列表（非法或空返回空列表）
     */
    public static List<ExtractedMemory> parseExtract(String aiText) {
        List<ExtractedMemory> out = new ArrayList<>();
        if (aiText == null || aiText.isBlank()) {
            return out;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            // 兼容 { "memories": [...] } 对象包装：取 memories 数组
            if (root.isObject()) {
                root = root.path("memories");
            }
            if (!root.isArray()) {
                return out;
            }
            for (JsonNode item : root) {
                if (item == null || !item.isObject()) {
                    continue;
                }
                String content = item.path("content").asText("");
                if (content.isBlank()) {
                    continue;
                }
                String kind = item.path("kind").asText("fact");
                if (!"fact".equals(kind) && !"summary".equals(kind)) {
                    kind = "fact";
                }
                int importance = item.path("importance").asInt(1);
                // 钳制重要度到 1~5（超出视为模型异常输出，按边界处理）
                importance = Math.max(1, Math.min(5, importance));
                out.add(new ExtractedMemory(kind, content.trim(), importance));
            }
        } catch (Exception e) {
            // 解析失败：返回已解析部分（若部分成功），整体失败返回空
            return out.isEmpty() ? out : out;
        }
        return out;
    }

    /**
     * 解析会话摘要 AI 输出：期望 JSON 对象 {content: "..."}。
     *
     * @param aiText AI 输出文本（可为 null/非法）
     * @return 摘要文本（解析失败或 content 空白返回 null）
     */
    public static String parseSummarize(String aiText) {
        if (aiText == null || aiText.isBlank()) {
            return null;
        }
        String json = JsonUtil.extractJson(aiText);
        if (json == null) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                return null;
            }
            String content = root.path("content").asText("");
            return content.isBlank() ? null : content.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析后的记忆条目。
     *
     * @param kind       类型（fact/summary）
     * @param content    记忆内容
     * @param importance 重要度（1~5）
     */
    public record ExtractedMemory(String kind, String content, Integer importance) {
    }
}
