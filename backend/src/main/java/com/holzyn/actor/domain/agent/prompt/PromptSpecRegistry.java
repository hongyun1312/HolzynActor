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

import com.holzyn.actor.domain.settings.service.PromptTemplateService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板规格注册表。
 * <p>每类场景（模板编码）登记四项工程参数，供组装、trace、指标与调试页统一消费：</p>
 * <ul>
 * <li><b>version</b>：模板版本号（内容随代码演进 +1，写入 AiCallTrace 供回溯/合规率统计）；</li>
 * <li><b>contract</b>：输出契约类型（PLAIN_TEXT / DIALOG_JSON / DECISION_JSON / SCHEMA_JSON）；</li>
 * <li><b>maxOutputTokens</b>：输出上限（角色扮演 300 / 实时决策 250 / 自主对话 500 等）；</li>
 * <li><b>temperature</b>：默认采样温度（角色扮演 0.7-0.9 / 实时决策 0.3-0.5 / 自主对话 0.6-0.8）。</li>
 * </ul>
 * <p>未登记的场景回退默认（SCHEMA_JSON / 0.7 / 1024），不阻断任何既有调用；
 * 本注册表与 {@link PromptTemplateService#builtins()} 模板内容配套维护。</p>
 */
public class PromptSpecRegistry {

    /** 单条场景规格（不可变）。 */
    public record PromptSpec(String code, int version, PromptContractType contract,
                             int maxOutputTokens, double temperature) {
        public PromptSpec {
            version = Math.max(1, version);
            maxOutputTokens = Math.max(1, maxOutputTokens);
            temperature = Math.max(0.0, Math.min(2.0, temperature));
        }
    }

    /** 未登记场景回退规格（SCHEMA_JSON / 0.7 / 1024）。 */
    private static final PromptSpec DEFAULT_SPEC = new PromptSpec("__default__", 1,
            PromptContractType.SCHEMA_JSON, 1024, 0.7);

    /** 登记表（code → spec）。 */
    private static final Map<String, PromptSpec> SPECS = new ConcurrentHashMap<>();

    private PromptSpecRegistry() {
    }

    static {
        // ===== 角色扮演回复 =====
        // 单聊：流式正文（PLAIN_TEXT），0.7-0.9 → 0.8，输出上限 300
        // v3（2026-10-08 批 2 对话链）：段 1A 世界观 system 前缀 + 段 2D 世界动态 + 段 2F few-shot + 段 3I Post-History 护栏
        register("dialog_system", 3, PromptContractType.PLAIN_TEXT, 300, 0.8);
        // 群聊逐角色：非流式 JSON 契约，0.7-0.9 → 0.8，输出上限 300
        // v2（2026-10-08 批 2 对话链）：段 1A 世界观 system 前缀 + 段 2D 世界动态 + 段 2F few-shot + 段 3I Post-History 护栏
        register("group_reply", 2, PromptContractType.DIALOG_JSON, 300, 0.8);
        // 自主对话（无玩家）逐角色回复：双角色模板，0.6-0.8 → 0.7，输出上限 500
        register("autonomous_reply", 1, PromptContractType.DIALOG_JSON, 500, 0.7);
        // 自主对话退场/收尾编排
        // v3（2026-10-08 批 2 对话链）：收尾编排注入段 2D 当前世界动态（退场/收尾判断有世界时刻依据）
        register("autonomous_chat_beat", 3, PromptContractType.SCHEMA_JSON, 400, 0.7);

        // ===== 角色实时决策 =====
        // v5（2026-10-10 批 6 收口）：请求前置 段1A【世界观】稳定简报（worldBrief，模板
        // {{persona_summary}}=段1C 角色核心卡、情境=段2D/2E/2H 由批3 保障）——决策骨架
        // 段1A+1C+2D+2E 补齐；未装配零阻断
        // v4（2026-10-08 批 3 决策与感知）：action_gen 情境缺【当前状态】段时前置
        // 段2D 当前世界动态 + 段2E 当前状态（STIMULUS 个性刺激情境「只知刺激不知处境」补齐）
        register("action_gen", 5, PromptContractType.DECISION_JSON, 250, 0.4);
        register("interrupt_probe", 2, PromptContractType.SCHEMA_JSON, 120, 0.3);
        register("interrupt_resume", 2, PromptContractType.SCHEMA_JSON, 256, 0.7);
        register("negotiate", 2, PromptContractType.SCHEMA_JSON, 200, 0.7);
        register("npc_crowd_decision", 2, PromptContractType.SCHEMA_JSON, 300, 0.6);

        // ===== 记忆/提炼/环境等高频角色相关（结构化迁移登记）=====
        // v2（2026-10-10 批 4 记忆与反思链）：抽取/摘要/复核请求按 V2.0 段位装配——
        // 注入 段1A 世界观 + 段1C 角色核心卡 + 段2D 当前世界动态 + 段2E 当前状态
        // （AiContextInjector.memoryChainContext，段 2F 对齐）
        register("memory_extract", 2, PromptContractType.SCHEMA_JSON, 512, 0.2);
        register("memory_extract_review", 2, PromptContractType.SCHEMA_JSON, 300, 0.3);
        // v2（2026-10-10 批 6 收口）：记忆注入预算档位判定（记忆调度员）前置 段1A+1C+2D+2E
        // （memoryChainContext，带角色引用调用）——「该给该角色注入多少记忆」先知道世界/身份/处境
        register("memory_tier", 2, PromptContractType.SCHEMA_JSON, 80, 0.2);
        // v2（2026-10-10 批 6 收口）：L1 自然遗忘模糊化（记忆链·角色视角）前置 段1A+1C+2D+2E
        register("memory_fuzz", 2, PromptContractType.SCHEMA_JSON, 400, 0.6);
        register("memory_summarize", 2, PromptContractType.SCHEMA_JSON, 400, 0.6);
        register("memory_distill", 2, PromptContractType.SCHEMA_JSON, 512, 0.6);
        register("memory_sleep_merge", 2, PromptContractType.SCHEMA_JSON, 400, 0.6);
        // v2（2026-10-10 批 6 收口）：L3 核心块自编辑（记忆链·角色视角）前置 段1A+1C+2D+2E
        register("core_memory_update", 2, PromptContractType.SCHEMA_JSON, 400, 0.4);
        // v2（2026-10-10 批 6 收口）：编年史提炼属「世界视角」——前置 段1A【世界观】+段2D
        register("chronicle_distill", 2, PromptContractType.SCHEMA_JSON, 512, 0.5);
        // v2（2026-10-10 批 6 收口）：实体状态更新属「世界视角」——前置 段1A【世界观】+段2D
        register("entity_state_update", 2, PromptContractType.SCHEMA_JSON, 400, 0.3);
        // v2（2026-10-10 批 5 世界与叙事）：日计划前置 段1A+1C+2D+2E（memoryChainContext：
        // 世界观+角色核心卡+当前世界动态+当前状态）
        register("plan_day", 2, PromptContractType.SCHEMA_JSON, 400, 0.4);
        register("plan_replan", 2, PromptContractType.SCHEMA_JSON, 400, 0.4);
        // v2（2026-10-10 批 5 世界与叙事）：目标生成前置 段1A+1C+2D+2E（memoryChainContext）
        register("goal_gen", 2, PromptContractType.SCHEMA_JSON, 1024, 0.5);
        // v2（2026-10-10 批 4 记忆与反思链）：反思输入前置 段1A+1C+2D+2E（段 2F 对齐）
        register("reflection", 2, PromptContractType.SCHEMA_JSON, 512, 0.6);
        // v2（2026-10-10 批 4 记忆与反思链）：情绪评估在角色卡基础上补 段2D+2E（此刻处境）
        register("npc_emotion_eval", 2, PromptContractType.SCHEMA_JSON, 256, 0.5);
        // v2（2026-10-10 批 6 收口）：环境描述润色属「世界视角」——前置 段1A【世界观】+段2D
        register("environment_desc", 2, PromptContractType.SCHEMA_JSON, 400, 0.7);
        // v2（2026-10-10 批 6 收口）：每日世界帧属「世界视角」——前置 段1A【世界观】+段2D
        register("world_frame_daily", 2, PromptContractType.SCHEMA_JSON, 900, 0.3);
        // v2（2026-10-10 批 6 收口）：信息认知失真改写属「世界视角」——前置 段1A【世界观】+段2D
        register("info_distortion", 2, PromptContractType.SCHEMA_JSON, 300, 0.7);
        // v2（2026-10-10 批 5 世界与叙事）：宏观/中观/涌现属「世界视角」——前置 段1A【世界观】+
        // 段2D（`worldContextPrefix`；macro_event/meso_directive/macro_emergence 无角色=段1A+2D；
        // macro_impact 带行动者 npcRef=段1A+1C+2D）
        register("macro_event", 2, PromptContractType.SCHEMA_JSON, 512, 0.6);
        register("macro_impact", 2, PromptContractType.SCHEMA_JSON, 400, 0.5);
        register("meso_directive", 2, PromptContractType.SCHEMA_JSON, 512, 0.6);
        register("macro_emergence", 2, PromptContractType.SCHEMA_JSON, 512, 0.6);
        // v2（2026-10-10 批 6 收口）：群聊编排属「世界视角」——前置 段1A【世界观】+段2D
        register("group_orchestrator", 2, PromptContractType.SCHEMA_JSON, 200, 0.7);
        // v2（2026-10-10 批 6 收口）：世界事件生成属「世界视角」——前置 段1A【世界观】+段2D
        register("world_event", 2, PromptContractType.SCHEMA_JSON, 400, 0.7);
        // v2（2026-10-10 批 5 世界与叙事）：世界事件润色属「世界视角」——前置 段1A【世界观】+段2D
        register("world_event_polish", 2, PromptContractType.SCHEMA_JSON, 400, 0.6);
        register("behavior_judge", 1, PromptContractType.SCHEMA_JSON, 700, 0.3);
        // 潜在世界预测注入（P1 ③-2）：基于世界趋势+局势一句话预判未来一天世界走向
        // v2（2026-10-10 批 6 收口）：属「世界视角」——前置 段1A【世界观】+段2D
        register("world_outlook", 2, PromptContractType.PLAIN_TEXT, 200, 0.7);
        // 世界常识摘要（V2.0 段 1A 稳定世界观注入源，2026-10-08 外网冒烟新增）：依最新世界观
        // 提炼 300-500 字「该世界角色都知道的常识」，纯文本正文（非 JSON）——PLAIN_TEXT 0.4 / 900。
        register("world_common_knowledge", 1, PromptContractType.PLAIN_TEXT, 900, 0.4);
        // 记忆-知识桥接判定（P1 ②-3，knowledge_bridge）：反思洞察 → 可复用事实型知识候选
        // 抽取（SCHEMA_JSON：knowledge_candidates[{content,importance,is_knowledge}]），
        // 0.3 / 512——「哪些洞察值得沉淀为知识」是严谨判定：低温抑制发散（is_knowledge=false
        // 为默认，防 AI 把临时情绪/一次性事件写成知识）、篇幅收敛（512：候选 2-5 条足够）
        // v2（2026-10-10 批 6 收口）：记忆-知识桥接判定（记忆链·角色视角）前置 段1A+1C+2D+2E
        register("knowledge_bridge", 2, PromptContractType.SCHEMA_JSON, 512, 0.3);
        // 世界地图生成（world_map_generate，位置移动与地图生成系统 M1）：SCHEMA_JSON——
        // 输入=地点列表+世界观+角色列表+既有布局种子，输出=regions/roads/placements/homes（同居共宅）。
        // 0.5 / 8192：地图生成是「创意 + 结构」混合任务（布局要现实、坐标必须合法），温度中等防呆板、
        // 上限给足余量防 15 地点×20 角色输出被截断导致整批 JSON 报废。
        register("world_map_generate", 1, PromptContractType.SCHEMA_JSON, 8192, 0.5);
        // 途中轻量交互（move_encounter，位置移动与地图生成系统 M3）：SCHEMA_JSON——
        // 输入=移动者处境+迎面人物+关系提示，输出={greet:"一句话", stop:bool}（§4.6）。
        // 0.5 / 200：一句招呼的轻量判定，温度中等自然、上限收敛防废话。
        // v2（2026-10-08 批 3 决策与感知）：注入 段2D 当前世界动态 + 段2E 当前状态
        // （移动者时节天气与自身生理/情绪参与「是否停下搭话」判定）
        register("move_encounter", 2, PromptContractType.SCHEMA_JSON, 200, 0.5);

        // ===== 角色档案统一重构 /：AI 两段式建档（新建）=====
        // 段 A（①身份与社会 ②外貌与体质 ④气质与人格 ⑤价值观与动机 ⑨经历与创伤）：0.4 / 1800
        // 段 B（③生理基线 ⑥能力与知识 ⑦语言与表达 ⑧习惯与日常 ⑩发育·谱系 ⑪社会面具）：0.5 / 1800
        // 输出上限 1800（而非早期设想的 1600）：11 维规范视图渲染态约 3120 字符 ≈ 1930 token，
        // 单段产出 5-6 维并要求"具体到可感知细节"，1600 会把细节压回去。
        register("persona_bootstrap_fill_a", 1, PromptContractType.SCHEMA_JSON, 1800, 0.4);
        register("persona_bootstrap_fill_b", 1, PromptContractType.SCHEMA_JSON, 1800, 0.5);

        // 角色卡生成（character_card_gen）： 起模板已从"旧角色卡 Schema"迁到"11 维 v2 档案契约"
        // 。
        // 输出上限 16384 对齐原调用点（用户可输入 2 万字详情，输出必须"只可多不可少"地完整承载）。
        // 收口：CharacterCardService.generateCard 已 delegate 到 PersonaBootstrapService（两段式），
        // 本模板保留为"一次产出 11 维"的合并版（创意发散/手工调试用），登记值供 trace/prompt_version/指标页消费。
        register("character_card_gen", 2, PromptContractType.SCHEMA_JSON, 16384, 0.7);

        // 普通居民批量生成（ordinary_npc_gen）： 收口——模板从"旧居民草稿扁平 Schema"
        // 迁到"结构列 + 每位居民自己的 11 维 v2 档案"（内容改写 → 按《Prompt 工程规范》§7 登记/升版；
        // 场景码与占位符保持不变，调用点与既有测试零改动）。输出上限 16384 对齐调用点
        // （OrdinaryNpcService.NPC_MAX_TOKENS）；温度 0.5 对齐调用点（居民需要发散创造，比建档略高）。
        // 注意：一次调用同时产出"count 位居民 × 11 维档案"，故调用点把批大小收窄到
        // holzyn.actor.crowd.npc-persona-batch-size（默认 5），避免 30 人 × 11 维直接撞上 16384 上限。
        register("ordinary_npc_gen", 2, PromptContractType.SCHEMA_JSON, 16384, 0.5);

        // ===== 轨道：全生命周期阶段机 + 此刻可发生空间（新建）=====
        // 空间生成（growth_space_generate）：四段式 SCHEMA_JSON（inner/possible_states/
        // possible_actions/forbidden），0.75 / 700——空间是"此刻可发生"的软引导清单，发散度中等
        // v2（2026-10-10 批 5 世界与叙事）：前置 段1A+1C+2D+2E（memoryChainContext）
        register("growth_space_generate", 2, PromptContractType.SCHEMA_JSON, 700, 0.75);
        // 阶段跨越 AI 叙事（growth_stage_transition_narrative）：正文 PLAIN_TEXT，0.8 / 500——
        // 编年史入口，叙事需要生动但克制（乡土世界口吻），温度略高增强文学性
        // v2（2026-10-10 批 5 世界与叙事）：前置 段1A+1C+2D+2E（memoryChainContext）
        register("growth_stage_transition_narrative", 2, PromptContractType.PLAIN_TEXT, 500, 0.8);
        // 死亡 AI 叙事（growth_death_chronicle）：正文 PLAIN_TEXT，0.7 / 400——丧葬氛围沉重，
        // 温度收敛（0.7）避免轻浮，篇幅收敛（400）保持沉静
        // v2（2026-10-10 批 5 世界与叙事）：前置 段1A+1C+2D+2E（memoryChainContext）
        register("growth_death_chronicle", 2, PromptContractType.PLAIN_TEXT, 400, 0.7);
        // 发育阶段行为润色（growth_stage_behavior）：正文 PLAIN_TEXT，0.7 / 300——把程序化
        // 行为骨架展开为带个人化细节的一句话行动（O6 发育演化·stage_gating 策略），
        // 温度中等（0.7）增强生活质感、篇幅收敛（300=一句话）防跑题
        // v2（2026-10-10 批 5 世界与叙事）：前置 段1A+1C+2D+2E（memoryChainContext）
        register("growth_stage_behavior", 2, PromptContractType.PLAIN_TEXT, 300, 0.7);
        // 自省-行为一致性校验（self_check，自省漂移检测·候选 A）：SCHEMA_JSON
        // （{"consistent":bool,"reason":str}），0.3 / 256——判定类低温稳定（对齐 shift eval
        // 风格）：反思 vs 决策的「一致/漂移」是严谨判定，低温抑制发散、篇幅收敛防废话
        // v2（2026-10-10 批 6 收口）：自省一致性校验（记忆链·角色视角）前置 段1A+1C+2D+2E
        register("self_check", 2, PromptContractType.SCHEMA_JSON, 256, 0.3);
        // 主动记忆检索判定（proactive_retrieval，主动记忆检索·P1 ②-1）：SCHEMA_JSON
        // （{"need":bool,"query":str,"reason":str}），0.3 / 256——「是否需要主动想起/查证」
        // 是严谨判定：低温稳定（need=false 为默认，防编造检索冲动）、query 定向收敛防泛问。
        // v2（2026-10-10 批 4 记忆与反思链）：判定输入前置 段1A+1C+2D+2E（段 2F 对齐）
        register("proactive_retrieval", 2, PromptContractType.SCHEMA_JSON, 256, 0.3);
        // 能力自评卡更新（self_efficacy_update，能力自评卡·P1 ①-2）：SCHEMA_JSON
        // （{"self_efficacy":{领域:1-5},"self_comment":str,"changed":bool,"reason":str}），
        // 0.3 / 400——自评微调是"克制校准"的严谨判定：低温抑制发散（防自评随反思剧变）、
        // 篇幅收敛（400：领域 3-6 个 + 一句理由足够）
        // v2（2026-10-10 批 6 收口）：能力自评更新（记忆链·角色视角）前置 段1A+1C+2D+2E
        register("self_efficacy_update", 2, PromptContractType.SCHEMA_JSON, 400, 0.3);
        // MUSE 策略切换·AI 档位复核（muse_strategy_check，MUSE 式策略切换·P1 ①-3）：SCHEMA_JSON
        // （{"posture":"conservative|balanced|aggressive","reason":str}），0.3 / 256——
        // 档位复核是"克制校准"的严谨判定：低温抑制发散（防 AI 随意推翻程序化档位）、
        // 篇幅收敛（256：档位 + 一句理由足够）
        // v2（2026-10-10 批 6 收口）：MUSE 档位复核（记忆链·角色视角）前置 段1A+1C+2D+2E
        register("muse_strategy_check", 2, PromptContractType.SCHEMA_JSON, 256, 0.3);

        // ===== 轨道：人格演化 + 社会面具（新建）=====
        // 人格偏移评估（growth_persona_shift_eval）：SCHEMA_JSON（shifts[{dim,field,delta,why}]+summary），
        // 0.3 / 500——偏移判定是"宁缺毋滥"的严谨评估：低温（0.3）抑制发散、篇幅收敛（500）防编造
        // v2（2026-10-10 批 6 收口）：人格偏移评估（记忆链·角色视角）前置 段1A+1C+2D+2E
        register("growth_persona_shift_eval", 2, PromptContractType.SCHEMA_JSON, 500, 0.3);

        // ===== 轨道：遗传婚育谱系（新建）=====
        //
        // 判定类低温稳定（对齐 shift eval 风格）；契约与 NanoJev boolean question 同构（N 轨道可替换实现）
        // v2（2026-10-10 批 5 世界与叙事）：判定前置 段1A+1C+2D+2E（memoryChainContext）
        register("growth_romance_decision", 2, PromptContractType.SCHEMA_JSON, 300, 0.4);
        //
        // 契约与 NanoJev choice question（propose/wait/give_up）同构
        // v2（2026-10-10 批 5 世界与叙事）：判定前置 段1A+1C+2D+2E（memoryChainContext）
        register("growth_proposal_decision", 2, PromptContractType.SCHEMA_JSON, 300, 0.4);
        // 生育意愿判定（growth_fertility_decision）：SCHEMA_JSON（{want,reason}），0.4 / 300——
        // 契约与 NanoJev score/boolean question 同构
        // v2（2026-10-10 批 5 世界与叙事）：判定前置 段1A+1C+2D+2E（memoryChainContext）
        register("growth_fertility_decision", 2, PromptContractType.SCHEMA_JSON, 300, 0.4);
        // 新生儿档案（growth_newborn_persona）：SCHEMA_JSON（{given_name,appearance,...}），0.6 / 600——
        // 起名与外貌继承需要一定发散度（0.6），篇幅克制（600：出生只填 ①② 两维的文本子集）
        // v2（2026-10-10 批 5 世界与叙事）：生成前置 段1A+1C+2D+2E（memoryChainContext）
        register("growth_newborn_persona", 2, PromptContractType.SCHEMA_JSON, 600, 0.6);
        // 婚礼叙事（growth_wedding_narrative）：正文 PLAIN_TEXT，0.8 / 400——喜庆场合，温度略高增强文学性
        // v2（2026-10-10 批 5 世界与叙事）：叙事前置 段1A+1C+2D+2E（memoryChainContext）
        register("growth_wedding_narrative", 2, PromptContractType.PLAIN_TEXT, 400, 0.8);

        // ===== N 轨道：NanoJev 判定场景登记骨架（ai/nanojev 独立 evaluate client） =====
        // 说明：NanoJev 不走 chat 网关（不吃 messages、不生成文字、输出概率分布零解码），
        // 本登记的 contract/maxOutputTokens/temperature 为信息性占位（供 trace prompt_version/
        // 版本留痕/ 模板版本化），真正受约束的是各场景的 instructions/criteria 模板本身——
        // 按《Prompt 工程规范》§7 变更流程升版（version+1），档位描述必须自包含（改写即换索引）。
        // 场景覆盖《N 轨道开发路径》§2 应用面全景：三插拔点 + 面具两判定点 + 判定类首批
        // + 空间候选选择 + 认知 LOD 与演化判定。模板内容 逐场景落地时配套编写。
        // 2026-10-08 V2.0 规范化重构批 1：判定输入对齐《AI调用规范化结构_V2.0》段 1+2+3 骨架
        // （AiContextInjector.decisionInput 全量拼接为单串：1A 稳定世界观/1B 系统指令/1C 角色核心卡/
        // 2D 当前世界动态/2H 本轮输入/3I 行为护栏/3J 输出契约）→ 运行时判定场景统一升版 2→3 留痕。
        // 冒烟/调试样例题
        register("nanojev_sample", 1, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        // 婚育三插拔点
        register("nanojev_romance_attract", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_proposal", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_fertility", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        // 面具两判定点
        register("nanojev_mask_context", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_mask_breach", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        // 判定类首批：情绪评估 / 普通 NPC 批量决策 / 社交意愿门控 / 群聊发言判定 / 打断仲裁 / 护栏语义
        // ⚠️ 三类场景已实际编写 instructions/criteria（ai/nanojev/ab/NanoJevAbTemplates），
        // 按《Prompt 工程规范》§7 升版 version 1→2 留痕（档位/候选描述自包含）。
        register("nanojev_emotion_eval", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_crowd_decision", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_social_willingness", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_group_speak", 1, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        // ：打断仲裁 / 护栏语义运行时模板内容已编写（NanoJevDecisionTemplates）→ v2
        register("nanojev_negotiate", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_guard_semantic", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        // ：社交意愿升级重问场景（运行时 Qwen 同题重问记账；内容=判定题转 Qwen）
        register("nanojev_social_willingness_escalation", 1, PromptContractType.SCHEMA_JSON, 512, 0.3);
        // 此刻可发生空间：候选选择 + forbidden 合规复核
        // ⚠️：运行时模板内容已编写（NanoJevDecisionTemplates.spacePreconditionQuestion /
        // spaceActionQuestion / SpaceDecisionServiceUtils 一次前向编排）→ v2 留痕
        register("nanojev_space_precondition", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_space_choice", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        // ：空间候选选择总场景（场景码 space_decision，NanoJevDecisionScenes）——
        // precondition 语义 boolean 批量 + 行动 choice 单 state 一次前向；sample 直接采纳无升级阈值
        register("nanojev_space_decision", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        // 认知 LOD 注意力 + 演化判定
        // ⚠️：运行时模板内容已编写（NanoJevDecisionTemplates.attentionStateQuestion /
        // eventRelevanceQuestion / microIntentQuestion / spaceTriggerQuestion / personaShiftEvidenceQuestion /
        // personaShiftDirectionQuestion / relationDeltaMagnitudeQuestion / relationDeltaDirectionQuestion /
        // accidentQuestion）→ 升版 v2 留痕；新场景登记（micro_decision / space_trigger / accident）
        register("nanojev_lod_attention", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_shift_eval", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_relation_delta", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_micro_decision", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_space_trigger", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);
        register("nanojev_accident", 3, PromptContractType.SCHEMA_JSON, 1024, 1.0);

        // ===== N 轨道：A/B 质量评估辅助场景（ai/nanojev/ab） =====
        // Qwen 同题型重问（对照基线）：走 chat 网关（jsonMode 输出选中项），场景码供
        // AiCallContext trace/用量归属与 Prompt 版本留痕；temperature/maxTokens 由
        // holzyn.actor.ai.nanojev.ab.qwen.* 实际控制，本登记为信息性占位。
        register("nanojev_ab_qwen", 1, PromptContractType.SCHEMA_JSON, 512, 0.3);
        // Judge 四维合理性评分（复用 JudgeChatClient + PromptTemplateService 模板
        // nanojev_ab_judge；judge 调用不落 actor_ai_call_log，本登记供模板版本留痕/指标）。
        register("nanojev_ab_judge", 1, PromptContractType.SCHEMA_JSON, 1024, 0.0);

        // ===== 项目级 AI 助手（2026-10-10 第 50 次 · M1+M2）=====
        // assistant_tool：工具决策一步（jsonMode 结构化）——「判断是否需要查询数据并声明工具调用」
        // 是严谨判定：低温（0.3）抑制发散、篇幅收敛（1024：工具调用 JSON 很短）。
        // v2（2026-10-12 冒烟）：明示「前一轮工具结果已足够时直接 no_tool、不要重复调用」——
        // 实测模型同一轮重复声明 macro_overview ×3 浪费 AI 调用，提示词收敛 + 执行器同参去重双保险。
        // assistant_chat：最终回答（PLAIN_TEXT 流式正文）——叙事需要一定文学性（0.6），
        // 篇幅放宽（1400）容纳双模式回答；事实模式经提示词约束不依赖低温。
        register("assistant_tool", 2, PromptContractType.SCHEMA_JSON, 1024, 0.3);
        register("assistant_chat", 1, PromptContractType.PLAIN_TEXT, 1400, 0.6);

        // ===== 对谈模式（2026-10-12 第 54 次 · M1 对谈地基）=====
        // dialogue_reply：单聊回复（PLAIN_TEXT 流式正文）——「像真人一样」的促膝长谈：
        // 需要自然与温度（0.7），篇幅收敛（300=一轮回复，防长篇大论）。
        // 上下文装配见 DialogueContextAssembler（L0 稳定 system + L1 动态 user）。
        register("dialogue_reply", 1, PromptContractType.PLAIN_TEXT, 300, 0.7);
        // dialogue_greeting：AI 动态开场白（PLAIN_TEXT 正文）——角色第一句话要自然亲切
        // 有温度（0.8 略高增强开场感），篇幅收敛（160=≤80 字约束的余量）。
        register("dialogue_greeting", 1, PromptContractType.PLAIN_TEXT, 160, 0.8);
        // dialogue_summary（M4 长对话滚动窗口自动摘要）：超窗前段折叠为 AI 摘要——
        // 需要忠实浓缩前情（低温 0.4 抑制发散），篇幅收敛（300=摘要正文上限）。
        // 运行时由 DialogueSummaryService 组装（既有摘要 + 新增超窗批），PLAIN_TEXT 只输出摘要正文。
        register("dialogue_summary", 1, PromptContractType.PLAIN_TEXT, 300, 0.4);
        // dialogue_group_reply（M5 群聊逐角色回复）：每回应独立 AI 调用、复用单聊流式正文形态
        // （设计 §6.4「每回应独立 AI 调用（复用单聊回应生成），串行流式」→ 实施抓修：设计稿 §8.3
        // 拟 DIALOG_JSON 整轮契约，M5 编排决策为确定性全员依次 + 逐角色独立调用，正文 PLAIN_TEXT 流式；
        // DIALOG_JSON 留待 AI 编排模式扩展）。群聊对话需要自然温度（0.7），篇幅放宽（500=逐角色一轮）。
        // 上下文装配见 DialogueContextAssembler.composeGroup（本人卡 + 场景段 + 【对家】+ 群聊历史）。
        register("dialogue_group_reply", 1, PromptContractType.PLAIN_TEXT, 500, 0.7);
        // dialogue_consolidation（M6 沉淀）：关系 delta + 记忆要点抽取——「沉淀时写什么」是克制的
        // 严谨判定（关系变化宁缺毋滥、记忆要点只写明确发生的活动/共识/承诺），低温（0.3）抑制发散，
        // 篇幅收敛（512=多角色各 1~3 条要点足够）。SCHEMA_JSON（jsonMode=true）只输出 relations 数组；
        // 运行时由 DialogueConsolidationService 组装（会话角色清单 + 当前关系 + 最近对话窗）。
        register("dialogue_consolidation", 1, PromptContractType.SCHEMA_JSON, 512, 0.3);
    }

    /** 登记一条场景规格（version=1）。 */
    public static void register(String code, PromptContractType contract, int maxOutputTokens, double temperature) {
        register(code, 1, contract, maxOutputTokens, temperature);
    }

    /** 登记一条场景规格（显式版本号）。 */
    public static void register(String code, int version, PromptContractType contract,
                                int maxOutputTokens, double temperature) {
        if (code == null || code.isBlank()) {
            return;
        }
        SPECS.put(code, new PromptSpec(code, version, contract, maxOutputTokens, temperature));
    }

    /** 查询场景规格（未登记回退默认；场景 null/空回退默认）。 */
    public static PromptSpec spec(String scene) {
        if (scene == null || scene.isBlank()) {
            return DEFAULT_SPEC;
        }
        return SPECS.getOrDefault(scene, DEFAULT_SPEC);
    }

    /** 查询模板版本号（未登记回退 1）。 */
    public static int versionOf(String scene) {
        return spec(scene).version();
    }

    /** 查询输出契约（未登记回退 SCHEMA_JSON）。 */
    public static PromptContractType contractOf(String scene) {
        return spec(scene).contract();
    }

    /** 查询输出上限（未登记回退 1024）。 */
    public static int maxOutputTokensOf(String scene) {
        return spec(scene).maxOutputTokens();
    }

    /** 查询默认温度（未登记回退 0.7）。 */
    public static double temperatureOf(String scene) {
        return spec(scene).temperature();
    }

    /** 全部登记规格（调试页展示用）。 */
    public static Map<String, PromptSpec> all() {
        return new HashMap<>(SPECS);
    }
}
