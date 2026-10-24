/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.mapgen.service;

import com.holzyn.actor.common.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 输出的 JSON 读取 + <b>截断抢救</b>。
 *
 * <p><b>为什么需要「抢救」</b>：一次真机跑就撞上了——S2 要 12~22 条资产类型，模型写到一半
 * 输出到上限被截断，整段 JSON 直接不可解析（{@code Unexpected end-of-input: expected close
 * marker for Array}）。如果这时整份回落兜底，等于白花一次调用、还退化成「内置海岛资产库」。
 * 但事实上<b>前 15 条是完整的</b>：只要把「已经闭合的对象」逐个捞出来，就能拿到一份可用的
 * 类型库——残缺的最后一条丢掉即可。</p>
 *
 * <p>实现的取舍：不引入 JSON 修复库（那类库会猜，猜错会造出更离谱的数据），只做一件明确的事——
 * 从指定数组字段的 {@code [} 开始扫描，按 <b>括号深度 + 字符串状态（含转义）</b> 切出所有
 * 已闭合的顶层对象。这是确定性算法，不会「猜出」内容，只丢掉没写完的部分。</p>
 */
@Slf4j
final class MapGenAiJson {

    /** 解析器（与全项目一致：Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MapGenAiJson() {
    }

    /**
     * 读取 AI 输出为 JSON 树：先走正常解析（容忍 Markdown 代码块），失败再抢救截断的数组。
     *
     * @param content    AI 原文
     * @param arrayField 目标数组字段名（抢救时用；单对象输出传 null）
     * @return JSON 树（抢救成功时只含该数组字段）
     * @throws Exception 完全无法解析（由调用方回落确定性兜底）
     */
    static JsonNode read(String content, String arrayField) throws Exception {
        return readMulti(content, arrayField == null ? new String[0] : new String[]{arrayField});
    }

    /**
     * 读取 AI 输出为 JSON 树，支持<b>多个</b>数组字段一起抢救。
     *
     * <p>为什么需要多字段：S3 的拓扑是 {@code {"nodes":[…],"roads":[…]}} 两个数组，
     * 截断通常发生在 {@code roads} 里——只按单字段抢救会丢掉前面的 {@code nodes}，
     * 结果「边全引用不存在的节点」被整批判非法、退化成兜底拓扑。宁可节点和路各捞一半，
     * 也好过整份丢弃。</p>
     *
     * @param content     AI 原文
     * @param arrayFields 需要抢救的数组字段名（空数组 = 不抢救）
     * @return JSON 树（抢救成功时只含被抢救的字段）
     * @throws Exception 完全无法解析（由调用方回落确定性兜底）
     */
    static JsonNode readMulti(String content, String... arrayFields) throws Exception {
        String json = JsonUtil.extractJson(content);
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            if (arrayFields == null || arrayFields.length == 0) {
                throw e;
            }
            StringBuilder sb = new StringBuilder("{");
            int salvaged = 0;
            for (String field : arrayFields) {
                List<String> objects = completeObjects(json, field);
                if (objects.isEmpty()) {
                    continue;
                }
                if (salvaged > 0) {
                    sb.append(',');
                }
                sb.append('"').append(field).append("\":[");
                for (int i = 0; i < objects.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(objects.get(i));
                }
                sb.append(']');
                salvaged++;
                log.warn("[mapgen] AI 输出被截断，已抢救出 {} 个完整对象（{}）", objects.size(), field);
            }
            if (salvaged == 0) {
                throw e;
            }
            sb.append('}');
            return MAPPER.readTree(sb.toString());
        }
    }

    /**
     * 从（可能被截断的）JSON 文本里切出指定数组字段下<b>已闭合</b>的顶层对象。
     *
     * @param json       文本
     * @param arrayField 数组字段名
     * @return 完整的对象文本列表（可能为空）
     */
    static List<String> completeObjects(String json, String arrayField) {
        List<String> out = new ArrayList<>();
        int start = arrayStart(json, arrayField);
        if (start < 0) {
            return out;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int objStart = -1;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> {
                    if (depth == 0) {
                        objStart = i;
                    }
                    depth++;
                }
                case '}' -> {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        out.add(json.substring(objStart, i + 1));
                        objStart = -1;
                    } else if (depth < 0) {
                        // 数组提前闭合（结构异常）：停止，保留已捞到的
                        return out;
                    }
                }
                case ']' -> {
                    if (depth == 0) {
                        return out;
                    }
                }
                default -> {
                    // 其它字符不改变状态
                }
            }
        }
        return out;
    }

    /**
     * 定位数组字段的 {@code [} 位置（先找 {@code "field"}，再找其后的第一个 {@code [}）。
     *
     * @param json       文本
     * @param arrayField 字段名
     * @return 位置；找不到返回 -1
     */
    private static int arrayStart(String json, String arrayField) {
        String key = "\"" + arrayField + "\"";
        int k = json.indexOf(key);
        if (k < 0) {
            return -1;
        }
        return json.indexOf('[', k + key.length());
    }
}
