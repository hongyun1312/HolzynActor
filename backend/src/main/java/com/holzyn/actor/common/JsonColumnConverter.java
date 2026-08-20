package com.holzyn.actor.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON 列字符串 JPA 属性转换器。
 * <p>职责：解决本地 H2（MODE=MySQL）下「JSON 列读取双重编码」问题——
 * 实体里声明为 columnDefinition = "json" 的 String 字段，在 H2 里写入时会被解析为 JSON 值存储；
 * 但 JDBC 读回（getString）时，H2 会把 JSON 值以「JSON 字符串字面量」形式返回，
 * 即 {@code {"identity":...}} 被读成 {@code "{\"identity\":...}"}（多了一层引号与转义）。
 * 前端 JSON.parse 或后端 readTree 解析后会得到【字符串】而非【对象】，导致
 * 角色卡结构化卡空白、Prompt 渲染降级、行动/世界演化/群聊/导入导出解析缺失等一连串问题。</p>
 * <p>转换规则：</p>
 * <ul>
 *   <li>入库（convertToDatabaseColumn）：原样返回单层 JSON 文本（H2 自动解析为 JSON 值存储）；</li>
 *   <li>读库（convertToEntityAttribute）：若读回文本整体是合法 JSON 且根节点为文本节点
 *       （即被 JSON 字符串包了一层的「双重编码」特征），解包一层返回真正的 JSON 文本；
 *       否则原样返回（兼容 MySQL 原生 JSON 列的单层读取，以及非法/旧数据的容错）。
 *       由于修复前导出的 .holzyn 文件可能已携带一层「字符串字面量」包裹，读库时循环解包
 *       （上限 5 层），直到根节点不是文本节点或内层文本不再是 JSON 形状为止。</li>
 * </ul>
 * <p>2026-08-18 引入：应用于 actor_character_card.persona_json 等全部 JSON 列。</p>
 * <p>所属模块：common（通用组件）</p>
 */
@Converter
public class JsonColumnConverter implements AttributeConverter<String, String> {

    /** 静态解析器（转换器非 Spring Bean，需自行持有；Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 循环解包上限：H2 每次读回包一层；历史 .holzyn 导入数据可能再多一层，5 层足够防死循环 */
    private static final int MAX_UNWRAP = 5;

    /**
     * 入库：保持单层 JSON 文本。
     * <p>H2 的 JSON 列会自行把文本解析为 JSON 值存储；MySQL 原生 JSON 列同理。
     * 不做任何改写，避免破坏已存储的数据格式。</p>
     *
     * @param attribute 实体侧的单层 JSON 文本
     * @return 原样返回
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute;
    }

    /**
     * 读库：循环解包 H2 JSON 列的「双重编码」。
     * <p>若根节点是文本节点（H2 JSON 列读回特征），说明值被 JSON 字符串包了一层，
     * 取出其文本内容；若内层文本仍是 JSON 形状（对象/数组/字符串字面量），继续解包，
     * 直到根节点不是文本节点为止。返回调用方期望的单层 JSON 文本。</p>
     *
     * @param dbData 数据库读回的原始文本
     * @return 归一化后的 JSON 文本
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }
        String current = dbData;
        for (int i = 0; i < MAX_UNWRAP; i++) {
            try {
                JsonNode node = MAPPER.readTree(current);
                // 根节点不是文本节点：已是单层 JSON（对象/数组）或非法值 → 原样返回
                if (!node.isTextual()) {
                    return current;
                }
                String inner = node.asText();
                // 内层文本必须是 JSON 形状才继续解包，防止对合法的短字符串值过度解包
                String trimmed = inner.trim();
                if (trimmed.isEmpty()
                        || !(trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\""))) {
                    return current;
                }
                current = inner;
            } catch (Exception e) {
                // 非法 JSON（旧数据/异常值）：原样返回，交由调用方容错处理
                return current;
            }
        }
        return current;
    }
}
