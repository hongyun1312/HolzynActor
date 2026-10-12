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

package com.holzyn.actor.domain.agent.prompt;

/**
 * Prompt 分层上下文常量。
 * <p>分层原则（按变化频率从低到高排列，稳定前缀前置以命中服务端前缀缓存）：</p>
 * <ul>
 * <li><b>SYSTEM</b>（L1 永久缓存）：世界规则 + 角色锚定（「你是{name}，绝不能跳出角色」）——跨会话稳定；</li>
 * <li><b>ROLE_PROFILE</b>（L2 会话级缓存）：身份/OCEAN/说话风格/当前目标/对目标角色态度——会话内稳定；</li>
 * <li><b>CURRENT_STATE</b>（L4 每轮更新）：游戏时间/季节/气温/天气/当前动作/情绪及强度/位置/周围实体/身体状态；</li>
 * <li><b>MEMORY_CONTEXT</b>（L3 时间窗缓存）：与目标相关的记忆 + 近期关注点；</li>
 * <li><b>CONVERSATION_HISTORY</b>（L4 每轮更新）：最近 5-8 轮完整对话 + 更早摘要；</li>
 * <li><b>TASK</b>（L4 每轮更新）：本轮任务指令（只回复一轮/先推理再决策等）；</li>
 * <li><b>OUTPUT_FORMAT</b>（稳定）：输出契约（JSON Schema / 正文），全大写强调「DO NOT OUTPUT ANYTHING
 * OTHER THAN VALID JSON」。</li>
 * </ul>
 * <p>核心原则（2026 年 Context Engineering / 过度规范悖论实践）：信息密度取最优而非堆砌——
 * Prompt 不是越长越好，超过阈值后细节会反向降低指令遵循度；每类场景设 token 上限与程序化回退。</p>
 */
public final class PromptLayers {

    /** 层：系统锚定（L1 永久缓存） */
    public static final String SYSTEM = "SYSTEM";
    /** 层：角色档案（L2 会话级缓存） */
    public static final String ROLE_PROFILE = "ROLE_PROFILE";
    /** 层：当前状态（L4 每轮更新） */
    public static final String CURRENT_STATE = "CURRENT_STATE";
    /**
     * 层：发育阶段约束：硬门控（阶段禁止/受限能力）+ 此刻可发生空间块——
     * 由感知漏斗统一注入（{@code PerceptionService.buildPerceptionText} 追加的空间段），
     * 变化频率随阶段/空间重算（低），置于 CURRENT_STATE 之后保持稳定前缀缓存友好
     */
    public static final String DEVELOPMENTAL_CONSTRAINT = "DEVELOPMENTAL_CONSTRAINT";
    /** 层：记忆上下文（L3 时间窗缓存） */
    public static final String MEMORY_CONTEXT = "MEMORY_CONTEXT";
    /** 层：对话历史（L4 每轮更新） */
    public static final String CONVERSATION_HISTORY = "CONVERSATION_HISTORY";
    /** 层：任务指令（L4 每轮更新） */
    public static final String TASK = "TASK";
    /**
     * 层：行为护栏（Post-History 高权重指令位，V2.0 段 3I）。
     * <p>位于 TASK 之后、OUTPUT_FORMAT 之前（会话历史之后的高权重位——V2.0 §3.4：
     * 历史后的指令对当代模型约束力显著强于历史前，SillyTavern jailbreak 同构）。
     * 单聊/群聊共用 {@code AiContextInjector.dialogueGuardRail}（第一人称/不替他人发言/
     * 只回复一轮/不跳出角色）。</p>
     */
    public static final String POST_HISTORY_GUARD = "POST_HISTORY_GUARD";
    /** 层：输出格式契约（稳定） */
    public static final String OUTPUT_FORMAT = "OUTPUT_FORMAT";

    /** 段标题统一格式（缓存友好：同一场景的段标题文本必须逐字节一致）。 */
    public static String sectionTitle(String layer) {
        return "—— " + layer + " ——";
    }

    /** 段内多块拼接分隔符（块与块之间空一行，保持稳定）。 */
    public static final String BLOCK_SEP = "\n\n";

    private PromptLayers() {
    }
}
