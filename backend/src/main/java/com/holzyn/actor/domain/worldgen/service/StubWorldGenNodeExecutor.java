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

package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.domain.worldgen.model.WorldGenStepType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桩执行器（占位实现，支持 agent + script 两类节点）。
 * <p>职责： 阶段「空跑节点链」——不调用 AI、不写文件，返回<b>确定性占位 JSON</b>
 * （把填充后的输入关键字段与上游产物引用回显），让任务链、SSE 进度、查询/重试/跳过端点
 * 可在无 AI 成本下完整验证； 由真实 Agent/Script 执行器替换。</p>
 * <p>模拟失败钩子：任务输入 {@code simulateFailNodeIds}（逗号分隔的节点 ID 列表）命中当前节点时
 * 返回 failed（错误信息含节点 ID），供运行时冒烟验证「失败节点原地续跑（retry/skip + CAS）」。</p>
 */
@Component
@Slf4j
public class StubWorldGenNodeExecutor implements WorldGenNodeExecutor {

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 桩执行器同时支持 agent 与 script 两类节点。
     *
     * @return 不支持按类型细分（两类均由本执行器处理），返回 null 表示全类型兜底
     */
    @Override
    public String supportedNodeType() {
        // null=通用兜底：Runner 未找到精确类型执行器时使用
        return null;
    }

    /**
     * 桩执行：agent 返回占位维度 JSON，script 返回占位脚本 JSON；命中 simulateFailNodeIds 则失败。
     *
     * @param ctx 节点执行上下文
     *
     * @return 占位成功/模拟失败结果
     */
    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        long start = System.currentTimeMillis();
        try {
            if (ctx.simulateFailNodeIds() != null && ctx.simulateFailNodeIds().contains(ctx.node().getId())) {
                String msg = "桩执行器模拟失败（simulateFailNodeIds 命中）：node=" + ctx.node().getId();
                log.info("[worldgen] {}", msg);
                return NodeExecutionResult.failure(msg);
            }
            String output;
            if (ctx.isAgent()) {
                output = buildAgentPlaceholder(ctx);
            } else {
                output = buildScriptPlaceholder(ctx);
            }
            long ms = System.currentTimeMillis() - start;
            log.info("[worldgen] 桩执行成功：task={}, node={}, type={}, {}ms",
                    ctx.taskId(), ctx.node().getId(), ctx.node().getNodeType(), ms);
            return NodeExecutionResult.success(output, ms);
        } catch (Exception e) {
            return NodeExecutionResult.failure("桩执行器异常：" + e.getMessage());
        }
    }

    /**
     * 构造 agent 节点占位输出 JSON（确定性：回显维度/题材/语言/上游维度引用）。
     *
     * @param ctx 节点执行上下文
     *
     * @return 占位输出 JSON 文本
     */
    private String buildAgentPlaceholder(NodeExecutionContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stub", true);
        out.put("dimension", ctx.node().getLabel());
        out.put("nodeId", ctx.node().getId());
        out.put("premise", ctx.taskInputs().getOrDefault("premise", ""));
        out.put("genre", ctx.taskInputs().getOrDefault("genre", ""));
        out.put("language", ctx.taskInputs().getOrDefault("language", "中文"));
        out.put("upstreamDimensions", new ArrayList<>(ctx.upstreamOutputs().keySet()));
        out.put("outputVariable", ctx.node().getOutputVariable());
        out.put("outputFilePath", ctx.node().getOutputFilePath());
        return writeJson(out);
    }

    /**
     * 构造 script 节点占位输出 JSON（确定性：回显脚本名/填充后的参数）。
     *
     * @param ctx 节点执行上下文
     *
     * @return 占位输出 JSON 文本
     */
    private String buildScriptPlaceholder(NodeExecutionContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stub", true);
        out.put("script", ctx.node().getScriptName());
        out.put("scriptArgs", ctx.filledInput());
        out.put("referencedFiles", collectReferencedFiles(ctx));
        out.put("ran", true);
        return writeJson(out);
    }

    /**
     * 收集 script 节点输入的产物文件引用。
     *
     * @param ctx 节点执行上下文
     *
     * @return 文件路径列表
     */
    private List<String> collectReferencedFiles(NodeExecutionContext ctx) {
        List<String> files = new ArrayList<>();
        for (String v : ctx.upstreamOutputs().values()) {
            String p = extractField(v, "outputFilePath");
            if (p != null && !p.isBlank()) {
                files.add(p);
            }
        }
        return files;
    }

    /**
     * 从 JSON 文本提取顶层字段（容错返回 null）。
     *
     * @param json JSON 文本
     *
     * @param field 字段名
     *
     * @return 字段文本值
     */
    private String extractField(String json, String field) {
        if (json == null) {
            return null;
        }
        try {
            tools.jackson.databind.JsonNode v = MAPPER.readTree(json).get(field);
            return v == null || v.isNull() ? null : v.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * JSON 序列化（失败兜底返回空对象文本）。
     *
     * @param obj 对象
     *
     * @return JSON 文本
     */
    private String writeJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
