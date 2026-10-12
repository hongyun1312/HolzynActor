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

package com.holzyn.actor.ai.nanojev;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * NanoJev evaluate 请求（官方契约 {@code {"states":[...]}} 的唯一承载）。
 * <p>序列化要点：顶层只能 {@code states}；
 * 每个 question 只允许 {@code type/instructions/criteria} 三字段（白名单之外多一字段即 400）；
 * choice 的 criteria 为对象、score 的 criteria 为数组、boolean 的 criteria 为可选的
 * false/true 对象。</p>
 * <p>本类同时提供 <b>paramsSnapshot</b>（states/questions/paths 三轴统计，写入 trace 的
 * detail_json，供「调度记录完整」验收与调试页回溯）与<b>请求体字节数</b>（批量拆分器
 * 2MB 上限分轴依据）。</p>
 *
 * @param states state 列表（非空；上限 32 由批量拆分器保证）
 */
public record NanoJevRequest(List<NanoJevState> states) {

    /** Jackson 3 序列化器（与项目 ai 包一致） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 构造校验：states 非空。 */
    public NanoJevRequest {
        if (states == null || states.isEmpty()) {
            throw new IllegalArgumentException("NanoJev 请求 states 必须非空");
        }
        for (NanoJevState s : states) {
            // 触发各问题构造期校验（type/instructions/criteria 白名单与档位范围）
            for (NanoJevQuestion q : s.questions().values()) {
                if (q == null) {
                    throw new IllegalArgumentException("NanoJev 问题不能为 null");
                }
            }
        }
    }

    /**
     * 序列化为官方契约 JSON 字符串（失败抛 IllegalStateException——调用方按编码错误处理）。
     *
     * @return 请求体 JSON（UTF-8 字节大小 = getBytes(UTF_8).length，供 2MB 上限判断）
     */
    public String toJson() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode statesNode = root.putArray("states");
            for (NanoJevState s : states) {
                ObjectNode so = statesNode.addObject();
                so.put("id", s.id());
                // state 允许字符串 / JSON 对象 / 数组
                if (s.state() instanceof String str) {
                    so.put("state", str);
                } else {
                    so.set("state", MAPPER.valueToTree(s.state()));
                }
                ObjectNode qs = so.putObject("questions");
                for (Map.Entry<String, NanoJevQuestion> e : s.questions().entrySet()) {
                    NanoJevQuestion q = e.getValue();
                    ObjectNode qo = qs.putObject(e.getKey());
                    qo.put("type", q.type());
                    qo.put("instructions", q.instructions());
                    if (NanoJevQuestion.TYPE_CHOICE.equals(q.type())) {
                        ObjectNode c = qo.putObject("criteria");
                        q.criteria().forEach(c::put);
                    } else if (NanoJevQuestion.TYPE_SCORE.equals(q.type())) {
                        ArrayNode arr = qo.putArray("criteria");
                        q.scoreCriteria().forEach(arr::add);
                    } else if (q.criteria() != null) {
                        ObjectNode c = qo.putObject("criteria");
                        q.criteria().forEach(c::put);
                    }
                }
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("NanoJev 请求序列化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 请求体 UTF-8 字节数（批量拆分器按官方 2MB 上限分轴）。
     *
     * @return 字节数
     */
    public int bodyBytes() {
        return toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * paramsSnapshot 文本（调度记录口径，与 LLM 服务端同形）：
     *
     * @return 快照文本
     */
    public String paramsSnapshot() {
        int nStates = states.size();
        int questions = 0;
        int paths = 0;
        for (NanoJevState s : states) {
            questions += s.questionCount();
            paths += s.pathCount();
        }
        return "states=" + nStates + ", questions=" + questions + ", paths=" + paths;
    }

    /**
     * 反序列化（调试页回放/测试用）：把官方请求 JSON 解析回请求对象（宽松：不校验语义）。
     *
     * @param json 官方契约请求体
     *
     * @return 请求对象（解析失败返回 null）
     */
    public static NanoJevRequest fromJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            ArrayNode arr = (ArrayNode) root.get("states");
            java.util.List<NanoJevState> list = new java.util.ArrayList<>();
            for (JsonNode so : arr) {
                Map<String, NanoJevQuestion> qs = new java.util.LinkedHashMap<>();
                JsonNode qn = so.get("questions");
                qn.properties().forEach(en -> {
                    JsonNode qo = en.getValue();
                    String type = qo.path("type").asText();
                    String instructions = qo.path("instructions").asText();
                    JsonNode c = qo.get("criteria");
                    Map<String, String> criteria = null;
                    java.util.List<String> score = null;
                    if (c != null && c.isObject()) {
                        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                        c.properties().forEach(e -> m.put(e.getKey(), e.getValue().asText()));
                        criteria = m;
                    } else if (c != null && c.isArray()) {
                        java.util.List<String> l = new java.util.ArrayList<>();
                        c.forEach(n -> l.add(n.asText()));
                        score = l;
                    }
                    qs.put(en.getKey(), new NanoJevQuestion(type, instructions, criteria, score));
                });
                JsonNode st = so.get("state");
                Object state = st.isTextual() ? st.asText() : MAPPER.treeToValue(st, Object.class);
                list.add(new NanoJevState(so.path("id").asText(), state, qs));
            }
            return new NanoJevRequest(list);
        } catch (Exception e) {
            return null;
        }
    }
}
