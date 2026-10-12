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

package com.holzyn.actor.domain.worldgen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流世界观生成模块配置。
 * <p>职责：集中承载世界生成工作流的开关与参数，供真实 Agent 执行器 / 脚本执行器读取：
 * 总开关、真实 AI vs 桩执行、维度 AI 输出上限、Schema 校验失败定向重试次数、检查点文件落盘开关、
 * 并行执行并行度、character 工作流角色阵容上限。</p>
 * <p>⚠️ maxTokens 说明（_05 工作流世界观生成）：默认由 4096 抬到 <b>8192</b>，
 * 与场景路由分档 {@code holzyn.actor.ai.routing.tiers.tool.max-tokens=8192}（worldgen → tool 类别）
 * 对齐——旧默认 4096 对复杂世界维度（多表字段 JSON，如空间地理 6 字段）存在截断风险，
 * Schema 校验失败→定向重试只能兜底不能保证成功；执行器对输出上限还有 8192 硬兜底
 */
@Data
@Component
@ConfigurationProperties(prefix = "holzyn.actor.worldgen")
public class WorldGenProperties {

    /** 世界生成工作流总开关（默认 true；false=真实执行器回退桩行为，供无 AI/测试环境空跑） */
    private boolean enabled = true;

    /** 真实 AI 执行开关（默认 true；false=Agent/脚本节点全部走桩占位输出，空跑冒烟/离线环境） */
    private boolean aiEnabled = true;

    /**
     * 每维度 AI 输出最大 token（默认 8192，对齐 routing tool 档位；不足时按 8192 硬兜底）。
     * <p>_05 适配：由 4096 抬到 8192（复杂维度多表 JSON 防截断），env 逃生门
     * {@code HOLOZYN_ACTOR_WORLDGEN_MAX_TOKENS}（连字符删除规则）。</p>
     */
    private int maxTokens = 8192;

    /** Schema 校验失败后的定向重试次数（默认 2：总尝试 = schemaRetry + 1，首调用再修 2 次） */
    private int schemaRetry = 2;

    /** 维度 Agent 采样温度（默认 0.7，对齐 agents.json 的 model_params） */
    private double temperature = 0.7;

    /** 检查点文件落盘开关（默认 true：Agent 产物写 data/worldgen/&lt;project&gt;/&lt;task&gt;/world/*.json） */
    private boolean checkpointEnabled = true;

    /**
     * 并行执行并行度上限（并行维度优化，默认 3：前 3 维 corelaws/spacetime/society 并行；
     * 0=串行回退）。env 逃生门 {@code HOLOZYN_ACTOR_WORLDGEN_PARALLELISM}。
     */
    private int parallelism = 3;

    /**
     * character 工作流角色阵容上限（character 工作流，默认 20：AI 自动定阵容时截断；
     * 冒烟可调小如 4~6 缩短链路）。env 逃生门 {@code HOLOZYN_ACTOR_WORLDGEN_ROSTER_LIMIT}。
     */
    private int rosterLimit = 20;

    /**
     * story-plan 工作流角色摘要注入上限（story-plan 工作流， _06，默认 12：
     * 故事引擎节点的「角色档案」输入按重要度排序后截断注入，防提示词膨胀）。
     * env 逃生门 {@code HOLOZYN_ACTOR_WORLDGEN_STORY_CHARACTER_LIMIT}（连字符删除规则）。
     */
    private int storyCharacterLimit = 12;
}
