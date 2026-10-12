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

import com.holzyn.actor.domain.worldgen.entity.ActorWorldgenWorkflow;
import com.holzyn.actor.domain.worldgen.repository.ActorWorldgenWorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置工作流定义启动初始化（引擎骨架，仿 PromptTemplateService 幂等 upsert；
 * _05 扩展为多工作流：build_v1 + character_v1）。
 * <p>职责：应用启动时把资源文件 {@code worldgen/*.json} 的内置定义
 * （nodes/edges/variables 三段）写入 actor_worldgen_workflow：</p>
 * <ul>
 * <li>缺失 → 插入（enabled=1）；</li>
 * <li>已存在且内容一致 → 跳过；</li>
 * <li>已存在但内容不一致（代码随版本演进）→ 同步更新并版本号 +1
 * （内置定义以代码为准，尚无 UI 编辑入口）。</li>
 * </ul>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WorldGenWorkflowSeeder implements ApplicationRunner {

    /** 内置工作流编码：世界观生成（6 维） */
    public static final String CODE_BUILD_V1 = "worldgen_build_v1";

    /** 内置工作流编码：角色阵容生成 */
    public static final String CODE_CHARACTER_V1 = "worldgen_character_v1";

    /** 内置工作流编码：故事规划生成 */
    public static final String CODE_STORY_PLAN_V1 = "worldgen_story_plan_v1";

    /** 内置工作流资源文件（classpath:worldgen/…，key=编码；顺序=workflows 列表展示顺序） */
    static final Map<String, String> BUILTIN_RESOURCES = new LinkedHashMap<>();

    static {
        BUILTIN_RESOURCES.put(CODE_BUILD_V1, "worldgen/worldgen_build_v1.json");
        BUILTIN_RESOURCES.put(CODE_CHARACTER_V1, "worldgen/worldgen_character_v1.json");
        BUILTIN_RESOURCES.put(CODE_STORY_PLAN_V1, "worldgen/worldgen_story_plan_v1.json");
    }

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorWorldgenWorkflowRepository workflowRepository;

    /**
     * 启动时幂等初始化全部内置工作流定义。
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        for (Map.Entry<String, String> e : BUILTIN_RESOURCES.entrySet()) {
            try {
                seedOne(e.getKey(), e.getValue());
            } catch (Exception ex) {
                // 资源缺失/解析失败不阻断启动（与 OldCrowdTableCleaner/BrainProfileBackfillRunner 失败不阻断一致）
                log.warn("内置工作流定义初始化失败（不阻断启动）: {} : {}", e.getKey(), ex.getMessage());
            }
        }
    }

    /**
     * 幂等 upsert 单个内置工作流定义。
     *
     * @param code 工作流编码
     *
     * @param resource classpath 资源路径
     */
    private void seedOne(String code, String resource) throws Exception {
        String json = new String(new ClassPathResource(resource).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        JsonNode root = MAPPER.readTree(json);
        String name = root.path("name").asText(code);
        int version = root.path("version").asInt(1);
        String nodesJson = MAPPER.writeValueAsString(root.path("nodes"));
        String edgesJson = MAPPER.writeValueAsString(root.path("edges"));
        String variablesJson = MAPPER.writeValueAsString(root.path("variables"));

        ActorWorldgenWorkflow existing = workflowRepository.findByCode(code).orElse(null);
        if (existing == null) {
            ActorWorldgenWorkflow w = new ActorWorldgenWorkflow();
            w.setCode(code);
            w.setName(name);
            w.setVersion(version);
            w.setNodesJson(nodesJson);
            w.setEdgesJson(edgesJson);
            w.setVariablesJson(variablesJson);
            w.setEnabled(1);
            workflowRepository.save(w);
            log.info("初始化内置工作流定义: {} - {}", code, name);
        } else if (!name.equals(existing.getName())
                || !nodesJson.equals(existing.getNodesJson())
                || !edgesJson.equals(existing.getEdgesJson())
                || !variablesJson.equals(existing.getVariablesJson())) {
            existing.setName(name);
            existing.setNodesJson(nodesJson);
            existing.setEdgesJson(edgesJson);
            existing.setVariablesJson(variablesJson);
            existing.setVersion(existing.getVersion() == null ? version : existing.getVersion() + 1);
            workflowRepository.save(existing);
            log.info("同步内置工作流定义内容: {} - {}（版本 → {}）", code, name, existing.getVersion());
        }
    }
}
