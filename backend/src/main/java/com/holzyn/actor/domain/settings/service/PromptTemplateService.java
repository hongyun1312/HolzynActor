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

package com.holzyn.actor.domain.settings.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.agent.context.AiContextInjector;
import com.holzyn.actor.domain.agent.prompt.PromptResources;
import com.holzyn.actor.domain.settings.entity.ActorPromptTemplate;
import com.holzyn.actor.domain.settings.repository.ActorPromptTemplateRepository;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板服务。
 * <p>职责：管理可编辑的 AI Prompt 模板（角色卡生成/对话系统/群聊编排/世界事件/行动生成）。
 * 内置模板以 user_id=0 幂等种子初始化；用户覆盖存 user_id>0（project_id NULL）；
 * 项目级覆盖存 user_id>0 + project_id 非空（随 .holzyn 包导入导出）；
 * 解析规则：项目覆盖 > 用户覆盖 > 内置；重置 = 删除对应覆盖行回退低一级。
 * render 统一做 {{占位符}} 替换，供各业务场景渲染 Prompt。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService implements ApplicationRunner {

    /** 统一系统提示词资源编码：所有模板共用同一段 system，跨模板共享前缀缓存。 */
    private static final String UNIFIED_SYSTEM_CODE = "_shared";

    /** 正文自身已把世界观放在最前的模板：再注入上下文段只会重复占上下文。 */
    private static final Set<String> CONTEXT_SKIP_CODES = Set.of("persona_dim_generate");

    /**
     * 正文自带完整契约、不需要再前置全局契约段的模板。
     * <p>persona_dim_generate 的正文已含 11 维契约 + 附A~附K 约 3.5 万字符，
     * 再拼一份通用契约既重复又会把前缀撑到 1.6 万 token 以上（冷启动逼近网关 300s 上限）。</p>
     */
    private static final Set<String> CONTRACT_SKIP_CODES = Set.of("persona_dim_generate");

    /** 全局共享契约段缓存（读盘一次，文本恒定）。 */
    private volatile String contractCache;

    /** 全局输出要求段缓存。 */
    private volatile String requirementsCache;

    /** 项目上下文段缓存：projectId → [写入毫秒, 文本]；TTL 见 PROJECT_CONTEXT_TTL_MS。 */
    private Map<Long, String[]> projectContextCache = new ConcurrentHashMap<>();

    /** 上下文段缓存有效期（毫秒）：世界观改动后最迟 2 分钟生效。 */
    private static final long PROJECT_CONTEXT_TTL_MS = 120_000L;

    /**
     * 世界观仓库（可选依赖）。
     * <p>用字段注入而不是构造器参数：既有测试大量直接 new 本类，改构造器签名会连带改测试；
     * 未注入时退化为不注入项目上下文段，功能仍可用。</p>
     */
    @Autowired(required = false)
    private ActorWorldSettingRepository worldSettingRepository;

    /**
     * 常驻前缀提供者（可选依赖）：把「人物名册 + 地点 + 民情常识」这类世界级恒定内容
     * 前移到 user 正文之前，把跨场景共享的连续稳定前缀从 1 个缓存块拉到 2-3 个块。
     *
     * <p>⚠️ {@code @Lazy}：{@link AiContextInjector} 依赖 PersonaService，与本服务存在潜在环；
     * 懒注入断环（同 AGENTS.md 8.1.5 既有结论）。未注入（测试直接 new）时退化为不加常驻前缀。</p>
     */
    @Lazy
    @Autowired(required = false)
    private AiContextInjector aiContextInjector;

    /** 模板编码常量：角色卡生成 */
    public static final String CODE_CARD_GEN = "character_card_gen";
    /** 模板编码常量：对话系统 */
    public static final String CODE_DIALOG = "dialog_system";

    /**
     * 群聊逐角色回复（Prompt 工程 双角色分层 + DIALOG_JSON 输出契约，
     * 群聊改非流式 JSON——，见次对接文档_20260921_02.md）
     */
    public static final String CODE_GROUP_REPLY = "group_reply";
    /** 模板编码常量：群聊编排 */
    public static final String CODE_GROUP = "group_orchestrator";
    /** 模板编码常量：世界事件 */
    public static final String CODE_WORLD_EVENT = "world_event";
    /** 模板编码常量：行动生成 */
    public static final String CODE_ACTION = "action_gen";
    /** 模板编码常量：人群集体行动编排 */
    public static final String CODE_CROWD = "crowd_orchestrator";
    /** 模板编码常量：文件导入解析 */
    public static final String CODE_PROJECT_IMPORT = "project_import";
    /** 模板编码常量：文件导入解析-角色 */
    public static final String CODE_PROJECT_IMPORT_CHARACTERS = "project_import_characters";
    /** 模板编码常量：世界观单字段生成（分阶段逐字段深化到 1000 字以上） */
    public static final String CODE_PROJECT_IMPORT_FIELD = "project_import_field";
    /** 模板编码常量：长期记忆抽取 */
    public static final String CODE_MEMORY_EXTRACT = "memory_extract";
    /** 模板编码常量：会话摘要生成 */
    public static final String CODE_MEMORY_SUMMARIZE = "memory_summarize";
    /** 模板编码常量：L3 核心块自编辑 */
    public static final String CODE_CORE_MEMORY_UPDATE = "core_memory_update";
    /** 模板编码常量：世界演化编排 */
    public static final String CODE_EVOLUTION = "evolution_orchestrator";
    /** 模板编码常量：场景 AI 自动生成 */
    public static final String CODE_SCENE_GENERATE = "scene_generate";
    /** 模板编码常量：角色关系生成（关系拓扑 Tab/全局页 AI 识别角色关系，json 数组输出；scene 用量沿用 relation_gen） */
    public static final String CODE_RELATION_GEN = "relation_gen";
    /** 模板编码常量：角色关系 AI 精修（：把「当前状态+数值+双方档案+世界观」交 AI 综合判断好感/信任/状态；scene=relation_refine） */
    public static final String CODE_RELATION_REFINE = "relation_refine";
    /** 模板编码常量：普通型 NPC 标准字段数据拟定（分类体系重构：AI 依据世界观一次性拟定种族[含次级种族]/归属/职业的标准字段数据[每条含出处]并选出主/次分类字段；scene=字段字典拟定，编码沿用 crowd_category_gen 兼容旧用量日志） */
    public static final String CODE_CROWD_CATEGORY = "crowd_category_gen";
    /** 模板编码常量：普通型 NPC 批量生成（重构后 AI 依据世界观+字段字典+地点清单生成居民档案，json 数组输出；scene=ordinary_npc_gen） */
    public static final String CODE_CROWD_NPC_GEN = "ordinary_npc_gen";
    /** 模板编码常量：普通型 NPC 项目级 AI 集体调度（两级调度第一步：项目级按主/次分类字段分组 + 归属下发指令，json 输出） */
    public static final String CODE_CROWD_SCHEDULE_PROJECT = "crowd_schedule_project";
    /** 模板编码常量：普通型 NPC 归属级 AI 集体调度（两级调度第二步：按归属合并归属指令 + 相关主分类分组指令，一次输出该归属全部居民状态/行动，json 数组输出） */
    public static final String CODE_CROWD_SCHEDULE_AFFILIATION = "crowd_schedule_affiliation";
    /** 模板编码常量：演化逐拍调度 */
    public static final String CODE_EVOLUTION_SCHEDULE = "evolution_schedule";
    /** 模板编码常量：世界观文件 AI 总结分段（新建项目解析重构：文件未按 7 类标准格式描写时，AI 总结并分段为 地理/势力/规则/文化/历史/补充/角色 七部分，json 输出） */
    public static final String CODE_WORLD_SEGMENT = "world_segment";
    /** 模板编码常量：角色分段 AI 完整分离（新建项目解析重构：从「角色信息」分段中逐个完整分离每个角色[含完整详细信息]，json 数组输出；detail 只可多不可少） */
    public static final String CODE_WORLD_SEGMENT_CHARACTERS = "world_segment_characters";
    /** 模板编码常量：世界时间 AI 推断（世界初始化第 5 步：依据世界观[历史脉络/时代背景]推断当前世界历时间点[不要从头开始计算]，json 输出 year/month/day/hour/minute/second） */
    public static final String CODE_WORLD_TIME_INFER = "world_time_infer";
    /** 模板编码常量：跨日世界事件 AI 润色（B3 世界推进引擎 T-4 双通道第二通道：把「机制生成的程序化草稿」润色为成文世界事件，json 输出 title/content；scene=world_event_polish） */
    public static final String CODE_WORLD_EVENT_POLISH = "world_event_polish";
    /** 模板编码常量：角色目标 AI 生成（：依据角色档案+世界观+当前状态生成 1~3 个 active 目标；scene=goal_gen） */
    public static final String CODE_GOAL_GEN = "goal_gen";
    /** 模板编码常量：日计划 AI 生成（：对齐 Smallville 层级规划，生成 5~8 个时段条目；scene=plan_day） */
    public static final String CODE_PLAN_DAY = "plan_day";
    /** 模板编码常量：计划中断重排（：世界事件打断后重排当前时段后的条目；scene=plan_replan） */
    public static final String CODE_PLAN_REPLAN = "plan_replan";
    /** 模板编码常量：时间性探针（以角色身份回答否定状态/时间回溯/因果账本问题，严格 JSON；scene=temporal_probe） */
    public static final String CODE_TEMPORAL_PROBE = "temporal_probe";
    /** 模板编码常量：反思洞察生成（/R4：从近期记忆生成洞察+目标状态更新；scene=reflection） */
    public static final String CODE_REFLECTION = "reflection";
    /** 模板编码常量：无玩家群聊退场/收尾编排（：判断哪个 NPC 自愿退场/是否收尾，json 输出；scene=autonomous_chat） */
    public static final String CODE_AUTONOMOUS_CHAT_BEAT = "autonomous_chat_beat";
    /** 模板编码常量：世界观生成-维度一 核心法则 */
    public static final String CODE_WORLDGEN_CORELAWS = "worldgen_corelaws";
    /** 模板编码常量：世界观生成-维度二 时空地理 */
    public static final String CODE_WORLDGEN_SPACETIME = "worldgen_spacetime";
    /** 模板编码常量：世界观生成-维度三 社会权力 */
    public static final String CODE_WORLDGEN_SOCIETY = "worldgen_society";
    /** 模板编码常量：世界观生成-维度四 历史文化 */
    public static final String CODE_WORLDGEN_HISTORYCULTURE = "worldgen_historyculture";
    /** 模板编码常量：世界观生成-维度五 存在基础 */
    public static final String CODE_WORLDGEN_EXISTENCE = "worldgen_existence";
    /** 模板编码常量：世界观生成-维度六 信息传播 */
    public static final String CODE_WORLDGEN_INFORMATION = "worldgen_information";

    /** character 工作流-角色阵容设计 */
    public static final String CODE_WORLDGEN_CHARACTER_ROSTER = "worldgen_character_roster";

    /** character 工作流-逐角色深度档案 */
    public static final String CODE_WORLDGEN_CHARACTER_DEPTH = "worldgen_character_depth";

    /** story-plan 工作流-故事宏观规划 */
    public static final String CODE_WORLDGEN_STORY_PLAN = "worldgen_story_plan";

    /** story-plan 工作流-风格档案 */
    public static final String CODE_WORLDGEN_STYLE_PROFILE = "worldgen_style_profile";
    /** 模板编码常量：NPC 情绪 AI 评估写回 */
    public static final String CODE_NPC_EMOTION_EVAL = "npc_emotion_eval";

    /** NPC 状态 */
    public static final String CODE_NPC_CROWD_DECISION = "npc_crowd_decision";

    /**
     * 实时行为决策系统：恢复决策（被打断任务 → AI 按性格/状态/情况决定 resume/adjust/abandon；
     * 场景 scene=interrupt_resume）
     */
    public static final String CODE_INTERRUPT_RESUME = "interrupt_resume";

    /**
     * 实时行为决策系统：打断前 AI 探针（/ 常规刺激 → AI 按性格/当前活动/刺激判断
     * 是否放下手头事去应对；场景 scene=interrupt_probe）
     */
    public static final String CODE_INTERRUPT_PROBE = "interrupt_probe";

    /**
     * 世界演化与游戏时间系统：每日世界帧生成（AI 主通道：天气/宏观事件/信息包/环境变化
     * 严格 JSON，禁止 NPC 行为；场景 scene=world_frame_daily；温度 0.3；独立每日预算）
     */
    public static final String CODE_WORLD_FRAME_DAILY = "world_frame_daily";

    /**
     * 世界演化与游戏时间系统：信息认知失真改写（三层过滤认知层：按失真规则+传播上下文
     * 改写信息为「该受众听到的版本」，文本输出；场景 scene=info_distortion；（受众×包×游戏日）缓存收敛成本）
     */
    public static final String CODE_INFO_DISTORTION = "info_distortion";

    /**
     * 记忆系统重构：记忆抽取按需复核（三重机制③按需 AI 复核：候选重要度≥3 或
     * 带 update/invalidate 操作时，对候选一次性批量确认保留/丢弃/修改；场景 scene=memory_extract_review）
     */
    public static final String CODE_MEMORY_EXTRACT_REVIEW = "memory_extract_review";

    /**
     * 记忆系统重构：编年史提炼（即时/每日/巩固三触发共用：当日事件流+天气帧+环境快照
     * →「世界发生了什么」条目数组；场景 scene=chronicle_distill）
     */
    public static final String CODE_CHRONICLE_DISTILL = "chronicle_distill";

    /**
     * 记忆系统重构：实体状态更新与失效判定（编年史涉及实体 → 判定世界视角公共状态
     * 是否变化并给出新状态；场景 scene=entity_state_update）
     */
    public static final String CODE_ENTITY_STATE_UPDATE = "entity_state_update";

    /**
     * 时间季节系统：环境描述 AI 润色（统一环境服务：按季节/时段/天气/体感
     * 撰写环境描写；场景 scene=environment_desc；帧生成/天气切换后异步预生成 4 时段，缓存+每日预算门控）
     */
    public static final String CODE_ENVIRONMENT_DESC = "environment_desc";

    /**
     * 宏观社会演化系统：重大宏观事件 AI 叙事（阈值触发——粮价波动&gt;20%、
     * 势力影响力位移&gt;30% 等重大变化 → LLM 叙事化宏观事件；场景 scene=macro_event；
     * 独立每日 AI 预算，失败/耗尽回退规则模板）
     */
    public static final String CODE_MACRO_EVENT = "macro_event";

    /**
     * 宏观社会演化系统：重大行动宏观影响 AI 判定（NPC 重大行动[urgency≥4]
     * 异步判定本次行动对宏观的影响——规则映射表的补充叠加；场景 scene=macro_impact；
     * 独立每日 AI 预算，失败/耗尽不叠加零阻断）
     */
    public static final String CODE_MACRO_IMPACT = "macro_impact";

    /**
     * 宏观社会演化系统 （中观协调层）：组织差异化指令 AI 生成（宏观重大事件 →
     * 组织接收三通道指令[倾向/约束/资源提示]，AI 个性化；场景 scene=meso_directive；
     * 档位联动：max 全 AI / light·full 重要事件 AI，失败/耗尽回退规则模板）
     */
    public static final String CODE_MESO_DIRECTIVE = "meso_directive";

    /**
     * 宏观社会演化系统 （二阶涌现检测）：涌现事件 AI 叙事（同类行为聚集检测命中后
     * 叙事化——囤积潮/迁徙潮/抗议潮等；场景 scene=macro_emergence；
     * 预算门控，失败回退规则文本）
     */
    public static final String CODE_MACRO_EMERGENCE = "macro_emergence";

    /**
     * 记忆内核扩展：L1 短期记忆「自然遗忘」式模糊化（三维降级：细节
     * 粒度/确定性/检索难度；场景 scene=memory_fuzz；四档联动——max/full 每次 AI 模糊、
     * light 低频 AI/规则混合、programmatic 纯规则；AI 失败回退规则降级）
     */
    public static final String CODE_MEMORY_FUZZ = "memory_fuzz";

    /**
     * 记忆内核扩展：睡眠清洗琐事合并概括（角色睡前把重要度≤2 的琐碎
     * 经历合并为一条信息量大减的概括记忆重存 L1；场景 scene=memory_sleep_merge；
     * max/full AI 概括、light/programmatic 规则模板概括）
     */
    public static final String CODE_MEMORY_SLEEP_MERGE = "memory_sleep_merge";

    /**
     * 记忆内核扩展：跨层蒸馏（L1→L2 语义化/L2→L3 关系倾向级抽象；
     * 场景 scene=memory_distill；max/full AI 蒸馏、light/programmatic 规则蒸馏兜底；
     * 输出 JSON 契约：content/importanceScore/entityRefs/reusedFacts/droppedDetails）
     */
    public static final String CODE_MEMORY_DISTILL = "memory_distill";

    /**
     * 记忆内核扩展：注入预算档位 AI 判定（每次注入前判定
     * memory_budget_tier=LOW/MID/HIGH；场景 scene=memory_tier；失败回退规则判定
     * [简单事实词→LOW/决策关系词→MID~HIGH/urgency≥4 或反思→HIGH]）
     */
    public static final String CODE_MEMORY_TIER = "memory_tier";

    /**
     * 决策与协调：意图冲突二次协商（场景 scene=negotiate；预算内
     * AI 协商——冲突且本方可让时由 AI 决定 proceed/wait/yield/redirect，失败回退确定性
     * 等待/改道兜底；现状优化轨道 §7.2）
     */
    public static final String CODE_NEGOTIATE = "negotiate";

    /**
     * 行为质量评估：LLM-as-Judge 决策样本评分（离线批跑——
     * 定制五维 1-5 绝对评分 + 偏见规避协议[绝对评分/固定 rubric 锚点/长度上限/逐维 chain-of-thought]）
     */
    public static final String CODE_BEHAVIOR_JUDGE = "behavior_judge";

    /**
     * 轨道：角色档案 AI 两段式建档 · 第一段（①身份与社会 ②外貌与体质 ④气质与人格
     * ⑤价值观与动机 ⑨经历与创伤；输出 11 维 v2 契约的 dims 子集；scene=persona_bootstrap_fill_a；
     * 温度 0.4 / 上限 1800，见 PromptSpecRegistry）
     */
    public static final String CODE_PERSONA_FILL_A = "persona_bootstrap_fill_a";

    /**
     * 轨道：角色档案 AI 两段式建档 · 第二段（③生理基线 ⑥能力与知识 ⑦语言与表达
     * ⑧习惯与日常 ⑩发育·遗传·谱系 ⑪社会面具；输入=第一段结果+同一骨架；scene=persona_bootstrap_fill_b；
     * 温度 0.5 / 上限 1800）
     */
    public static final String CODE_PERSONA_FILL_B = "persona_bootstrap_fill_b";

    /**
     * 轨道：角色档案逐维生成（前缀缓存友好版：一次调用只产 1 维，11 次调用攒满全档；
     * 段落顺序固定为「世界观 → 11 维通用要求/契约/示例 → 角色基础原始信息 → 已生成前序维 → 本次生成」，
     * 前四段在同角色的 11 次调用里逐字节一致，缓存可复用到"本次生成"之前；
     * scene=persona_dim_generate；温度 0.45 / 上限 1200，见 PromptSpecRegistry）
     */
    public static final String CODE_PERSONA_DIM = "persona_dim_generate";

    /**
     * 轨道：此刻可发生空间生成（四段式 SCHEMA_JSON：inner/possible_states/
     * possible_actions/forbidden；scene=growth_space_generate；温度 0.75 / 上限 700）
     */
    public static final String CODE_SPACE_GENERATE = "growth_space_generate";

    /**
     * 轨道：阶段跨越 AI 叙事（编年史入口，正文；scene=growth_stage_transition_narrative；
     * 温度 0.8 / 上限 500）
     */
    public static final String CODE_STAGE_TRANSITION_NARRATIVE = "growth_stage_transition_narrative";

    /**
     * 轨道：死亡 AI 叙事（编年史入口，正文；scene=growth_death_chronicle；
     * 温度 0.7 / 上限 400）
     */
    public static final String CODE_DEATH_CHRONICLE = "growth_death_chronicle";

    /**
     * 轨道：发育阶段行为润色（正文一句话；scene=growth_stage_behavior；
     * 温度 0.7 / 上限 300——O6 发育演化 stage_gating 策略的 AI 润色通道：
     * 把程序化行为骨架展开为带个人化细节的行动，越界/失败回退骨架）
     */
    public static final String CODE_STAGE_BEHAVIOR = "growth_stage_behavior";

    /**
     * 轨道：开局家族·成长档案精修（scene=growth_family_profile_infer；
     * 温度 0.2 / 上限 900——从既有身份/档案里读出每个角色的性别与年龄，输出
     * {@code {"people":[{"name","gender","age"}]}}；批量覆盖一批角色，故上限留足）
     */
    public static final String CODE_FAMILY_PROFILE_INFER = "growth_family_profile_infer";

    /**
     * 自省-行为一致性校验（自省漂移检测·候选 A）：判断角色最近反思与本次决策是否一致。
     */
    public static final String CODE_SELF_CHECK = "self_check";

    /**
     * 主动记忆检索判定（ProactAgent「Ask Only When Needed」·主动记忆检索）：
     * 角色按当前情境判断「是否需要主动想起/查证某事」，需要时产出定向检索 query；
     * scene=proactive_retrieval；温度 0.3 / 上限 256。
     */
    public static final String CODE_PROACTIVE_RETRIEVAL = "proactive_retrieval";

    /**
     * 能力自评卡更新（self-efficacy，P1 ①-2）：反思后角色按近期经历（成功/失败信号）微调
     * 自己对各领域能力的主观评估（1-5）；scene=self_efficacy_update；温度 0.3 / 上限 400。
     */
    public static final String CODE_SELF_EFFICACY_UPDATE = "self_efficacy_update";

    /**
     * MUSE 策略切换·AI 档位复核（muse_strategy，P1 ①-3）：程序化初始档位（保守/均衡/激进）
     * 抽样交 AI 复核合理性（输入=情境+领域+自评信心+经历卷+初始档位，输出
     * {"posture":"conservative|balanced|aggressive","reason":str}）；AI 失败回退初始档位
     * （fail-open）；scene=muse_strategy_check；温度 0.3 / 上限 256。
     */
    public static final String CODE_MUSE_STRATEGY_CHECK = "muse_strategy_check";

    /**
     * 潜在世界预测注入（P1 ③-2，world_outlook）：角色「脑内预演世界走向」——
     * 基于世界近期趋势（程序化环比）+ 当前宏观简报，预测「未来一天世界走向」一句话
     * （≤80 字）；注入 AI 深思（action_gen {{world_outlook}}）/ 前瞻推演宏观段；
     * AI 失败回退趋势摘要（fail-open）；scene=world_outlook；温度 0.7 / 上限 200。
     */
    public static final String CODE_WORLD_OUTLOOK = "world_outlook";

    /**
     * 世界常识摘要（world_common_knowledge，V2.0 段 1A 稳定世界观注入源）：依最新世界观
     * 设定 AI 提炼 300-500 字「该世界角色都知道的世界常识」——精炼总览（世界格局/主要
     * 聚居地/势力与规则/日常文化风俗），不罗列全部设定、不做句子级截断；
     * 纯文本正文（PLAIN_TEXT，非 JSON）；scene=world_common_knowledge；
     * 温度 0.4 / 上限 900（300-500 汉字 ≈ 450-750 token 给足余量）。
     */
    public static final String CODE_WORLD_COMMON_KNOWLEDGE = "world_common_knowledge";

    /**
     * 世界名与世界简介建议（world_meta_suggest）：根据已生成的世界观设定，产出一句世界名
     * （6-14 字）与一段世界简介（60-100 字），用于世界画廊卡片展示；
     * 输出 {"name":"…","summary":"…"}（SCHEMA_JSON）；scene=world_meta_suggest；温度 0.8 / 上限 300。
     * <p>本模板与 {@code WorldMetaSuggestService} 的内联 Prompt 配套维护（服务内联组装、
     * 运行时零模板库依赖，与 {@code player_profile_gen} 同模式），此处为版本留痕与设置页可见。</p>
     */
    public static final String CODE_WORLD_META_SUGGEST = "world_meta_suggest";

    /**
     * 记忆-知识桥接判定（P1 ②-3，knowledge_bridge）：反思洞察 → 「可复用事实型知识候选」
     * 抽取（输入=角色档案摘要 + 本次反思洞察列表，输出
     * {"knowledge_candidates":[{content,importance,is_knowledge}]}）；独立 AI 调用
     * （反思 AfterCommit 时序）；全自动确认后写入角色级「经历沉淀」文档；
     * scene=knowledge_bridge；温度 0.3 / 上限 512。
     */
    public static final String CODE_KNOWLEDGE_BRIDGE = "knowledge_bridge";

    /** 世界地图生成（world_map_generate）：AI 依据地点/世界观/角色/既有布局种子生成详细世界地图 */
    public static final String CODE_WORLD_MAP_GENERATE = "world_map_generate";

    /**
     * 世界地图 · S0 世界档案（world_map_profile）：把世界观地理设定翻成一组「可解释的地理量」
     * （岛多狭长、山脊朝哪、平原多大、河几条…），<b>不含任何坐标</b>；地形栅格由算法确定性推导。
     * SCHEMA_JSON；scene=world_map_profile；温度 0.5 / 上限 2048。
     */
    public static final String CODE_WORLD_MAP_PROFILE = "world_map_profile";

    /**
     * 世界地图 · S2 建筑资产（world_map_assets）：依据世界观与禁忌清单产出「这一带该有什么房子」
     * 的类型库（尺寸/层数/墙色/屋顶形制/权重），<b>不含坐标</b>；落位由 S4 按语义求。
     * SCHEMA_JSON；scene=world_map_assets；温度 0.5 / 上限 8192。
     */
    public static final String CODE_WORLD_MAP_ASSETS = "world_map_assets";

    /**
     * 世界地图 · S3 路网拓扑（world_map_roads）：只给「谁连谁 + 路性格」（主干/巷道/桥），
     * 坐标由最小代价 A* 在真实地形上求；<b>不含坐标</b>。
     * SCHEMA_JSON；scene=world_map_roads；温度 0.4 / 上限 4096。
     */
    public static final String CODE_WORLD_MAP_ROADS = "world_map_roads";

    /**
     * 世界地图 · S0b 世界→地图契约（world_map_brief）：把世界观/地点/住所翻成
     * 「区域方位 + 公共设施清单 + 聚落约束」；<b>只给语义与方位，绝不给坐标</b>。
     * 人口（户数/户均）由程序从住所表推导，不交给 AI。
     * SCHEMA_JSON；scene=world_map_brief；温度 0.5 / 上限 2048。
     */
    public static final String CODE_WORLD_MAP_BRIEF = "world_map_brief";

    /**
     * 途中轻量交互（move_encounter）：移动者途中迎面遇到他角色时，AI 决定是否停下搭话
     * （SCHEMA_JSON：{greet:"一句话", stop:bool}；scene=move_encounter；温度 0.5 / 上限 200；
     * 位置移动与地图生成系统 M3 §4.6）
     */
    public static final String CODE_MOVE_ENCOUNTER = "move_encounter";

    /**
     * 轨道：人格偏移评估（SCHEMA_JSON：shifts[{dim,field,delta,why}]+summary；
     * scene=growth_persona_shift_eval；温度 0.3 / 上限 500）
     */
    public static final String CODE_PERSONA_SHIFT_EVAL = "growth_persona_shift_eval";

    /**
     * 轨道：心动判定（SCHEMA_JSON：{attracted,confidence,reason}；
     * scene=growth_romance_decision；温度 0.4 / 上限 300；决策模型 boolean question 同构）
     */
    public static final String CODE_ROMANCE_DECISION = "growth_romance_decision";

    /**
     * 轨道：求婚判定（SCHEMA_JSON：{propose,target_ref,reason}；
     * scene=growth_proposal_decision；温度 0.4 / 上限 300；决策模型 choice question 同构）
     */
    public static final String CODE_PROPOSAL_DECISION = "growth_proposal_decision";

    /**
     * 轨道：生育意愿判定（SCHEMA_JSON：{want,reason}；
     * scene=growth_fertility_decision；温度 0.4 / 上限 300；决策模型 score/boolean question 同构）
     */
    public static final String CODE_FERTILITY_DECISION = "growth_fertility_decision";

    /**
     * 轨道：新生儿档案（SCHEMA_JSON：{given_name,appearance,...}；
     * scene=growth_newborn_persona；温度 0.6 / 上限 600； 分阶段揭示的出生维）
     */
    public static final String CODE_NEWBORN_PERSONA = "growth_newborn_persona";

    /**
     * 轨道：婚礼叙事（正文 PLAIN_TEXT；scene=growth_wedding_narrative；
     * 温度 0.8 / 上限 400）
     */
    public static final String CODE_WEDDING_NARRATIVE = "growth_wedding_narrative";

    /**
     * N 轨道：决策模型 A/B 判定合理性评分（LLM-as-Judge 四维 1-5 绝对评分——
     * 一致性/合理性/情境利用/自然度；复用 JudgeChatClient 独立云端模型；scene=decision_ab_judge）
     */
    public static final String CODE_DECISION_AB_JUDGE = "decision_ab_judge";

    /**
     * 项目级 AI 助手·工具决策（assistant_tool，jsonMode 结构化输出）——
     * 助手工具循环的「模型选工具」一步：输出 {"tool_calls":[...]} 或 {"no_tool":true}。
     */
    public static final String CODE_ASSISTANT_TOOL = "assistant_tool";

    /**
     * 项目级 AI 助手·最终回答（assistant_chat，PLAIN_TEXT 流式正文）——
     * 工具结果回喂后的最终回答（叙事/事实双模式），正文直接输出、无 JSON 外壳。
     */
    public static final String CODE_ASSISTANT_CHAT = "assistant_chat";

    /**
     * 对谈·单聊回复（dialogue_reply，PLAIN_TEXT 流式正文）——
     * 对谈模式的单聊回复（与「浮生」同级的世界静止聊天）。上下文装配见
     * DialogueContextAssembler（L0 稳定 system + L1 动态 user）；本模板为版本留痕
     * 与「设置-提示词模板」可见（对齐 assistant_* 先例）。
     */
    public static final String CODE_DIALOGUE_REPLY = "dialogue_reply";

    /**
     * 对谈·AI 动态开场白（dialogue_greeting，PLAIN_TEXT 正文）——
     * 新会话角色第一句话（≤80 字，第一人称不代答）。同上为版本留痕模板。
     */
    public static final String CODE_DIALOGUE_GREETING = "dialogue_greeting";

    /**
     * 对谈·超窗自动摘要（dialogue_summary，PLAIN_TEXT 正文）——
     * M4 长对话滚动窗口：超窗前段折叠为 AI 摘要（与既有摘要合并，≤300 字），
     * 缓存到会话 summary 字段，装配时以【对话摘要】注入。运行时由
     * DialogueSummaryService 组装（既有摘要 + 新增超窗批），本模板为版本留痕模板。
     */
    public static final String CODE_DIALOGUE_SUMMARY = "dialogue_summary";

    /**
     * 对谈·群聊逐角色回复（dialogue_group_reply，PLAIN_TEXT 流式正文）——
     * M5 群聊：每回应独立 AI 调用、复用单聊流式正文形态（设计 §6.4 串行流式；
     * 实施抓修：设计稿 §8.3 拟 DIALOG_JSON 整轮契约，M5 编排决策为确定性全员依次 +
     * 逐角色独立调用 → PLAIN_TEXT 流式正文，DIALOG_JSON 留待 AI 编排模式扩展）。
     * 上下文装配见 DialogueContextAssembler.composeGroup（本人卡 + 场景段 + 【对家】+ 群聊历史），
     * 本模板为版本留痕模板。
     */
    public static final String CODE_DIALOGUE_GROUP_REPLY = "dialogue_group_reply";

    /**
     * 对谈·沉淀（dialogue_consolidation，SCHEMA_JSON）——
     * M6 沉淀收尾：从会话最近对话中抽取每位对谈角色的关系 delta（好感/信任/阶段/备注/状态摘要）
     * 与 NPC 记忆要点（与玩家的活动/共识/承诺）。运行时由 DialogueConsolidationService 组装
     * （会话角色清单 + 当前关系 + 最近对话窗），jsonMode=true 只输出 relations 数组；
     * 本模板为版本留痕与「设置-提示词模板」可见。
     */
    public static final String CODE_DIALOGUE_CONSOLIDATION = "dialogue_consolidation";

    /**
     * 对谈·世界回写批量抽取（dialog_digest，SCHEMA_JSON）——
     * 一次 AI 调用同时输出关系/记忆与强声明（PROMISE/STATEMENT）。
     * 运行时由 {@code DialogDigestService} 组装（参与者 + 已知事实清单 + 最近对话），
     * 每 6 轮或会话关闭触发；本模板为版本留痕与「设置-提示词模板」可见。
     */
    public static final String CODE_DIALOG_DIGEST = "dialog_digest";

    /**
     * 行为裁决（action_adjudicate，SCHEMA_JSON）——
     * 规则 UNSURE 且行动重要度达到阈值时的一次闭集裁决（三态 + ≤4 条效果草稿）。
     * 运行时由 {@code AdjudicationAiService} 组装；本模板为版本留痕与「设置-提示词模板」可见。
     */
    public static final String CODE_ACTION_ADJUDICATE = "action_adjudicate";

    /**
     * 玩家 persona 六维 AI 补充生成（player_profile_gen，SCHEMA_JSON）——
     * 新用户引导「我的形象」页：用户写一段自我介绍 → AI 生成六维
     * （identity/background/personality/voice/goals/ability）回填预览。
     * 运行时由 PlayerProfileGenService 组装（自我介绍为 {{self_intro}} 占位），
     * 本模板为版本留痕与「设置-提示词模板」可见（对齐 dialogue_* 先例）。
     */
    public static final String CODE_PLAYER_PROFILE_GEN = "player_profile_gen";

    /* ============================================================
       模板功能分类（V3：设置页「Prompt 模板」按功能横向 Tab 分组展示）
       key = 分类键（落库 actor_prompt_template.category）；label = 页面 Tab 文案。
       CODE_CATEGORY 以编码 -> 分类键建立全量映射（91 个内置模板逐一归类），
       seed 与覆盖创建时回填；未知编码归入 "other"（前端归到「其他」Tab）。
       说明：分类是模板编码的稳定属性（按 prompt 所属功能划分），
       与 PromptSpecRegistry 的场景注册表无关，仅用于设置页浏览分组。
       ============================================================ */
    /** 全部分类（键 -> Tab 文案），顺序即设置页横向 Tab 顺序 */
    public static final java.util.LinkedHashMap<String, String> CATEGORIES = new java.util.LinkedHashMap<>() {{
        put("dialogue", "对话交流");
        put("character", "角色档案");
        put("relation", "关系与成长");
        put("worldgen", "世界观生成");
        put("world", "世界事件与演化");
        put("action", "行动与计划");
        put("memory", "记忆与知识");
        put("crowd", "人群与调度");
        put("import", "导入与解析");
        put("judge", "自省与评估");
        put("other", "其他");
    }};

    /** 模板编码 -> 功能分类键（全量内置模板归类；缺失回退 "other"） */
    static final java.util.Map<String, String> CODE_CATEGORY = Map.ofEntries(
            // 导入与解析
            Map.entry(CODE_PROJECT_IMPORT, "import"),
            Map.entry(CODE_PROJECT_IMPORT_CHARACTERS, "import"),
            Map.entry(CODE_PROJECT_IMPORT_FIELD, "import"),
            Map.entry(CODE_WORLD_SEGMENT, "import"),
            Map.entry(CODE_WORLD_SEGMENT_CHARACTERS, "import"),
            // 角色档案
            Map.entry(CODE_CARD_GEN, "character"),
            Map.entry(CODE_PERSONA_FILL_A, "character"),
            Map.entry(CODE_PERSONA_FILL_B, "character"),
            Map.entry(CODE_PERSONA_DIM, "character"),
            Map.entry(CODE_NPC_EMOTION_EVAL, "character"),
            Map.entry(CODE_PLAYER_PROFILE_GEN, "character"),
            // 关系与成长
            Map.entry(CODE_RELATION_GEN, "relation"),
            Map.entry(CODE_RELATION_REFINE, "relation"),
            Map.entry(CODE_ROMANCE_DECISION, "relation"),
            Map.entry(CODE_PROPOSAL_DECISION, "relation"),
            Map.entry(CODE_FERTILITY_DECISION, "relation"),
            Map.entry(CODE_WEDDING_NARRATIVE, "relation"),
            Map.entry(CODE_NEWBORN_PERSONA, "relation"),
            Map.entry(CODE_PERSONA_SHIFT_EVAL, "relation"),
            Map.entry(CODE_SPACE_GENERATE, "relation"),
            Map.entry(CODE_STAGE_TRANSITION_NARRATIVE, "relation"),
            Map.entry(CODE_DEATH_CHRONICLE, "relation"),
            Map.entry(CODE_STAGE_BEHAVIOR, "relation"),
            Map.entry(CODE_FAMILY_PROFILE_INFER, "relation"),
            // 世界观生成
            Map.entry(CODE_WORLDGEN_CORELAWS, "worldgen"),
            Map.entry(CODE_WORLDGEN_SPACETIME, "worldgen"),
            Map.entry(CODE_WORLDGEN_SOCIETY, "worldgen"),
            Map.entry(CODE_WORLDGEN_HISTORYCULTURE, "worldgen"),
            Map.entry(CODE_WORLDGEN_EXISTENCE, "worldgen"),
            Map.entry(CODE_WORLDGEN_INFORMATION, "worldgen"),
            Map.entry(CODE_WORLDGEN_CHARACTER_ROSTER, "worldgen"),
            Map.entry(CODE_WORLDGEN_CHARACTER_DEPTH, "worldgen"),
            Map.entry(CODE_WORLDGEN_STORY_PLAN, "worldgen"),
            Map.entry(CODE_WORLDGEN_STYLE_PROFILE, "worldgen"),
            Map.entry(CODE_WORLD_MAP_GENERATE, "worldgen"),
            Map.entry(CODE_WORLD_MAP_PROFILE, "worldgen"),
            Map.entry(CODE_WORLD_MAP_BRIEF, "worldgen"),
            Map.entry(CODE_WORLD_MAP_ASSETS, "worldgen"),
            Map.entry(CODE_WORLD_MAP_ROADS, "worldgen"),
            Map.entry(CODE_SCENE_GENERATE, "worldgen"),
            // 世界事件与演化
            Map.entry(CODE_WORLD_EVENT, "world"),
            Map.entry(CODE_WORLD_EVENT_POLISH, "world"),
            Map.entry(CODE_EVOLUTION, "world"),
            Map.entry(CODE_EVOLUTION_SCHEDULE, "world"),
            Map.entry(CODE_WORLD_FRAME_DAILY, "world"),
            Map.entry(CODE_INFO_DISTORTION, "world"),
            Map.entry(CODE_ENVIRONMENT_DESC, "world"),
            Map.entry(CODE_MACRO_EVENT, "world"),
            Map.entry(CODE_MACRO_IMPACT, "world"),
            Map.entry(CODE_MESO_DIRECTIVE, "world"),
            Map.entry(CODE_MACRO_EMERGENCE, "world"),
            Map.entry(CODE_WORLD_COMMON_KNOWLEDGE, "world"),
            Map.entry(CODE_WORLD_META_SUGGEST, "world"),
            Map.entry(CODE_WORLD_OUTLOOK, "world"),
            Map.entry(CODE_WORLD_TIME_INFER, "world"),
            // 行动与计划
            Map.entry(CODE_ACTION, "action"),
            Map.entry(CODE_GOAL_GEN, "action"),
            Map.entry(CODE_PLAN_DAY, "action"),
            Map.entry(CODE_PLAN_REPLAN, "action"),
            Map.entry(CODE_INTERRUPT_RESUME, "action"),
            Map.entry(CODE_INTERRUPT_PROBE, "action"),
            Map.entry(CODE_NEGOTIATE, "action"),
            Map.entry(CODE_MOVE_ENCOUNTER, "action"),
            // 记忆与知识
            Map.entry(CODE_MEMORY_EXTRACT, "memory"),
            Map.entry(CODE_MEMORY_SUMMARIZE, "memory"),
            Map.entry(CODE_MEMORY_EXTRACT_REVIEW, "memory"),
            Map.entry(CODE_CHRONICLE_DISTILL, "memory"),
            Map.entry(CODE_ENTITY_STATE_UPDATE, "memory"),
            Map.entry(CODE_CORE_MEMORY_UPDATE, "memory"),
            Map.entry(CODE_MEMORY_FUZZ, "memory"),
            Map.entry(CODE_MEMORY_SLEEP_MERGE, "memory"),
            Map.entry(CODE_MEMORY_DISTILL, "memory"),
            Map.entry(CODE_MEMORY_TIER, "memory"),
            Map.entry(CODE_KNOWLEDGE_BRIDGE, "memory"),
            Map.entry(CODE_PROACTIVE_RETRIEVAL, "memory"),
            // 人群与调度
            Map.entry(CODE_CROWD, "crowd"),
            Map.entry(CODE_CROWD_CATEGORY, "crowd"),
            Map.entry(CODE_CROWD_NPC_GEN, "crowd"),
            Map.entry(CODE_CROWD_SCHEDULE_PROJECT, "crowd"),
            Map.entry(CODE_CROWD_SCHEDULE_AFFILIATION, "crowd"),
            Map.entry(CODE_NPC_CROWD_DECISION, "crowd"),
            // 自省与评估
            Map.entry(CODE_REFLECTION, "judge"),
            Map.entry(CODE_SELF_CHECK, "judge"),
            Map.entry(CODE_SELF_EFFICACY_UPDATE, "judge"),
            Map.entry(CODE_MUSE_STRATEGY_CHECK, "judge"),
            Map.entry(CODE_BEHAVIOR_JUDGE, "judge"),
            Map.entry(CODE_DECISION_AB_JUDGE, "judge"),
            Map.entry(CODE_ACTION_ADJUDICATE, "judge"),
            // 对话交流
            Map.entry(CODE_DIALOG, "dialogue"),
            Map.entry(CODE_GROUP_REPLY, "dialogue"),
            Map.entry(CODE_GROUP, "dialogue"),
            Map.entry(CODE_AUTONOMOUS_CHAT_BEAT, "dialogue"),
            Map.entry(CODE_DIALOGUE_REPLY, "dialogue"),
            Map.entry(CODE_DIALOGUE_GREETING, "dialogue"),
            Map.entry(CODE_DIALOGUE_SUMMARY, "dialogue"),
            Map.entry(CODE_DIALOGUE_GROUP_REPLY, "dialogue"),
            Map.entry(CODE_DIALOGUE_CONSOLIDATION, "dialogue"),
            Map.entry(CODE_DIALOG_DIGEST, "dialogue"),
            Map.entry(CODE_ASSISTANT_TOOL, "dialogue"),
            Map.entry(CODE_ASSISTANT_CHAT, "dialogue")
    );

    /** 模板编码 -> 功能分类键（未知回退 "other"） */
    public static String categoryOf(String code) {
        return CODE_CATEGORY.getOrDefault(code, "other");
    }

    /** 分类键 -> Tab 文案（未知回退「其他」） */
    public static String categoryLabel(String category) {
        return CATEGORIES.getOrDefault(category == null ? "other" : category, "其他");
    }

    /**
     * 全部分类（有序，供设置页横向 Tab 渲染）：{key, label, count}。
     * count = 当前有效列表（含覆盖合并后）各分类的模板数，供 Tab 角标。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户覆盖/内置）
     *
     * @return 有序分类列表（含空分类，前端始终展示全部 Tab）
     */
    public List<java.util.Map<String, Object>> categories(Long userId, Long projectId) {
        List<ActorPromptTemplate> list = effectiveList(userId, projectId);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (ActorPromptTemplate t : list) {
            counts.merge(categoryOf(t.getCode()), 1, Integer::sum);
        }
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        CATEGORIES.forEach((key, label) -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("key", key);
            m.put("label", label);
            m.put("count", counts.getOrDefault(key, 0));
            out.add(m);
        });
        return out;
    }

    /** 内置模板归属用户标记 */
    private static final long BUILTIN_USER_ID = 0L;

    /** 模板仓库 */
    private final ActorPromptTemplateRepository repository;

    /**
     * 残留占位符清扫：渲染后未提供的 {{标识符}} 统一抹除（模板新增占位符而调用方未传时，
     * 防 prompt 出现裸占位符；只匹配标识符形态，不影响 JSON 双大括号文本）
     */
    private static final java.util.regex.Pattern RESIDUAL_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{\\{[A-Za-z_][A-Za-z0-9_]*}}");

    /** 渲染后清扫残留占位符（模板缺内容兜底：未知/未传占位符 → 抹除而非残留原文）。 */
    private static String stripResidualPlaceholders(String text) {
        return text == null ? "" : RESIDUAL_PLACEHOLDER.matcher(text).replaceAll("");
    }

    /**
     * 应用启动时幂等初始化内置模板（user_id=0，缺失才插入；<b>内容变更则同步更新</b>）。
     * <p>改为「upsert」：内置模板内容随代码版本演进（如普通人群分类体系重构后
     * 字段字典/居民生成/调度模板全面改写），仅「缺失插入」会让旧库保留过期模板内容；
     * 现对已存在的内置行做内容比对，名称或模板不一致时更新（内置行 user_id=0 不可被 UI 编辑，
     * 以代码为准同步安全），版本号 +1。</p>
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        builtins().forEach((code, def) -> {
            ActorPromptTemplate existing = repository.findByUserIdAndCode(BUILTIN_USER_ID, code).orElse(null);
            if (existing == null) {
                ActorPromptTemplate t = new ActorPromptTemplate();
                t.setUserId(BUILTIN_USER_ID);
                t.setCode(code);
                t.setName(def.name());
                t.setTemplate(def.template());
                t.setSystemMessage(def.systemMessage());
                t.setCategory(categoryOf(code));
                repository.save(t);
                log.info("初始化内置 Prompt 模板: {} - {}", code, def.name());
            } else if (!def.name().equals(existing.getName())
                    || !def.template().equals(existing.getTemplate())
                    || !java.util.Objects.equals(def.systemMessage(), existing.getSystemMessage())) {
                // 内置模板内容随代码演进：同步为新版本（内置行不可被用户编辑，以代码为准；含系统提示词）
                existing.setName(def.name());
                existing.setTemplate(def.template());
                existing.setSystemMessage(def.systemMessage());
                existing.setCategory(categoryOf(code));
                existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
                repository.save(existing);
                log.info("同步内置 Prompt 模板内容: {} - {}", code, def.name());
            } else if (!java.util.Objects.equals(categoryOf(code), existing.getCategory())) {
                // 仅分类缺失/不一致：回填分类（V3 迁移后旧库内置行无分类，启动时补齐；不触发版本号）
                existing.setCategory(categoryOf(code));
                repository.save(existing);
                log.info("回填内置 Prompt 模板分类: {} - {}", code, categoryOf(code));
            }
        });
    }

    /**
     * 获取「项目覆盖或用户覆盖或内置」的有效模板（三级回退）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户覆盖/内置）
     *
     * @param code 模板编码
     *
     * @return 命中的模板（无则空）
     */
    public Optional<ActorPromptTemplate> effectiveTemplate(Long userId, Long projectId, String code) {
        // ① 项目级覆盖（project_id=projectId）
        if (projectId != null) {
            Optional<ActorPromptTemplate> project = repository.findByUserIdAndProjectIdAndCode(userId, projectId, code)
                    .filter(t -> Integer.valueOf(1).equals(t.getEnabled()));
            if (project.isPresent()) {
                return project;
            }
        }
        // ② 用户覆盖（project_id IS NULL）
        Optional<ActorPromptTemplate> user = repository.findByUserIdAndProjectIdAndCode(userId, null, code)
                .filter(t -> Integer.valueOf(1).equals(t.getEnabled()));
        if (user.isPresent()) {
            return user;
        }
        // ③ 内置（user_id=0）
        return repository.findByUserIdAndProjectIdAndCode(BUILTIN_USER_ID, null, code)
                .filter(t -> Integer.valueOf(1).equals(t.getEnabled()));
    }

    /**
     * 渲染模板：以「项目覆盖 > 用户覆盖 > 内置」解析模板文本并替换 {{占位符}}。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户覆盖/内置）
     *
     * @param code 模板编码
     *
     * @param placeholders 占位符映射（键不带花括号，如 world_setting）
     *
     * @return 渲染后的 Prompt 文本
     */
    public String render(Long userId, Long projectId, String code, Map<String, Object> placeholders) {
        ActorPromptTemplate t = effectiveTemplate(userId, projectId, code)
                .orElseThrow(() -> new BizException(400, "Prompt 模板不存在：" + code));
        String text = t.getTemplate();
        if (placeholders != null) {
            for (Map.Entry<String, Object> e : placeholders.entrySet()) {
                Object v = e.getValue();
                text = text.replace("{{" + e.getKey() + "}}", v == null ? "" : String.valueOf(v));
            }
        }
        // Prompt 工程：渲染期规范化——全部模板统一输出契约措辞与段分隔符
        // （结构化迁移，零语义改动，存量模板行即时生效）
        String body = com.holzyn.actor.domain.agent.prompt.PromptNormalizer.normalize(stripResidualPlaceholders(text));
        // 前缀缓存优化：所有模板的 user 正文前统一加一段"逐字一致"的项目世界观，
        // 让不同场景（决策/对话/情绪/记忆……）的请求共享同一段最长公共前缀。
        return withProjectContext(projectId, code, body);
    }

    /**
     * 在 user 正文前拼接「共享契约段 + 项目上下文段」，并在正文后拼接「输出要求段」。
     *
     * <p><b>为什么这么排</b>：llama.cpp 的前缀缓存只复用"从第一个 token 起完全一致"的部分，
     * 因此顺序必须是「全局逐字一致 → 本项目逐字一致 → 本场景固定 → 本次任务易变数据 → 输出要求」。
     * 共享契约段（{@code prompts/_shared/contract.md}）跨场景、跨项目完全相同，是最大的一块可复用前缀；
     * 项目世界观次之；模板自带的场景说明再之后；易变占位符排最后。</p>
     *
     * @param projectId 项目 ID（可为 null：世界创建早期尚无项目）
     *
     * @param code 模板编码
     *
     * @param body 已渲染的模板正文
     *
     * @return 拼接后的 user 文本
     */
    private String withProjectContext(Long projectId, String code, String body) {
        StringBuilder sb = new StringBuilder(body.length() + 8192);
        // ① 全局共享契约（所有场景、所有项目逐字一致；模板正文已含自有契约的 persona_dim_generate 除外）
        if (!CONTRACT_SKIP_CODES.contains(code)) {
            sb.append(contractBlock()).append("\n\n");
        }
        // ② 项目世界观（全项目逐字一致）
        if (projectId != null && !CONTEXT_SKIP_CODES.contains(code)) {
            String context = projectContext(projectId);
            if (!context.isEmpty()) {
                sb.append(context).append("\n\n");
            }
        }
        // ②' 常驻前缀（世界级恒定：人物名册 + 地点 + 民情常识）
        // 两游戏日实测：头只有契约+世界观 ≈ 780-1940 token，跨不过服务端 2048 token
        // 的缓存块，命中率被压在 32%。这里把本来就恒定、却散落在各场景正文里的名册/地点前移，
        // 让「契约 + 世界观 + 名册 + 地点」连成一段更长的逐字一致前缀，一次喂给所有场景。
        if (projectId != null && aiContextInjector != null && !CONTEXT_SKIP_CODES.contains(code)) {
            String stable = aiContextInjector.stablePrefix(projectId);
            if (stable != null && !stable.isEmpty()) {
                sb.append(stable).append("\n\n");
            }
        }
        // ③ 模板正文（场景固定段在前，占位符内容由调用方按「固定在前、易变在后」顺序拼好）
        sb.append(body);
        // ④ 输出要求（逐字一致，放最后：不参与前缀复用，但保证"要求在最末尾"的结构约束）
        if (!CONTRACT_SKIP_CODES.contains(code)) {
            sb.append("\n\n").append(requirementsBlock());
        }
        return sb.toString();
    }

    /**
     * 全局共享契约段（带缓存：文本恒定，只在首次渲染时读盘）。
     *
     * @return 契约段文本
     */
    private String contractBlock() {
        if (contractCache == null) {
            contractCache = PromptResources.shared("contract");
        }
        return contractCache;
    }

    /**
     * 全局输出要求段（带缓存）。
     *
     * @return 要求段文本
     */
    private String requirementsBlock() {
        if (requirementsCache == null) {
            requirementsCache = PromptResources.shared("requirements");
        }
        return requirementsCache;
    }

    /**
     * 取项目上下文段（带 TTL 缓存）。
     *
     * @param projectId 项目 ID
     *
     * @return 上下文段文本（无世界观时为空串）
     */
    private String projectContext(Long projectId) {
        long now = System.currentTimeMillis();
        String[] cached = projectContextCache.get(projectId);
        if (cached != null && now - Long.parseLong(cached[0]) < PROJECT_CONTEXT_TTL_MS) {
            return cached[1];
        }
        String text = buildProjectContext(projectId);
        projectContextCache.put(projectId, new String[] {String.valueOf(now), text});
        return text;
    }

    /**
     * 构建项目上下文段：只放"全项目固定、逐字相同"的内容，且字段顺序与空值处理固定，
     * 保证同一项目的每次渲染产出完全相同的字节（否则前缀缓存整段失效）。
     *
     * @param projectId 项目 ID
     *
     * @return 上下文段文本
     */
    private String buildProjectContext(Long projectId) {
        if (worldSettingRepository == null) {
            return "";
        }
        Optional<ActorWorldSetting> found = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId);
        if (found.isEmpty()) {
            return "";
        }
        ActorWorldSetting s = found.get();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("—— 世界设定（全项目共用，逐字一致；任何任务都不得违背） ——\n");
        appendLine(sb, "【世界】", s.getName());
        appendLine(sb, "【题材】", s.getGenre());
        appendLine(sb, "【年代】", s.getEra());
        appendLine(sb, "【地理】", s.getGeography());
        appendLine(sb, "【势力】", s.getFactions());
        appendLine(sb, "【规则】", s.getMagicSystem());
        appendLine(sb, "【文化】", s.getCulture());
        appendLine(sb, "【历史】", s.getHistory());
        appendLine(sb, "【设定】", s.getFreeText());
        return sb.toString().trim();
    }

    /**
     * 追加一行「标签 + 值」；空白值整行省略（保证空值不会造成字节差异）。
     *
     * @param sb 目标缓冲
     *
     * @param label 标签
     *
     * @param value 值
     */
    private void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append(value.trim()).append('\n');
    }

    /**
     * 获取系统提示词（系统提示词随模板落库，代码不再硬编码 Prompt 文本）。
     * 以「项目覆盖 > 用户覆盖 > 内置」解析，取模板的 system_message 字段并替换 {{占位符}}；
     * 未配置（null/空白）返回空串（调用方不发 system 消息，模板自身已含输出约束）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户覆盖/内置）
     *
     * @param code 模板编码
     *
     * @param placeholders 占位符映射（系统提示词一般无占位符，可传 null）
     *
     * @return 系统提示词文本（无则空串）
     */
    public String systemMessage(Long userId, Long projectId, String code, Map<String, Object> placeholders) {
        // 统一 system：全部模板共用同一段（prompts/_shared/system.md），作为每次请求的第一个 token 序列，
        // 使不同场景之间共享前缀缓存；模板自身的 system_message 仍保留在库里（兼容旧覆盖），但不再发送。
        return PromptResources.system(UNIFIED_SYSTEM_CODE);
    }

    /**
     * 内置模板编码集合（调试巡检用；与 {@link #builtins()} 同源，直读资源文件、不经过数据库覆盖）。
     *
     * @return 编码集合（只读）
     */
    public static java.util.Set<String> builtinCodes() {
        return java.util.Collections.unmodifiableSet(builtins().keySet());
    }

    /**
     * 内置模板显示名（调试巡检用）。
     *
     * @param code 模板编码
     *
     * @return 显示名；未知编码返回 null
     */
    public static String builtinName(String code) {
        BuiltinDef def = builtins().get(code);
        return def == null ? null : def.name();
    }

    /**
     * 当前归属有效模板列表（项目覆盖 ∪ 用户覆盖 ∪ 内置，按编码排序；高优先级覆盖同编码低优先级）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=仅用户覆盖/内置）
     *
     * @return 有效模板列表
     */
    public List<ActorPromptTemplate> effectiveList(Long userId, Long projectId) {
        List<ActorPromptTemplate> builtins = repository.findByUserIdOrderByCodeAsc(BUILTIN_USER_ID).stream()
                .filter(t -> Integer.valueOf(1).equals(t.getEnabled())).toList();
        List<ActorPromptTemplate> overrides = repository.findByUserIdAndProjectIdOrderByCodeAsc(userId, null).stream()
                .filter(t -> Integer.valueOf(1).equals(t.getEnabled())).toList();
        List<ActorPromptTemplate> projectOverrides = projectId == null ? List.of()
                : repository.findByUserIdAndProjectIdOrderByCodeAsc(userId, projectId).stream()
                        .filter(t -> Integer.valueOf(1).equals(t.getEnabled())).toList();
        return mergeEffective(builtins, overrides, projectOverrides);
    }

    /**
     * 合并内置 / 用户覆盖 / 项目覆盖（静态可测）：按 code 合并，项目覆盖覆盖用户覆盖覆盖内置，按编码排序。
     *
     * @param builtins 内置模板列表（user_id=0）
     *
     * @param overrides 用户覆盖列表（user_id>0, project_id NULL）
     *
     * @param projectOverrides 项目级覆盖列表（project_id 非空）
     *
     * @return 合并后的有效模板列表
     */
    static List<ActorPromptTemplate> mergeEffective(List<ActorPromptTemplate> builtins,
                                                    List<ActorPromptTemplate> overrides,
                                                    List<ActorPromptTemplate> projectOverrides) {
        Map<String, ActorPromptTemplate> byCode = new TreeMap<>();
        builtins.forEach(t -> byCode.put(t.getCode(), t));
        overrides.forEach(t -> byCode.put(t.getCode(), t));
        projectOverrides.forEach(t -> byCode.put(t.getCode(), t));
        return new ArrayList<>(byCode.values());
    }

    /**
     * 保存覆盖模板（不存在则新建，存在则更新内容）。projectId 非空保存为项目级覆盖，
     * 为空保存为用户级覆盖（同归属同编码互斥）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=用户级覆盖）
     *
     * @param code 模板编码
     *
     * @param name 模板名称（可空，缺省保留内置名称）
     *
     * @param template 模板内容
     *
     * @param systemMessage 系统提示词（可空； 起随模板落库）
     *
     * @return 保存后的模板
     */
    public ActorPromptTemplate saveOverride(Long userId, Long projectId, String code, String name, String template,
                                            String systemMessage) {
        if (userId == null || userId <= 0) {
            throw new BizException(400, "内置模板不允许编辑，请先创建覆盖");
        }
        if (template == null || template.isBlank()) {
            throw new BizException(400, "模板内容不能为空");
        }
        ActorPromptTemplate t = repository.findByUserIdAndProjectIdAndCode(userId, projectId, code).orElseGet(() -> {
            ActorPromptTemplate nt = new ActorPromptTemplate();
            nt.setUserId(userId);
            nt.setProjectId(projectId);
            nt.setCode(code);
            nt.setCategory(categoryOf(code));
            return nt;
        });
        // 覆盖行分类：新行已按编码回填；旧行（V3 前无分类）也按编码补齐，保证设置页分组稳定
        if (t.getCategory() == null || t.getCategory().isBlank()) {
            t.setCategory(categoryOf(code));
        }
        if (name != null && !name.isBlank()) t.setName(name);
        t.setTemplate(template);
        if (systemMessage != null) t.setSystemMessage(systemMessage.isBlank() ? null : systemMessage);
        t.setVersion(t.getVersion() == null ? 1 : t.getVersion() + 1);
        t.setEnabled(1);
        return repository.save(t);
    }

    /**
     * 重置为低一级：删除项目级/用户级覆盖行，回退用户覆盖或内置模板。
     * 注意：派生删除查询必须处于事务中，否则抛「No EntityManager with actual transaction」。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID（NULL=删除用户级覆盖）
     *
     * @param code 模板编码
     */
    @org.springframework.transaction.annotation.Transactional
    public void reset(Long userId, Long projectId, String code) {
        if (userId != null && userId > 0) {
            repository.deleteByUserIdAndProjectIdAndCode(userId, projectId, code);
        }
    }

    /**
     * 内置模板定义（编码 -> 名称与内容；内容含 {{占位符}} 由 render 替换）。
     *
     * @return 内置模板映射
     */
    static Map<String, BuiltinDef> builtins() {
        // 模板正文一律走 classpath 资源：prompts/<code>/user.md 与 system.md（加载器见 PromptResources）。
        // 为什么不再内联：98 个模板合计 30 万字符，内联会把本文件撑到 4500 行以上，且无法用 diff 审阅。
        // 维护约定：改模板 = 改资源文件；新增模板 = 加目录 + 在下方登记一行（顺序保持不变，便于按代码审阅）。
        Map<String, BuiltinDef> map = new java.util.LinkedHashMap<>();
        map.put(CODE_PROJECT_IMPORT, BuiltinDef.ofResource("文件导入解析-世界观", CODE_PROJECT_IMPORT));
        map.put(CODE_PROJECT_IMPORT_CHARACTERS, BuiltinDef.ofResource("文件导入解析-角色", CODE_PROJECT_IMPORT_CHARACTERS));
        map.put(CODE_PROJECT_IMPORT_FIELD, BuiltinDef.ofResource("文件导入解析-世界观字段", CODE_PROJECT_IMPORT_FIELD));
        map.put(CODE_CARD_GEN, BuiltinDef.ofResource("角色档案生成（11 维 v2）", CODE_CARD_GEN));
        map.put(CODE_PERSONA_FILL_A, BuiltinDef.ofResource("角色档案-两段式建档·第一段", CODE_PERSONA_FILL_A));
        map.put(CODE_PERSONA_FILL_B, BuiltinDef.ofResource("角色档案-两段式建档·第二段", CODE_PERSONA_FILL_B));
        map.put(CODE_PERSONA_DIM, BuiltinDef.ofResource("角色档案-逐维生成（缓存友好）", CODE_PERSONA_DIM));
        map.put(CODE_DIALOG, BuiltinDef.ofResource("对话系统", CODE_DIALOG));
        map.put(CODE_GROUP_REPLY, BuiltinDef.ofResource("群聊逐角色回复", CODE_GROUP_REPLY));
        map.put(CODE_GROUP, BuiltinDef.ofResource("群聊编排", CODE_GROUP));
        map.put(CODE_WORLD_EVENT, BuiltinDef.ofResource("世界事件", CODE_WORLD_EVENT));
        map.put(CODE_ACTION, BuiltinDef.ofResource("行动生成", CODE_ACTION));
        map.put(CODE_CROWD, BuiltinDef.ofResource("人群集体行动编排", CODE_CROWD));
        map.put(CODE_MEMORY_EXTRACT, BuiltinDef.ofResource("长期记忆抽取（三重机制重构）", CODE_MEMORY_EXTRACT));
        map.put(CODE_MEMORY_SUMMARIZE, BuiltinDef.ofResource("会话摘要生成", CODE_MEMORY_SUMMARIZE));
        map.put(CODE_MEMORY_EXTRACT_REVIEW, BuiltinDef.ofResource("记忆抽取按需复核", CODE_MEMORY_EXTRACT_REVIEW));
        map.put(CODE_CHRONICLE_DISTILL, BuiltinDef.ofResource("编年史提炼", CODE_CHRONICLE_DISTILL));
        map.put(CODE_ENTITY_STATE_UPDATE, BuiltinDef.ofResource("实体状态更新与失效判定", CODE_ENTITY_STATE_UPDATE));
        map.put(CODE_CORE_MEMORY_UPDATE, BuiltinDef.ofResource("L3 核心块自编辑", CODE_CORE_MEMORY_UPDATE));
        map.put(CODE_EVOLUTION, BuiltinDef.ofResource("世界演化编排", CODE_EVOLUTION));
        map.put(CODE_SCENE_GENERATE, BuiltinDef.ofResource("场景自动生成", CODE_SCENE_GENERATE));
        map.put(CODE_EVOLUTION_SCHEDULE, BuiltinDef.ofResource("演化逐拍调度", CODE_EVOLUTION_SCHEDULE));
        map.put(CODE_RELATION_GEN, BuiltinDef.ofResource("角色关系生成", CODE_RELATION_GEN));
        map.put(CODE_RELATION_REFINE, BuiltinDef.ofResource("角色关系 AI 精修", CODE_RELATION_REFINE));
        map.put(CODE_CROWD_CATEGORY, BuiltinDef.ofResource("普通人群-标准字段数据拟定", CODE_CROWD_CATEGORY));
        map.put(CODE_CROWD_NPC_GEN, BuiltinDef.ofResource("普通人群-居民批量生成（11 维 v2）", CODE_CROWD_NPC_GEN));
        map.put(CODE_CROWD_SCHEDULE_PROJECT, BuiltinDef.ofResource("普通人群-项目级集体调度", CODE_CROWD_SCHEDULE_PROJECT));
        map.put(CODE_CROWD_SCHEDULE_AFFILIATION, BuiltinDef.ofResource("普通人群-归属级集体调度", CODE_CROWD_SCHEDULE_AFFILIATION));
        map.put(CODE_WORLD_SEGMENT, BuiltinDef.ofResource("世界观文件-总结分段", CODE_WORLD_SEGMENT));
        map.put(CODE_WORLD_SEGMENT_CHARACTERS, BuiltinDef.ofResource("世界观文件-角色完整分离", CODE_WORLD_SEGMENT_CHARACTERS));
        map.put(CODE_WORLD_TIME_INFER, BuiltinDef.ofResource("世界初始化-世界时间推断", CODE_WORLD_TIME_INFER));
        map.put(CODE_WORLD_EVENT_POLISH, BuiltinDef.ofResource("世界引擎-跨日事件润色", CODE_WORLD_EVENT_POLISH));
        map.put(CODE_GOAL_GEN, BuiltinDef.ofResource("角色目标 AI 生成", CODE_GOAL_GEN));
        map.put(CODE_PLAN_DAY, BuiltinDef.ofResource("日计划 AI 生成", CODE_PLAN_DAY));
        map.put(CODE_PLAN_REPLAN, BuiltinDef.ofResource("日计划中断重排", CODE_PLAN_REPLAN));
        map.put(CODE_TEMPORAL_PROBE, BuiltinDef.ofResource("时间性探针（否定状态/回溯/因果）", CODE_TEMPORAL_PROBE));
        map.put(CODE_REFLECTION, BuiltinDef.ofResource("反思洞察生成", CODE_REFLECTION));
        map.put(CODE_AUTONOMOUS_CHAT_BEAT, BuiltinDef.ofResource("无玩家群聊退场/收尾编排", CODE_AUTONOMOUS_CHAT_BEAT));
        map.put(CODE_WORLDGEN_CORELAWS, BuiltinDef.ofResource("世界观生成-维度一 核心法则", CODE_WORLDGEN_CORELAWS));
        map.put(CODE_WORLDGEN_SPACETIME, BuiltinDef.ofResource("世界观生成-维度二 时空地理", CODE_WORLDGEN_SPACETIME));
        map.put(CODE_WORLDGEN_SOCIETY, BuiltinDef.ofResource("世界观生成-维度三 社会权力", CODE_WORLDGEN_SOCIETY));
        map.put(CODE_WORLDGEN_HISTORYCULTURE, BuiltinDef.ofResource("世界观生成-维度四 历史文化", CODE_WORLDGEN_HISTORYCULTURE));
        map.put(CODE_WORLDGEN_EXISTENCE, BuiltinDef.ofResource("世界观生成-维度五 存在基础", CODE_WORLDGEN_EXISTENCE));
        map.put(CODE_WORLDGEN_INFORMATION, BuiltinDef.ofResource("世界观生成-维度六 信息传播", CODE_WORLDGEN_INFORMATION));
        map.put(CODE_WORLDGEN_CHARACTER_ROSTER, BuiltinDef.ofResource("角色工作流-阵容设计", CODE_WORLDGEN_CHARACTER_ROSTER));
        map.put(CODE_WORLDGEN_CHARACTER_DEPTH, BuiltinDef.ofResource("角色工作流-逐角色深度档案", CODE_WORLDGEN_CHARACTER_DEPTH));
        map.put(CODE_WORLDGEN_STORY_PLAN, BuiltinDef.ofResource("故事工作流-故事宏观规划（三层力学引擎·世界版）", CODE_WORLDGEN_STORY_PLAN));
        map.put(CODE_WORLDGEN_STYLE_PROFILE, BuiltinDef.ofResource("故事工作流-风格档案（世界风格宪法）", CODE_WORLDGEN_STYLE_PROFILE));
        map.put(CODE_NPC_EMOTION_EVAL, BuiltinDef.ofResource("NPC 情绪 AI 评估写回", CODE_NPC_EMOTION_EVAL));
        map.put(CODE_NPC_CROWD_DECISION, BuiltinDef.ofResource("人群行动 AI 决策", CODE_NPC_CROWD_DECISION));
        map.put(CODE_INTERRUPT_RESUME, BuiltinDef.ofResource("恢复决策", CODE_INTERRUPT_RESUME));
        map.put(CODE_INTERRUPT_PROBE, BuiltinDef.ofResource("打断前探针（是否放下手头事）", CODE_INTERRUPT_PROBE));
        map.put(CODE_WORLD_FRAME_DAILY, BuiltinDef.ofResource("每日世界帧生成", CODE_WORLD_FRAME_DAILY));
        map.put(CODE_INFO_DISTORTION, BuiltinDef.ofResource("信息认知失真改写", CODE_INFO_DISTORTION));
        map.put(CODE_ENVIRONMENT_DESC, BuiltinDef.ofResource("环境描述润色", CODE_ENVIRONMENT_DESC));
        map.put(CODE_MACRO_EVENT, BuiltinDef.ofResource("宏观事件叙事", CODE_MACRO_EVENT));
        map.put(CODE_MACRO_IMPACT, BuiltinDef.ofResource("重大行动宏观影响判定", CODE_MACRO_IMPACT));
        map.put(CODE_MESO_DIRECTIVE, BuiltinDef.ofResource("中观组织指令生成", CODE_MESO_DIRECTIVE));
        map.put(CODE_MACRO_EMERGENCE, BuiltinDef.ofResource("二阶涌现事件叙事", CODE_MACRO_EMERGENCE));
        map.put(CODE_MEMORY_FUZZ, BuiltinDef.ofResource("L1 记忆自然遗忘模糊化", CODE_MEMORY_FUZZ));
        map.put(CODE_MEMORY_SLEEP_MERGE, BuiltinDef.ofResource("睡眠清洗琐事合并概括", CODE_MEMORY_SLEEP_MERGE));
        map.put(CODE_MEMORY_DISTILL, BuiltinDef.ofResource("跨层记忆蒸馏", CODE_MEMORY_DISTILL));
        map.put(CODE_MEMORY_TIER, BuiltinDef.ofResource("记忆注入预算档位判定", CODE_MEMORY_TIER));
        map.put(CODE_NEGOTIATE, BuiltinDef.ofResource("冲突二次协商", CODE_NEGOTIATE));
        map.put(CODE_BEHAVIOR_JUDGE, BuiltinDef.ofResource("行为质量评估·决策样本评分", CODE_BEHAVIOR_JUDGE));
        map.put(CODE_DECISION_AB_JUDGE, BuiltinDef.ofResource("决策模型 判定合理性评分", CODE_DECISION_AB_JUDGE));
        map.put(CODE_SPACE_GENERATE, BuiltinDef.ofResource("此刻可发生空间生成", CODE_SPACE_GENERATE));
        map.put(CODE_STAGE_TRANSITION_NARRATIVE, BuiltinDef.ofResource("阶段跨越叙事", CODE_STAGE_TRANSITION_NARRATIVE));
        map.put(CODE_DEATH_CHRONICLE, BuiltinDef.ofResource("死亡叙事", CODE_DEATH_CHRONICLE));
        map.put(CODE_FAMILY_PROFILE_INFER, BuiltinDef.ofResource("开局家族档案精修", CODE_FAMILY_PROFILE_INFER));
        map.put(CODE_STAGE_BEHAVIOR, BuiltinDef.ofResource("发育阶段行为润色", CODE_STAGE_BEHAVIOR));
        map.put(CODE_SELF_CHECK, BuiltinDef.ofResource("自省-行为一致性校验", CODE_SELF_CHECK));
        map.put(CODE_PROACTIVE_RETRIEVAL, BuiltinDef.ofResource("主动记忆检索判定", CODE_PROACTIVE_RETRIEVAL));
        map.put(CODE_SELF_EFFICACY_UPDATE, BuiltinDef.ofResource("能力自评卡更新", CODE_SELF_EFFICACY_UPDATE));
        map.put(CODE_MUSE_STRATEGY_CHECK, BuiltinDef.ofResource("MUSE 策略切换·AI 档位复核", CODE_MUSE_STRATEGY_CHECK));
        map.put(CODE_WORLD_OUTLOOK, BuiltinDef.ofResource("潜在世界预测注入", CODE_WORLD_OUTLOOK));
        map.put(CODE_KNOWLEDGE_BRIDGE, BuiltinDef.ofResource("记忆-知识桥接判定", CODE_KNOWLEDGE_BRIDGE));
        map.put(CODE_WORLD_MAP_GENERATE, BuiltinDef.ofResource("世界地图生成", CODE_WORLD_MAP_GENERATE));
        map.put(CODE_WORLD_MAP_PROFILE, BuiltinDef.ofResource("世界地图 · 世界档案（地理参数）", CODE_WORLD_MAP_PROFILE));
        map.put(CODE_WORLD_MAP_BRIEF, BuiltinDef.ofResource("世界地图 · 世界→地图契约（区域与设施）", CODE_WORLD_MAP_BRIEF));
        map.put(CODE_WORLD_MAP_ASSETS, BuiltinDef.ofResource("世界地图 · 建筑资产类型库", CODE_WORLD_MAP_ASSETS));
        map.put(CODE_WORLD_MAP_ROADS, BuiltinDef.ofResource("世界地图 · 路网拓扑（只给连接关系）", CODE_WORLD_MAP_ROADS));
        map.put(CODE_MOVE_ENCOUNTER, BuiltinDef.ofResource("途中轻量交互（是否停下搭话）", CODE_MOVE_ENCOUNTER));
        map.put(CODE_PERSONA_SHIFT_EVAL, BuiltinDef.ofResource("人格偏移评估", CODE_PERSONA_SHIFT_EVAL));
        map.put(CODE_ROMANCE_DECISION, BuiltinDef.ofResource("心动判定", CODE_ROMANCE_DECISION));
        map.put(CODE_PROPOSAL_DECISION, BuiltinDef.ofResource("求婚判定", CODE_PROPOSAL_DECISION));
        map.put(CODE_FERTILITY_DECISION, BuiltinDef.ofResource("生育意愿判定", CODE_FERTILITY_DECISION));
        map.put(CODE_NEWBORN_PERSONA, BuiltinDef.ofResource("新生儿档案", CODE_NEWBORN_PERSONA));
        map.put(CODE_WEDDING_NARRATIVE, BuiltinDef.ofResource("婚礼叙事", CODE_WEDDING_NARRATIVE));
        map.put(CODE_WORLD_COMMON_KNOWLEDGE, BuiltinDef.ofResource("世界常识摘要（300-500 字精炼常识）", CODE_WORLD_COMMON_KNOWLEDGE));
        map.put(CODE_ASSISTANT_TOOL, BuiltinDef.ofResource("项目级 AI 助手-工具决策（结构化）", CODE_ASSISTANT_TOOL));
        map.put(CODE_ASSISTANT_CHAT, BuiltinDef.ofResource("项目级 AI 助手-最终回答（叙事/事实双模式）", CODE_ASSISTANT_CHAT));
        map.put(CODE_DIALOGUE_REPLY, BuiltinDef.ofResource("对谈-单聊回复（流式正文）", CODE_DIALOGUE_REPLY));
        map.put(CODE_DIALOGUE_GREETING, BuiltinDef.ofResource("对谈-AI 动态开场白", CODE_DIALOGUE_GREETING));
        map.put(CODE_PLAYER_PROFILE_GEN, BuiltinDef.ofResource("玩家档案-AI 补充生成", CODE_PLAYER_PROFILE_GEN));
        map.put(CODE_DIALOGUE_SUMMARY, BuiltinDef.ofResource("对谈-超窗自动摘要", CODE_DIALOGUE_SUMMARY));
        map.put(CODE_DIALOGUE_GROUP_REPLY, BuiltinDef.ofResource("对谈-群聊逐角色回复（流式正文）", CODE_DIALOGUE_GROUP_REPLY));
        map.put(CODE_DIALOGUE_CONSOLIDATION, BuiltinDef.ofResource("对谈-沉淀（关系 delta + 记忆要点）", CODE_DIALOGUE_CONSOLIDATION));
        map.put(CODE_DIALOG_DIGEST, BuiltinDef.ofResource("对谈-世界回写批量抽取（记忆 + 强声明）", CODE_DIALOG_DIGEST));
        map.put(CODE_ACTION_ADJUDICATE, BuiltinDef.ofResource("行为裁决-规则不确定时的一次闭集裁决", CODE_ACTION_ADJUDICATE));
        map.put(CODE_WORLD_META_SUGGEST, BuiltinDef.ofResource("世界名与简介建议", CODE_WORLD_META_SUGGEST));
        return map;
    }

    /**
     * 内置模板定义记录（编码映射值）。
     *
     * @param name 模板名称
     *
     * @param template 模板内容
     *
     * @param systemMessage 系统提示词（可空； 起随模板落库，代码不再硬编码）
     */
    record BuiltinDef(String name, String template, String systemMessage) {

        /** 兼容旧两参构造（无系统提示词） */
        BuiltinDef(String name, String template) {
            this(name, template, null);
        }

        /**
         * 从 classpath 资源构造（一个 Prompt 一个目录：{@code prompts/<code>/user.md} + {@code system.md}）。
         * <p>大模板不再内联成 Java 文本块：正文动辄数万字符，内联会把本文件撑到数千行，
         * 也无法用普通 diff 审阅。资源缺失由 {@link PromptResources} 抛异常（不允许静默降级成空模板）。</p>
         *
         * @param name 模板显示名
         *
         * @param code 模板编码（= 资源目录名）
         *
         * @return 内置模板定义
         */
        static BuiltinDef ofResource(String name, String code) {
            return new BuiltinDef(name, PromptResources.user(code), PromptResources.system(code));
        }
    }
}
