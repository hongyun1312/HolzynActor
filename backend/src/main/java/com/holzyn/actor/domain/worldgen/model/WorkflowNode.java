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

package com.holzyn.actor.domain.worldgen.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流节点定义（nodes_json 数组元素的数据模型）。
 * <p>字段对齐 DeterminFlow WorkflowNode 概念（id/label/node_type/agent_type/first_message/
 * output_variable/save_output_to_file/output_file_path/node_params/auto_retry），
 * 由 {@code WorldGenDefinitionParser} 从 JSON 解析；JSON 结构见资源文件
 * {@code worldgen/worldgen_build_v1.json}（自写定义，参考 bishu-novel build 的节点语义）。
 * JSON 键采用<b>驼峰命名与 Java 字段一一对应</b>（项目 Jackson 3 无注解模块，走默认 bean 映射）。</p>
 * <p>两种节点类型共用本模型：</p>
 * <ul>
 * <li>agent：{@link #agentType} 语义化 Agent 名 + {@link #firstMessage} 首条消息模板
 * （含 {@code {{premise}}/{{genre}}/{{language}}/{{上游输出变量}}} 占位符）+ {@link #outputVariable}；</li>
 * <li>script：{@link #scriptName} 脚本名 + {@link #scriptArgs} 参数模板（含占位符）。</li>
 * </ul>
 */
@Data
public class WorkflowNode {

    /** 节点 ID（定义内唯一，如 agent_corelaws / script_merge_world） */
    private String id;

    /** 节点中文名（如 维度一：核心法则） */
    private String label;

    /** 节点类型：agent / script（{@link WorldGenStepType}） */
    private String nodeType;

    /** agent 节点的 Agent 语义名 */
    private String agentType;

    /** agent 节点的首条消息模板 */
    private String firstMessage;

    /** agent 节点的输出变量名（上游节点以此引用本节点产物，如 agent_corelaws_output） */
    private String outputVariable;

    /** 是否将产物写入文件 */
    private Boolean saveOutputToFile = false;

    /** 产物文件相对路径 */
    private String outputFilePath;

    /** script 节点脚本名 */
    private String scriptName;

    /** script 节点参数模板（含 {{占位符}}，如 checkpoint --files world/core_laws.json） */
    private String scriptArgs;

    /** 节点级参数（agent：temperature/max_turns/json_mode 等模型参数；script：timeout 等） */
    private Map<String, Object> nodeParams = new LinkedHashMap<>();

    /** 自动重试次数（默认 0=关；失败后自动重试 N 次再标记节点失败） */
    private Integer autoRetryCount = 0;

    /** 自动重试间隔（秒，默认 0=不等待） */
    private Integer autoRetryIntervalSeconds = 0;

    /** 上游节点产物引用：占位符 key → 输出变量名（由解析器从 first_message/script_args 提取） */
    private List<String> referencedOutputVariables = new ArrayList<>();
}
