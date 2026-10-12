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
 * Prompt 分层组装契约文本。
 * <p>提供各场景的 TASK / OUTPUT_FORMAT 层标准文本（与 {@link PromptLayers} 配套）：
 * 组装侧（RoleplayService 等）把稳定前缀（SYSTEM+ROLE_PROFILE=角色 system prompt）放 system，
 * 把变化段（CURRENT_STATE / MEMORY_CONTEXT / CONVERSATION_HISTORY / TASK / OUTPUT_FORMAT）
 * 拼入 user 触发消息——同一角色同一会话的请求前缀逐字节一致，命中服务端前缀缓存。</p>
 * <ul>
 * <li><b>单聊</b>：TASK=只回复一轮不替对方说话；OUTPUT_FORMAT=只输出正文（SSE 流式通道，
 * 单聊保持流式正文，主前端零改动）；</li>
 * <li><b>群聊逐角色</b>：TASK=当前轮到谁发言+接话要求；OUTPUT_FORMAT=DIALOG_JSON 契约
 * （dialogue/action/emotion_update/internal_thought，非流式解析落库）；</li>
 * <li><b>决策</b>：foresight_requirement=前瞻推演要求段。</li>
 * </ul>
 */
public final class PromptContract {

    private PromptContract() {
    }

    /**
     * 单聊 TASK 段（角色扮演回复：只回复一轮、不替对方说话）。
     *
     * @param name 角色名
     *
     * @return TASK 段文本
     */
    public static String singleChatTask(String name) {
        String who = name == null || name.isBlank() ? "你" : name;
        return "当前是玩家在与你（" + who + "）单独交谈。请像真实的人一样，针对玩家刚说的话自然地回应。\n"
                + "1. 只回复这一轮；不要替玩家说话、不要代答、不要替玩家做决定。\n"
                + "2. 用第一人称、贴合你的身份/性格/说话风格；长度贴合情境，不要长篇大论。";
    }

    /**
     * 单聊 OUTPUT_FORMAT 段（流式正文契约——不输出 JSON 包装）。
     *
     * @param name 角色名
     *
     * @return OUTPUT_FORMAT 段文本
     */
    public static String singleChatOutputFormat(String name) {
        String who = name == null || name.isBlank() ? "你" : name;
        return "直接输出 " + who + " 要说的回复正文：\n"
                + "1. 只输出角色说的话本身，不要任何 JSON 包装、不要 Markdown 标注、不要【】或方括号说明。\n"
                + "2. 开头不要带角色名字或『某某说：』前缀。";
    }

    /**
     * 群聊 TASK 段（当前轮到谁发言 + 针对性接话要求）。
     *
     * @param speaker 当前发言者名
     *
     * @return TASK 段文本
     */
    public static String groupReplyTask(String speaker) {
        String who = speaker == null || speaker.isBlank() ? "你" : speaker;
        return "当前轮到【" + who + "】发言。请先仔细阅读对话历史，特别关注上一位发言者（或玩家）刚说的话，"
                + "像真实社交对话一样自然地接话、回应、追问、反驳或补充，随机应变，不要自说自话，"
                + "不要机械重复已经讨论过的问题。若本场尚无他人发言（由你起头），则围绕本场背景/话题开口，"
                + "不要等待别人先说话。";
    }

    /**
     * 群聊 OUTPUT_FORMAT 段（DIALOG_JSON 契约——与 {@link GroupReplyParse} 配套）。
     *
     * @return OUTPUT_FORMAT 段文本
     */
    public static String groupReplyOutputFormat() {
        return "请严格输出一个 JSON 对象，禁止输出 JSON 以外的任何文字（不要用 Markdown 代码块包裹）：\n"
                + "{\n"
                + "  \"dialogue\": \"你这次要说的原话（第一人称，贴合你的说话风格与性格，15-80 字；场上已有他人发言时必须针对性回应其内容；若由你起头（场上尚无发言）则围绕本场背景/话题自然开口）\",\n"
                + "  \"action\": \"说话时伴随的细微动作（如「端起茶碗抿了一口」；没有就填空串）\",\n"
                + "  \"emotion_update\": { \"emotion\": \"此刻情绪（喜/怒/哀/惧/惊/平淡/羞等）\", \"intensity\": 1到5的整数 },\n"
                + "  \"internal_thought\": \"你此刻没有说出口的内心想法（一句话，25 字内）\"\n"
                + "}\n"
                + "DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.";
    }

    /**
     * 决策前瞻推演要求段。
     *
     * @return 前瞻要求段文本（为空时调用方不注入）
     */
    public static String foresightRequirement() {
        return "\n—— 前瞻推演要求 ——\n"
                + "本情境含重要变化：请先在心里推演 2-3 个候选行动及其短期后果，"
                + "再把候选与最终决策一并输出（candidates 数组，每项 {\"action\": \"候选行动\", \"consequence\": \"短期后果\"}）。";
    }

    /** 是否含 JSON 契约的强制句（调试/断言用）。 */
    public static final String FORCE_CLAUSE = "DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.";
}
