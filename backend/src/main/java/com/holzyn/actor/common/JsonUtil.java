package com.holzyn.actor.common;

/**
 * JSON 文本提取工具。
 * <p>职责：从模型输出中提取合法 JSON 文本（剥离 Markdown 代码块 / 首尾说明文字），
 * 供行动决策、群聊发言人评估、世界事件生成等结构化输出场景复用。</p>
 * <p>所属模块：common（通用组件）</p>
 */
public final class JsonUtil {

    /** 工具类禁止实例化 */
    private JsonUtil() {
    }

    /**
     * 从模型输出中提取 JSON 对象文本。
     * <p>处理顺序：去首尾空白 → 剥离 ```json 代码块 → 截取首个 { 到最后一个 } 之间的内容。</p>
     *
     * @param content 模型输出文本
     * @return 提取后的 JSON 文本（无可用 JSON 时返回 null）
     */
    public static String extractJson(String content) {
        if (content == null) return null;
        String text = content.trim();
        if (text.isEmpty()) return null;
        // 剥离 ```json ... ``` 代码块
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) text = text.substring(firstNewline + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
            text = text.trim();
        }
        // 兼容首尾有说明文字的情况：以 [ 开头按 JSON 数组处理（截取首个 [ 到最后一个 ]），
        // 否则按 JSON 对象处理（截取首个 { 到最后一个 }）
        if (text.startsWith("[")) {
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) {
                text = text.substring(start, end + 1);
            }
        } else {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                text = text.substring(start, end + 1);
            } else {
                // 没有 JSON 对象但可能是数组（如说明文字后跟 JSON 数组）：回退按数组截取
                int s2 = text.indexOf('[');
                int e2 = text.lastIndexOf(']');
                if (s2 >= 0 && e2 > s2) {
                    text = text.substring(s2, e2 + 1);
                }
            }
        }
        return text;
    }

    /**
     * 从 JSON 文本中提取指定顶层字段的字符串值。
     * <p>用于在无需完整反序列化时快速取单个字段（解析失败返回 null，不抛异常）。</p>
     *
     * @param json  合法 JSON 对象文本
     * @param field 顶层字段名
     * @return 字段字符串值（字段缺失/非文本/解析失败返回 null）
     */
    public static String extractField(String json, String field) {
        if (json == null || field == null) {
            return null;
        }
        try {
            tools.jackson.databind.JsonNode node = new tools.jackson.databind.ObjectMapper().readTree(json);
            tools.jackson.databind.JsonNode v = node.get(field);
            return v == null || v.isNull() ? null : v.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
