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
import com.holzyn.actor.domain.settings.entity.ActorPromptTemplate;
import com.holzyn.actor.domain.settings.repository.ActorPromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

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
     * scene=growth_romance_decision；温度 0.4 / 上限 300；NanoJev boolean question 同构）
     */
    public static final String CODE_ROMANCE_DECISION = "growth_romance_decision";

    /**
     * 轨道：求婚判定（SCHEMA_JSON：{propose,target_ref,reason}；
     * scene=growth_proposal_decision；温度 0.4 / 上限 300；NanoJev choice question 同构）
     */
    public static final String CODE_PROPOSAL_DECISION = "growth_proposal_decision";

    /**
     * 轨道：生育意愿判定（SCHEMA_JSON：{want,reason}；
     * scene=growth_fertility_decision；温度 0.4 / 上限 300；NanoJev score/boolean question 同构）
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
     * N 轨道：NanoJev A/B 判定合理性评分（LLM-as-Judge 四维 1-5 绝对评分——
     * 一致性/合理性/情境利用/自然度；复用 JudgeChatClient 独立云端模型；scene=nanojev_ab_judge）
     */
    public static final String CODE_NANOJEV_AB_JUDGE = "nanojev_ab_judge";

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
            Map.entry(CODE_NANOJEV_AB_JUDGE, "judge"),
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
        return com.holzyn.actor.domain.agent.prompt.PromptNormalizer.normalize(stripResidualPlaceholders(text));
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
        String sys = effectiveTemplate(userId, projectId, code)
                .map(ActorPromptTemplate::getSystemMessage)
                .orElse(null);
        if (sys == null || sys.isBlank()) {
            return "";
        }
        if (placeholders != null) {
            for (Map.Entry<String, Object> e : placeholders.entrySet()) {
                Object v = e.getValue();
                sys = sys.replace("{{" + e.getKey() + "}}", v == null ? "" : String.valueOf(v));
            }
        }
        // Prompt 工程：渲染期规范化（与 render 同口径，见 PromptNormalizer）
        return com.holzyn.actor.domain.agent.prompt.PromptNormalizer.normalize(stripResidualPlaceholders(sys));
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
        Map<String, BuiltinDef> map = new java.util.LinkedHashMap<>();
        map.put(CODE_PROJECT_IMPORT, new BuiltinDef("文件导入解析-世界观",
                        """
                        你是一位世界设定专家。请阅读以下上传文件内容，提取【项目信息】与【世界观设定】的基础信息，并给五个详细字段各写一份【简要初稿】（100-200 字），严格输出一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        {
                          "project": { "name": "项目名称（从内容或文件名推断）", "summary": "项目概要（100 字内）" },
                          "worldSetting": {
                            "name": "世界观名称",
                            "genre": "题材",
                            "era": "时代背景",
                            "geography": "地理/地图设定（简要初稿 100-200 字）",
                            "factions": "势力/阵营（简要初稿 100-200 字）",
                            "magicSystem": "规则体系（简要初稿 100-200 字）",
                            "culture": "文化/风俗（简要初稿 100-200 字）",
                            "history": "历史背景（简要初稿 100-200 字）",
                            "freeText": "完整世界观自由文本（汇总上述初稿，500 字内）"
                          }
                        }

                        —— 要求 ——
                        1. 严格基于文件内容提取；内容不足时合理推断，不得与原文矛盾。
                        2. 五个详细字段只写简要初稿（100-200 字），后续会单独深化到 1000 字以上。
                        3. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                        —— 上传文件内容 ——
                        {{files_content}}
                        """));
        map.put(CODE_PROJECT_IMPORT_CHARACTERS, new BuiltinDef("文件导入解析-角色",
                        """
                        你是一位角色档案提取器。请阅读以下上传文件内容，提取其中出现的【所有角色】，逐个输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        [ { "type": "special", "name": "角色名", "title": "头衔", "detail": "角色详细信息（背景/性格/目标/关系，200 字内）", "isProtagonist": 0, "importance": 3 } ]

                        —— 要求 ——
                        1. 必须提取文件中出现的全部角色，一个都不能遗漏（包括配角、反派、龙套）。
                        2. 每个角色独立一条；detail 概括该角色的完整档案，200 字内。
                        3. 主角 isProtagonist=1，重要角色 importance 3-5，普通角色 1-2。
                        4. 若没有任何角色信息，输出空数组 []，不要编造。
                        5. 只输出 JSON 数组，不要用 Markdown 代码块包裹。

                        —— 上传文件内容 ——
                        {{files_content}}
                        """));
        map.put(CODE_PROJECT_IMPORT_FIELD, new BuiltinDef("文件导入解析-世界观字段",
                        """
                        你是一位世界设定专家。请为以下项目生成【{{field}}】的详细设定内容，输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        { "content": "{{field}} 的详细设定内容" }

                        —— 要求 ——
                        1. content 内容{{requirement}}，展开具体细节，不得与上传文件原文矛盾。
                        2. 若提供已有初稿，必须保留并扩展，不要丢弃。
                        3. 字数要求：内容以 1000-3000 字为佳，最多不要超过 4000 字；若内容过多，请提炼浓缩、删除冗余，保留最关键、最有细节的设定，宁精勿长。
                        4. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                        —— 项目 ——
                        {{project_name}}

                        —— 世界观 ——
                        {{world_name}}

                        —— 已有初稿 ——
                        {{current}}

                        —— 上传文件原文（截断）——
                        {{files_content}}
                        """));
        // ：：角色卡 Schema 已废弃，统一输出 11 维档案。
        // 场景码 character_card_gen 与占位符 {{world_setting}}/{{character_input}} 保持不变（调用点零改动），
        // 输出契约由旧角色卡结构（identity.name/personality/background/speechStyle/knowledge/behaviorPatterns）
        // 改为《角色档案 Schema 契约与分维来源》的 11 维 v2 视图（schema_version + dims + _sources），
        // 与 persona_bootstrap_fill_a/b 共用同一 schema、校验器与落库分派（PersonaService.savePersona 按
        // _sources 分派：③→actor_state、⑤→actor_goal、⑥→actor_knowledge_doc，其余留在 persona_json）。
        // 本模板 = 段 A + 段 B 的合并版（一次产出 11 维），供"创意发散生成全量初始化世界"用。
        map.put(CODE_CARD_GEN, new BuiltinDef("角色档案生成（11 维 v2）",
                        """
                        你是角色档案构建师，为虚构世界中的一位角色构建【角色统一档案 v2】（11 维）。
                        （G1a：角色卡旧 Schema 已废弃——不要输出 identity.name / personality / background / speechStyle / knowledge / behaviorPatterns 这类旧结构，统一输出下方 11 维 dims。）
                        请严格基于给定的【世界观设定】与【角色档案】，严格输出 JSON 对象，禁止输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                        —— 一、约束强度（铁律：耦合维度严格锚定，非耦合维度大胆创造） ——
                        A. 耦合维度 —— 严格锚定（①身份与社会 ③生理基线 ⑤价值观与动机 ⑥能力与知识 ⑧习惯与日常 ⑨经历与创伤 ⑩发育·谱系）：
                           1. ①必须贴合世界观的时代/地理/势力/文化/规则，并严格贴合【角色档案】给出的姓名/性别/种族/归属/职业/地点/年龄；财产与声望的量级要与身份相称；不得新造与世界观地理冲突的地名。
                           2. ③的数值会直接写入 NPC 状态模块作为 per-NPC 生理参数，驱动饥饿/口渴/精力/睡眠/耐痛的衰减与阈值计算（不是装饰文本）：作息型 chronotype 取 early_bird/intermediate/night_owl；sleep_need_hours 取 5-10（默认 8）；meal_cycle_hours 取 4-12（默认 8，须与 meals_per_day 相容）；meals_per_day 取 1-5；metabolism 取 slow/normal/fast；pain_tolerance/thirst_tolerance/cold_tolerance/heat_tolerance 取 0-10 整数。年龄偏老者应体现衰退，年轻力壮者相反。
                           3. ⑤的 life_goals/current_goals 必须由身份、职业与当前处境自然推出；moral_bottom_lines 要写成可判定的具体行为，不得写成笼统美德。
                           4. ⑥必须落在世界观允许的知识范围内——不得出现超出该世界观时代/技术水平的知识、技术、器物、制度、地名或概念；skills 键名必须是该世界观里的具体技艺（如 农事耕作/水利修渠/记账算筹），熟练度 0-10。
                           5. ⑧的 schedule 必须与计划模块的日程粒度兼容：给 4-8 条，每条格式为「时段 + 地点/活动」，时段须覆盖他清醒的一整天并与 ③ 的作息型一致。
                           6. ⑨必须能挂到记忆模块与世界编年史上：key_events 每条都要能在世界时间线上定位（age + 发生了什么含地点/涉及谁/结果，source 一律 "preset"）；严禁凭空发明影响他人或世界史的大事件；traumas 必须带具体触发物。
                           7. ⑩的谱系只能使用关系模块已支持的亲属类型（父/母/配偶/子女/兄弟姐妹/祖辈/孙辈），已有关系里没有的亲属一律不写；子女年龄必须小于本人年龄且与 ⑨ 的经历自洽。
                        B. 非耦合维度 —— 大胆创造（②外貌与体质 ④气质与人格 ⑦语言与表达 ⑪社会面具）：
                           鼓励大胆、具体、个体化的发明，这是"个体辨识度"的唯一来源：疤痕/老花/口音/称呼习惯/口头禅/小动作/面具切换阈值都要具体到可感知；OCEAN 五个 0-1 数值不得一律 0.5；面具的 context_suppression 八个情境码（before_authority/before_partner/before_stranger/before_peer/before_child/before_rival/public_place/private_place）各给 0-1 抑制强度，要体现"对谁压得最狠、对谁几乎不设防"。
                        C. 允许并鼓励"未解心结与秘密"作为内部驱动力，但必须可追溯到【角色档案】或世界观中已有的事实。

                        —— 二、输出 JSON 结构（键名严格照抄；dims 含 11 个键；引号内的区间/说明文字是占位说明，必须替换成你判断的实际取值） ——
                        {
                          "schema_version": 2,
                          "dims": {
                            "identity": { "aliases": ["别名/绰号 1-3 个，每个 10 字内"], "identity_title": "身份头衔（20 字内）", "social_class": "阶层（20 字内）", "livelihood": "谋生方式（40 字内）", "property": { "house": "住房（30 字内）", "land_mu": "田亩数", "grain_shi": "存粮石数", "money_guan": "现钱贯数", "heirlooms": ["传家物 0-3 条"], "notes": "备注（40 字内）" }, "reputation": { "本地": "0-1", "邻域": "0-1", "官府": "0-1", "notes": "声望说明（40 字内）" }, "status_tags": ["身份标签 1-3 个"] },
                            "appearance": { "height_cm": "身高厘米数", "build": "体型（15 字内）", "posture": "体态（20 字内）", "face_features": "相貌特征（60 字内，含疤痕等辨识特征）", "hair": "须发（20 字内）", "attire_style": "衣着风格（40 字内）", "voice_timbre": "嗓音音色（30 字内）", "scent": "体味（20 字内）", "strength": "0-10 整数", "endurance": "0-10 整数", "agility": "0-10 整数", "balance": "0-10 整数", "senses": { "vision": "0-10", "hearing": "0-10", "smell": "0-10", "taste": "0-10", "touch": "0-10", "notes": "感官特点或缺陷（40 字内）" }, "illnesses": [ { "name": "病名", "season": "发作季节/条件", "severity": "轻/中/重" } ], "disabilities": [], "constitution_weaknesses": ["体质弱点 1-3 条（如：怕冷，低于 8℃ 即关节痛）"], "appearance_notes": "习惯性外观细节（50 字内）" },
                            "physio": { "chronotype": "early_bird 或 intermediate 或 night_owl", "sleep_need_hours": "5-10", "sleep_quality": "睡眠质量（20 字内）", "meal_cycle_hours": "4-12", "meals_per_day": "1-5 整数", "metabolism": "slow 或 normal 或 fast", "pain_tolerance": "0-10", "thirst_tolerance": "0-10", "cold_tolerance": "0-10", "heat_tolerance": "0-10", "fertility_state": "生育状态（20 字内）", "libido": "亲密状态（20 字内）", "alcohol_tolerance": "酒量（20 字内）", "stimulants": { "茶": "摄入习惯", "酒": "摄入习惯" }, "excretion_regularity": "排泄规律（20 字内）", "sweat_tendency": "出汗倾向（20 字内）", "physio_notes": "生理补充（40 字内）" },
                            "personality": { "ocean": { "openness": "0-1", "conscientiousness": "0-1", "extraversion": "0-1", "agreeableness": "0-1", "neuroticism": "0-1" }, "temperament_type": "气质型（30 字内）", "attachment_style": "依恋类型（30 字内）", "emotion_thresholds": { "anger": "0-1", "fear": "0-1", "joy": "0-1", "sadness": "0-1" }, "emotion_recovery": "情绪恢复速率（30 字内）", "impulse_control": "0-10 整数", "cognitive_style": "认知风格（20 字内）", "risk_preference": "low 或 medium 或 high", "time_orientation": "时间取向（20 字内）", "traits_summary": "特质摘要（30 字内）" },
                            "values": { "value_weights": { "价值名（取自世界观的价值范畴）": "0-1" }, "core_fears": ["1-3 条，每条 25 字内"], "core_desires": ["1-3 条，每条 25 字内"], "moral_bottom_lines": ["2-4 条，每条 25 字内，必须可判定"], "life_goals": ["2-3 条，每条 30 字内"], "current_goals": ["1-3 条，每条 30 字内"], "regrets": ["1-3 条，每条 25 字内"] },
                            "ability": { "skills": { "技能名": "0-10" }, "knowledge_domains": ["1-5 条，每条 15 字内"], "knowledge_blind_spots": ["1-5 条，每条 15 字内"], "literacy": "识字程度（30 字内）", "languages": [ { "name": "语言/方言名", "level": "熟练度" } ], "talents": ["天赋 1-3 条"], "self_efficacy": { "领域（本人主要技能/生活领域 3-6 个）": "1-5（本人主观评估：1 很弱…5 很强；允许与 skills 有偏差——自信/谦逊）" }, "self_comment": "一句话整体自评（40 字内，如：手上有几把力气，就是不识字，遇事先慌）", "secrets": ["知情秘密 0-3 条，每条 30 字内"] },
                            "voice": { "sentence_length": "句长倾向（20 字内）", "register": "用词雅俗（30 字内）", "speech_rate": "语速（15 字内）", "pitch": "音高（10 字内）", "address_habits": { "to_junior": "对晚辈的称呼", "to_peer": "对同辈的称呼", "self_reference": "自称" }, "catchphrases": ["口头禅 2-5 条"], "dialect": "口音腔调（20 字内）", "taboo_topics": ["忌讳话题 1-3 条"], "silence_tendency": "沉默倾向（25 字内）", "rhetoric_preference": "修辞偏好（25 字内）", "written_vs_spoken": "书面与口头的差异（25 字内）", "vocabulary_keys": ["特征词表 3-8 个"] },
                            "habits": { "schedule": ["时段 + 地点/活动 4-8 条"], "sleep_habits": ["1-5 条"], "quirks": ["癖好 1-5 条"], "micro_movements": ["小动作 1-5 条"], "food_preferences": "饮食偏好（30 字内）", "addictions": ["嗜好 0-4 条"], "ritual_behaviors": ["仪式性行为 0-4 条"] },
                            "experience": { "origin": "出身（60 字内）", "key_events": [ { "age": "年龄整数", "what": "事件（40 字内，含地点/涉及谁/结果）", "source": "preset" } ], "traumas": [ { "event": "创伤事件（30 字内）", "age": "年龄整数", "triggers": ["触发物 1-3 个"] } ], "honors": ["0-3 条"], "unresolved_knots": ["0-3 条，每条 30 字内"] },
                            "development": { "stage": "阶段码（INFANT/TODDLER/CHILD/ADOLESCENT/YOUTH/PRIME/MIDDLE/ELDER/ANCIENT）", "genome": { "位点名": "等位基因对" }, "epigenetic_marks": ["表观修饰 0-2 条"], "lineage": { "father": "父（无则省略该键）", "mother": "母", "spouse": "配偶", "children": ["子女"], "siblings": ["兄弟姐妹"] } },
                            "mask": { "context_suppression": { "before_authority": "0-1", "before_partner": "0-1", "before_stranger": "0-1", "before_peer": "0-1", "before_child": "0-1", "before_rival": "0-1", "public_place": "0-1", "private_place": "0-1" }, "allowed_expressions": { "情境码": ["允许表达 1-3 个"] }, "forbidden_expressions": { "情境码": ["禁止表达 1-3 个"] }, "breaches": [ { "trigger": "触发物", "suppression_delta": "-1 到 1 的数（负值=失态）" } ] }
                          },
                          "_sources": { "identity": {"kind": "MIXED", "module": "actor_profile"}, "appearance": {"kind": "PERSONA", "module": ""}, "physio": {"kind": "MODULE", "module": "actor_state"}, "personality": {"kind": "PERSONA", "module": ""}, "values": {"kind": "MIXED", "module": "actor_goal"}, "ability": {"kind": "MIXED", "module": "actor_knowledge_doc"}, "voice": {"kind": "PERSONA", "module": ""}, "habits": {"kind": "MIXED", "module": "actor_state"}, "experience": {"kind": "MIXED", "module": "actor_memory"}, "development": {"kind": "MIXED", "module": "actor_relation"}, "mask": {"kind": "PERSONA", "module": "actor_state"} }
                        }

                        —— 三、要求（铁律，必须严格遵守） ——
                        1. 11 个维度都要有实质内容（不允许空对象/空数组的空壳维）；文本字段不得超过字数上限。
                        2. 完整保留铁律：角色档案中的【每一项设定与细节都必须完整保留】到对应字段——身份/外貌/性格/能力/语言风格/语录/目标/心结/习惯/经历一个都不能少；只可多不可少：内容可以比原信息更丰富、更展开，但【绝不可省略、不可概括压缩、不可改写原意、不可丢弃】；信息量大时优先增加输出长度来完整承载。
                        3. 所有内容必须与世界观一致，禁止出现与世界观/角色档案冲突的设定（杜绝 OOC）。
                        4. 键名一律 snake_case；数值字段必须是数字（不要把区间说明文字当取值输出）；无内容的数组给空数组，禁止 null 占位。
                        5. _sources 必须原样照抄、11 键齐全，不得改动。
                        6. 只输出上述 JSON 对象，不要用 Markdown 代码块包裹，不要输出任何解释文字。

                        —— 世界观设定 ——
                        {{world_setting}}

                        —— 角色档案 ——
                        {{character_input}}
                        """, "你是角色档案构建师：只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        // ：AI 两段式建档 · 第一段（5 维）。占位符由 PersonaBootstrapService 提供：
        // world_setting/character_skeleton/anchor_text/locations/relations。
        map.put(CODE_PERSONA_FILL_A, new BuiltinDef("角色档案-两段式建档·第一段",
                        """
                        你是角色档案构建师，为虚构世界中的一位既有 NPC 构建【角色统一档案 v2】的第一段（全档共两段）。
                        本段只产出 5 个维度：①身份与社会 identity、②外貌与体质 appearance、④气质与人格 personality、⑤价值观与动机 values、⑨经历与创伤 experience。
                        请严格依据下方的【世界观设定】【角色骨架】【锚定文本】【已有地点清单】【已有关系】，严格输出 JSON 对象，禁止输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                        —— 一、约束强度（本段最重要的规则） ——
                        铁律：耦合维度严格锚定，非耦合维度大胆创造。
                        A. 耦合维度 —— 严格锚定（①身份与社会、⑤价值观与动机、⑨经历与创伤）：
                           1. ①必须贴合【世界观设定】的时代/地理/势力/文化/规则，并严格贴合【角色骨架】给出的姓名/性别/种族/次级种族/归属/职业/所在地点/年龄；财产与声望的量级必须与身份相称（村社首领不该有县城产业，贫户不该有三进祖宅）；地名优先取自【已有地点清单】，不得新造与世界观地理冲突的地名。
                           2. ⑤的 life_goals / current_goals 必须由骨架中的身份、职业与当前处境自然推出，且必须落在世界观允许的范围内——不得出现超出该世界观时代的技术、制度、器物或概念；moral_bottom_lines 是"绝不做的事"，必须写成可判定的具体行为（如"不吞占公中粮"），不得写成笼统美德。
                           3. ⑨必须能挂到记忆模块（actor_memory 的 L2/L3）与世界编年史（actor_world_chronicle）上：key_events 每条都要能在世界时间线上定位——给出 age 与"发生了什么（含地点/涉及谁/结果）"，source 一律填 "preset"；严禁凭空发明影响他人或世界史的大事件（战争、灾荒、政权更替、神迹等），只写"这个人自己的经历"，且不得与【已有关系】矛盾；traumas 必须带具体触发物（气味/声音/称呼/物件），它是后续情绪突变与面具破绽的确定性输入。
                        B. 非耦合维度 —— 大胆创造（②外貌与体质、④气质与人格）：
                           鼓励大胆、具体、个体化的发明，这是"个体辨识度"的唯一来源。必须具体到可感知的细节：疤痕/皱纹/老花/耳背/口音/体味/衣着习惯/病痛的发作季节/习惯性小动作。OCEAN 五个 0-1 数值不得一律 0.5，要组合出有区分度的人格（并与 traits_summary 的描述一致）；emotion_thresholds 要体现"这个人最容易被什么激起、对什么几乎无感"。
                        C. 允许并鼓励"未解心结与秘密"作为内部驱动力，但必须可追溯到【角色骨架】【锚定文本】或【已有关系】中已有的事实，不得凭空发明秘密。
                        D. 【锚定文本】是开发者已写的内容：其中每一项设定与细节都必须完整保留到对应字段，只可更丰富、更展开，绝不可省略、不可概括压缩、不可改写原意、不可丢弃。

                        —— 二、输出 JSON 结构（键名严格照抄；dims 只含本段的 5 个键；引号内的区间/说明文字是占位说明，必须替换成你判断的实际取值） ——
                        {
                          "schema_version": 2,
                          "dims": {
                            "identity": { "aliases": ["别名/绰号 1-3 个，每个 10 字内"], "identity_title": "身份头衔（20 字内）", "social_class": "阶层（20 字内）", "livelihood": "谋生方式（40 字内，须与骨架的职业/归属一致）", "property": { "house": "住房（30 字内）", "land_mu": "田亩数", "grain_shi": "存粮石数", "money_guan": "现钱贯数", "heirlooms": ["传家物 0-3 条，每条 15 字内"], "notes": "备注（40 字内）" }, "reputation": { "本地": "0-1", "邻域": "0-1", "官府": "0-1", "notes": "声望说明（40 字内）" }, "status_tags": ["身份标签 1-3 个，每个 10 字内"] },
                            "appearance": { "height_cm": "身高厘米数（按种族与年龄合理）", "build": "体型（15 字内）", "posture": "体态（20 字内）", "face_features": "相貌特征（60 字内，含疤痕/皱纹等辨识特征）", "hair": "须发（20 字内）", "attire_style": "衣着风格（40 字内）", "voice_timbre": "嗓音音色（30 字内）", "scent": "体味（20 字内）", "strength": "0-10 整数", "endurance": "0-10 整数", "agility": "0-10 整数", "balance": "0-10 整数", "senses": { "vision": "0-10", "hearing": "0-10", "smell": "0-10", "taste": "0-10", "touch": "0-10", "notes": "感官特点或缺陷（40 字内）" }, "illnesses": [ { "name": "病名（10 字内）", "season": "发作季节/条件（15 字内）", "severity": "轻/中/重" } ], "disabilities": ["残障 0-2 条，每条 20 字内"], "constitution_weaknesses": ["体质弱点 1-3 条，每条 20 字内（如：怕冷，低于 8℃ 即关节痛）"], "appearance_notes": "习惯性外观细节（50 字内，如：走路右肩略沉；落座前必先抚平衣襟）" },
                            "personality": { "ocean": { "openness": "0-1", "conscientiousness": "0-1", "extraversion": "0-1", "agreeableness": "0-1", "neuroticism": "0-1" }, "temperament_type": "气质型（30 字内，如：黏液质偏抑郁（稳、慢、内敛））", "attachment_style": "依恋类型（30 字内）", "emotion_thresholds": { "anger": "0-1", "fear": "0-1", "joy": "0-1", "sadness": "0-1" }, "emotion_recovery": "情绪恢复速率（30 字内，如：慢，约 2 时辰回落到基线六成）", "impulse_control": "0-10 整数", "cognitive_style": "认知风格（20 字内）", "risk_preference": "low 或 medium 或 high", "time_orientation": "时间取向（20 字内）", "traits_summary": "特质摘要（30 字内，4 个词以内）" },
                            "values": { "value_weights": { "价值名（取自世界观的价值范畴，如 宗族/乡邻生计/名节/承诺/秩序/信仰/私利）": "0-1" }, "core_fears": ["核心恐惧 1-3 条，每条 25 字内"], "core_desires": ["核心欲望 1-3 条，每条 25 字内"], "moral_bottom_lines": ["道德底线（绝不做的事）2-4 条，每条 25 字内，必须可判定"], "life_goals": ["长期目标 2-3 条，每条 30 字内"], "current_goals": ["中期目标 1-3 条，每条 30 字内"], "regrets": ["遗憾 1-3 条，每条 25 字内"] },
                            "experience": { "origin": "出身（60 字内：家族/家境/幼年关键处境）", "key_events": [ { "age": "年龄整数", "what": "事件（40 字内，含地点/涉及谁/结果）", "source": "preset" } ], "traumas": [ { "event": "创伤事件（30 字内）", "age": "年龄整数", "triggers": ["触发物 1-3 个，每个 10 字内"] } ], "honors": ["荣誉 0-3 条，每条 25 字内"], "unresolved_knots": ["未解心结 0-3 条，每条 30 字内"] }
                          },
                          "_sources": { "identity": {"kind": "MIXED", "module": "actor_profile"}, "appearance": {"kind": "PERSONA", "module": ""}, "physio": {"kind": "MODULE", "module": "actor_state"}, "personality": {"kind": "PERSONA", "module": ""}, "values": {"kind": "MIXED", "module": "actor_goal"}, "ability": {"kind": "MIXED", "module": "actor_knowledge_doc"}, "voice": {"kind": "PERSONA", "module": ""}, "habits": {"kind": "MIXED", "module": "actor_state"}, "experience": {"kind": "MIXED", "module": "actor_memory"}, "development": {"kind": "MIXED", "module": "actor_relation"}, "mask": {"kind": "PERSONA", "module": "actor_state"} }
                        }

                        —— 三、输入材料 ——
                        —— 世界观设定 ——
                        {{world_setting}}

                        —— 角色骨架（结构列，权威数据，不可更改，本段所有内容必须与之相容） ——
                        {{character_skeleton}}

                        —— 锚定文本（开发者已写内容，必须完整保留） ——
                        {{anchor_text}}

                        —— 已有地点清单（① 的地名优先取自其中） ——
                        {{locations}}

                        —— 已有关系（⑨ 经历与 ⑤ 目标不得与之矛盾） ——
                        {{relations}}

                        —— 四、输出要求 ——
                        1. 只输出上述 JSON 对象，禁止输出任何解释、前后缀或 Markdown 代码块。
                        2. dims 只包含 identity / appearance / personality / values / experience 五个键，不要输出其它维度。
                        3. 键名一律 snake_case；数值字段必须是数字（不要把区间说明文字当取值输出）；数组字段无内容时给空数组，禁止用 null 占位。
                        4. 所有文本字段不得超过字数上限；宁可精炼，但细节必须具体可感知。
                        5. key_events 给 4-8 条并按 age 升序，最后一条的 age 不得大于骨架中的年龄。
                        6. _sources 必须原样照抄、11 键齐全，不得改动。
                        """, "你是角色档案构建师：只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        // ：AI 两段式建档 · 第二段（6 维）。占位符由 PersonaBootstrapService 提供：
        // world_setting/character_skeleton/persona_a_json/relations（persona_a_json = 第一段结果回灌）。
        map.put(CODE_PERSONA_FILL_B, new BuiltinDef("角色档案-两段式建档·第二段",
                        """
                        你是角色档案构建师，为虚构世界中的一位既有 NPC 构建【角色统一档案 v2】的第二段（全档共两段）。
                        本段只产出 6 个维度：③生理基线 physio、⑥能力与知识 ability、⑦语言与表达 voice、⑧习惯与日常 habits、⑩发育·遗传·谱系 development、⑪社会面具 mask。
                        请严格依据下方的【世界观设定】【角色骨架】【第一段档案】【已有关系】，严格输出 JSON 对象，禁止输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                        —— 一、约束强度（本段最重要的规则） ——
                        铁律：耦合维度严格锚定，非耦合维度大胆创造。
                        A. 耦合维度 —— 严格锚定（③生理基线、⑥能力与知识、⑧习惯与日常、⑩发育·遗传·谱系）：
                           1. ③必须与 NPC 状态模块的生理参数口径一致：本维数值会直接写入状态模块的 per-NPC 生理参数（actor_state 的生理参数覆盖列）并换算成生效系数，驱动饥饿/口渴/精力/睡眠/耐痛的衰减与阈值计算——它是 per-NPC 的参数来源，不是装饰文本，因此取值必须落在下列范围并互相自洽：
                              作息型 chronotype 取 early_bird / intermediate / night_owl；sleep_need_hours 取 5-10（默认 8）；sleep_quality 为描述；meal_cycle_hours 取 4-12（默认 8）；meals_per_day 取 1-5 且与 meal_cycle_hours 相容（醒着小时数 ÷ meal_cycle_hours ≈ meals_per_day）；metabolism 取 slow / normal / fast（与体型/年龄/劳作强度一致）；pain_tolerance / thirst_tolerance / cold_tolerance / heat_tolerance 取 0-10 整数。
                               取值分散（防止"千人一面"，与上一条同等重要）：③ 是全档里唯一直接改变状态曲线形状的维度，如果整个村子都取 early_bird、睡眠都取 6-7 小时，"作息"就退化成同一个人的复制品，世界立刻失真。因此禁止使用"想不出理由就取常见值"的默认套路：
                                 · chronotype 必须由本人真实节律决定，而不是由职业想当然决定——务农者未必都是晨型：更夫/守夜人/酒肆伙计/夜里读书写账的人/长期失眠者应是 night_owl 或 intermediate；只有"天不亮就醒、入夜即困"的人才是 early_bird。
                                 · 先在心里答三个问题再取值：他一天里最清醒的时段是什么时候？他因为什么被迫早起或晚睡？他的身体被什么长期消耗（劳作/病痛/生育/年岁）？答案不同，取值就必须不同。
                                 · sleep_need_hours（5-10）要落在具体处境上（体力劳动者恢复慢 → 偏长；老人浅眠 → 偏短但夜间易醒），不要只在 6 / 7 之间二选一；meal_cycle_hours 与 meals_per_day 必须成对推导（日两餐的农户与日三餐的商贩不是同一个数）；metabolism 按体型/年龄/劳作强度真实分布，不要人人 normal。
                                 · 四项 0-10 耐受度不得人人落在 4-6 的中庸区间：长期户外劳作 → 耐寒耐热偏高、耐痛偏高；养在深闺/年老体弱/旧伤在身 → 明显偏低；要有 1-2 项敢于取到 2 以下或 8 以上。
                              年龄偏老者应体现衰退（代谢慢、睡眠需求少但浅、耐寒耐痛下降），年轻力壮者相反；体质必须与第一段的 ②外貌与体质（疾病/体质弱点）自洽。
                           2. ⑥必须落在世界观允许的知识范围内：不得出现超出该世界观时代/技术水平的知识、技术、器物、制度、外来语言或概念（古代村社不该出现蒸汽机、电报、现代医学名词）；knowledge_domains / knowledge_blind_spots 必须由骨架的职业、归属、年龄与第一段的 ⑨经历推出；skills 键名必须是该世界观里的具体技艺（如 农事耕作/水利修渠/记账算筹/宗族礼法），熟练度 0-10；secrets 必须是"这个人知情而他人不知"的具体事实，且可追溯到【角色骨架】或【第一段档案】已有的事实。self_efficacy 是"本人对自己各领域本事的主观评估"（1-5），须由技能与经历自然推出——它允许与 skills 有偏差（自信者高估、谦逊者低估、吃过亏的领域偏低），偏差本身即性格，但不得偏离到荒谬（技能 9/10 自评 1）；self_comment 给一句整体自评。
                           3. ⑧必须与计划模块的日程粒度兼容：schedule 给 4-8 条，每条格式为「时段 + 地点/活动」（如：寅末起身巡田、辰时村公所理事），时段须覆盖他清醒的一整天，且与 ③ 的作息型与睡眠需求一致；不要求分钟级精度，不要安排超出世界观的活动。
                           4. ⑩必须与关系模块的谱系类型兼容：lineage 只能使用已支持的亲属类型（父/母/配偶/子女/兄弟姐妹/祖辈/孙辈）；已有关系里没有的亲属一律不写（没有亲属就省略 lineage）；亲属的年龄必须与本人年龄和第一段 ⑨经历自洽；stage 取当前发育阶段码（INFANT/TODDLER/CHILD/ADOLESCENT/YOUTH/PRIME/MIDDLE/ELDER/ANCIENT）且必须与骨架年龄一致；genome 给 3-6 个位点（键为位点名，值为等位基因对）；epigenetic_marks 只在 ⑨经历中确有重大事件（饥荒/战乱/长期劳作）时给 1-2 条，否则给空数组。
                        B. 非耦合维度 —— 大胆创造（⑦语言与表达、⑪社会面具）：
                           「⑦」口音腔调、句长、语速、音高、称呼习惯（对晚辈/对同辈/自称）、口头禅 2-5 条、忌讳话题、沉默倾向（如：被质问时先沉默 3-5 息再答）、修辞偏好（打比方爱用什么题材）——要能让人只看文字就认出是他说的。
                           「⑪」context_suppression 八个情境码（before_authority / before_partner / before_stranger / before_peer / before_child / before_rival / public_place / private_place）各给 0-1 抑制强度（0=完全不压抑，1=完全隐藏），切换阈值要具体——对谁压得最狠、对谁几乎不设防；allowed_expressions / forbidden_expressions 至少覆盖 4 个情境、每个情境 1-3 个表达词；breaches 给 1-3 条「触发物 → 抑制强度临时增量（负值=失态）」，触发物应取自第一段 ⑨ 的创伤触发物或本段 ⑧ 的嗜好（如饮酒）。
                        C. 本段必须与【第一段档案】保持一致：年龄、职业、体质、创伤、价值取向不得出现矛盾（⑩的亲属与 ③的生理状态尤其要对得上 ⑨经历）。

                        —— 二、输出 JSON 结构（键名严格照抄；dims 只含本段的 6 个键；引号内的区间/说明文字是占位说明，必须替换成你判断的实际取值） ——
                        {
                          "schema_version": 2,
                          "dims": {
                            "physio": { "chronotype": "early_bird 或 intermediate 或 night_owl", "sleep_need_hours": "5-10", "sleep_quality": "睡眠质量（20 字内）", "meal_cycle_hours": "4-12", "meals_per_day": "1-5 整数", "metabolism": "slow 或 normal 或 fast", "pain_tolerance": "0-10", "thirst_tolerance": "0-10", "cold_tolerance": "0-10", "heat_tolerance": "0-10", "fertility_state": "生育状态（20 字内）", "libido": "亲密状态（20 字内）", "alcohol_tolerance": "酒量（20 字内）", "stimulants": { "茶": "摄入习惯", "酒": "摄入习惯" }, "excretion_regularity": "排泄规律（20 字内）", "sweat_tendency": "出汗倾向（20 字内）", "physio_notes": "生理补充（40 字内，如：寅时末即醒，戌时初必寝；日两餐）" },
                            "ability": { "skills": { "技能名（世界观内的具体技艺）": "0-10" }, "knowledge_domains": ["1-5 条，每条 15 字内"], "knowledge_blind_spots": ["1-5 条，每条 15 字内"], "literacy": "识字程度（30 字内）", "languages": [ { "name": "语言/方言名", "level": "熟练度（如 母语 / 会听，说时夹土音）" } ], "talents": ["天赋 1-3 条"], "self_efficacy": { "领域（本人主要技能/生活领域 3-6 个，对齐 skills 键名）": "1-5（本人主观评估：1 很弱…5 很强；允许与 skills 有偏差——自信/谦逊，正是真人感来源）" }, "self_comment": "一句话整体自评（40 字内，如：手上有几把力气，就是不识字，遇事先慌）", "secrets": ["知情秘密 0-3 条，每条 30 字内"] },
                            "voice": { "sentence_length": "句长倾向（20 字内，如 偏长：20-40 字，爱铺陈因果）", "register": "用词雅俗（30 字内，如 半文半白）", "speech_rate": "语速（15 字内）", "pitch": "音高（10 字内）", "address_habits": { "to_junior": "对晚辈的称呼", "to_peer": "对同辈的称呼", "self_reference": "自称" }, "catchphrases": ["口头禅 2-5 条"], "dialect": "口音腔调（20 字内，如 入声重、儿化少）", "taboo_topics": ["忌讳话题 1-3 条"], "silence_tendency": "沉默倾向（25 字内）", "rhetoric_preference": "修辞偏好（25 字内）", "written_vs_spoken": "书面与口头的差异（25 字内）", "vocabulary_keys": ["特征词表 3-8 个"] },
                            "habits": { "schedule": ["时段 + 地点/活动，4-8 条，须与作息型一致"], "sleep_habits": ["作息习惯 1-5 条"], "quirks": ["癖好 1-5 条（如：思考时摩挲印信）"], "micro_movements": ["小动作 1-5 条（如：捋须、负手踱步）"], "food_preferences": "饮食偏好（30 字内）", "addictions": ["嗜好 0-4 条"], "ritual_behaviors": ["仪式性行为 0-4 条（如：初一十五必去祠堂上香）"] },
                            "development": { "stage": "阶段码（INFANT/TODDLER/CHILD/ADOLESCENT/YOUTH/PRIME/MIDDLE/ELDER/ANCIENT）", "genome": { "位点名": "等位基因对" }, "epigenetic_marks": ["表观修饰 0-2 条"], "lineage": { "father": "父（无则省略该键）", "mother": "母", "spouse": "配偶", "children": ["子女"], "siblings": ["兄弟姐妹"] } },
                            "mask": { "context_suppression": { "before_authority": "0-1", "before_partner": "0-1", "before_stranger": "0-1", "before_peer": "0-1", "before_child": "0-1", "before_rival": "0-1", "public_place": "0-1", "private_place": "0-1" }, "allowed_expressions": { "情境码": ["允许表达 1-3 个"] }, "forbidden_expressions": { "情境码": ["禁止表达 1-3 个"] }, "breaches": [ { "trigger": "触发物", "suppression_delta": "-1 到 1 的数（负值=抑制下降、易失态）" } ] }
                          },
                          "_sources": { "identity": {"kind": "MIXED", "module": "actor_profile"}, "appearance": {"kind": "PERSONA", "module": ""}, "physio": {"kind": "MODULE", "module": "actor_state"}, "personality": {"kind": "PERSONA", "module": ""}, "values": {"kind": "MIXED", "module": "actor_goal"}, "ability": {"kind": "MIXED", "module": "actor_knowledge_doc"}, "voice": {"kind": "PERSONA", "module": ""}, "habits": {"kind": "MIXED", "module": "actor_state"}, "experience": {"kind": "MIXED", "module": "actor_memory"}, "development": {"kind": "MIXED", "module": "actor_relation"}, "mask": {"kind": "PERSONA", "module": "actor_state"} }
                        }

                        —— 三、输入材料 ——
                        —— 世界观设定 ——
                        {{world_setting}}

                        —— 角色骨架（结构列，权威数据，不可更改） ——
                        {{character_skeleton}}

                        —— 锚定文本（开发者已写内容：⑥ 的知识范围与 ⑩ 的亲属/谱系必须与之相容，不得凭空发明） ——
                        {{anchor_text}}

                        —— 已有地点清单（⑧ 的日程地点优先取自其中） ——
                        {{locations}}

                        —— 第一段档案（已落库的 ①身份与社会 ②外貌与体质 ④气质与人格 ⑤价值观与动机 ⑨经历与创伤，本段必须与之自洽） ——
                        {{persona_a_json}}

                        —— 已有关系（⑩ 谱系只能引用其中的亲属） ——
                        {{relations}}

                        —— 四、输出要求 ——
                        1. 只输出上述 JSON 对象，禁止输出任何解释、前后缀或 Markdown 代码块。
                        2. dims 只包含 physio / ability / voice / habits / development / mask 六个键，不要输出其它维度。
                        3. 键名一律 snake_case；数值字段必须是数字（不要把区间说明文字当取值输出）；数组字段无内容时给空数组，禁止用 null 占位。
                        4. 所有文本字段不得超过字数上限；③的数值必须落在给定范围内且体现"取值分散"（不同人物的 ③ 不应呈现同一组数值），⑦与⑪要大胆具体。
                        5. _sources 必须原样照抄、11 键齐全，不得改动。
                        6. ⑥ 的知识范围、⑩ 的亲属与年龄必须与【锚定文本】【第一段档案】一致，不得出现开发者在锚定文本中未写、又与世界史冲突的亲属或经历。
                        """, "你是角色档案构建师：只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        // ===== 前缀缓存优化：静态段前置约定（Prompt 前缀缓存命中率） =====
        // 依据（真跑实证，非推断）：本地 llama-server / 云端上下文缓存都按「从头开始逐 token 相同的连续前缀」
        // 计算命中，命中上界 = 第一处逐 token 变化的位置（LCP）。 本地长跑 30 场景实测命中率 13.8%；
        // 逐场景 LCP 统计显示，本文件绝大多数模板已把静态规则/输出契约前置、变量后置（如 CODE_ACTION、
        // CODE_REFLECTION、CODE_CORE_MEMORY_UPDATE、CODE_INTERRUPT_PROBE、CODE_SPACE_GENERATE 的 LCP 均等于
        // 其静态段长度），唯独 dialog 家族与 CODE_WORLD_EVENT 把 {{name}}/{{world_setting}} 等变量放在开头，
        // 实测 LCP 仅 3~49 字符（dialog 命中率 15.3%，为全部场景最低）。
        // 以下四处仅调整段落顺序：纯重排，不增删一字、不改占位符、不改语义；使静态段成为全场景可复用前缀。
        // 维护约定：模板一律「静态在前、变量在后」；模板请沿用（输出契约放静态段或紧邻静态段之后）。
        map.put(CODE_DIALOG, new BuiltinDef("对话系统",
                        """
                        —— SYSTEM ——
                        1. 始终保持角色身份与世界观一致性，绝不跳出角色。
                        2. 不知道的事明确表示不知道，不得编造；你的认知只来自你的身份与经历。
                        3. 回复使用角色说话风格，长度贴合角色身份与情境，像一个真实的人那样说话。
                        4. 你只代表你自己、用第一人称说话；不得替其他角色发言、不得代答，不得在回复中标注或转述其他角色的台词。

                        你是【{{name}}】，{{world_name}}世界的【{{title}}】。你是一个真实活在这个世界里的人，绝不是扮演者——你的一言一行都发自这个身份。

                        —— ROLE_PROFILE ——
                        身份：种族 {{species}}；职业 {{occupation}}；所属势力 {{affiliation}}
                        性格：特质 {{personality}}；价值观 {{values}}；怪癖 {{quirks}}
                        背景与经历：{{history}}
                        当前目标：{{goals}}；心结：{{wounds}}
                        社会关系：{{relations}}
                        说话风格：语气 {{tone}}；用词 {{vocabulary}}；口头禅 {{catchphrases}}；禁忌 {{taboos}}
                        知识边界：知道 {{knows}}；不知道/绝不假装知道 {{notKnows}}
                        行为模式：{{behaviors}}

                        {{tom_section}}
                        """));
        map.put(CODE_GROUP_REPLY, new BuiltinDef("群聊逐角色回复",
                        """
                        —— SYSTEM ——
                        1. 始终保持角色身份与世界观一致性，绝不跳出角色。
                        2. 对话历史中【角色名：】前缀代表其他角色的发言，请针对他们的最新发言做出针对性回应——像真实社交一样自然接话、回应、追问、反驳或补充，不要自说自话，不要机械重复已经讨论过的问题。
                        3. 严格只用第一人称扮演【{{name}}】：只说你自己的话，不要替其他角色发言，不要代答，不要转述或标注其他角色的台词。
                        4. 回复开头不要带任何角色名字、方括号标注或『某某说：』之类的前缀。

                        你是【{{name}}】，{{world_name}}世界的【{{title}}】。你此刻正与在场其他人闲聊，你是一个真实活在这个世界里的人。

                        —— ROLE_PROFILE ——
                        身份：种族 {{species}}；职业 {{occupation}}；所属势力 {{affiliation}}
                        性格：特质 {{personality}}；价值观 {{values}}；怪癖 {{quirks}}
                        背景与经历：{{history}}
                        当前目标：{{goals}}；心结：{{wounds}}
                        社会关系：{{relations}}
                        说话风格：语气 {{tone}}；用词 {{vocabulary}}；口头禅 {{catchphrases}}；禁忌 {{taboos}}
                        行为模式：{{behaviors}}

                        {{tom_section}}

                        —— OUTPUT_FORMAT ——
                        请严格输出一个 JSON 对象，禁止输出 JSON 以外的任何文字（不要用 Markdown 代码块包裹）：
                        {
                          "dialogue": "你这次要说的原话（第一人称，贴合你的说话风格与性格，15-80 字；场上已有他人发言时必须针对性回应其内容；若由你起头（场上尚无发言）则围绕本场背景/话题自然开口）",
                          "action": "说话时伴随的细微动作（如「端起茶碗抿了一口」；没有就填空串）",
                          "emotion_update": { "emotion": "此刻情绪（喜/怒/哀/惧/惊/平淡/羞等）", "intensity": 1到5的整数 },
                          "internal_thought": "你此刻没有说出口的内心想法（一句话，25 字内）"
                        }
                        DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.
                        """));
        map.put(CODE_GROUP, new BuiltinDef("群聊编排",
                        """
                        你是一位群聊主持人（编排调度模型）。请根据本场背景与当前群聊上下文，评估各成员此刻的发言欲望（谁最想开口、谁有理由回应），并决定下一位发言的角色。


                        —— 输出规则 ——
                        请严格输出一个 JSON 对象，禁止输出任何其他文字：
                        {
                          "memberIndex": 成员序号（1到N的整数，必须从下方成员序号列表中选择）,
                          "desire": 1到5的整数（该角色此刻的发言欲望：1~2=确实没什么可说的，3=中等（有话可说但不急切），4~5=很想开口）,
                          "reason": "一句话理由（基于人设/背景/上下文）"
                        }
                        要求：结合人设与下方背景/上下文，判断谁最自然、最想开口。
                        1. 场上已有人发言时，优先选择有明确回应动机（被点名/被提到/关系相关/有冲突或话题）的角色；
                        2. 场上尚无发言（本场开场）时，选择最可能主动起头的人——与下方背景/话题相关、或性格外向、或身份合适者；此时**不要**因为「没人刚说过话、没人可回应」就判定无人想开口；
                        3. 只有在该成员确实毫无开口理由（与话题无关、性格孤僻、场合不合）时，desire 才取 1~2。

                        —— 成员角色摘要 ——
                        {{members_summary}}

                        —— 本场背景（本次交谈的缘由） ——
                        {{background}}

                        —— 当前对话上下文（最近消息；本场开场时为空） ——
                        {{context}}
                        """));
        map.put(CODE_WORLD_EVENT, new BuiltinDef("世界事件",
                        """
                        你是一位虚构世界的叙述者。请基于世界观设定与当前情境，生成一个世界事件。

                        —— 输出规则 ——
                        请严格输出一个 JSON 对象，禁止输出任何其他文字：
                        {
                          "title": "事件标题（一句话）",
                          "content": "事件描述（包含时间、地点、发生了什么、对在场角色的影响，150字以内）",
                          "targetCharacterIds": [受影响或在场角色的角色ID列表]
                        }

                        —— 世界设定 ——
                        {{world_setting}}

                        —— 当前情境 ——
                        {{context}}
                        """));
        map.put(CODE_ACTION, new BuiltinDef("行动生成",
                        """
                        你是一位真实活着的角色，正在做此刻的实时决策。请先想清楚「为什么」，再决定「做什么」。

                        —— SYSTEM ——
                        1. 始终保持角色身份与世界观一致性，绝不跳出角色；你的每个决定都源于你的性格、目标、记忆与当下处境。
                        2. 不知道/不确定的事明确表示不确定，不得编造；决策必须可执行、符合你的身份与能力。
                        3. 没有需要改变现状的充分理由时，继续你手头正在做的事（action_type=continue）——真实的人不会无时无刻都在切换行动。

                        —— ROLE_PROFILE ——
                        {{persona_summary}}

                        —— CURRENT_STATE ——
                        {{situation}}

                        {{tom_section}}

                        {{proactive_memory}}

                        {{strategy_preference}}

                        {{world_outlook}}

                        —— TASK ——
                        1. 先简短地在 reasoning 里推理（30-80 字）：结合你的性格/目标/记忆/当下处境与刺激，判断此刻该做什么、为什么。
                        2. 再选择 action_type 并给出 action_detail。action_type 含义：
                           - continue：继续当前手头的行为，不切换（无新行动）；
                           - start_action：开始一项新的具体行动（如「去村口集市买米」）；
                           - move_to：移动到某地点（action_detail 写目标地点）；
                           - speak_to：主动与某人交谈（action_detail 写对象与要谈的事）；
                           - wait：等待某个时机/时辰/人（expected_duration_minutes 写预计等待的游戏分钟数，action_detail 描述等什么）。
                        3. priority 映射本系统的紧急度 1-5（1=最低，5=火烧眉毛）；expected_duration_minutes 为预计持续游戏分钟。
                        4. 若此刻有明显的更好时机稍后到来（如在等某人/某时辰），选 wait 并给出等待分钟数；能立即行动就不要 wait。
                        {{foresight_requirement}}

                        —— OUTPUT_FORMAT ——
                        在 <decision> 标签内输出一个 JSON 对象，禁止输出任何其他文字：
                        <decision>{
                          "reasoning": "你的推理（30-80 字，先推理后决策）",
                          "action_type": "continue|start_action|move_to|speak_to|wait 之一",
                          "action_detail": "具体行动描述（15-40 字；continue 时描述你在继续什么）",
                          "target": "目标对象/地点（move_to 填地点名，speak_to 填对象名，可空）",
                          "priority": 1到5的整数,
                          "expected_duration_minutes": 预计持续游戏分钟数（正整数）,
                          "wait_minutes": "选 wait 时填预计等待游戏分钟数（正整数；其余省略）",
                          "candidates": "可选数组，仅当情境含【前瞻推演】要求时输出，每项 {\"action\": \"候选行动\", \"consequence\": \"短期后果\"}"
                        }</decision>
                        DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.
                        """));
        map.put(CODE_CROWD, new BuiltinDef("人群集体行动编排",
                        """
                        你是一位集体行动编排者。请基于人群配置与当前时段，为整个群体生成一份「群体快照」，
                        描述各区块人群此刻正在做什么，输出一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        {
                          "areas": [ { "name": "区块/地点名", "activity": "该区块人群此刻在做什么（一句话）", "count": 人数 } ],
                          "summary": "群体整体状态的一句话描述（含时间/地点/主要活动，100 字内）"
                        }

                        —— 人群配置 ——
                        {{crowd_config}}

                        —— 当前时段 ——
                        {{hour}} 点

                        —— 活动区域 ——
                        {{area}}

                        —— 要求 ——
                        1. 基于人群职业分布与作息合理推断各区块活动，不得与配置矛盾。
                        2. areas 至少 2 个区块，覆盖主要活动圈。
                        3. summary 为 100 字内的群体状态描述，供环境摘要注入对话/行动情境。
                        """));
        map.put(CODE_MEMORY_EXTRACT, new BuiltinDef("长期记忆抽取（三重机制重构）",
                        """
                        你是一位记忆抽取器。请阅读【最近对话】与【已有记忆清单】，抽取值得长期记住的信息，
                        输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        [ {
                          "target": "character | world | both",
                          "operation": "add | update | invalidate",
                          "kind": "fact | episodic | event | entity",
                          "content": "一句话内容（含主体/事件/结果，可独立理解）",
                          "importance": 1到5的整数
                        } ]

                        —— 「什么值得记」准则（只抽以下内容） ——
                        1. 稳定事实：角色/世界的长期属性、偏好、习惯、关系变化、承诺、约定。
                        2. 新信息：对话中首次出现且未被已有记忆覆盖的信息。
                        3. 重要经历：对角色有影响的事件（升职/受伤/冲突/喜事/变故）。
                        4. 重要决定：影响未来的计划与目标。

                        —— 不抽取（以下一律不输出） ——
                        1. 寒暄、客套、随口情绪（「今天天气不错」若无后续信息）。
                        2. 重复内容（已被已有记忆覆盖的）。
                        3. 纯世界公共信息（——这类抽到也要 target=world，见下）。
                        4. 一次性场景细节（具体某句话的措辞）。

                        —— target 路由 ——
                        1. target=character：角色个人相关（个人偏好/经历/关系/感受/承诺）。kind=fact（事实）
                           或 episodic（个人经历）。
                        2. target=world：世界公共信息（谁都知道的客观世界事实），如「村口大树被雷劈了」
                           「张屠户改行卖布了」。kind=event（事件类——发生了什么）或 entity（状态类——
                           某地点/人物/势力的当前状态变化）。
                        3. target=both：角色亲身经历且世界公共可知的事件（如「我和村长一起扑灭了粮仓的火」），
                           content 写公共视角；系统会双写：项目记公共事件 + 角色侧记个人经历。

                        —— operation 判定（对每条记忆判定） ——
                        1. add：新事实/新经历（默认）。
                        2. update：与已有记忆矛盾或演进（同一条事实被新情况更新），如「张屠户的铺子从卖肉
                           改成了卖布」是对旧记忆「张屠户卖肉」的更新。
                        3. invalidate：旧事实已失效（角色明确推翻/情况明确变化），如「我和他的误会已解除」
                           使旧记忆「我与他有误会」失效。

                        —— 其他要求 ——
                        1. 只输出新信息；与已有记忆重复、已被覆盖的内容一律不得输出。
                        2. importance 依据对角色/世界的重要性给 1-5：影响角色命运/世界走向的给 4-5，
                           日常琐事给 1-2。importance=1 会被直接丢弃（不写任何层）。
                        3. 若没有值得记的信息，输出空数组 []，不要编造。
                        4. 只输出 JSON 数组，不要用 Markdown 代码块包裹。
                        5. 【寒暄过滤】问候/客套/寒暄（你好、在吗、谢谢、再见、闲聊等）不产出任何条目；
                           若最近对话均为寒暄、无实质信息，必须输出空数组 []——宁可少而精，不要多而杂。

                        —— 最近对话 ——
                        {{recent_dialog}}

                        —— 已有记忆清单（已覆盖的内容不要重复输出） ——
                        {{existing_memories}}
                        """));
        map.put(CODE_MEMORY_SUMMARIZE, new BuiltinDef("会话摘要生成",
                        """
                        你是一位对话摘要器。请阅读【最近对话】，提炼出一段【会话摘要】，输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        { "content": "会话摘要（150字内：谁和谁、聊了什么、达成了什么、关键进展/约定）" }

                        —— 要求 ——
                        1. 摘要要概括对话的实质进展与关键信息，供后续对话压缩上下文（超窗部分以摘要记忆承接）使用。
                        2. 不写废话，不复述对话原文。
                        3. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                        —— 最近对话 ——
                        {{recent_dialog}}
                        """)
        );
        map.put(CODE_MEMORY_EXTRACT_REVIEW, new BuiltinDef("记忆抽取按需复核",
                        """
                        你是一位记忆复核器。下面列出本轮抽取得到的候选记忆（已通过初判：重要度≥3 或涉及
                        对旧记忆的更新/失效操作）。请逐一确认它们是否真的值得记、操作判定是否正确，
                        输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        [ { "index": 候选序号, "action": "keep | drop | modify", "content": "（modify 时的新内容，可空=沿用原内容）", "importance": 1到5的整数（modify 时可改，可空=沿用） } ]

                        —— 复核规则 ——
                        1. keep：确认值得记，按初判执行（默认，未提及的 index 一律按 keep）。
                        2. drop：不值得记（价值不高/与已有记忆重复/判定有误）→ 丢弃，不写入任何层。
                        3. modify：内容或重要度需要修正 → 给出修正后的 content/importance，仍按原 target/operation 执行。
                        4. 复核只针对候选列表，不要新增候选，不要输出候选之外的 index。
                        5. 只输出上述 JSON 数组，不要用 Markdown 代码块包裹。

                        —— 候选记忆 ——
                        {{review_candidates}}

                        —— 复核参考（该角色已有记忆，判定是否重复/矛盾用） ——
                        {{existing_memories}}
                        """));
        map.put(CODE_CHRONICLE_DISTILL, new BuiltinDef("编年史提炼",
                        """
                        你是一位世界编年史编纂者。请根据【当日事件流】与【当日天气】等世界信息，
                        提炼出「世界发生了什么」的编年史条目，输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        {
                          "entries": [
                            {
                              "title": "条目标题（一句话，40字内）",
                              "content": "发生了什么+影响（含时间地点经过影响，可独立理解，150字内）",
                              "importance": 1到5的整数,
                              "event_ids": [关联事件 id 列表（输入事件流中每条的「事件id」编号，可空）],
                              "involved_entities": ["实体引用列表，如 location:村口牌坊 / person:special:110 / faction:村民"],
                              "involved_characters": ["special:110"]（可空）
                            }
                          ]
                        }

                        —— 编纂要求 ——
                        1. 编年史记「世界发生了什么」：重要的事件、异动、变化才写；日常琐事不写。
                        2. 事件类条目引用 event_ids（输入事件流中每条的「事件id」编号，即该条前面的「事件id X」）；状态类变化（某地点/人物/
                           势力的当前状态变了）写入 involved_entities 并在 content 中说明。
                        3. 实体引用用统一键：location:地点名 / person:special:角色id / person:角色名 / faction:势力名。
                        4. 每日本次提炼 1~5 条；无重要内容输出 {"entries": []}，不要编造。
                        5. 只输出上述 JSON 对象，不要用 Markdown 代码块包裹。

                        —— 提炼模式 ——
                        {{mode}}（instant=重要事件即时提炼 / daily=每日批量提炼）

                        —— 当前游戏日 ——
                        第 {{game_day}} 游戏日

                        —— 当前季节/时刻 ——
                        {{season_context}}

                        —— 当日事件流 ——
                        {{event_stream}}

                        —— 当日天气/环境 ——
                        {{weather_context}}
                        """));
        map.put(CODE_ENTITY_STATE_UPDATE, new BuiltinDef("实体状态更新与失效判定",
                        """
                        你是一位世界状态管理者。请根据【编年史条目】判定其中涉及实体的「世界视角公共状态」
                        是否发生变化，并给出变化后的状态，输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        {
                          "updates": [
                            { "entity_key": "实体键（与清单一致）", "changed": true,
                              "current_state": "变化后的当前状态描述（谁都知道的客观状态，可独立理解，100字内）",
                              "recent_change": "最近一次变化的一句话描述（可空）" },
                            { "entity_key": "实体键", "changed": false }
                          ]
                        }

                        —— 判定规则 ——
                        1. 只判定「世界视角公共状态」：客观、公共可知的地点/人物/势力状态变化；
                           不记录个人感受、主观评价（那是角色记忆的职责）。
                        2. 编年史未提及该实体状态变化 → changed=false。
                        3. 状态确实变化 → changed=true 并给出新状态（与旧状态不同）；仅提及无实质变化 → false。
                        4. 每个清单中的实体都要有对应条目；只输出上述 JSON 对象，不要用 Markdown 代码块包裹。

                        —— 当前游戏日 ——
                        第 {{game_day}} 游戏日

                        —— 编年史条目 ——
                        {{chronicle_entries}}

                        —— 实体清单 ——
                        {{entity_keys}}

                        —— 实体当前状态（判定基线） ——
                        {{current_states}}
                        """));
        map.put(CODE_CORE_MEMORY_UPDATE, new BuiltinDef("L3 核心块自编辑",
                        """
                        你是一位记忆管理引擎。请阅读【角色恒定身份信息】（静态基线 + 当前核心块）与【本次情境】，
                        判断是否需要更新角色的【核心块】（恒定在上下文的核心身份信息，Letta core memory blocks 风格），
                        输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        {
                          "updates": [
                            { "action": "update|create|delete", "block_key": "标准键或 custom:键", "block_name": "块显示名（可空）",
                              "content": "块内容（update/create 必填，≤400字）", "importance": 1到5的整数, "reason": "更新理由（一句话）" }
                          ],
                          "reason": "本次自编辑的整体理由（无更新则空串）"
                        }

                        —— 更新规则 ——
                        1. 只更新【真正的身份级变化】：核心信念/价值观/身份事实/长期目标/关键关系发生实质演进时才更新；
                           日常琐事、一时情绪、临时状态一律不动核心块。
                        2. update：修改既有块的内容（替换整块内容）；create：新建块（标准键或 custom:自定义键）；
                           delete：删除已失效/被取代的块。
                        3. block_key 只能取标准键（identity/personality/beliefs/values/goals/relations）
                           或 custom: 开头自定义键；importance 依据对身份的重要性给 1-5。
                        4. 同一块键同时只保留一条活动块：要改某键就用 update 直接替换，不要 create 重复键。
                        5. 内容要精炼具体、可独立理解（≤400 字）；宁可少而准，不要多而杂。
                        6. 没有需要更新的身份级变化时，输出 { "updates": [], "reason": "" }，不要为了改而改。

                        —— 角色恒定身份信息 ——
                        {{character_info}}

                        —— 当前核心块 ——
                        {{current_core_blocks}}

                        —— 本次情境类型 ——
                        {{context_kind}}

                        —— 本次情境 ——
                        {{context}}

                        —— 当前游戏时间 ——
                        {{game_time}}
                        """));
        map.put(CODE_EVOLUTION, new BuiltinDef("世界演化编排",
                """
                你是一位虚构世界的「演化导演」。你负责编排一场多角色世界演化：根据世界观、场景背景、在场角色的人设与过往言行，
                决定本轮谁说话、谁行动、场景发生了什么变化、谁加入、谁退场，并在剧情自然收束或场面冷清时结束演化。
                你必须保证：角色登场、退场都有理有据（符合人设、剧情与世界观），对话符合角色身份与说话风格，剧情逻辑自洽。

                —— 输出结构（严格 JSON，禁止输出其他文字） ——
                {
                  "messages": [ { "characterId": 角色ID, "type": "text|action", "content": "该角色的发言（text）或行为描述（action，第三人称）" } ],
                  "sceneEvent": "本轮的场景/环境变化描述（可空，如：窗外下起了暴雨）",
                  "joins": [ { "characterId": 角色ID, "reason": "加入理由（有理有据）" } ],
                  "leaves": [ { "characterId": 角色ID, "reason": "退场理由（有理有据，如：有事离开/话不投机/受到召唤）" } ],
                  "shouldFinish": false,
                  "finishReason": "收尾理由（shouldFinish=true 时填写）",
                  "summary": "本轮剧情的一句话摘要（供收尾归档，50 字内）"
                }

                —— 铁律 ——
                1. messages 至少 1 条、至多 3 条；每轮让 1-2 个角色互动即可，避免所有人同时开口。
                2. 角色讲话必须符合其身份/性格/说话风格/知识边界；不知道的事绝不编造。
                3. 加入/退场必须给出充分理由：退场不能突兀（可因剧情冲突、个人事务、受到召唤等）；
                   加入可因「路过、被请来、听到动静」等合理契机；理由要写入 reason。
                4. 演化不能无限持续：当场景只剩 1 名角色、或剧情已自然收束、或场面已无新进展时，
                   必须 shouldFinish=true 开始收尾，finishReason 说明为何收束，并输出适当的后续（如角色各自离去）。
                5. 已在场角色若此轮没有说话，不要强行让 TA 说；不要制造毫无意义的客套寒暄。
                6. 所有内容必须与【世界观设定】【场景背景】【角色人设】保持一致，禁止跳出。

                —— 世界设定 ——
                {{world_setting}}

                —— 场景背景 ——
                {{scene_background}}

                —— 在场角色人设（含关系摘要） ——
                {{characters}}

                —— 最近演化经过 ——
                {{history}}

                —— 今日世界状态（当前世界帧，导演上帝视角） ——
                {{world_frame}}

                —— 本演化的指令/背景 ——
                {{evolution_background}}
                """));
        map.put(CODE_SCENE_GENERATE, new BuiltinDef("场景自动生成",
                """
                你是一位虚构世界的「场景设计师」。请根据【世界观设定】与【角色档案】，为该世界设计一批自然合理、逻辑自洽的场景（地点），
                供角色对话与剧情演化使用。每个场景都必须有明确的来源依据（取自世界观/角色的哪部分设定），不得凭空捏造与世界观冲突的内容。

                —— 输出结构（严格 JSON 数组，禁止输出其他文字） ——
                [
                  {
                    "name": "场景/地点名称",
                    "location": "所处位置（如：暖绒市中心/城东/王都西门）",
                    "description": "一句话描述（50 字内）",
                    "background": "场景背景设定（环境/氛围/常驻人员/可发生的剧情，100-200 字，供世界演化 AI 注入）",
                    "source": "来源依据（明确写出依据了世界观/角色的哪部分设定，如：取自世界观【地理设定】的城东市场与角色【林安】的药材铺）"
                  }
                ]

                —— 要求 ——
                1. 场景必须与【世界观设定】一致：名称、位置、氛围不得与设定冲突；优先从世界观的地理/势力/文化/历史中挖掘真实存在的地点。
                2. 场景应与【角色档案】呼应：尽量关联到角色所在位置、职业、身份（如某角色的工作场所/常驻地）。
                3. 每个场景的 source 必须明确写出依据（引用世界观或角色的具体段落/字段），保证有来源、可追溯、逻辑自洽。
                4. 恰好生成 {{count}} 个场景；避免与【已有场景】重复；风格与世界观保持一致。

                —— 世界观设定 ——
                {{world_setting}}

                —— 角色档案 ——
                {{characters}}

                —— 已有场景 ——
                {{existing_scenes}}
                """));
        map.put(CODE_EVOLUTION_SCHEDULE, new BuiltinDef("演化逐拍调度",
                """
                你是一位虚构世界的「剧情调度」。当前有一场正在进行的场景化世界演化（不是普通对话）：角色们在某个场景/地点中持续互动，
                剧情像真实社交一样一拍一拍地自然推进。请判断：此刻最应该由哪位角色行动或说话（最有发言/行动欲望），
                并决定这一拍是「说话(text)」还是「行为(action)」——行为指非言语的身体/环境互动（如：端起咖啡杯、望向窗外、走到门口）。
                场景若发生有意义的可感知变化（如天色骤变、有人推门而入、物品掉落）才在 sceneEvent 中描述；没有则留空字符串。
                一般情况下不要安排角色加入/退场；仅当剧情确实需要（角色有事离开/新角色被请来）才填写 joins/leaves 并给出理由。

                —— 输出结构（严格 JSON，禁止输出其他文字） ——
                {
                  "characterId": 角色ID,
                  "desire": 1-5（该角色此刻的发言/行动欲望强度）,
                  "reason": "为什么选 TA（结合世界观/场景/角色/最近剧情的理由，50 字内）",
                  "beatType": "text|action",
                  "sceneEvent": "场景变化描述（无则空字符串）",
                  "joins": [ { "characterId": 角色ID, "reason": "加入理由" } ],
                  "leaves": [ { "characterId": 角色ID, "reason": "退场理由" } ]
                }

                —— 要求 ——
                1. characterId 必须从【在场角色】中选择；joins 只能选择不在场但属于项目的角色；leaves 只能选择在场角色。
                2. desire 低于 3 表示 TA 此时并不想动；即便如此也请选欲望最高者并给出 reason，让演化持续进行，不要停止。
                3. 说话用第一人称符合角色性格；行为描述要有场景感、可被其他角色观察到。
                4. joins/leaves 默认空数组，不要频繁安排；sceneEvent 也只在场景真有变化时填写。
                5. 所有内容必须与【世界观】【场景背景】【角色人设】【最近剧情】保持一致，逻辑自洽。

                —— 世界观 ——
                {{world_setting}}

                —— 场景背景 ——
                {{scene_background}}

                —— 在场角色（含人设摘要） ——
                {{characters}}

                —— 最近剧情 ——
                {{history}}

                —— 今日世界状态（当前世界帧，导演上帝视角） ——
                {{world_frame}}
                """));
        map.put(CODE_RELATION_GEN, new BuiltinDef("角色关系生成",
                """
                你是世界观角色关系梳理专家，请基于世界观设定与角色信息，识别角色之间的关系，输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。
                —— 世界观设定 ——
                {{world_setting}}

                —— 现有角色名单（关系端点请尽量使用名单中的准确名称） ——
                {{roster}}

                {{target_section}}
                {{task_requirement}}

                —— 输出格式 ——
                只输出 JSON 数组，格式：[{"from":"发起方角色名","to":"目标方角色名","relationType":"关系类型(如亲属/师徒/敌对/朋友/恋人/上下级/同门等)","description":"一句话描述"}]

                —— 规则 ——
                1. 使用名单中的准确角色名。
                2. 世界观提到但名单没有的角色也可作为端点（系统会用名称暂存，后续可补充创建）。
                3. 禁止 from==to 的自环。
                4. 关系类型尽量具体简短。
                5. 同一对角色之间的同一类型只输出一条。
                """, "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        map.put(CODE_RELATION_REFINE, new BuiltinDef("角色关系 AI 精修",
                """
                你是一位角色关系动态评估专家。请根据两位角色的档案、当前关系状态与互动背景，
                综合判断他们此刻应处于的好感度 / 信任度 / 关系状态，输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                { "affinity": 好感度(-100到100的整数), "trust": 信任度(0到100的整数), "relationState": "stranger|acquaintance|friend|intimate|rival|hostile", "description": "关系描述（一句话，可空，null 表示不修改）", "reason": "判断理由（50字内）" }

                —— 要求 ——
                1. affinity 表示好感（-100 极度厌恶 ~ 100 极度喜爱）；trust 表示信任（0 完全不信任 ~ 100 完全信任）。
                2. relationState 从枚举中选取：stranger 陌生 / acquaintance 相识 / friend 朋友 / intimate 亲密 / rival 对手 / hostile 敌对。
                3. 必须基于给定的角色档案、当前关系状态与互动背景合理推断；不得凭空设定与角色人设、世界观冲突的关系。
                4. 若档案信息不足，宁保守（维持现状或微调），不要大幅跳变。

                —— 角色 A ——
                {{from_info}}

                —— 角色 B ——
                {{to_info}}

                —— 当前关系 ——
                {{current_relation}}

                —— 世界背景（可选） ——
                {{world_setting}}
                """, "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        map.put(CODE_CROWD_CATEGORY, new BuiltinDef("普通人群-标准字段数据拟定",
                """
                你是「{{world_name}}」世界观的标准字段数据设计师。请依据以下世界观设定，为该世界的普通平民拟定一套【标准字段数据】（种族含次级种族/归属/职业），并选出最适合用于人群分类调度的 2 个分类字段（一主一次），输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                {
                  "classification": { "primary": "主分类字段", "secondary": "次分类字段" },
                  "fields": {
                    "race": [ { "level1": "种族大类", "level2": "次级种族", "source": "出处" } ],
                    "affiliation": [ { "level1": "归属/势力/组织/村落/家族名", "level2": "", "source": "出处" } ],
                    "occupation": [ { "level1": "职业/身份名", "level2": "", "source": "出处" } ]
                  }
                }

                —— 要求 ——
                1. classification.primary/secondary 从 race(种族)/affiliation(归属)/occupation(职业) 中选 2 个不同字段（如 primary=race、secondary=affiliation）；选「最能区分人群、影响调度立场」的字段。
                2. race：level1=种族大类（如 人族/妖精），level2=该大类下的次级种族（如 汉族/猫妖）；每条都要有出处。
                3. affiliation：level1=归属/势力/组织/村落/家族等（如 会馆/联合国/政府/人类军队）；level2 留空。
                4. occupation：level1=具体职业/身份（如 渔夫/铁匠/官吏）；level2 留空。
                5. 全部字段值必须严格符合世界观：从世界观的地理/势力/文化/历史/种族设定中推导，每条标注 source 出处（引用世界观的具体字段/段落，如「取自世界观【种族】设定的猫妖」），禁止凭空捏造与该世界矛盾的内容（如古代/奇幻世界不应出现现代职业）。
                6. race 一级 3-10 个、每个下二级 2-8 个；affiliation 4-15 个；occupation 5-30 个；名称贴合世界观时代与氛围、具体有辨识度，不要敷衍。
                7. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 世界观设定 ——
                {{world_setting}}
                """, "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        // 轨道 （收口）：居民批量生成模板迁到 11 维 v2 输出契约（《角色档案 Schema 契约与
        // 分维来源》§7 缺口收口：创意发散生成 / 世界初始化同样必须产出 11 维档案）。
        // <p>场景码 {@code ordinary_npc_gen} 与占位符（world_name / world_setting / field_dict / locations /
        // existing_names / count）保持不变（调用点与既有测试零改动）；输出仍是"一个 JSON 数组"（系统提示词
        // 仍为「你只输出严格的 JSON 数组，不输出任何其他文字。」），但数组的<b>每个元素</b>由"旧居民草稿
        // 扁平 Schema"升级为：9 个结构列键（name/gender/race/sub_race/age/affiliation/location/occupation/
        // detail，原样写入 {@code actor_profile} 表列）+ 该居民自己的 11 维档案三键（schema_version=2 /
        // dims 11 键 / _sources 11 键照抄）。</p>
        // <p>为什么契约三键放在<b>元素顶层</b>而不是嵌套一层 persona 子对象：这样
        // {@code PersonaJson.readQuietly(元素 JSON)} 就能直接把该元素解析成 {@code PersonaProfile}
        // （多余的结构列键被 Jackson 忽略），"解析 → 落库"链路零特判。</p>
        // <p>与 {@code character_card_gen}／{@code persona_bootstrap_fill_a/b} 共用同一 schema 与落库分派
        //
        // ⑥→actor_knowledge_doc，其余留在 persona_json，落库前剥离 ③ 维与 ⑩.lineage）。</p>
        // <p>成本口径：一次调用产出 {@code {{count}}} 位居民（含 11 维档案），单人输出预算由
        // {@code OrdinaryNpcService} 的 {@code holzyn.actor.crowd.npc-persona-batch-size}（默认 5）控制——
        // 档案"只可精炼、不可省略"，故批次远小于纯结构列的 30/批（单人 11 维 JSON 约 1.5-2.2k token）。</p>
        map.put(CODE_CROWD_NPC_GEN, new BuiltinDef("普通人群-居民批量生成（11 维 v2）",
                """
                你是「{{world_name}}」世界的居民档案生成器。请基于世界观设定、标准字段数据与地点清单，生成一批符合该世界的普通平民档案（结构列 + 每位居民自己的 11 维【角色统一档案 v2】），输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。

                —— 一、输出契约（数组的每个元素 = 一位居民） ——
                1. 每个元素先给 9 个「结构列」键：name / gender / race / sub_race / age / affiliation / location / occupation / detail。它们是权威数据，会原样写入角色主表列；dims 内的内容必须与它们相容，且不得在 dims 内重复写姓名（姓名权威在表列，不在档案 JSON 内）。
                2. 每个元素还必须给该居民自己的【角色统一档案 v2】三个契约键：schema_version（固定 2）、dims（恰好 11 个键）、_sources（11 键照抄，见下方结构）。只有结构列、没有 dims 的扁平输出已废弃：创意发散生成同样必须产出 11 维档案。
                3. 键名一律 snake_case；数值字段必须是数字（不要把区间说明文字当取值输出）；数组字段无内容时给空数组，禁止 null 占位。
                4. dims 的 11 个键一个都不能少、一个都不能是空壳（空对象/空数组视为残缺）；所有文本字段不得超过括号内标注的字数上限。
                5. 本模板一次产出 {{count}} 位居民，输出长度有上限：若篇幅紧张，先保证 JSON 结构完整（数组与 11 个维键闭合），再适度精炼文本；绝不允许中途截断、也不允许省略维度。

                —— 二、约束强度（铁律：耦合维度严格锚定，非耦合维度大胆创造） ——
                A. 耦合维度 —— 严格锚定（①身份与社会 identity、③生理基线 physio、⑤价值观与动机 values、⑥能力与知识 ability、⑧习惯与日常 habits、⑨经历与创伤 experience、⑩发育·谱系 development）：
                   1. ① 必须贴合【世界观设定】的时代/地理/势力/文化/规则，并严格贴合本居民结构列给出的 种族/次级种族/归属/职业/所在地点/年龄/性别；财产与声望的量级要与身份相称（村社贫户不该有三进祖宅）；地名优先取自【现有地点清单】，不得新造与世界观地理冲突的地名。
                   2. ③ 的数值会直接写入 NPC 状态模块作为 per-NPC 生理参数，驱动饥饿/口渴/精力/睡眠/耐痛的衰减与阈值计算（不是装饰文本）：作息型 chronotype 取 early_bird/intermediate/night_owl；sleep_need_hours 取 5-10（默认 8）；meal_cycle_hours 取 4-12（默认 8，须与 meals_per_day 相容）；meals_per_day 取 1-5；metabolism 取 slow/normal/fast；pain_tolerance/thirst_tolerance/cold_tolerance/heat_tolerance 取 0-10 整数。年龄偏老者应体现衰退，年轻力壮者相反。
                   3. ⑤ 的 life_goals/current_goals 必须由结构列的职业、归属、年龄与当前处境自然推出，并落在世界观允许的范围内（不得出现超出该世界时代的技术/制度/器物/概念）；moral_bottom_lines 要写成可判定的具体行为（如「不吞占公中粮」），不得写成笼统美德。
                   4. ⑥ 必须落在世界观允许的知识范围内——不得出现超出该世界观时代/技术水平的知识、技术、器物、制度、地名或概念；skills 键名必须是该世界观里的具体技艺（如 农事耕作/水利修渠/记账算筹/渔汛辨识），熟练度 0-10；knowledge_domains/knowledge_blind_spots 由职业、归属与年龄推出。
                   5. ⑧ 的 schedule 必须与计划模块的日程粒度兼容：给 4-8 条，每条格式为「时段 + 地点/活动」，时段须覆盖他清醒的一整天并与 ③ 的作息型一致。
                   6. ⑨ 必须能挂到记忆模块与世界编年史上：key_events 每条都要能在世界时间线上定位（age + 发生了什么含地点/涉及谁/结果，source 一律 "preset"）；严禁凭空发明影响他人或世界史的大事件（战争/灾荒/政权更替/神迹）；traumas 必须带具体触发物。
                   7. ⑩ 的谱系只能使用关系模块已支持的亲属类型（父/母/配偶/子女/兄弟姐妹/祖辈/孙辈）；亲属姓名只能引用【已存在居民名单】里的人或本批数组中已出现过的元素，不得凭空发明亲属；无可引用的亲属就省略 lineage；stage 必须与结构列年龄一致。
                B. 非耦合维度 —— 大胆创造（②外貌与体质 appearance、④气质与人格 personality、⑦语言与表达 voice、⑪社会面具 mask）：
                   鼓励大胆、具体、个体化的发明，这是"个体辨识度"的唯一来源：疤痕/老花/口音/称呼习惯/口头禅/小动作/面具切换阈值都要具体到可感知；OCEAN 五个 0-1 数值不得一律 0.5；面具的 context_suppression 八个情境码（before_authority/before_partner/before_stranger/before_peer/before_child/before_rival/public_place/private_place）各给 0-1 抑制强度，要体现"对谁压得最狠、对谁几乎不设防"。
                C. 本批 {{count}} 位居民彼此必须可区分：职业/归属尽量分散（贴合标准字段数据与地点清单的分布），年龄与性别不要整齐划一；禁止"复制粘贴式"的重复人格（OCEAN、口头禅、小动作、面具阈值至少有一处明显不同）。

                —— 三、输出格式（键名严格照抄；dims 含 11 个键；引号内的区间/说明文字是占位说明，必须替换成你判断的实际取值；数组恰好 {{count}} 个元素） ——
                [
                  {
                    "name": "姓名（贴合世界观的时代/文化/种族命名习惯，不得与已存在居民重名）",
                    "gender": "男 / 女 / 无性",
                    "race": "种族（取自标准字段数据一级）",
                    "sub_race": "次级种族（取自对应二级）",
                    "age": 年龄整数,
                    "affiliation": "归属（取自标准字段数据）",
                    "location": "当前所在地（取自地点清单或合理补充）",
                    "occupation": "职业（取自标准字段数据）",
                    "detail": "角色详情（120-200 字：背景/性格/谋生方式/家庭/与世界观的联系）",
                    "schema_version": 2,
                    "dims": {
                      "identity": { "aliases": ["别名/绰号 0-2 个，每个 10 字内"], "identity_title": "身份头衔（20 字内）", "social_class": "阶层（20 字内）", "livelihood": "谋生方式（30 字内，须与结构列的职业/归属一致）", "property": { "house": "住房（20 字内）", "land_mu": "田亩数", "grain_shi": "存粮石数", "money_guan": "现钱贯数", "heirlooms": ["传家物 0-2 条，每条 12 字内"], "notes": "备注（30 字内）" }, "reputation": { "本地": "0-1", "邻域": "0-1", "官府": "0-1", "notes": "声望说明（30 字内）" }, "status_tags": ["身份标签 1-3 个，每个 8 字内"] },
                      "appearance": { "height_cm": "身高厘米数（按种族与年龄合理）", "build": "体型（12 字内）", "posture": "体态（15 字内）", "face_features": "相貌特征（40 字内，含疤痕/皱纹等辨识特征）", "hair": "须发（15 字内）", "attire_style": "衣着风格（25 字内）", "voice_timbre": "嗓音音色（15 字内）", "scent": "体味（12 字内）", "strength": "0-10 整数", "endurance": "0-10 整数", "agility": "0-10 整数", "balance": "0-10 整数", "senses": { "vision": "0-10", "hearing": "0-10", "smell": "0-10", "taste": "0-10", "touch": "0-10", "notes": "感官特点或缺陷（25 字内）" }, "illnesses": [ { "name": "病名（8 字内）", "season": "发作季节/条件（12 字内）", "severity": "轻/中/重" } ], "disabilities": [], "constitution_weaknesses": ["体质弱点 0-2 条，每条 15 字内"], "appearance_notes": "习惯性外观细节（30 字内，如：走路右肩略沉）" },
                      "physio": { "chronotype": "early_bird 或 intermediate 或 night_owl", "sleep_need_hours": "5-10", "sleep_quality": "睡眠质量（12 字内）", "meal_cycle_hours": "4-12", "meals_per_day": "1-5 整数", "metabolism": "slow 或 normal 或 fast", "pain_tolerance": "0-10", "thirst_tolerance": "0-10", "cold_tolerance": "0-10", "heat_tolerance": "0-10", "fertility_state": "生育状态（12 字内）", "libido": "亲密状态（12 字内）", "alcohol_tolerance": "酒量（12 字内）", "stimulants": { "茶": "摄入习惯（12 字内）", "酒": "摄入习惯（12 字内）" }, "excretion_regularity": "排泄规律（12 字内）", "sweat_tendency": "出汗倾向（12 字内）", "physio_notes": "生理补充（25 字内）" },
                      "personality": { "ocean": { "openness": "0-1", "conscientiousness": "0-1", "extraversion": "0-1", "agreeableness": "0-1", "neuroticism": "0-1" }, "temperament_type": "气质型（20 字内）", "attachment_style": "依恋类型（20 字内）", "emotion_thresholds": { "anger": "0-1", "fear": "0-1", "joy": "0-1", "sadness": "0-1" }, "emotion_recovery": "情绪恢复速率（20 字内）", "impulse_control": "0-10 整数", "cognitive_style": "认知风格（15 字内）", "risk_preference": "low 或 medium 或 high", "time_orientation": "时间取向（15 字内）", "traits_summary": "特质摘要（20 字内，4 个词以内）" },
                      "values": { "value_weights": { "价值名（取自世界观的价值范畴，如 宗族/乡邻生计/名节/承诺/信仰/私利）": "0-1" }, "core_fears": ["0-2 条，每条 18 字内"], "core_desires": ["0-2 条，每条 18 字内"], "moral_bottom_lines": ["0-2 条，每条 18 字内，必须可判定"], "life_goals": ["1-2 条，每条 22 字内"], "current_goals": ["1-2 条，每条 22 字内"], "regrets": ["0-2 条，每条 18 字内"] },
                      "ability": { "skills": { "技能名（世界观内的具体技艺）": "0-10" }, "knowledge_domains": ["1-3 条，每条 12 字内"], "knowledge_blind_spots": ["1-3 条，每条 12 字内"], "literacy": "识字程度（20 字内）", "languages": [ { "name": "语言/方言名", "level": "熟练度（如 母语 / 会听，说时夹土音）" } ], "talents": ["0-2 条，每条 12 字内"], "self_efficacy": { "领域（1-3 个，对齐 skills 键名）": "1-5（本人主观评估，允许与 skills 有偏差）" }, "self_comment": "一句话整体自评（30 字内）", "secrets": ["知情秘密 0-2 条，每条 20 字内"] },
                      "voice": { "sentence_length": "句长倾向（15 字内）", "register": "用词雅俗（20 字内）", "speech_rate": "语速（12 字内）", "pitch": "音高（10 字内）", "address_habits": { "to_junior": "对晚辈的称呼", "to_peer": "对同辈的称呼", "self_reference": "自称" }, "catchphrases": ["口头禅 2-4 条，每条 12 字内"], "dialect": "口音腔调（15 字内）", "taboo_topics": ["忌讳话题 0-2 条，每条 10 字内"], "silence_tendency": "沉默倾向（18 字内）", "rhetoric_preference": "修辞偏好（18 字内）", "written_vs_spoken": "书面与口头的差异（18 字内）", "vocabulary_keys": ["特征词表 3-6 个，每个 6 字内"] },
                      "habits": { "schedule": ["时段 + 地点/活动，4-8 条，须与作息型一致"], "sleep_habits": ["1-3 条，每条 15 字内"], "quirks": ["癖好 1-3 条，每条 15 字内"], "micro_movements": ["小动作 1-3 条，每条 15 字内"], "food_preferences": "饮食偏好（20 字内）", "addictions": ["嗜好 0-3 条，每条 12 字内"], "ritual_behaviors": ["仪式性行为 0-3 条，每条 15 字内"] },
                      "experience": { "origin": "出身（40 字内：家族/家境/幼年关键处境）", "key_events": [ { "age": "年龄整数", "what": "事件（30 字内，含地点/涉及谁/结果）", "source": "preset" } ], "traumas": [ { "event": "创伤事件（20 字内）", "age": "年龄整数", "triggers": ["触发物 1-2 个，每个 8 字内"] } ], "honors": ["0-2 条，每条 15 字内"], "unresolved_knots": ["0-2 条，每条 20 字内"] },
                      "development": { "stage": "阶段码（INFANT/TODDLER/CHILD/ADOLESCENT/YOUTH/PRIME/MIDDLE/ELDER/ANCIENT）", "genome": { "位点名": "等位基因对" }, "epigenetic_marks": ["表观修饰 0-2 条"], "lineage": { "father": "父（无则省略该键）", "mother": "母", "spouse": "配偶", "children": ["子女"], "siblings": ["兄弟姐妹"] } },
                      "mask": { "context_suppression": { "before_authority": "0-1", "before_partner": "0-1", "before_stranger": "0-1", "before_peer": "0-1", "before_child": "0-1", "before_rival": "0-1", "public_place": "0-1", "private_place": "0-1" }, "allowed_expressions": { "情境码": ["允许表达 1-3 个"] }, "forbidden_expressions": { "情境码": ["禁止表达 1-3 个"] }, "breaches": [ { "trigger": "触发物", "suppression_delta": "-1 到 1 的数（负值=失态）" } ] }
                    },
                    "_sources": { "identity": {"kind": "MIXED", "module": "actor_profile"}, "appearance": {"kind": "PERSONA", "module": ""}, "physio": {"kind": "MODULE", "module": "actor_state"}, "personality": {"kind": "PERSONA", "module": ""}, "values": {"kind": "MIXED", "module": "actor_goal"}, "ability": {"kind": "MIXED", "module": "actor_knowledge_doc"}, "voice": {"kind": "PERSONA", "module": ""}, "habits": {"kind": "MIXED", "module": "actor_state"}, "experience": {"kind": "MIXED", "module": "actor_memory"}, "development": {"kind": "MIXED", "module": "actor_relation"}, "mask": {"kind": "PERSONA", "module": "actor_state"} }
                  }
                ]

                —— 四、结构列铁律（必须严格遵守） ——
                1. 每位居民都必须严格符合该世界观：姓名贴合世界观的时代/文化/种族/命名习惯，起名具体、有辨识度，禁止「张伟/小明/翠花」这类敷衍名，禁止凭空捏造与世界观冲突的设定（杜绝 OOC）。
                2. race/sub_race 必须从「标准字段数据」中选取且层级对应（如 种族=人族 → 次级种族=汉族）；affiliation/occupation 必须从字段字典中选取。
                3. location 优先从「现有地点清单」中选取；确需新地点时必须符合世界观地理设定。
                4. 性别只能是「男 / 女 / 无性」三选一：绝大多数种族用 男 或 女；该种族无性别概念或无法区分时（如 植灵妖精、元素生命 等）用「无性」。禁止 雄性/雌性/雄/雌 等其他任何表述，禁止在性别后附加括号或任何补充说明（如「女（…）」「无性（…）」均不允许）。性别不得从姓名臆断，依角色设定合理选择。
                5. 年龄与世界观一致、依种族寿命合理设定；姓名不得与「已存在居民名单」重复，同一批内也不得重复。
                6. 恰好生成 {{count}} 名居民，全部有效，不要输出空壳或占位；结构列与档案 dims 均不允许省略。
                7. 只输出上述 JSON 数组（以 [ 开头、以 ] 结尾），不要用 Markdown 代码块包裹，不要输出任何解释文字。

                —— 五、输入材料 ——
                —— 世界观设定 ——
                {{world_setting}}

                —— 标准字段数据（种族/次级种族、归属、职业，必须从其中选取） ——
                {{field_dict}}

                —— 现有地点清单（当前所在地优先从其中选取；确需补充新地点时必须符合世界观） ——
                {{locations}}

                —— 已存在居民名单（避免与已有居民重名；⑩ 谱系的亲属也只能引用其中的人） ——
                {{existing_names}}
                """, "你只输出严格的 JSON 数组，不输出任何其他文字。"));
        map.put(CODE_CROWD_SCHEDULE_PROJECT, new BuiltinDef("普通人群-项目级集体调度",
                """
                你是「{{world_name}}」世界的城市调度中枢。请基于世界观设定与当前时刻，结合普通平民的【分类字段分组概况】与【归属概况】，做一次【项目级集体调度】：判断此刻世界整体氛围，并为每个主分类字段分组（如按种族的人群）与每个归属下发本时段的具体调度指令，输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                —— 世界观设定 ——
                {{world_setting}}

                —— 当前时刻 ——
                {{hour}} 点

                —— 主分类字段（{{primary_field}}）分组概况 ——
                {{primary_overview}}

                —— 次分类字段（{{secondary_field}}）分组概况 ——
                {{secondary_overview}}

                —— 各归属的普通平民概况（人数/职业构成/状态） ——
                {{affiliation_overview}}

                —— 输出格式 ——
                { "summary": "项目整体状态的一句话描述（含时刻/氛围/主要活动，100 字内）", "primaryGroups": [ { "group": "主分类分组名（与概况中的分组完全一致）", "directive": "该人群本时段的调度指令（如：人族午后在广场集会游行，50-100字，必须符合世界观与该人群构成）" } ], "affiliations": [ { "affiliation": "势力/归属名（与概况完全一致）", "directive": "该归属本时段的调度指令（如：会馆维持秩序、联合国发表声明，50-100 字，必须符合世界观与该归属立场/职业构成）" } ] }

                —— 要求 ——
                1. primaryGroups 只为概况中出现的主分类分组下发指令；affiliations 只为概况中出现的归属下发指令；名称必须与概况完全一致。
                2. 调度可「按人群」（primaryGroups：如某种族因某事件组织集会游行）也可「按归属」（affiliations：不同归属立场不同），两者并存、互补；主/次分类字段即人群维度（如按种族），归属维度独立叠加。
                3. 所有指令必须符合世界观与该分组/归属的职业、身份、立场构成，不得矛盾，禁止敷衍。
                4. summary 供对话环境注入，要自然、有世界感。
                """, "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        map.put(CODE_CROWD_SCHEDULE_AFFILIATION, new BuiltinDef("普通人群-归属级集体调度",
                """
                你是「{{world_name}}」世界中「{{affiliation}}」的调度者。请根据【项目级调度指令（含该归属指令与相关人群指令）】与该归属的居民名单，为每位居民确定本时段的【状态与行动】，输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。

                —— 项目级调度指令（该归属） ——
                {{directive}}

                —— 相关人群调度指令（该归属成员所属主分类分组的指令，可能影响个体行动） ——
                {{group_directives}}

                —— 当前时刻 ——
                {{hour}} 点

                —— 该归属居民名单（姓名/种族/次级种族/职业/归属/当前状态） ——
                {{members}}

                —— 输出格式 ——
                [ { "name":"居民姓名", "state":"idle|walk|stop|talk|rest 之一", "action":"该居民本时段的行动描述（20-60 字，第三人称，符合其职业/身份/种族、调度指令与当前时刻）" } ]

                —— 要求 ——
                1. 每位居民一条；name 必须与名单中的姓名完全一致，不得遗漏、不得新增。
                2. state 从给定枚举中选择；action 要符合其职业/身份/种族、归属指令、人群指令与当前时刻，符合世界观，禁止敷衍。
                3. 输出数量必须与名单人数完全一致。
                """, "你只输出严格的 JSON 数组，不输出任何其他文字。"));
        map.put(CODE_WORLD_SEGMENT, new BuiltinDef("世界观文件-总结分段",
                        """
                        你是一位世界设定整理专家。请阅读以下世界观文件，将其内容【总结并分段】为七个部分，严格输出一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        {
                          "projectName": "项目名称（从内容或文件名推断）",
                          "projectSummary": "项目概要（100 字内）",
                          "worldName": "世界观名称",
                          "genre": "题材",
                          "era": "时代背景",
                          "geography": "地理设定（地理/地图/地貌/生态/版图，忠实概括原文）",
                          "factions": "势力格局（势力/阵营/组织/政治格局，忠实概括原文）",
                          "magicSystem": "规则体系（规则/体系/能力/法则/魔法/科技，忠实概括原文）",
                          "culture": "社会文化（文化/风俗/民俗/社会/传统/信仰，忠实概括原文）",
                          "history": "历史脉络（历史/脉络/纪元/大事/时间线，忠实概括原文）",
                          "supplement": "补充设定（上述六类之外的其他设定内容，如实收录）",
                          "characters": "角色信息（文件中出现的全部角色设定原文，尽量完整保留每个角色的详细信息，不要压缩丢失）"
                        }

                        —— 要求 ——
                        1. 总结必须忠实于原文，不得编造原文不存在的设定；内容不足的分段可为空字符串或简短描述。
                        2. 各分段应尽量完整覆盖原文对应章节，不要遗漏重要信息。
                        3. 角色信息分段请完整保留角色详细信息（姓名/身份/性格/能力/经历/关系等），后续会逐个提取角色，丢失即角色缺失。
                        4. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                        —— 世界观文件 ——
                        {{files_content}}
                        """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLD_SEGMENT_CHARACTERS, new BuiltinDef("世界观文件-角色完整分离",
                        """
                        你是一位角色档案提取器。请阅读以下【角色信息】分段内容，将其中出现的每个角色【完整分离】，逐个输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        [ { "type": "special", "name": "角色名", "title": "头衔/称号", "detail": "该角色的完整详细信息（身份/性格/能力/经历/目标/关系等，原文有多少写多少，只可多不可少）", "isProtagonist": 0, "importance": 3 } ]

                        —— 要求 ——
                        1. 必须提取该分段中出现的全部角色，一个都不能遗漏（包括配角、反派、龙套、提及过名字且有设定的角色）。
                        2. detail 必须完整保留该角色的详细信息，原文有详细信息就原样保留；内容充足时不得压缩到几句话；只可多不可少，禁止 OOC。
                        3. isProtagonist=1 表示主角（可有多位主角）；importance 为 1-5 的重要度：主角 5，重要配角 3-4，普通角色 1-2——由你依据世界观信息与角色详细信息自行判断。
                        4. 若没有任何角色信息，输出空数组 []，不要编造。
                        5. 只输出 JSON 数组，不要用 Markdown 代码块包裹。

                        —— 角色信息分段 ——
                        {{characters_content}}
                        """, "你只输出严格的 JSON 数组，不输出任何其他文字。"));
        map.put(CODE_WORLD_TIME_INFER, new BuiltinDef("世界初始化-世界时间推断",
                        """
                        你是一位世界编年史专家。请依据给定世界观的历史脉络与时代背景，推断【当前】世界历时间点——即这个世界的故事目前进行到哪一年/月/日/时刻（不要从头开始计算，要落在历史脉络中「现在」所处的位置），严格输出一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        { "year": 1050, "month": 3, "day": 12, "hour": 8, "minute": 0, "second": 0, "summary": "一句话说明推断依据" }

                        —— 要求 ——
                        1. year 必须是正整数（>=1），即当前故事所处年份；month 1-12、day 1-30、hour 0-23、minute/second 0-59。
                        2. 依据历史脉络/时代背景/纪元纪年推断「当前时刻」落在哪个时间点；若世界观未给出具体纪年，依据其历史跨度合理推断（如「上古→中古→近代」可按纪元推进给出一个当前年份）。
                        3. 世界历规则：1 年 = 12 月 = 360 日（每月 30 日），请按此换算。
                        4. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                        —— 世界观名称 ——
                        {{world_name}}

                        —— 题材 / 时代背景 ——
                        {{genre}} / {{era}}

                        —— 历史脉络（截断）——
                        {{history}}
                        """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLD_EVENT_POLISH, new BuiltinDef("世界引擎-跨日事件润色",
                        """
                        你是一位虚构世界的叙述者。请把下面的「机制生成事件草稿」润色为一段成文的世界事件（保留机制设定的事件主旨与时序，补全发生经过与对世界/角色的影响），严格输出一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        { "title": "事件标题（一句话，贴合世界观）", "content": "事件描述（包含时间、地点、发生了什么、对世界/在场角色的影响，120 字以内，贴合世界观，不要编造机制草稿未提及的设定）" }

                        —— 世界观设定 ——
                        {{world_setting}}

                        —— 机制生成事件草稿（必须以此为基础润色，不得另起炉灶）——
                        {{draft}}

                        —— 当前情境 ——
                        {{context}}

                        —— 世界氛围（世界的气质底色，可自然地融入事件的叙述语气与侧重点，但不直白复述）——
                        {{atmosphere}}

                        —— 要求 ——
                        1. 必须以草稿为基础：草稿确定「何时/何地/什么事件」，润色只补经过与影响，不改变事件主旨。
                        2. 贴合给定世界观设定，不要引入与世界观冲突的设定。
                        3. 只输出上述 JSON，不要用 Markdown 代码块包裹。
                        """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_GOAL_GEN, new BuiltinDef("角色目标 AI 生成",
                """
                你是一位角色的长期规划顾问。请基于【角色档案】与【世界观设定】，为该角色生成 1~3 个合理的【长期目标】
                ——即这个角色在当前阶段真正想达成、会持续为之努力的方向（对齐 Smallville 层级规划的目标层），
                输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                [ { "title": "目标标题（一句话，如：修复与旧友的关系）", "description": "目标描述（背景/动机/成功标准，40-80 字）", "priority": 1到5的整数 } ]

                —— 要求 ——
                1. 目标必须与角色档案（性格/背景/职业/身份/当前状态）一致，符合世界观与逻辑，禁止 OOC。
                2. 目标要有方向性与行为弧线：能引导该角色后续多日的行动（计划围绕目标展开）。
                3. priority 表示优先级（5 最高），1~2 个高优先 + 1 个低优先均可，避免全部等权。
                4. 若已有 active 目标（见 existing_goals），只需补充【新方向】或与已有目标互补的目标，不要重复。
                5. 只输出 JSON 数组，不要用 Markdown 代码块包裹。

                —— 角色档案 ——
                {{character_info}}

                —— 世界观设定 ——
                {{world_setting}}

                —— 当前状态 ——
                {{current_state}}

                —— 已有 active 目标 ——
                {{existing_goals}}
                """, "你只输出严格的 JSON 数组，不输出任何其他文字。"));
        map.put(CODE_PLAN_DAY, new BuiltinDef("日计划 AI 生成",
                """
                你是一位角色日程规划师。请为角色生成【今天的日计划】（对齐 Smallville 层级规划：日计划→时段→动作），
                输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                { "date_game_day": {{date_game_day}}, "items": [ { "hour": 8, "action": "动作描述（贴合角色身份，15-40 字）", "target": "目标地点/对象（可空）", "location": "活动地点（可空）", "state": "walk|stop|talk|rest 之一" } ] }

                —— 要求 ——
                1. items 恰好 5~8 条，hour 取值 0-23 且按时间升序排列，覆盖一整天的主要时段
                   （清晨 6-7、上午 8-11、正午 12-13、午后 14-17、傍晚 18-19、夜晚 20-22、深夜 23-5）。
                2. 动作必须贴合角色职业/身份/性格/目标（见 goals），符合世界观与当前状态，禁止 OOC。
                3. 若角色有 active 目标（见 goals），计划应围绕目标展开（至少 2 个时段与目标相关）。
                4. state 与动作一致：赶路=walk、劳作/驻足=stop、社交=walk/talk、休息=rest。
                5. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 角色档案 ——
                {{character_info}}

                —— 世界观设定 ——
                {{world_setting}}

                —— 当前状态 ——
                {{current_state}}

                —— 角色 active 目标 ——
                {{goals}}
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_PLAN_REPLAN, new BuiltinDef("日计划中断重排",
                """
                你是角色日程规划师。该角色的今日计划被突发事件打断，请基于打断事件【重排今日剩余时段】的计划
                （当前小时及之后的条目），输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                { "date_game_day": {{date_game_day}}, "items": [ { "hour": 当前小时及之后的整数(0-23), "action": "动作描述（15-40 字）", "target": "目标地点/对象（可空）", "location": "活动地点（可空）", "state": "walk|stop|talk|rest 之一" } ] }

                —— 要求 ——
                1. items 按时间升序，hour 必须 ≥ 当前小时（{{current_hour}}）；覆盖被打断之后的剩余时段（4~8 条）。
                2. 重排要体现打断事件的影响：角色先应对事件，再视情况回归原计划或调整后续安排（见 old_plan）。
                3. 动作贴合角色身份/目标（见 goals）与世界观，禁止 OOC；不要机械复述 old_plan 已过时段。
                4. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 角色档案 ——
                {{character_info}}

                —— 世界观设定 ——
                {{world_setting}}

                —— 当前状态 ——
                {{current_state}}

                —— 角色 active 目标 ——
                {{goals}}

                —— 打断事件 ——
                {{disruption_event}}

                —— 原计划（供参考已过时段） ——
                {{old_plan}}
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_REFLECTION, new BuiltinDef("反思洞察生成",
                """
                你是一位自我反思模块。请基于该角色【近期记忆】提炼高层的【自我认知/社会洞察/经验教训】，并判断
                其【目标状态】是否应更新，输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                { "insights": [ { "content": "洞察内容（一句话，20-50 字：从近期经历抽象出的自我认识/人际关系教训/世界认知/新目标动机）", "importance": 1到5的整数 } ], "goal_updates": [ { "goal_id": 目标ID, "status": "done|abandoned", "reason": "判定理由（30字内）" } ] }

                —— 要求 ——
                1. insights 1~3 条：必须基于【近期记忆】中的证据抽象，不得凭空编造；比底层事实更高一层
                   （如「我发现自己更信任合作过的伙伴」而非复述事件）。
                2. importance 依洞察对角色长期行为的重要性给 1-5。
                3. goal_updates：仅当某 active 目标确实已达成（done）或因现实原因无法继续（abandoned）才输出；
                   证据不足保持 active 不输出；status 只能是 done 或 abandoned。
                4. 标记「（可能过时）」的记忆代表其事实可能已失效——优先基于这些记忆复核，不要把它们当作
                   确定事实引用（可在洞察中体现「情况可能有变」）。
                5. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 角色档案 ——
                {{character_info}}

                —— 当前游戏时刻 ——
                {{game_time}}

                —— 角色 active 目标 ——
                {{goals}}

                —— 近期记忆（反思证据；「（可能过时）」=待复核） ——
                {{recent_memories}}

                —— 近期自省漂移记录（若有；你最近「知道但没做到」的时刻，下次要引以为戒） ——
                {{self_drift_summary}}

                —— 此刻主动想起（若有；你刚想起的与当前反思相关的过往/见闻，可纳入反思证据） ——
                {{proactive_memory}}
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_AUTONOMOUS_CHAT_BEAT, new BuiltinDef("无玩家群聊退场/收尾编排",
                """
                你是一位无玩家群聊的「社交调度」。当前有一场 NPC 之间自发的闲聊正在进行（没有玩家在场，角色们在同一地点自然交谈）。
                请判断：是否有成员要【自愿退场】（有事离开/话不投机/被叫走等，有理有据），本场闲聊是否应【收尾】，
                以及这场闲聊让在场成员之间的关系发生了什么变化（供关系系统沉淀）。

                —— 输出结构（严格 JSON，禁止输出其他文字） ——
                {
                  "leaves": [ { "memberIndex": 成员序号, "reason": "退场理由（有理有据，符合人设与对话情境，如：有事要办/话不投机/天色已晚/受到召唤）" } ],
                  "shouldFinish": false,
                  "finishReason": "收尾理由（shouldFinish=true 时填写；如：大家已无话可聊/天色已晚各自散去）",
                  "relationship_impact": [ { "memberIndexA": 成员序号, "memberIndexB": 成员序号, "delta": -5到5的整数, "reason": "一句话依据（这场闲聊中的具体言行）" } ]
                }

                —— 铁律 ——
                1. memberIndex 只能从在场成员序号 1..N 中选择（见成员序号列表）；leaves 默认空数组，不要频繁安排退场。
                2. 退场必须有理有据，符合角色身份与对话走向；不要无故、突兀离场。
                3. 当场景只剩 1 名角色、或大家已无话可聊、或剧情自然收束时，必须 shouldFinish=true 并给出 finishReason。
                4. relationship_impact 只记录这场闲聊中真实的互动（相谈甚欢/争执/帮忙/冷场等），无明显互动则空数组；
                   delta 正值=关系变好（亲近/信任），负值=关系变差；不要无中生有。
                5. 所有判断必须基于【成员序号列表】【最近对话】与本场闲聊背景，逻辑自洽，不替角色编造剧情。

                —— 成员序号列表 ——
                {{members_summary}}

                —— 最近对话 ——
                {{context}}

                —— 本场闲聊背景 ——
                {{chat_background}}

                —— 角色与世界背景 ——
                {{injected_context}}

                {{tom_section}}
                """, "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        map.put(CODE_WORLDGEN_CORELAWS, new BuiltinDef("世界观生成-维度一 核心法则",
                """
                你是一位世界底层规则架构师，负责为这个虚构世界定义【不可违背的核心法则】。

                ## 角色职责与核心原则
                1. 只描述客观世界事实（力量体系、公理、禁忌、显化方式）；不写剧情、不写角色命运、不写叙事建议。
                2. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
                3. 启发式详略：与现实世界无异的常识（物理规律、衣食住行）略写；本作独创/改编/偏离常识越大的设定详写清楚，便于后续使用。
                4. 内部自洽：公理之间不矛盾，禁忌红线应是公理的直接推论。
                5. 为这一维度写一句 essence 定调句（用本作专有名词，≤50 字，具体不空泛）。
                6. 本维度正文建议 500-800 字，信息密度优先。

                ## 各字段说明
                - essence：一句话定调，≤50 字，用本作专有名词。
                - power_system：力量或技术的来源、等级划分、使用代价、边界限制。
                - axioms：不可违背的底层规则数组，每条必须含 name/statement/cost/boundary/enforcement。
                - taboos：绝对禁区数组，违反即遭受世界级惩罚的事。
                - power_manifestation：力量施展时的感官反应，给写手可用的描写素材。

                ## 输出格式（只返回一个纯 JSON 对象，不要 Markdown 围栏，不要解释文字）
                {
                  "core_laws": {
                    "essence": "string（≤50字，一句话定调）",
                    "power_system": "string",
                    "axioms": [
                      {"name": "string", "statement": "string", "cost": "string", "boundary": "string", "enforcement": "string"}
                    ],
                    "taboos": ["string"],
                    "power_manifestation": "string"
                  }
                }

                ## 🔴 JSON 完整性铁律
                - 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
                - 字符串值内不得包含未转义的换行符，如需换行请用 \\n。
                - 数组/对象最后一个元素后不得有尾随逗号。
                - 禁止使用破折号「——」，用逗号或句号断句。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLDGEN_SPACETIME, new BuiltinDef("世界观生成-维度二 时空地理",
                """
                你是一位世界地理与时空架构师，负责根据已确立的核心法则构建这个世界的【时空地理】。

                ## 角色职责与核心原则
                1. 只描述客观世界事实（世界格局、关键地点、生态、时代、环境质感）；不写剧情、不写角色命运。
                2. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
                3. 必须尊重前序维度已确立的设定，不得推翻或矛盾。
                4. 启发式详略：与现实无异的常识（四季更替、日升月落）略写；本作独创的地理形态/地点越特殊越需详写。
                5. 为这一维度写一句 essence 定调句（用本作专有名词，≤50 字，具体不空泛）。
                6. 本维度正文建议 500-800 字，信息密度优先。

                ## 各字段说明
                - essence：一句话定调，≤50 字。
                - world_layout：世界格局宏观总览（大陆/海洋/区域划分、各区域关系）。
                - key_locations：关键地点数组，每条含 name/terrain/feature/risk/controlling_force。
                - ecology：生态（动植物、自然环境与本作特色）。
                - era：时代（纪年/技术/交通水平）。
                - environment_texture：环境质感（给写手可用的氛围描写素材）。

                ## 输出格式（只返回一个纯 JSON 对象，不要 Markdown 围栏）
                {
                  "space_time": {
                    "essence": "string（≤50字，一句话定调）",
                    "world_layout": "string",
                    "key_locations": [
                      {"name": "string", "terrain": "string", "feature": "string", "risk": "string", "controlling_force": "string"}
                    ],
                    "ecology": "string",
                    "era": "string",
                    "environment_texture": "string"
                  }
                }

                ## 🔴 JSON 完整性铁律
                - 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
                - 字符串值内不得包含未转义的换行符，如需换行请用 \\n。
                - 数组/对象最后一个元素后不得有尾随逗号。
                - 禁止使用破折号「——」，用逗号或句号断句。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLDGEN_SOCIETY, new BuiltinDef("世界观生成-维度三 社会权力",
                """
                你是一位社会结构架构师，负责根据已确立的前序维度构建这个世界的【社会权力格局】。

                ## 角色职责与核心原则
                1. 只描述客观世界事实（种族、阶层、政体、势力与关系、权力可见性）；不写剧情、不写角色命运。
                2. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
                3. 必须尊重前序维度已确立的设定，不得推翻或矛盾（阶层/政体须与时代、地理相容）。
                4. 启发式详略：与现实无异的组织形态略写；本作独创的种族/势力/制度越特殊越需详写。
                5. 为这一维度写一句 essence 定调句（用本作专有名词，≤50 字）。
                6. 本维度正文建议 500-800 字，信息密度优先。

                ## 各字段说明
                - essence：一句话定调，≤50 字。
                - races：种族数组，每条含 name/traits/population/social_status。
                - class_structure：阶层结构。
                - political_system：政治制度。
                - forces：势力数组，每条含 name/type/base_of_power/goals/methods。
                - force_relations：势力关系数组，每条含 source/target/relation/tension_point。
                - power_visibility：权力在日常生活中的可见程度。

                ## 输出格式（只返回一个纯 JSON 对象，不要 Markdown 围栏）
                {
                  "society": {
                    "essence": "string（≤50字，一句话定调）",
                    "races": [
                      {"name": "string", "traits": "string", "population": "string", "social_status": "string"}
                    ],
                    "class_structure": "string",
                    "political_system": "string",
                    "forces": [
                      {"name": "string", "type": "string", "base_of_power": "string", "goals": "string", "methods": "string"}
                    ],
                    "force_relations": [
                      {"source": "string", "target": "string", "relation": "string", "tension_point": "string"}
                    ],
                    "power_visibility": "string"
                  }
                }

                ## 🔴 JSON 完整性铁律
                - 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
                - 字符串值内不得包含未转义的换行符，如需换行请用 \\n。
                - 数组/对象最后一个元素后不得有尾随逗号。
                - 禁止使用破折号「——」，用逗号或句号断句。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLDGEN_HISTORYCULTURE, new BuiltinDef("世界观生成-维度四 历史文化",
                """
                你是一位历史与文化架构师，负责根据已确立的前序维度构建这个世界的【历史文化】。

                ## 角色职责与核心原则
                1. 只描述客观世界事实（重大事件、宗教、风俗、经济、日常切片）；不写剧情、不写角色命运。
                2. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
                3. 必须尊重前序维度已确立的设定，不得推翻或矛盾（宗教/经济须与种族、势力、时代相容）。
                4. 启发式详略：与现实无异的日常习俗略写；本作独创的历史事件/宗教/风俗越特殊越需详写。
                5. 为这一维度写一句 essence 定调句（用本作专有名词，≤50 字）。
                6. 本维度正文建议 500-800 字，信息密度优先。

                ## 各字段说明
                - essence：一句话定调，≤50 字。
                - major_events：重大事件数组，每条含 event/era/lasting_impact。
                - religions：宗教数组，每条含 name/core_belief/follower_scope。
                - customs：风俗习惯。
                - economy：经济（生产、贸易、货币、贫富）。
                - daily_slice：日常切片（普通人的一天，给写手生活感素材）。

                ## 输出格式（只返回一个纯 JSON 对象，不要 Markdown 围栏）
                {
                  "history_culture": {
                    "essence": "string（≤50字，一句话定调）",
                    "major_events": [
                      {"event": "string", "era": "string", "lasting_impact": "string"}
                    ],
                    "religions": [
                      {"name": "string", "core_belief": "string", "follower_scope": "string"}
                    ],
                    "customs": "string",
                    "economy": "string",
                    "daily_slice": "string"
                  }
                }

                ## 🔴 JSON 完整性铁律
                - 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
                - 字符串值内不得包含未转义的换行符，如需换行请用 \\n。
                - 数组/对象最后一个元素后不得有尾随逗号。
                - 禁止使用破折号「——」，用逗号或句号断句。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLDGEN_EXISTENCE, new BuiltinDef("世界观生成-维度五 存在基础",
                """
                你是一位存在与生命规则架构师，负责根据已确立的前序维度定义这个世界的【存在基础】。

                ## 角色职责与核心原则
                1. 只描述客观世界事实（历法、寿命、死亡、疾病与生育）；不写剧情、不写角色命运。
                2. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
                3. 必须尊重前序维度已确立的设定，不得推翻或矛盾（历法须与时代/天文设定相容，寿命须与种族相容）。
                4. 启发式详略：与现实无异的常识（凡人需吃饭睡觉）略写；本作独创的历法/寿命/死亡规则越特殊越需详写。
                5. 为这一维度写一句 essence 定调句（用本作专有名词，≤50 字）。
                6. 本维度正文建议 400-700 字，信息密度优先。

                ## 各字段说明
                - essence：一句话定调，≤50 字。
                - calendar：历法（纪年、月份、季节、特殊天文现象）。
                - lifespan：寿命与衰老（各族寿命、衰老曲线、本作特殊规则）。
                - death：死亡（死亡方式、死后世界/轮回/超度等本作规则）。
                - disease_and_birth：疾病与生育（流行病、医疗手段、生育习俗）。

                ## 输出格式（只返回一个纯 JSON 对象，不要 Markdown 围栏）
                {
                  "existence": {
                    "essence": "string（≤50字，一句话定调）",
                    "calendar": "string",
                    "lifespan": "string",
                    "death": "string",
                    "disease_and_birth": "string"
                  }
                }

                ## 🔴 JSON 完整性铁律
                - 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
                - 字符串值内不得包含未转义的换行符，如需换行请用 \\n。
                - 数组/对象最后一个元素后不得有尾随逗号。
                - 禁止使用破折号「——」，用逗号或句号断句。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLDGEN_INFORMATION, new BuiltinDef("世界观生成-维度六 信息传播",
                """
                你是一位信息生态架构师，负责根据已确立的前五维度定义这个世界的【信息传播格局】。

                ## 角色职责与核心原则
                1. 只描述客观世界事实（信息流速、知识媒介、信息壁垒、谣言与真相）；不写剧情、不写角色命运。
                2. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
                3. 必须尊重前序维度已确立的设定，不得推翻或矛盾（信息速度须与时代相容，信息壁垒须与政体/势力相容，知识载体须与力量体系/宗教相容）。
                4. 启发式详略：与现实无异的常识（飞鸽传书、口耳相传）略写；本作独创的信息格局/媒介越特殊越需详写。
                5. 为这一维度写一句 essence 定调句（用本作专有名词，≤50 字）。
                6. 本维度正文建议 400-700 字，信息密度优先。

                ## 各字段说明
                - essence：一句话定调，≤50 字。
                - info_speed：从世界一端到另一端，重大消息需多久被普通人知晓。
                - knowledge_medium：知识存在哪、谁掌管、普通人能接触到什么层级。
                - info_barriers：信息垄断者是谁、封锁了什么、封锁手段。
                - rumor_and_truth：信息如何失真、常见误解、被谁利用。

                ## 输出格式（只返回一个纯 JSON 对象，不要 Markdown 围栏）
                {
                  "information": {
                    "essence": "string（≤50字，一句话定调）",
                    "info_speed": "string",
                    "knowledge_medium": "string",
                    "info_barriers": "string",
                    "rumor_and_truth": "string"
                  }
                }

                ## 🔴 JSON 完整性铁律
                - 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
                - 字符串值内不得包含未转义的换行符，如需换行请用 \\n。
                - 数组/对象最后一个元素后不得有尾随逗号。
                - 禁止使用破折号「——」，用逗号或句号断句。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLDGEN_CHARACTER_ROSTER, new BuiltinDef("角色工作流-阵容设计",
                """
                你是一位角色导演，负责为这个世界设计「核心角色阵容」。这些角色将作为世界中的主要 NPC 生活与行动。

                ## 角色职责与核心原则
                1. 只设计角色清单，不写剧情、不写角色命运、不设计情节。
                2. 阵容规模：依据世界观体量自然决定，至少 3 人、通常 6~12 人；角色之间应有差异化的社会位置与性格光谱，避免同质化。
                3. 每个角色必须与世界观设定相容（种族/势力/职业须符合既有设定），并服务于让世界「像真实社会」运转。
                4. 重要度分级（importance）：主角（1~2 人，命运与世界主线强绑定）/重要（2~3 人，与主角有强关系）/次要（其余，有鲜明立场的普通人）。宁缺毋滥，每个角色都要有「存在的理由」。
                5. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。

                ## 各字段说明
                - characters：角色数组，每条含 name/role/importance/gender/race/age/affiliation/occupation/tagline。
                - role：一句话定位（如「村长·沈氏宗族族长」「茶馆老板娘」「游方说书人」），是其社会身份。
                - importance：主角 / 重要 / 次要 三档之一。
                - tagline：一句话人物标签（如「守旧而刚正的族长」），供后续深度生成锚定，≤20 字。
                - age：建议年龄（数字或区间），须符合世界观种族寿命规则。

                ## 输出格式（只返回一个纯 JSON 对象，不要 Markdown 围栏）
                {
                  "characters": [
                    {
                      "name": "string（角色姓名）",
                      "role": "string（一句话社会定位）",
                      "importance": "主角|重要|次要",
                      "gender": "string",
                      "race": "string（须符合世界观种族）",
                      "age": "string（如 56 或 40~50）",
                      "affiliation": "string（归属势力/组织/村落/家族）",
                      "occupation": "string（职业）",
                      "tagline": "string（≤20字人物标签）"
                    }
                  ]
                }

                ## 🔴 JSON 完整性铁律
                - 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
                - 字符串值内不得包含未转义的换行符，如需换行请用 \\n。
                - 数组/对象最后一个元素后不得有尾随逗号。
                - 禁止使用破折号「——」，用逗号或句号断句。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLDGEN_CHARACTER_DEPTH, new BuiltinDef("角色工作流-逐角色深度档案",
                """
                你是一位角色塑造师，为下面这个角色生成【深度档案】——决定他/她「为什么是这样的人」「说话是什么样子」，让角色像真实的人一样被驱动、被约束、被认出。

                ## 角色职责与核心原则
                1. 深度档案只服务角色本身：写「这个人是谁」，不写剧情、不写命运安排。
                2. 四段必须内部自洽：beliefs（信念）驱动 deep（深层）与 voice（声线）；skeleton（骨架）是外部事实。信念与深层不能互相矛盾。
                3. 信念越具体越可信：不要「善良/勇敢」式空标签，要「救人之前先算清代价」这种可解释的立场。
                4. 声线必须让角色「开口即被认出」：说话方式、口头禅、用词习惯要与身份/性格一致（如村长的官腔、说书人的抑扬顿挫）。
                5. 忠于世界观：角色的出身/秘密/欲望不得与世界观设定（势力、禁忌、力量体系）冲突。
                6. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。

                ## 各字段说明
                - skeleton：骨架（外部事实）——name（须与阵容一致）、title、gender、race、age、origin（出身）、background（过往经历概要）、appearance（外貌，供渲染用）。
                - beliefs：信念（内部驱动）——core_values[]（核心价值，2~4 条具体立场）、desires[]（欲望，2~3 条）、fears[]（恐惧，2~3 条）、beliefs[]（处世信条，2~4 条）。
                - deep：深层（不轻易示人的内核）——secret（秘密）、trauma（创伤）、moral_boundary（道德边界：绝不做/绝对会做）、growth_arc（可能的成长弧线，仅潜台词）。
                - voice：声线（语言指纹）——speech_style（说话风格）、catchphrases[]（口头禅，2~3 个）、tone（语气基调）、language_habits（用词习惯：称谓/方言/忌讳词）。

                ## 输出格式（只返回一个纯 JSON 对象，不要 Markdown 围栏）
                {
                  "character_depth": {
                    "skeleton": {
                      "name": "string",
                      "title": "string",
                      "gender": "string",
                      "race": "string",
                      "age": "string",
                      "origin": "string",
                      "background": "string",
                      "appearance": "string"
                    },
                    "beliefs": {
                      "core_values": ["string"],
                      "desires": ["string"],
                      "fears": ["string"],
                      "beliefs": ["string"]
                    },
                    "deep": {
                      "secret": "string",
                      "trauma": "string",
                      "moral_boundary": "string",
                      "growth_arc": "string"
                    },
                    "voice": {
                      "speech_style": "string",
                      "catchphrases": ["string"],
                      "tone": "string",
                      "language_habits": "string"
                    }
                  }
                }

                ## 🔴 JSON 完整性铁律
                - 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
                - 字符串值内不得包含未转义的换行符，如需换行请用 \\n。
                - 数组/对象最后一个元素后不得有尾随逗号。
                - 禁止使用破折号「——」，用逗号或句号断句。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLDGEN_STORY_PLAN, new BuiltinDef("故事工作流-故事宏观规划（三层力学引擎·世界版）",
                """
                你是一位世界叙事引擎设计师。你的任务不是规划情节，而是**定义驱动这个世界的力学结构**——什么力在推、什么力在拉、这些力怎么互相作用、力的边界在哪里。

                ## 管线位置

                你位于世界生成管线第 3 站：世界观 → 角色 → **你在这里** → 世界运行。

                你的产出是这个世界持续运行的**力学基础**：
                - 你定义"重力加速度是9.8，摩擦力与正压力成正比"
                - 世界的日常推进决定"物体在第3秒到达什么位置"
                - 每个角色的自由意志决定"这个瞬间灰尘怎么飞"

                **你绝不做下游的事。**

                ## 核心原则（世界版）

                1. **只定义力的性质和规律**。不定义哪个角色在什么时刻遭遇什么事件——那是角色自由意志与日常运行自然涌现的事
                2. **给定角色直接用**。输入中已有具体角色姓名和设定——直接引用，不改为抽象槽位
                3. **禁止创造新角色**。如需某个功能位的角色尚不存在，在 issues 中标记 missing_info
                4. **禁止创造新世界规则**。优先使用输入中已有的世界规则（力量体系/势力/禁忌），不在任何字段中凭空扩展世界设定
                5. **禁止写具体事件**。不写"谁说了什么""谁做了什么""某日发生了某事"——本世界是持续运行的生活，不是被书写的剧本
                6. **控制信息密度**。不凭空扩展设定，优先使用输入中已有的世界规则和角色信息
                7. **一切为了真实**：本世界的目标不是"好看的故事"，而是"可信的生活"——张力来自角色欲望与现实约束的持续碰撞，来自世界内在矛盾的自然演化

                ## 题材适配

                根据提供的题材调整叙事重心：
                - 仙侠/玄幻：力量体系的内外冲突、境界代价、天道规则与个人意志的碰撞
                - 都市：阶层壁垒、信息不对称、资源博弈、社会规则的内化与反抗
                - 悬疑/推理：信息揭示的节奏控制、认知误导的合法性、真相分层推进
                - 恐怖/克苏鲁：认知崩塌、现实不可信、真相不可直视、恐惧的不可逆性
                - 历史：权力结构的不可逆性、时代约束下的个人选择空间
                - 通用：注重人物困境与关系张力

                ## 构建要求（三层力学引擎）

                你必须完成以下力学构建：

                **一、显性层 surface——世界在生活其中的人（与观察者）面前持续呈现的气质**
                1. essence：一句话定调——这个世界里的人持续感受到的东西。用世界专有名词，≤50字
                2. expanded_premise：强化张力后的世界前提。写清「这个世界为什么充满张力」「人们为什么无法安于现状」。不是简介润色。≤800字
                3. core_conflict：长期不可调和的对立。写清对立双方、为什么不可能妥协（是结构性的不可能，不是某个角色的意愿）。≤300字
                4. world_experience：这个世界的居民（与造访者）会持续体验到的生活质感类型。不是事件预告——不写「某角色会遭遇什么」，而是写「生活在这个世界会持续感受到什么」。≤400字
                5. tone_reference：世界叙事气质的感性锚点。用具体例子（一段叙述口吻、一个场景质感、一句对话节奏）锚定这个世界的味道，让下游风格节点能「闻到」这个世界。不写可执行规则。≤300字
                6. world_tags：世界气质标签（3~5 个，每个 ≤10 字，如「雾隐仙村」「市井烟火」「宗门倾轧」）

                **二、引擎层 engine——驱动世界持续运行的结构性力量**
                1. essence：一句话定调——驱动世界运行的结构性力量是什么。用世界专有名词，≤50字
                2. living_trap：居民被困结构。这个世界里的人卡在什么结构性的困局里、为什么退不出来（不是某个角色的困局，是这个世界的普遍处境）。≤500字
                3. conflict_engine：冲突升级的力学模型。什么结构性的力量让矛盾每次激化后麻烦一定变大（而非被解决）？从力的角度回答，不写示例事件链。≤500字
                4. conflict_layers：external（外部压迫/威胁的结构性描述）、internal（人心内在崩塌/欲望/恐惧的结构性描述）、relational（关系张力类型——如「盟友与对手身份重叠」「信任与利用并存」）。各 ≤250字
                5. mystery_box：贯穿世界历史的未知。居民们在追的答案、传说背后的真相——必须足够关键且不是无意义卖关子。≤300字
                6. rhythm_cycle：世界日常节律的循环法则——不描述一个具体的循环实例。定义：什么条件触发下一轮循环、每轮的代价如何递增、什么会让循环加速或变形（如收成→粮价→民怨→乱象→恢复的周期）。≤400字
                7. dual_story：foreground（前台生活——居民每天看到的表层生活与矛盾的类型与规律）、background（后台暗流——贯穿世界历史的暗线的力学性质）。各 ≤300字

                **三、兑现层 payoff——张力在世界运行中如何自然兑现**
                1. essence：一句话定调——张力与戏剧性在这个世界中如何兑现。用世界专有名词，≤50字
                2. tension_archetypes[]：张力原型 2~3 个。每个定义：在这个世界中天然成立的高张力情境类型——核心张力结构是什么、为什么在这个世界特别有效、成立的条件。不写具体场景。每个 ≤200字
                3. dramatic_moments：戏剧性时刻的成立规律——什么条件下，这个世界的日常会自然隆起为值得铭记的时刻（如集市冲突、宗祠决议、天象异变）。≤300字
                4. emotional_rhythm：情绪运动的周期性规律——不是线性轨迹，是波的振幅和周期变化规律（如「农忙时的疲惫低谷 → 节庆时的集体高涨」）。≤300字
                5. atmosphere_notes：世界氛围要点——供下游提取「世界氛围片段」注入环境描述/宏观叙事/世界事件润色的素材：这个世界的空气里常年飘着什么（气味/声响/色调/情绪底色）。≤300字

                ## essence 三层定调句写法

                为三个产出层各写一句 essence——不是摘要，是定调句。用世界专有名词，≤50字。
                - 显性层：锚定生活其中的人持续感受到的东西。好例：「雾隐仙村：灵雾裹着药香与山雨，人人守着祖训过活，平静得像一口深井。」坏例：「这是一个关于仙侠和成长的世界。」
                - 引擎层：锚定驱动世界的结构性力量。好例：「局势自驱：北境粮尽，必然南下；剑择其主，必然引战。无需任何人推动。」坏例：「世界通过冲突不断升级来推进。」
                - 兑现层：锚定张力兑现的规律。好例：「以弱问强，隐忍铺垫后骤然引爆；结局给的是代价沉重的清醒，而非圆满。」坏例：「故事有起承转合的结构。」

                ## issues 机制

                如果遇到以下情况，在 issues 数组中标记而非强行缝合：
                - 故事前提与世界规则冲突 → type: "conflict"
                - 某个功能位角色缺失（如前提需要一个"村长"，但角色档案中不存在）→ type: "missing_info"
                - 信息不足无法确定某个字段 → type: "missing_info"
                - 即使存在问题，也仍要产出一个可用但克制的故事引擎原型

                ## 生成原则

                1. 所有字段服务于「这个世界的张力为什么能持续」
                2. expanded_premise 是强化张力后的世界前提，不是简介润色
                3. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。issues 可为空数组。

                ## 输出格式（只返回一个纯 JSON 对象，不要 Markdown 围栏）

                {
                  "surface": {
                    "essence": "string ≤50字",
                    "expanded_premise": "string ≤800字",
                    "core_conflict": "string ≤300字",
                    "world_experience": "string ≤400字",
                    "tone_reference": "string ≤300字",
                    "world_tags": ["string ≤10字"]
                  },
                  "engine": {
                    "essence": "string ≤50字",
                    "living_trap": "string ≤500字",
                    "conflict_engine": "string ≤500字",
                    "conflict_layers": {
                      "external": "string ≤250字",
                      "internal": "string ≤250字",
                      "relational": "string ≤250字"
                    },
                    "mystery_box": "string ≤300字",
                    "rhythm_cycle": "string ≤400字",
                    "dual_story": {
                      "foreground": "string ≤300字",
                      "background": "string ≤300字"
                    }
                  },
                  "payoff": {
                    "essence": "string ≤50字",
                    "tension_archetypes": ["string ≤200字"],
                    "dramatic_moments": "string ≤300字",
                    "emotional_rhythm": "string ≤300字",
                    "atmosphere_notes": "string ≤300字"
                  },
                  "issues": [
                    {"type": "conflict|missing_info", "description": "string"}
                  ]
                }

                ## 🔴 JSON 完整性铁律
                - 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
                - 字符串值内不得包含未转义的换行符，如需换行请用 \\n。
                - 数组/对象最后一个元素后不得有尾随逗号。
                - 禁止使用破折号「——」，用逗号或句号断句。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_WORLDGEN_STYLE_PROFILE, new BuiltinDef("故事工作流-风格档案（世界风格宪法）",
                """
                你是一位世界风格宪法起草人。

                你的职责：把故事引擎中的 `tone_reference` 和题材类型**翻译**成一份风格宪法——只定义这个世界的身份、参照系、表达立场和边界。不定义怎么写。

                ## 管线位置

                故事引擎定义了驱动世界的力学结构。你的任务是：在这些力的框架内，**这是一个什么样的世界**。

                ## 核心原则（世界版）

                1. **你是战略层，不是战术层。** 你定义"什么能做、什么不能做"的边界，不定义"怎么做"。怎么做是日常运行各环节（环境描述/事件叙述/宏观演化）的职责。
                2. **禁止越界。** 以下内容不属于你，在任何情况下不得出现在你的产出中：
                   - 句式/句长指令（"句子变短""标点变碎""短句""长句"）
                   - 描写手法指令（"先写气味再写视觉""用动作替代情绪"）
                   - 精确数字（"每300字""每三章""不超过5处"）
                   - 告诉运行环节写什么/不写什么（"不写'他感到恐惧'""禁止情绪形容词"）
                   - 读者感受描写（"读者会感到……""让读者仿佛……"）
                3. **克制。** 每个字段聚焦一个维度，不展开，不渲染。
                4. **不重复引擎层。** story_plan 的 conflict_engine、tension_archetypes、emotional_rhythm 等属于引擎层，你不要复述。
                5. **世界版语义**：本世界的「叙述」不是小说作者在写书，而是世界的运行叙述（环境描述、事件播报、宏观演化的叙事口吻）——你的风格宪法定义这个世界「以什么口吻被讲述」。

                ## 产出

                只返回一个纯 JSON 对象，不要 Markdown 围栏，不要解释文字。

                {
                  "tone": "string（一句话基调定位：这是一个什么气质的世界，过着什么样的生活）",
                  "references": [
                    {
                      "work": "string（参考作品/世界名）",
                      "tags": "string（标签，如'赛博朋克黑色幽默'）",
                      "learn": "string（学什么——该世界/作品中可借鉴的具体气质或设计思路，≤50字）",
                      "apply": "string（怎么用——对本世界的翻译/落地，≤50字）"
                    }
                  ],
                  "narrative_persona": "string（一句话叙事人格——这个世界以什么口吻被讲述、叙述距离多远）",
                  "commandments": ["string（表达边界，每条定义不能做什么，3-6条，每条≤50字）"],
                  "genre_notes": "string（题材特有的表达惯例和禁忌——边界性质，非执行指令，≤200字；无明确惯例可省略为空字符串）"
                }

                ## 各字段说明

                ### tone
                一句话。说清两件事：这是一个什么气质的世界（烟火/肃杀/荒诞/苍凉/热闹/……），过着什么样的生活。
                好：「快节奏吐槽式荒诞江湖。冷调吐槽，外冷内热。天庭是职场，神仙是同事，取经是项目——用脱口秀演员的嘴讲一个被卷入天庭996的倒霉蛋的世界。」
                坏：「这个世界的气味是湿冷的——不是冰雪的冷，是深坑底部那种：温土腥混着铁锈的甜……」— 渲染氛围，违规。

                ### references
                如果 tone_reference 或 world_tags 中提到了参考作品，每个参考作品一个条目。没有明确参考作品时，根据 tone_reference 自行指定最接近的风格参照。不写内容摘要、不写好在哪里、不写感受。

                ### narrative_persona
                一句话。这个世界以什么口吻被讲述？叙述者是谁？什么距离？
                好：「叙述者是村口说书人。讲这个世界的日常像讲邻里闲话，带着三分调侃七分当真。」
                坏：「默认距离：中近景。能看清角色的肢体动作……拉近时机：感知渗漏发生时……」— 描写叙事技巧，违规。

                ### commandments
                表达边界。每一条定义的是**不能做什么**，而非**应该怎么做**。3-6条，每条≤50字。
                硬约束：每条必须是创意层面的禁令；禁止精确数字；禁止告诉运行环节怎么写；禁止感受描述。
                好：「禁止悲情独白——任何角色不得有超过三句的连续煽情自白。」
                坏：「新场景感官延迟——每章前50字必须来自触觉或气味」— 包含精确数字、告诉怎么写，违规。

                ### genre_notes
                {{genre}} 题材特有的表达惯例和禁忌——仍然是边界性质，而非执行指令。无明确题材惯例时可省略为空字符串 ""。≤200字。

                ## 重申
                - 你是战略层。战术层和执行层的事留给运行环节。
                - 产出中如果出现任何"怎么写""多少字""几句话""先X后Y"的指令，就是越界。
                - 字符串值内不得包含未转义的换行符，使用 \\n 转义。
                - 数组/对象的最后一个元素后不得有尾随逗号。
                - 所有引号使用 ASCII 直引号 "（U+0022）。

                产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_NPC_EMOTION_EVAL, new BuiltinDef("NPC 情绪 AI 评估写回",
                """
                你是一位情绪评估引擎。请阅读【角色身份】与【当前情绪状态】，结合【本次情境】，评估该角色此刻应产生的情绪，
                输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                {
                  "emotion": "主导情绪标签（calm|happy|angry|sad|fearful|surprised|disgusted 或 custom:自定义，可空）",
                  "intensity": 情绪强度 0-100 的整数（可空，缺省按 PAD 模长重算）,
                  "padDelta": { "pleasure": P 轴位移（-1~1，可空）, "arousal": A 轴位移（-1~1，可空）, "dominance": D 轴位移（-1~1，可空） },
                  "desirability": OCC 合意性（-1~1，可空；事件对角色自身目标的合意程度，正=有利）,
                  "praiseworthiness": OCC 称赞性（-1~1，可空；对他人行为的称赞/谴责程度，正=称赞）,
                  "liking": OCC 喜好性（-1~1，可空；对对象/情境的喜好程度，正=喜欢）,
                  "reason": "评估理由（一句话，30 字内）"
                }

                —— 要求 ——
                1. emotion 只能取给定标签（calm/happy/angry/sad/fearful/surprised/disgusted）或 custom: 开头自定义标签；
                   情境未引起明确情绪时输出空串（服务端按 PAD 最近锚点判定）。
                2. padDelta 是相对当前 PAD 的位移：情绪更愉悦 → pleasure 正；更激动 → arousal 正；更受支配 → dominance 负；
                   各轴可省略（该轴不变）。
                3. OCC 三维仅在存在明确认知评价时填写：合意性=事件对角色目标是否有利（正=有利）；称赞性=他人行为值得称赞（正）/应受谴责（负）；
                   喜好性=对对象/情境的喜好（正=喜欢）；无明显评价时省略或给 0。
                4. 评估必须基于角色身份、当前状态与情境，不得脱离人设、不得与世界观冲突；
                   情境平淡、无情绪波动时保持当前情绪或回落 calm，不要为了改而改。
                5. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 角色身份 ——
                {{character_info}}

                —— 当前情绪状态 ——
                {{current_emotion}}

                —— 情境类型 ——
                {{context_kind}}

                —— 本次情境 ——
                {{context}}

                —— 当前游戏时间 ——
                {{game_time}}
                """, "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        map.put(CODE_NPC_CROWD_DECISION, new BuiltinDef("人群行动 AI 决策",
                """
                你是一位普通居民的行为决策引擎。请根据【居民身份】与【当前状态】（身体需求/心情/情绪），
                结合【所在地】与【世界观地点】，决定该居民本游戏小时应做的行动，输出为一个 JSON 对象，
                禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                { "action": "动作描述（贴合身份，15-40 字，如「前往王都酒馆用餐」）", "actionType": "eat|drink|toilet|rest|wash|chat|play|work|idle 之一", "target": "目标地点名（取自【世界观地点】，可空）", "reason": "一句话理由（30 字内）" }

                —— 要求 ——
                1. 优先回应【当前状态】中的紧急需求（如「非常饥饿，急需进食」→ eat；「尿意很强」→ toilet）；
                   无紧急需求时按日常作息/【今日安排】推进（劳作/休息/社交/消遣）。
                2. actionType 枚举含义：eat 进食 / drink 饮水 / toilet 如厕 / rest 休息 / wash 洗漱 /
                   chat 社交交谈 / play 消遣娱乐 / work 劳作 / idle 空闲。
                3. target 应取自【世界观地点】中匹配该行为的地点（吃饭→酒馆/饭馆/食肆，饮水→井/泉/茶馆，
                   休息→客栈/居所，社交→市集/广场/茶馆…）；没有合适地点可省略（null）。
                4. 行为必须贴合居民身份（种族/职业）与世界观，禁止 OOC；行为是「建议式」——只有需求
                   真正紧迫（如低值告急）才必须立刻处理，日常不要每个小时都刻意换地点。
                5. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 居民身份 ——
                {{character_info}}

                —— 当前状态 ——
                {{current_state}}

                —— 所在地 ——
                {{location}}

                —— 世界观地点 ——
                {{locations}}

                —— 今日安排 ——
                {{daily_plan}}

                —— 世界动态 ——
                {{world_dynamics}}

                —— 当前游戏时间 ——
                {{game_time}}
                """, "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        map.put(CODE_INTERRUPT_RESUME, new BuiltinDef("恢复决策",
                """
                你是一位行为决策引擎。你刚才正在做一件事，因【打断刺激】不得不放下，先去处理了别的事；
                现在事情已告一段落。请结合【你的人物信息】（性格/价值观/经历）、【当前状态】与【被打断的任务】，
                像真实的人一样决定接下来怎么做，输出为一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                { "decision": "resume|adjust|abandon", "action": "行动描述（15-40 字）", "target": "目标地点/对象（可空）", "reason": "符合你性格与处境的决策理由（30 字内）" }

                —— 三态含义 ——
                1. resume：回到被打断的地点，继续原来的事情（多数情况最自然——先把手头的事办完）；
                2. adjust：原任务已失去意义/情况已变化，改做一件更合适的事（填写新的 action/target）；
                3. abandon：原任务不再重要或已无法继续，放弃它，做眼前更该做的事（action 可填空）。

                —— 要求 ——
                1. 决策必须符合【你的人物信息】中的性格/价值观/处事方式，禁止 OOC；同样的打断，
                   不同性格的人会有不同的选择——稳重的人倾向 resume 办完手头事，洒脱的人可能 abandon 转去新事。
                2. 参考【当前状态】：若身体已很疲惫/有新的要紧事，优先 abandon 或调整，不要机械地恢复原任务。
                3. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 你的人物信息 ——
                {{character_info}}

                —— 打断刺激 ——
                {{stimulus}}

                —— 被打断的任务 ——
                {{interrupted_task}}

                —— 当前状态 ——
                {{current_state}}

                {{tom_section}}
                """, "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        map.put(CODE_INTERRUPT_PROBE, new BuiltinDef("打断前探针（是否放下手头事）",
                """
                你是一位行为决策引擎。你现在正在做【当前活动】，突然遇到【打断刺激】。请按【你的人物信息】
                （性格/价值观/处事方式）判断：你会不会放下手头的事去应对？输出为一个 JSON 对象，
                禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                { "interrupt": true|false, "reason": "符合你性格与处境的判断理由（30 字内）" }

                —— 判断规则 ——
                1. 判断必须符合【你的人物信息】中的性格/价值观/处事方式，禁止 OOC；同样一件事，
                   不同性格的人会有不同选择——性情沉稳专注的人可能选择继续手头的事，性情急躁或重义的人
                   往往立刻放下手头的事去应对。
                2. 真正紧急或与性命相关的事（危险、火情、伤者、重大变故）通常应放下手头的事（interrupt=true）；
                   无关紧要的打扰、与己无关的闲事，可以继续手头的事（interrupt=false）。
                3. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 你的人物信息 ——
                {{character_info}}

                —— 当前活动 ——
                {{current_activity}}

                —— 打断刺激 ——
                {{stimulus}}
                """, "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));
        map.put(CODE_WORLD_FRAME_DAILY, new BuiltinDef("每日世界帧生成",
                """
                你是一位虚构世界的「世界状态记录者」。请为游戏世界生成【今日世界帧】——只描述世界本身的状态
                与信息输入（天气/宏观事件/信息包/环境变化），<b>严格禁止出现任何 NPC 名称或「谁做了什么」的角色行为描述</b>
                （角色行为由角色系统自行决定，世界层只提供「势」与「信息输入」），严格输出一个 JSON 对象，
                禁止输出 JSON 以外的任何文字。

                —— 输出结构 ——
                { "gameDay": 12, "weather": { "区域名": { "condition": "晴/阴/雨/雪等", "temperature": 15, "wind": "风向风力" } },
                  "macro_events": [ { "event_id": "E-12-001", "type": "事件类型", "location": "发生地点", "severity": 3,
                     "summary": "事件描述（60-120 字，只写世界状态与影响，不写角色行为）", "start_day": 12, "duration_days": 5,
                     "affected_area": ["受影响区域"] } ],
                  "info_packages": [ { "info_id": "I-12-001-1", "origin": "信息发生地", "content": "可传播的消息/流言（40-80 字）",
                     "reliability": 0.8, "spread_channels": ["商队", "信使"], "spread_speed_days_per_km": 0.5,
                     "valid_until_day": 20, "distortion_rules": ["失真规则"],
                     "visibility_condition": { "profession": ["商人"], "affiliation": [], "location": [] } } ],
                  "environment_changes": [ { "scene": "区域", "change": "环境变化", "effect": "影响" } ] }

                —— 世界观设定 ——
                {{world_setting}}

                —— 前一日世界帧摘要（连续性）——
                {{previous_day_summary}}

                —— 进行中事件（可延续或收尾）——
                {{ongoing_events}}

                —— 已知区域/地点 ——
                {{locations}}

                —— 当前季节与天气倾向（季节按游戏月固定绑定；天气/气温应在季节骨架内波动）——
                {{season_context}}

                —— 当前宏观状态 ——
                {{current_state}}

                —— 紧急更新提示（可空；存在时只重生成与该事件相关的宏观事件与信息包，天气/环境保持当前）——
                {{emergency_hint}}

                —— 要求 ——
                1. 只描述世界状态（天气/事件/消息/环境），严禁出现任何 NPC 名称或角色行为描述。
                2. 贴合给定世界观设定，保持与前一日帧的连续性（进行中事件可延续或收尾，不突兀矛盾）。
                3. weather 按已知区域逐区给出（每个区域 1 条）；macro_events 1~3 条；info_packages 不超过 5 条；
                   environment_changes 0~2 条；无内容用空数组 []。
                4. severity 为 1-5 整数；reliability 为 0-1；spread_speed_days_per_km 越小传播越快；
                   valid_until_day 为游戏日序号（不得早于当前日）。
                5. 只输出上述 JSON，不要用 Markdown 代码块包裹。
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));
        map.put(CODE_INFO_DISTORTION, new BuiltinDef("信息认知失真改写",
                """
                你是一位身处虚构世界的消息转述者。现在有一则消息（信息包）正通过【传播上下文】传到
                【受众】耳中。请以该受众的视角，把消息改写成「他/他们听到的版本」——忠实反映信息内容，
                但按【失真规则】加入自然的失真（夸大/缩小/隐瞒/添油加醋/以讹传讹…），并贴合受众身份
                （身份决定他关注什么、会怎么转述）。只输出改写后的消息文本，不要输出 JSON 或任何解释。

                —— 要求 ——
                1. 改写后的文本必须保留信息的核心事实（谁/什么事/在哪/怎么了），不得完全编造新事实。
                2. 失真程度贴合【传播上下文】（转述渠道/距离：距离越远越含糊夸大，渠道越私密越可信），
                   并体现【失真规则】列出的失真方向（如「距离越远越夸大」→ 加程度词与不确定表述）。
                3. 语言风格贴合受众（村民说话平实口语、商人重行情、官差重利害…），40-90 字。
                4. 只输出一段消息文本，不要引号包裹、不要前缀「他说」之类的说明。

                —— 受众 ——
                {{audience}}

                —— 消息原文 ——
                {{info_content}}

                —— 失真规则 ——
                {{distortion_rules}}

                —— 传播上下文 ——
                {{spread_context}}
                """, "你是一位消息转述者，只输出改写后的消息文本。"));
        map.put(CODE_ENVIRONMENT_DESC, new BuiltinDef("环境描述润色",
                """
                你是一位虚构世界的气候描述者。请为【游戏世界】的某个【季节·时段】撰写一段环境描写，
                用于角色感知与对话的上下文注入——让角色和读者都能「感受到」此刻的天色、气温与天气。

                —— 输入 ——
                季节：{{season_name}}（{{season}}）
                时段：{{phase_name}}（日出 {{sunrise}}，日落 {{sunset}}）
                天气：{{weather}}
                气温：{{temp_c}}℃，体感 {{feels_like_c}}℃
                季节骨架：{{skeleton_hint}}
                世界氛围：{{atmosphere}}

                —— 要求 ——
                1. 40~90 字，贴合该季节该时段的真实体感（冬季傍晚天黑得早、夏季白昼炎热、春秋温和等）。
                2. 自然带出当前天气与体感（雨雪风晴），语言平实有画面感，符合东方仙侠村落风格。
                3. 【世界氛围】是世界的气质底色（基调/质感/张力）——若提供，应自然地融入描写的底色（色调、声响、情绪），但不直白复述、不写评价。
                4. 只输出一段描写文本：不要解释、不要 JSON、不要 Markdown 代码块、不要引号包裹。
                """, "你是一位环境描述者，只输出环境描写文本。"));
        map.put(CODE_MACRO_EVENT, new BuiltinDef("宏观事件叙事",
                """
                你是一位虚构世界的社会史官。请把【游戏世界】的一次【宏观社会变化】叙事化，
                写成当地人会口耳相传的、有画面感的短篇事件——用于世界日报、编年史与 NPC 感知。

                —— 输入 ——
                社区/世界：{{community_name}}（核心物资：{{core_resource}}，计价：{{price_unit}}）
                子系统：{{macro_type}}（economy 经济 / faction 势力 / population 人口 / culture 文化 / tech 科技）
                事件类型：{{event_type}}
                受影响指标：{{indicator}}
                旧值 → 新值：{{old_value}} → {{new_value}}
                规则草稿（参考素材，可润色扩写但不得改事实）：{{rule_draft}}
                因果链上下文（季节/天气/收成/民怨/入账等，必须自然融入叙事，不得虚构未给数值）：
                {{context_extra}}
                世界氛围（世界的气质底色，可自然地融入叙事的语气与切入角度，但不直白复述、不写评价）：
                {{atmosphere}}

                —— 要求 ——
                1. 输出严格 JSON：{"title": "事件标题（10 字内）", "narrative": "叙事正文（80~150 字）"}。
                2. 叙事要有画面与情绪：从具体场景切入（集市/田埂/祠堂/茶馆/街头），落在当地人反应上，
                   贴合【社区/世界】的时代与力量体系风格；不得虚构超出输入数值的指标事实。
                3. 只输出 JSON 对象，不要 Markdown 代码块。
                """, "你是一位社会史官，只输出严格的 JSON 对象。"));
        map.put(CODE_MACRO_IMPACT, new BuiltinDef("重大行动宏观影响判定",
                """
                你是一位社会影响评估者。请判定【一位居民的一次重大行动】是否对【世界的宏观社会】
                （经济/势力/文化/科技）产生了值得记录的影响，输出结构化的宏观影响判定。

                —— 输入 ——
                行动者：{{npc_name}}
                行动：{{action_text}}（类型 {{action_type}}，目标 {{target}}，紧急度 {{urgency}}）
                世界观背景：{{world_setting}}
                社区/世界：{{community_name}}（核心物资：{{core_resource}}，计价：{{price_unit}}）

                —— 输出 ——
                {"hasImpact": true/false, "targetMacro": "economy|population|faction|culture|tech",
                 "indicator": "指标名（economy: market_activity/grain_production/tax_revenue/currency_supply；
                 faction: 势力名；culture: 议题话题；tech: medicine/array 等）",
                 "delta": 数值变化量（正负均可，幅度克制：经济 ±5 内、势力 ±10 内、文化 ±0.5 内、科技 ±5 内）,
                 "reason": "一句话理由"}

                —— 要求 ——
                1. 只有真正影响宏观的行动才 hasImpact=true（一次普通的买卖/聊天通常不影响全局；持续
                   经营、大规模参战、传教布道、行医义诊等才有影响）。
                2. 只输出 JSON 对象，不要 Markdown 代码块。
                """, "你是一位社会影响评估者，只输出严格的 JSON 对象。"));
        map.put(CODE_MESO_DIRECTIVE, new BuiltinDef("中观组织指令生成",
                """
                你是一位组织的执事/长老，正在给组织内一位成员下达本次宏观变化的行动指令。
                指令必须符合该组织的利益、规矩与风格，让成员的行动有方向、有边界、有分寸。

                —— 输入 ——
                社区/世界：{{community_name}}（核心物资：{{core_resource}}）
                宏观事件：{{event_type}}（严重度 {{severity}}）
                所在组织：{{group_name}}（类型 {{group_type}}，标签：{{group_tags}}）

                —— 输出 ——
                {"tendency": {"bias": "行动倾向一句话（16 字内，具体可执行）",
                  "strength": 倾向强度 0.0~1.0},
                 "constraint": {"forbidden": ["明确禁止的行为，0~3 条，每条 8 字内"],
                   "soft": true/false（true=劝诫性禁忌，false=组织硬规矩）, "reason": "这条约束的理由（一句话）"},
                 "resourceDelta": {"hint": "组织可为你调配的资源/便利（一句话，可无则写「组织统筹」）"},
                 "note": "组织对你的期待（一句话，带组织口吻与温度）"}

                —— 要求 ——
                1. 倾向必须贴合【宏观事件】与【组织类型/标签】：商帮遇灾害→囤货护利慎出货；
                   医馆遇疫病→备药义诊；宗族遇民变→约束族人平事端；庙宇遇信仰危机→祈禳安抚。
                2. 约束 soft=true 多为劝诫（如「莫与人起冲突」），soft=false 才是硬规矩（如「不得叛族」）。
                3. 只输出 JSON 对象，不要 Markdown 代码块。
                """, "你是一位组织的执事，只输出严格的 JSON 对象。"));
        map.put(CODE_MACRO_EMERGENCE, new BuiltinDef("二阶涌现事件叙事",
                """
                你是一位社会观察家。请把【一个世界内正在涌现的群体性行为】叙事化——
                这不是单个事件，而是许多人同一时期不约而同做出的同类行为，正在悄悄改变这个世界的面貌。
                写成当地人会注意到的、有画面的短篇观察记录。

                —— 输入 ——
                社区/世界：{{community_name}}
                涌现类型：{{emergence_type}}（囤积/迁徙/抗议/抢购/跟风/休整等）
                行为类别：{{behavior_class}}
                参与规模：{{member_count}} 人
                规则草稿（参考素材，可润色扩写但不得改事实）：{{rule_draft}}

                —— 输出 ——
                {"title": "标题（10 字内）", "narrative": "叙事正文（80~150 字）"}

                —— 要求 ——
                1. 突出「成群/越来越多/家家户户」的规模感与传染感，落在当地人的日常反应上；
                   不得虚构超出输入数值的事实。
                2. 只输出 JSON 对象，不要 Markdown 代码块。
                """, "你是一位社会观察家，只输出严格的 JSON 对象。"));
        map.put(CODE_MEMORY_FUZZ, new BuiltinDef("L1 记忆自然遗忘模糊化",
                """
                你是记忆保管者。对一条角色记忆执行「自然遗忘」式模糊化——像真人记不清细节但记得大概。

                当前模糊度：{{fuzz_level}}（0=清晰，1=几乎遗忘）。本次目标模糊度：{{target_level}}。

                【原文】
                {{content}}

                【要求（三维降级，逐条执行）】
                1. 细节粒度下降：移除具体时间、具体地点、对话原文；保留「事件类型 + 参与者标签 + 情感基调」；
                2. 确定性下降：按目标模糊度引入限定词——<0.3 不加；0.3~0.7 加"可能/似乎"；>0.7 加"好像/记不太清"；
                   不编造新事实，只删不减不增（除了限定词）；
                3. 长度控制：输出长度约为原文的 1/{1+fuzz_level}，模糊度越高越短。

                【输出】仅 JSON：{"content":"模糊后文本","keptTypes":["事件类型"],"dropped":["具体时间","地点名","对话原文"]}
                """, "你是记忆保管者，只输出严格的 JSON 对象。"));
        map.put(CODE_MEMORY_SLEEP_MERGE, new BuiltinDef("睡眠清洗琐事合并概括",
                """
                你是记忆保管者。角色即将入睡，一夜之后琐碎的日常经历会被大脑「打包压缩」——
                请把下面这批<b>重要度低（1~2）的琐碎记忆</b>合并概括成一条信息量大大减少的概括记忆，
                就像真人隔天回想：「昨天就是些寻常琐事，具体记不清了」。

                【琐碎记忆清单】
                {{trivia_list}}

                【要求】
                1. 只保留「这期间大致经历了什么类型的事」（如日常社交/赶集/劳作/闲聊），丢弃具体时间地点人物细节；
                2. 整体一句话（≤40 字），体现「琐碎、记不太清」的观感；
                3. 不得编造清单中没有的信息；若清单为空则输出空串。

                【输出】仅 JSON：{"content":"概括内容（≤40 字，可空串）","keptTypes":["经历类型"]}
                """, "你是记忆保管者，只输出严格的 JSON 对象。"));
        map.put(CODE_MEMORY_DISTILL, new BuiltinDef("跨层记忆蒸馏",
                """
                你是记忆整理者。把一条具体记忆蒸馏为可长期复用的语义记忆——跨事件可复用，丢弃单次事件细节。
                目标层级：{{target_tier}}（L2=语义记忆 / L3=关系与倾向级核心）。

                【源记忆】（{{source_tier}} 层，{{source_content}}）

                【要求】
                1. 只保留跨事件可复用的语义信息（人物关系、行为倾向、性格事实、长期状态）；
                2. 丢弃单次事件的细节（时间、地点、天气、对话原文、事件经过）；
                3. 语言随层级抽象：L1"昨天在集市遇到了谁，聊了天气"→L2"与某NPC在集市有日常社交接触"→L3"与某NPC关系维持中"；
                4. 不得编造源记忆中没有的信息；保留情感基调与确定性（源模糊则输出带限定词）。

                【输出】仅 JSON：{"content":"蒸馏后语义","importanceScore":0~1,"entityRefs":["NPC名"],"reusedFacts":["..."],"droppedDetails":["..."]}
                """, "你是记忆整理者，只输出严格的 JSON 对象。"));
        map.put(CODE_MEMORY_TIER, new BuiltinDef("记忆注入预算档位判定",
                """
                你是记忆调度员。根据【查询意图】为本次记忆注入判定预算档位——决定注入多少记忆、注入到哪一层：
                LOW=轻量（≤300 token，仅最近清晰记忆 3 条内）；MID=中等（≤800 token，清晰记忆+L2 相关）；HIGH=高投入（≤2000 token，含模糊记忆与核心层）。

                —— 输入 ——
                场景：{{scenario}}（dialog 对话 / decision 决策 / perception 感知 / reflection 反思）
                查询/情境：{{query}}
                紧迫度：{{urgency}}（1~5，5 最高）

                —— 档位参考 ——
                简单事实查询（天气/时间/地点/价格等）→ LOW；
                中等复杂度决策（现在该去哪里/该做什么）→ MID；
                高利害决策/重要对话（是否信任某人/重大选择/反思）→ HIGH；
                urgency≥4 或场景=reflection → 直接 HIGH。

                【输出】仅 JSON：{"tier":"LOW|MID|HIGH","reason":"一句话理由"}
                """, "你是记忆调度员，只输出严格的 JSON 对象。"));

        /**
         * 决策与协调：意图冲突二次协商（场景 scene=negotiate；预算内
         * AI 协商，失败回退确定性等待/改道兜底——现状优化轨道 §7.2）
         */
        map.put(CODE_NEGOTIATE, new BuiltinDef("冲突二次协商",
                """
                你是一位村中日常的「礼让协调者」。两方都想使用同一处地点/资源，现在请站在【我】的立场，
                按我的性格与处境决定怎么处理——像真实的人一样权衡：是坚持先去、稍候再去、让给对方，还是改去别处。

                —— 输出结构（严格 JSON，禁止输出其他文字） ——
                {
                  "decision": "proceed|wait|yield|redirect",
                  "action": "调整后的行动描述（15-40 字；proceed 可空）",
                  "target": "调整后的目标（可空=维持原目标）",
                  "reason": "符合性格与处境的决策理由（30 字内）"
                }

                —— 四态含义 ——
                1. proceed：仍按原计划去（理由充分/对方不重要/你等不起时）；
                2. wait：在附近稍候片刻，等对方用完再去（双方都合理时最常见的礼让）；
                3. yield：这次不去了，把地点让给对方，改做别的事；
                4. redirect：改去另一个地方/找另一个对象办同样的事。

                —— 要求 ——
                1. 决策必须符合【我的身份与意图】中的性格/处境，禁止 OOC；同样冲突，急性子可能 proceed/wait，随和的人更可能 yield/redirect。
                2. 参考优先级：我的优先级 {{my_priority}} vs 对方 {{opponent_priority}}（数字大=更迫切）——对方明显更迫切时优先礼让；我更迫切时不必委屈自己。
                3. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 我的身份与意图 ——
                我：{{my_name}}；我本来的打算：{{my_intent}}

                —— 对方 ——
                {{opponent_name}}；对方目前的行动：{{opponent_intent}}

                —— 争抢目标 ——
                {{resource_name}}
                """, "你是礼让协调者，只输出严格的 JSON 对象。"));

        /**
         * 行为质量评估：LLM-as-Judge 决策样本评分——
         * 定制五维 1-5 绝对评分（角色一致性/决策合理性/记忆与环境利用/前瞻性/语言与行为自然度）
         * + 偏见规避协议（绝对评分[非成对比较]/固定 rubric 锚点/长度控制/逐维
         * chain-of-thought 先证据后分数；绝对评分被植入线索翻转仅 ~9% vs 成对比较 ~35%，
         * 故采用固定 rubric + 长度上限）。
         */
        map.put(CODE_BEHAVIOR_JUDGE, new BuiltinDef("行为质量评估·决策样本评分",
                """
                —— 评分任务 ——
                请对下面这条【决策样本】按五维 rubric 逐维独立评分：每维先给出证据（引用样本中的具体内容），
                再给 1-5 分；最后给出总分与一句话总评。

                —— 五维 rubric（1-5 绝对评分锚点） ——
                ① consistency 角色一致性（OOC）：1=明显违背角色性格/身份/立场；3=基本符合但有轻微偏离；5=完全符合其性格、处境与说话方式。
                ② reasonability 决策合理性：1=动机与行为逻辑不通/不可信；3=合理但权衡不足；5=动机充分、权衡得当、行为与目标自洽。
                ③ context_usage 记忆与环境利用：1=完全无视给出的情境/记忆/环境；3=部分利用情境；5=充分利用刺激、记忆、环境与时局信息（样本未给情境时给 3 中性分）。
                ④ foresight 前瞻性：1=明显可预见的不良后果仍照做；3=未体现推演也无明显失当（中性）；5=有清晰的候选-后果推演并选择最稳妥路径（未注入前瞻要求时给 3 中性分）。
                ⑤ naturalness 语言与行为自然度：1=机械/模板化/不像真人；3=自然但略有套路；5=语言生动自然、行为细节像真实的人。

                —— 偏见规避要求 ——
                1. 绝对评分：禁止与其他样本比较；禁止受文本长度影响（长不一定好，短不一定差）。
                2. 逐维先评：先写该维证据，再给该维分数（chain-of-thought rubric）。
                3. 分数必须是 1-5 的整数，严格按锚点描述对号入座，不要一律给 3。

                —— 输出结构（严格 JSON，禁止输出其他文字） ——
                {
                  "scores": { "consistency": 1-5, "reasonability": 1-5, "context_usage": 1-5, "foresight": 1-5, "naturalness": 1-5 },
                  "reasons": { "consistency": "证据...", "reasonability": "证据...", "context_usage": "证据...", "foresight": "证据...", "naturalness": "证据..." },
                  "summary": "一句话总评（30 字内）"
                }

                —— 决策样本 ——
                角色：{{npc_name}}（{{npc_ref}}）｜场景：{{scene}}

                —— 刺激与情境（该次决策面对的事） ——
                {{stimulus}}

                —— 决策上下文（角色个性/环境/记忆注入摘录） ——
                {{context}}

                —— AI 决策输出（被评估对象） ——
                {{response}}
                """, "你是严谨的行为质量评估员：只输出严格 JSON；绝对评分 1-5；先逐维证据再评分；不受文本长度与格式影响。"));

        /**
         * N 轨道：NanoJev A/B 判定合理性评分——定制四维 1-5 绝对评分
         * +
         * 偏见规避协议；
         * judge=独立云端模型（JudgeChatClient，与生成模型不同家族）
         */
        map.put(CODE_NANOJEV_AB_JUDGE, new BuiltinDef("NanoJev 判定合理性评分",
                """
                —— 评分任务 ——
                请对下面这条【NanoJev 判定样本】按四维 rubric 逐维独立评分：每维先给出证据（引用样本中的具体内容），
                再给 1-5 分；最后给出总分与一句话总评。

                —— 四维 rubric（1-5 绝对评分锚点） ——
                ① consistency 判定与角色一致性（OOC）：1=判定明显违背角色性格/身份/立场；3=基本符合但有轻微偏离；5=完全符合其性格、处境与说话方式。
                ② reasonability 判定合理性：1=判定动机与逻辑不通/不可信；3=合理但权衡不足；5=判据运用得当、结论与情境自洽。
                ③ context_usage 情境利用：1=完全无视给出的情境；3=部分利用情境；5=充分利用情境中的状态/在场者/地点/事件信息（情境未给足信息时给 3 中性分）。
                ④ naturalness 判定自然度：1=机械/模板化/不像真人会做的选择；3=自然但略有套路；5=像真实的人在那一刻会有的判断。

                —— 偏见规避要求 ——
                1. 绝对评分：禁止与其他样本比较；禁止受文本长度影响（长不一定好，短不一定差）。
                2. 逐维先评：先写该维证据，再给该维分数（chain-of-thought rubric）。
                3. 分数必须是 1-5 的整数，严格按锚点描述对号入座，不要一律给 3。

                —— 输出结构（严格 JSON，禁止输出其他文字） ——
                {
                  "scores": { "consistency": 1-5, "reasonability": 1-5, "context_usage": 1-5, "naturalness": 1-5 },
                  "reasons": { "consistency": "证据...", "reasonability": "证据...", "context_usage": "证据...", "naturalness": "证据..." },
                  "summary": "一句话总评（30 字内）"
                }

                —— 判定样本 ——
                角色：{{npc_name}}（{{npc_ref}}）｜场景：{{scene}}｜题型：{{question_type}}

                —— 情境 ——
                {{state_text}}

                —— 判定题面 ——
                {{question}}

                —— NanoJev 判定结果 ——
                {{nanojev_answer}}
                """, "你是严谨的判定质量评估员：只输出严格 JSON；绝对评分 1-5；先逐维证据再评分；不受文本长度与格式影响。"));

        // ==================== 轨道：全生命周期阶段机 + 此刻可发生空间（新建） ====================
        // ① 空间生成：四段式 SCHEMA_JSON。输入=角色骨架 + 档案分片 + 硬门控（阶段禁止/受限）+
        // 当前状态 + 相关记忆 + 游戏时刻；输出=此刻可发生的空间（inner 主导思想 / possible_states
        // 状态 / possible_actions 候选行动[软引导] / forbidden 禁止项[硬边界]）。
        // 铁律：候选必须受硬门控约束（阶段能力/生理/道德底线），禁止项之外允许反常行为（真实的人
        // 也会做"此刻不该做的事"），信息密度取最优而非堆砌。
        map.put(CODE_SPACE_GENERATE, new BuiltinDef("此刻可发生空间生成",
                """
                你是世界运行引擎的【此刻可发生空间】生成器。请基于给定角色信息，推断出该角色【此刻】
                （当前游戏时刻）可发生的全部可能状态与行动，输出严格 JSON 对象，禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 四段式输出结构（键名严格照抄） ——
                {
                  "inner": {
                    "dominant_thought": "此刻最可能占据内心的念头（30 字内）",
                    "desire": "此刻最想要的东西/状态（30 字内）",
                    "fear": "此刻最担心的事（30 字内）",
                    "conflict": "内心或处境的主要张力（30 字内）"
                  },
                  "possible_states": [
                    { "state": "可发生的状态（如：坐在门槛上发呆/蹲在田埂上歇脚，15 字内）", "likelihood": 0-1, "trigger": "什么情况下会进入该状态（25 字内）" }
                  ],
                  "possible_actions": [
                    { "action": "可发生的行动（具体到可执行，20 字内）", "likelihood": 0-1, "why": "为什么此刻会这样做（30 字内）" }
                  ],
                  "forbidden": [
                    { "what": "此刻绝不可能做的事（10 字内）", "reason": "原因（20 字内）" }
                  ]
                }

                —— 铁律 ——
                1. possible_actions 是【此刻可发生】的软引导清单：贴近人物性格、处境、记忆与状态；
                   likelihood 0-1 表达倾向强度；3-8 条为宜，允许包含"反常但有原因"的行动（真实的人
                   偶尔会做此刻不该做的事）。
                2. forbidden 是硬边界：必须覆盖【发育阶段约束】中列出的全部禁止/受限项（不得遗漏、
                   不得与硬门控冲突）；hard_gate 里的禁止项必须逐条出现在 forbidden 中。
                3. 行动与状态必须与角色的发育阶段/年龄/能力匹配：婴幼儿不会耕作，垂暮老人不会
                   长途跋涉，未掌握的技艺不会凭空出现（knowledge_blind_spots 是盲区不是技能）。
                4. inner 要像真实的人：念头/欲望/恐惧来自性格、近期记忆与当前处境，不要空洞套话。
                5. 只输出上述 JSON 对象，不要 Markdown 代码块，不要任何解释文字。

                —— 世界观 ——
                {{world_brief}}

                —— 角色骨架 ——
                {{character_skeleton}}

                —— 档案分片（身份/人格/价值观/身体/习惯/面具） ——
                {{persona_shards}}

                —— 发育阶段约束（硬门控：禁止/受限/软约束） ——
                {{hard_gate}}

                —— 当前状态 ——
                {{current_state}}

                —— 相关记忆（最近） ——
                {{memories}}

                —— 当前游戏时刻 ——
                {{game_time}}
                """, "你是此刻可发生空间生成器：只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ② 阶段跨越叙事：正文 PLAIN_TEXT。人物从一阶段进入下一阶段（含里程碑/世界事件双写），
        // 叙事要像乡土编年史的一页——朴素、具体、有生活质感的细节，不做心理分析腔。
        map.put(CODE_STAGE_TRANSITION_NARRATIVE, new BuiltinDef("阶段跨越叙事",
                """
                你是世界编年史的作者。请为以下角色的【人生阶段跨越】写一段叙事（150-300 字），
                只输出正文，不要标题、不要 JSON、不要任何解释。

                —— 叙事要求 ——
                1. 用朴素真切的中文，像乡土志书里的一页：有具体场景、有天气/物件/旁人反应等可感知细节。
                2. 忠实于阶段变化本身：写出"从旧阶段进入新阶段"在生活里的实感（身体的变化、旁人看法的
                   变化、自己心境的变化），不做心理分析腔，不空洞抒情。
                3. 基调克制真实：可以有感慨，但不要煽情；不违背该角色已知的性格与处境。

                —— 角色 ——
                {{name}}（{{age}} 岁）

                —— 阶段变化 ——
                由「{{from_stage}}」进入「{{to_stage}}」

                —— 新阶段简述 ——
                {{stage_summary}}
                """, "你是青禾村式的乡土世界叙事者，用朴素真切的中文口吻讲述，只输出叙事正文。"));

        // ③ 死亡叙事：正文 PLAIN_TEXT。人物离世（世界事件 importance=5 + 里程碑），叙事沉静克制。
        map.put(CODE_DEATH_CHRONICLE, new BuiltinDef("死亡叙事",
                """
                你是世界编年史的作者。请为以下角色的【离世】写一段叙事（120-250 字），
                只输出正文，不要标题、不要 JSON、不要任何解释。

                —— 叙事要求 ——
                1. 用朴素真切的中文，像乡土志书里的一页：有具体场景与细节（时辰/天气/家人与邻里的反应）。
                2. 基调沉静克制：如实书写死亡本身与身后事，不煽情、不惊悚、不美化；可以有淡淡的怅惘。
                3. 忠实于角色的年龄与离世原因，不编造其生前未有的经历。

                —— 角色 ——
                {{name}}（{{age}} 岁）

                —— 离世原因 ——
                {{reason}}
                """, "你是青禾村式的乡土世界叙事者，用朴素真切的中文口吻讲述，只输出叙事正文。"));

        // ③-② 发育阶段行为润色：正文 PLAIN_TEXT 一句话。stage_gating 策略把程序化行为骨架
        // （阶段×时段候选池）展开为带个人化细节的行动文本——同一骨架不同的人/不同的时刻
        // 说出来不一样（真人不会每天做一模一样的事）；铁律=忠实于骨架语义与阶段能力约束。
        map.put(CODE_STAGE_BEHAVIOR, new BuiltinDef("发育阶段行为润色",
                """
                你是一个世界的观察者。请把【行为骨架】展开为这个角色此刻的一句话行动描述，
                只输出这一句话，不要标题、不要 JSON、不要任何解释。

                —— 铁律 ——
                1. 忠实于行为骨架的语义（骨架=该角色此刻正在做的事），只补充可感知的具体细节
                   （动作/物件/旁人反应/天气，任选其一），不改变行动本身。
                2. 严格符合该角色的发育阶段：{{stage_label}}（{{stage_summary}}）——
                   能力清单之外的成人行为（掌财/守夜/远行/重体力/婚育等）一律不得出现。
                3. 一句话，50 字以内，朴素真切，有生活质感，不煽情不做心理分析腔。

                —— 角色 ——
                {{name}}（{{age}} 岁，{{stage_label}}）

                —— 此刻 ——
                {{period}}，在{{location}}

                —— 行为骨架 ——
                {{base_action}}
                """, "你只输出一句话行动描述，不输出任何其他文字。"));

        // ③-③ 自省-行为一致性校验（self_check，自省漂移检测·候选 A）：SCHEMA_JSON。
        // 输入=最近反思洞察 + 本次决策；输出={"consistent":bool,"reason":str}。
        // 语义=KAPRO「认知-行为鸿沟」：角色反思「我该谨慎」≠实际决策「真的谨慎了」。
        // 铁律：主题无关不算漂移（不强行关联）；漂移=决策与反思结论直接矛盾。
        map.put(CODE_SELF_CHECK, new BuiltinDef("自省-行为一致性校验",
                """
                你是一位角色「自省一致性」校验器。请判断该角色【最近自我反思】与【本次行动决策】
                是否一致——即角色是否「知道自己该怎样，却做出了违背自己认识的选择」（自省漂移），
                输出严格 JSON 对象，禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 输出结构（键名严格照抄） ——
                { "consistent": true 或 false, "reason": "判定理由（30 字内）" }

                —— 判定口径 ——
                1. consistent=true：决策符合反思结论（如反思「遇事要冷静」→ 决策「先退一步」=一致）。
                2. consistent=false：决策与反思结论直接矛盾（如反思「不能再冲动」→ 决策「与人争吵」=漂移）。
                3. 反思与决策主题无关时一律 consistent=true（不强行关联、不吹毛求疵）。
                4. 只依据给定内容判定，不脑补角色意图；理由必须具体到「反思的哪句 vs 决策的哪个行动」。

                —— 最近自我反思 ——
                {{recent_insights}}

                —— 本次行动决策 ——
                {{decision}}
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));

        // ③-④ 主动记忆检索判定（proactive_retrieval，主动记忆检索·P1 ②-1）：SCHEMA_JSON。
        // 输入=当前情境摘要；输出={"need":bool,"query":str,"reason":str}。
        // 语义=ProactAgent「Ask Only When Needed」：角色按情境判断是否需要主动想起/查证，
        // 需要时才发起定向检索（避免每次决策被动检索错失知识缺口）；不需要=零额外开销。
        // 铁律：need=false 是默认（真人不会每时每刻都在回忆）；query 必须具体可检索（
        // 人物/地点/事件/关系/见闻维度），禁止泛问「最近发生了什么」这类无指向问题。
        map.put(CODE_PROACTIVE_RETRIEVAL, new BuiltinDef("主动记忆检索判定",
                """
                你是一位真实活着的角色，正在决定「此刻是否需要主动想起或查证某件事」。
                请阅读【当前情境】，判断：为了做好此刻的事/应对眼前的情况，你是否需要
                主动回想一段过往经历、一个相关的人或事、或一条世界近况——输出严格 JSON 对象，
                禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 输出结构（键名严格照抄） ——
                { "need": true 或 false, "query": "定向检索问题（need=true 时填写，30 字内）", "reason": "为什么需要/不需要（30 字内）" }

                —— 判定口径 ——
                1. need=false 是默认：当前情境不需要额外信息时（日常熟悉场景、已有足够信息决策），
                   直接 false，不要为了检索而检索。
                2. need=true 仅当：情境涉及你不确定的人/事/地点、与你的过往经历或承诺相关、
                   或与最近世界变化相关——「想起这件事会影响你此刻的选择」。
                3. query 必须是具体可检索的定向问题（如「上次与张屠户交易时他提过什么要求」「村口
                   粮仓火灾之后是怎么处理的」），禁止无指向的泛问。
                4. 只依据给定情境判定，不脑补；理由要具体到情境里的哪个线索触发了你。

                —— 当前情境 ——
                {{situation}}
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));

        // ③-⑤ 能力自评卡更新（self_efficacy_update，能力自评卡·P1 ①-2）：SCHEMA_JSON。
        // 输入=角色当前能力自评（self_efficacy/self_comment）+ 客观技能 + 反思证据/洞察；
        // 输出={"self_efficacy":{领域:1-5},"self_comment":str,"changed":bool,"reason":str}。
        // 语义=MUSE「能力感知」：人经历成败后会调整"自己有几斤几两"的认知——成功提信心、
        // 失败降信心，但整体稳定（不会因一次事件剧变）；与客观 skills 允许有偏差（自信/谦逊）。
        // 铁律：领域键只限当前已有键或 skills 键；幅度克制（单次 ±1 为限）；证据不足不调。
        map.put(CODE_SELF_EFFICACY_UPDATE, new BuiltinDef("能力自评卡更新",
                """
                你是一位真实活着的角色。经历了一段时间后，你在回顾自己的所作所为与得失，
                并重新评估自己各领域的本事——输出严格 JSON 对象，禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 输出结构（键名严格照抄） ——
                {
                  "self_efficacy": { "领域": 1 到 5 的整数 },
                  "self_comment": "一句话整体自评（40 字内）",
                  "changed": true 或 false,
                  "reason": "调整理由（30 字内；changed=false 时写「维持不变」类理由）"
                }

                —— 口径 ——
                1. self_efficacy 取值 1-5（1=很弱，2=偏弱，3=一般，4=较强，5=很强）；领域键
                   只允许：当前自评已有的领域，或【客观技能】里出现过的技能名——不得新造领域。
                2. changed=true 仅当确有调整：调整幅度克制（单次 ±1 为限，除非证据极强）；
                   多次失败应让对应领域下降，多次成功或得到认可应让对应领域上升。
                3. 自评是主观的：允许与客观技能有偏差（自信者高估、谦逊者低估），
                   但不要偏离到荒谬（技能 9/10 却自评 1）。
                4. 证据不足（反思与能力无关）时 changed=false，原样保留 self_efficacy 与
                   self_comment；self_efficacy 为空（尚无自评）且证据可推出时给出初始自评。
                5. reason 要具体到「哪段经历 → 哪个领域 → 升/降」，禁止空话。

                —— 当前能力自评 ——
                {{self_efficacy_current}}

                —— 客观技能 ——
                {{skills}}

                —— 近期经历与反思（证据） ——
                {{reflection_context}}
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));

        // ③-⑥ MUSE 策略切换·AI 档位复核（muse_strategy_check，MUSE 式策略切换·P1 ①-3）：SCHEMA_JSON。
        // 输入=当前情境 + 程序化识别的领域/自评信心/经历卷 + 程序化初始档位；
        // 输出={"posture":"conservative|balanced|aggressive","reason":str}。
        // 语义=MUSE「能力感知→策略选择」的复核通道：程序化规则先出档位（白盒保底），
        // 抽样交 AI 复核「这个档位合不合理」——AI 可维持/调整；AI 失败回退初始档位（fail-open）。
        // 铁律：posture 只能三选一；reason 要具体到情境线索；不得输出档位以外的内容。
        map.put(CODE_MUSE_STRATEGY_CHECK, new BuiltinDef("MUSE 策略切换·AI 档位复核",
                """
                你是一位真实活着的角色，正在判断自己「此刻该用什么态度面对眼前的事」。
                一个程序化规则已给出初始策略档位（保守/均衡/激进），请你复核它是否合理，
                输出严格 JSON 对象，禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 输出结构（键名严格照抄） ——
                { "posture": "conservative 或 balanced 或 aggressive", "reason": "复核理由（40 字内）" }

                —— 口径 ——
                1. posture 三选一：conservative=谨慎行事（先观察、多留退路、不冒进）；balanced=按常理行事；
                   aggressive=胸有成竹、可以果断大胆（认准了就放手去做）。
                2. 复核标准=「情境陌生度 × 你对相关领域的自评把握」：面对陌生/没把握的事 → conservative；
                   拿手领域且经历丰富 → aggressive；一般情形 → balanced。
                3. 初始档位是程序化参考，你可以维持它，也可以在理由充分时调整一档（不要无理由推翻）。
                4. 只依据给定信息判定，不脑补；reason 要具体到情境里的线索。

                —— 当前情境 ——
                {{situation}}

                —— 程序化识别 ——
                {{domain_info}}

                —— 初始策略档位 ——
                {{initial_posture}}
                """, "你只输出严格的 JSON 对象，不输出任何其他文字。"));

        // ③-⑦ 潜在世界预测注入（world_outlook，P1 ③-2）：PLAIN_TEXT 一句话预测。
        // 输入=世界近期趋势（程序化环比：粮价/民怨/势力/议题方向）+ 当前宏观简报；
        // 输出=「未来一天世界走向」≤80 字预判（角色脑内预演世界演化，GLAM「我不行动世界会怎样」）。
        // 铁律：只依据趋势与局势推演（不脑补无依据走向）；一句话 80 字内；不带 JSON。
        map.put(CODE_WORLD_OUTLOOK, new BuiltinDef("潜在世界预测注入",
                """
                你是一位熟悉此方世界局势的智者，正预演「如果我不做任何事，明天世界会怎样」。

                —— 世界近期趋势 ——
                {{trend_summary}}

                —— 当前局势 ——
                {{macro_summary}}

                请基于以上趋势与局势，用一句话（80 字以内）预判未来一天世界的走向
                （如粮价/集市/势力/人心的可能变化），不要输出 JSON、不要多余解释。
                """, "你只输出一句不超过 80 字的世界走向预判，不输出任何其他文字。"));

        // ②-③ 记忆-知识桥接判定（knowledge_bridge，P1 ②-3）：SCHEMA_JSON。
        // 输入=角色档案摘要 + 本次反思洞察列表；输出=「可复用事实型知识候选」判定
        // （{content, importance, is_knowledge}）。铁律：只把「可复用的客观事实型知识」
        // （关于世界/他人/自身稳定的长期认知）判为知识；临时情绪/主观想法/一次性事件不写入；
        // 宁缺毋滥（is_knowledge=false 默认）。低温（0.3）稳定判定、篇幅收敛（512）。
        map.put(CODE_KNOWLEDGE_BRIDGE, new BuiltinDef("记忆-知识桥接判定",
                """
                你是一位严谨的知识管理员，负责把角色的反思洞察沉淀为「可复用的客观事实型知识」。

                请逐条判断：哪些洞察是值得长期记住的客观事实型知识——关于世界、他人或自身
                的稳定认知（例如「沈氏宗族控制着镇上的粮市」「集市每逢初一休市」「李掌柜对欠账
                格外宽容」）；哪些只是临时情绪、主观想法或一次性事件（不写入知识库）。

                —— 角色档案 ——
                {{character_info}}

                —— 本次反思洞察 ——
                {{insights}}

                输出严格 JSON 对象（键名严格照抄），禁止输出任何其他文字：
                {"knowledge_candidates":[{"content":"可复用事实（完整陈述句，不超过 100 字）","importance":3,"is_knowledge":true}]}
                铁律：宁缺毋滥——不确定时 is_knowledge 填 false。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）
                """, "你只输出有效 JSON，不要输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ==================== 轨道：位置移动与地图生成系统（M1 世界地图生成） ====================
        // 世界地图生成（world_map_generate）：SCHEMA_JSON 0.5/8192（PromptSpecRegistry 登记）。
        // 输入=地点列表+世界观+角色列表+既有布局种子；输出=regions（每地点一区域）+
        // roads（道路网）+ placements（建筑摆放）+ homes（住所，同居共宅 members[]）。
        // 铁律（用户需求「生成的地图要符合现实的建筑分布规律」）：
        //  1. 团状聚落——公共核心（祠堂/集市/水井/茶馆）居中，民居环带围绕，生产用地（农田/梯田/林）
        //     在外围，商业服务（医馆/铁匠/客栈/粮铺）沿主路两侧近核心，村口地标（牌坊/渡口/山神庙）在边；
        //  2. 临水布置（渡口/水磨/洗衣处靠河），道路网主街贯穿核心、支路连接民居群；
        //  3. 同居共宅——亲密关系（配偶/情侣/亲子/兄弟姐妹/亲属）同住一间住所，每角色恰在一个
        //     homes[].members 中，一间住所一个户主（role=户主）；
        //  4. 坐标合法——全部坐标在 [margin, size-margin] 内；区域两两不重叠（圆心距 ≥ r1+r2+4）；
        //     住所/摆放不落在河、路（距路中线 ≥ 路宽/2 + 3）；住所两两间距 ≥ 12。
        map.put(CODE_WORLD_MAP_GENERATE, new BuiltinDef("世界地图生成",
                """
                你是一位精通聚落营造的风水师与村镇规划师，正在为一方世界绘制<b>详细世界地图</b>——
                把地点设定落实到空间：每个地点一个区域（圆心+半径），道路连接，建筑摆放，以及每个角色的住所。

                —— 世界观 ——
                {{world_setting}}

                —— 地点列表（locationId 必须原样使用） ——
                {{locations}}

                —— 角色列表（characterRef 必须原样使用；有亲密关系的应同住） ——
                {{characters}}

                —— 既有布局种子（河/路/摆放，可沿用其河道路径方向） ——
                {{preset_layout}}

                —— 场景尺寸 ——
                场景边长为 {{scene_size}}（格=米），坐标 x/z ∈ [0, {{scene_size}})。

                —— 现实聚落分布规律（必须遵守） ——
                1. 团状聚落：公共核心（祠堂/集市/水井/茶馆/凉亭）居中；民居（住宅）围绕核心呈环带分布；
                   生产用地（农田/梯田/菜地/林地）在聚落外围一侧；商业服务（医馆/铁匠铺/客栈/粮铺/茶摊）
                   沿主路两侧、近核心；村口地标（牌坊/渡口/学堂/山神庙）在村边或高地。
                2. 临水布置：渡口/水磨/洗衣处在河畔（若有河）。
                3. 道路网：主街贯穿核心（连接村口与村内），支路连接各民居群与商业点；路不穿过建筑。
                4. 每地点一个区域：regionDesc 用口语描述（「村西口，进村第一景」「河畔渡口」），供角色感知。
                5. 建筑摆放 placements 描述地点内的具体建筑（店铺/住宅/地标/农田），每个地点至少 1 条。

                —— 同居共宅（必须遵守） ——
                6. 有亲密关系（配偶/情侣/亲子/兄弟姐妹/亲属）的角色<b>同住一间住所</b>：只生成一间房，
                   members 列出全部同住者；每角色恰在一个 homes[].members 中（不得遗漏、不得重复）；
                   每间住所一个户主（role=户主）；孤立角色独自一间。
                7. 住所名用家中最体面的称呼（「沈家大宅」「李记面馆后屋」）；roomLabel 为房间（「正房东间」）。

                —— 坐标合法性（必须遵守，否则整批作废） ——
                8. 全部坐标（区域圆心/摆放/住所）在 [40, {{scene_size}}-40] 内；
                9. 区域两两不重叠：圆心距 ≥ r1+r2+4；半径 8~90；
                10. 住所/摆放不落在河、路上（距道路中线 ≥ 路宽/2 + 3）；住所两两间距 ≥ 12；
                11. 区域 locationId 与地点列表一一对应；住所/摆放的 locationId 必须存在；
                    homes[].members[].characterRef 必须来自角色列表。

                输出严格 JSON 对象（键名严格照抄，禁止输出任何其他文字），结构如下：
                {
                  "mapName": "地图名（如 青禾村总图）",
                  "regions": [
                    { "locationId": 1, "name": "村口石牌坊", "centerX": 250, "centerZ": 260,
                      "radius": 12, "shape": "circle", "regionDesc": "村西口，进村第一景，石牌坊下常有人歇脚" }
                  ],
                  "roads": [
                    { "name": "村中主道", "path": [[245, 255], [290, 300]], "width": 3 }
                  ],
                  "placements": [
                    { "name": "沈记粮铺", "category": "store", "posX": 310, "posZ": 220, "rotY": 0, "locationId": 6 }
                  ],
                  "homes": [
                    { "name": "沈家大宅", "locationId": 6, "posX": 315, "posZ": 215, "roomLabel": "正房",
                      "region": "村西", "note": "沈砚秋书房在西厢",
                      "members": [
                        { "characterRef": "npc:1", "role": "户主", "roomLabel": "正房东间" },
                        { "characterRef": "npc:7", "role": "配偶", "roomLabel": "东厢" }
                      ] }
                  ]
                }
                铁律：坐标必须合法；同居共宅；每角色恰一住所成员。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）
                """, "你是精通聚落营造的村镇规划师：只输出严格 JSON 对象，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ==================== 轨道：世界地图生成管线 V1.1 ====================
        // S0 世界档案（world_map_profile）：世界观地理设定 → 一组「可解释的地理量」。
        // 关键设计（用户决策 #2/#27）：AI 只描述地理性格，**绝不输出坐标**——坐标由
        // TerrainGenerator 确定性推导，同一个 seed 永远得到同一张岛。这样 AI 写错数字
        // 也只会让岛「风格不同」，不会让地图「结构崩坏」（这是上一版 AI 直接画图失败的根因）。
        map.put(CODE_WORLD_MAP_PROFILE, new BuiltinDef("世界地图 · 世界档案（地理参数）",
                """
                你是地理学家兼世界构建师。请阅读下面的世界观与地理设定，把这片土地的<b>地理性格</b>
                翻译成一组参数，供程序据此生成真实地形（高度场、水系、海岸、生物群系）。

                —— 世界观 ——
                {{world_setting}}

                —— 已设定的地点（地名与位置描述，若与地貌冲突以地貌为先） ——
                {{locations}}

                —— 题材提示 ——
                {{genre_hint}}

                —— 地图尺度 ——
                地图边长 {{size_meters}} 米，1 格 = 1 米。请按「步行可达的小镇/村落」来考虑尺度：
                一个镇子的核心区通常 300~600 米见方，走完一条街只要几分钟。

                —— 参数含义与合法范围（越界会被夹到边界，但请尽量准确） ——
                genreProfile: island（海岛）/ river_valley_village（河谷村）/ coastal_town（海滨镇）/
                              mountain_hamlet（山村）/ plain_city（平原城）
                reliefHeight: 0.12~0.62  陆地相对海平面的最大抬升（归一化）。海岛小镇 0.22~0.34；
                              山地 0.45~0.60。这个值直接决定「有多平坦」。
                elevationMeters: 30~900 最高点真实海拔（米）。海岛上 60~150 米就够；雪山才需要 600+。
                islandRadiusRatio: 0.18~1.05 陆地半径 / 地图半径。0.34 → 岛直径约地图的 68%。
                elongation: 0.7~2.0 狭长度（>1 沿主轴拉长）。
                rotationDeg: 0~360 主轴旋转角。
                coastRoughness: 0~1 海岸破碎度（0 浑圆，1 犬牙交错）。治愈系海岛 0.4~0.65。
                bays: 0~5 海湾/岬角数量。
                coastShelf: 10~120 近岸浅海棚架宽度（格=米）。越大越像「浅滩缓坡入海」。
                reliefStrength: 0.2~1.4 起伏强度（山多不多）。
                peakCount: 0~5 山峰数量。
                peakHeight: 0.05~0.55 山峰高度。
                ridgeBearingDeg: 0~360 山脊走向（度，0=南北向）。
                plainFraction: 0~0.9 平原占比——<b>这是「适合活动的地方有多少」的总开关</b>。
                              田园小镇请给 0.5~0.75；山地给 0.15~0.3。
                plainRadius: 0.10~0.60 平原半径（相对地图边长）。
                plainCenterX / plainCenterZ: 0~1 平原中心（归一化）。聚落通常就在平原上。
                settlementRadius: 0.08~0.6 聚落影响半径（决定农田/伐木痕迹的范围）。
                riverCount: 0~4 期望河流条数。
                lakeCount: 0~3 湖泊数量。
                temperature: 0~1（0 寒 1 热）。moisture: 0~1（0 干 1 湿）。
                forestBias: 0.3~1.4 森林倾向（>1 更密）。
                beachWidth: 1~12 沙滩宽度（米）。
                erosionStrength: 0~0.5 水力侵蚀强度（越大河谷越深、地形越细碎）。
                landmarks: 地貌级地标（灯塔、岬角、老树、渡口、泉眼…），只给「在地貌的什么位置」，
                           <b>不要给坐标</b>；sit 用空间语义描述（岬角最东端的高处 / 河口西岸 / 山脊南坡）。
                forbidden: 这个世界观里<b>不该出现</b>的建筑类型（禁忌清单），供后续过滤用。
                allowedCategories: 允许的建筑大类，从 民居/商铺/工坊/公共/生产/水工/军事/宗教 中选。

                —— 判定原则 ——
                1. 忠于世界观：设定里写「低矮石屋」就不要给高楼；写「多雾多风」就让湿度和海岸破碎度偏高。
                2. 宜居优先：这是一个有人长期生活的地方，必须留出足够平地——不要把整座岛做成山地。
                   只有世界观明确写「高山/雪岭/险峻」时才允许 elevationMeters ≥ 600 与高峰。
                3. 水系合理：有河就必有谷；岛上的河都不长（1~3 公里），riverCount 别超过 3。
                4. 缺口自己补：设定没写的地理细节（海湾数量、林密度…）由你按题材补全，但要自洽。

                输出严格 JSON 对象（键名严格照抄，禁止输出任何其他文字）：
                {
                  "mapName": "地图名（如 青屿镇）",
                  "genreProfile": "island",
                  "climate": "气候的一句话描述",
                  "narrative": "整体地貌的 2~3 句叙述（给策划看，不进渲染）",
                  "reliefHeight": 0.30, "elevationMeters": 110,
                  "islandRadiusRatio": 0.36, "elongation": 1.14, "rotationDeg": 18,
                  "coastRoughness": 0.58, "bays": 3, "coastShelf": 52,
                  "reliefStrength": 0.58, "peakCount": 2, "peakHeight": 0.30, "ridgeBearingDeg": 148,
                  "plainFraction": 0.55, "plainRadius": 0.34,
                  "plainCenterX": 0.52, "plainCenterZ": 0.62,
                  "settlementRadius": 0.34,
                  "riverCount": 2, "lakeCount": 1,
                  "temperature": 0.68, "moisture": 0.60, "forestBias": 0.85, "beachWidth": 3,
                  "erosionStrength": 0.18,
                  "landmarks": [
                    { "name": "老灯塔", "kind": "lighthouse", "sit": "岬角最东端的高处",
                      "note": "青屿镇的精神性地标，灯长年不灭" }
                  ],
                  "forbidden": ["现代设施", "武器铺", "监牢", "赌坊"],
                  "allowedCategories": ["民居", "商铺", "工坊", "公共", "生产", "水工"]
                }
                铁律：不输出任何坐标；数值必须在合法范围内；landmarks 的 sit 必须是空间语义。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）
                """, "你是地理学家：只输出严格 JSON 对象，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // S0b 世界→地图契约（world_map_brief）：区域方位 + 设施清单 + 聚落约束。
        // 用户决策：AI 给布局，程序只做约束修正；人口由住所表推导，AI 不许猜数量。
        map.put(CODE_WORLD_MAP_BRIEF, new BuiltinDef("世界地图 · 世界→地图契约（区域与设施）",
                """
                你是聚落规划师。下面是一个世界观、它已经设定好的地点、以及已经存在的住所清单。
                请把它们翻译成「地图生成用的契约」：这块地分成哪几个区域、各在什么方位、
                有哪些**人造设施**必须出现在地图上、聚落该有多大。

                —— 世界观 ——
                {{world_setting}}

                —— 已设定的地点（地名 + 类型 + 简介；这是地图必须对上的东西） ——
                {{locations}}

                —— 已有的住所（户数是硬事实，不要增删） ——
                {{homes}}

                —— 判定原则 ——
                1. 只描述「方位、区域、设施、尺度」，**绝对不要给任何坐标**。坐标由程序在地形上求。
                2. 区域按地貌与功能区划分（丘陵/林地/滩涂/海湾/岬角/聚落/溪流/山脊/农田…），
                   方位角 0=正北、顺时针，必须覆盖岛的各个方向，不要全挤在一个方向。
                3. 设施只从「人造场所」里出：灯塔、渡口/码头、市集/广场、议事堂、学堂、药铺、
                   铁匠铺、杂货铺、茶馆、仓廪、磨坊、水井、船屋、晒场、祠庙（若世界观允许）…
                   自然地名（山、河、海、大陆、岛屿本身）**不要**生成建筑。
                4. 每个地点最多对应一个设施；anchorLocation 必须照抄地点清单里的原名，写不出原名就留空。
                5. 规模感：住所清单里只有十几户，就是一个「小村落/小岛镇」，设施总量应等于
                   「人造地点数 + 3~6 个必要公共设施」，不要凭空发明几十个设施。
                6. 尺寸用真实米制：民居开间 6~12 米、铺面 5~10 米、灯塔基座 5~8 米、
                   渡口平台 8~16 米、广场单边 15~30 米。层数 1~2 层为主（灯塔可 2~3 层）。
                7. 若世界观没写某个区域，按题材补全，但要与已有地点自洽。

                输出严格 JSON 对象（键名严格照抄，禁止输出任何其他文字）：
                {
                  "terrainFamily": "ISLAND",
                  "regions": [
                    { "code": "hills_north", "name": "北面丘陵", "kind": "HILLS",
                      "bearingDeg": 350, "radialFrom": 0.15, "radialTo": 0.85,
                      "elevationBias": 0.5, "treeDensity": 0.7, "moistureBias": 0.1 }
                  ],
                  "settlement": { "centerBearingDeg": 170, "radiusM": 160, "slopeMax": 0.18 },
                  "facilities": [
                    { "code": "lighthouse", "name": "长明灯塔", "kind": "lighthouse",
                      "anchorLocation": "长明灯塔", "role": "公共",
                      "frontageM": 7, "depthM": 7, "floors": 2 }
                  ],
                  "householdSize": 3,
                  "forbidden": ["现代设施", "武器铺", "监牢", "赌坊", "神殿", "摩天楼"],
                  "allowedCategories": ["民居", "商铺", "工坊", "公共", "生产", "水工"]
                }

                kind 取值：HILLS / FOREST / MUDFLAT / BAY / CAPE / SETTLEMENT / CREEK / RIDGE / FIELD / BEACH。
                role 取值：公共 / 商铺 / 工坊 / 生产 / 水工。
                bearingDeg: 0~360；radialFrom/radialTo: 0~1（相对地图半边长）；elevationBias/moistureBias: -1~1。
                铁律：不输出坐标；不虚构地点；设施数量与住所规模匹配；householdSize 默认 3。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）
                """, "你是聚落规划师：只输出严格 JSON 对象，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // S2 建筑资产（world_map_assets）：这一带该有什么房子——**只有类型，没有坐标**。
        // 用户决策 #19/#20：固定通用大类（跨时代可用）+ 自由命名；禁忌清单三层拦截。
        map.put(CODE_WORLD_MAP_ASSETS, new BuiltinDef("世界地图 · 建筑资产类型库",
                """
                你是聚落营造师。请依据世界观，为这片土地设计一整套<b>建筑资产类型</b>——
                回答「这里该有什么房子」，<b>不要指定它们在哪里</b>（位置由后续规则在地形上求得）。

                —— 世界观 ——
                {{world_setting}}

                —— 地理档案摘要 ——
                {{profile_summary}}

                —— 禁忌（这个世界不该出现的建筑，绝对不能产出） ——
                {{forbidden}}

                —— 设计要求 ——
                1. 数量：12~22 个类型，覆盖「民居 / 商铺 / 工坊 / 公共 / 生产 / 水工」六个大类，
                   民居占 40% 左右（小镇以住为主），公共 15%，其余各 10~15%。
                2. 尺寸要真实（米）：民居开间 6~12、进深 5~9；店铺面宽 5~9；工坊 8~16；
                   公共建筑（议事厅/学堂/祠）12~24；仓库 8~14。层数：小镇 1~2 层，不要 4 层以上。
                3. 形制要符合世界观与技术代差：没有现代材料就不要玻璃幕墙/钢筋；没有宗教就不要神殿。
                4. 配色取自当地材料：木/石/夯土/茅草/瓦——给出 hex 颜色，同一聚落内要协调
                   （墙色偏暖灰或土黄，屋顶偏深褐、青灰、苔绿）。
                5. roofShape 只能取 flat / gable / hip / shed / dome / spire / thatch。
                6. code 用英文小写下划线，全局唯一；name 用世界观里的叫法（「渔家小院」「晒盐棚」）。
                7. weight 是出现权重（1~20）：民居类高（10~16），地标类低（1~3）。
                8. description 一句话说清「谁在用、用来干什么」，不超过 40 字。

                —— 大类定义（必须严格使用这些 category） ——
                民居：住人的（农舍/渔家院/长屋/吊脚楼）
                商铺：做买卖的（杂货铺/茶摊/酒肆/粮行/布庄）
                工坊：做东西的（铁匠铺/木工房/染坊/磨坊/窑）
                公共：大家共用的（议事厅/学堂/祠庙/井亭/钟楼/医馆）
                生产：种与养的（谷仓/畜棚/晒场/渔获棚/盐田）
                水工：与水相关的（码头/渡口/水车/洗衣埠/防波堤）

                输出严格 JSON 对象（键名严格照抄，禁止输出任何其他文字）：
                {
                  "assetTypes": [
                    { "code": "house_fisher", "name": "渔家小院", "category": "民居",
                      "kind": "building", "baseW": 9.0, "baseD": 7.5, "floorsMin": 1, "floorsMax": 2,
                      "wallColor": "#d9c9a8", "roofColor": "#8a5a3c", "roofShape": "gable",
                      "material": "wood", "styleTags": ["坡屋顶", "木构", "晾网架"], "weight": 14,
                      "description": "渔民一家三代同住，院里晒网、檐下挂鱼干" }
                  ],
                  "districts": [
                    { "code": "old_street", "name": "老街", "character": "石板路两侧是铺面，早市最热闹" }
                  ]
                }
                铁律：只给类型不给坐标；不得出现禁忌清单里的东西；配色必须协调。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）
                """, "你是聚落营造师：只输出严格 JSON 对象，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // S3 路网拓扑（world_map_roads）：只给「谁连谁 + 路性格」，坐标由最小代价 A* 求。
        // 用户决策 #6：AI 定拓扑，A* 在真实地形/水系上寻路（坡度、过河、绕建筑都是代价）。
        map.put(CODE_WORLD_MAP_ROADS, new BuiltinDef("世界地图 · 路网拓扑（只给连接关系）",
                """
                你是村镇道路规划师。请依据地理与聚落性格，设计这片土地的<b>道路拓扑</b>——
                只说明「哪些地方必须连通、这条路是什么性格」，<b>不要给坐标</b>：
                具体走线会由最小代价寻路在真实地形上求出（会自动绕陡坡、跨河架桥、避开房屋）。

                —— 世界观 ——
                {{world_setting}}

                —— 地理与聚落摘要 ——
                {{profile_summary}}

                —— 已备好的建筑类型 ——
                {{asset_summary}}

                —— 地貌级地标（可作为路的端点） ——
                {{landmarks}}

                —— 规划原则（现实聚落规律，必须遵守） ——
                1. 一个核心：镇中心（广场/井台/大树下）是全图的枢纽，至少 3 条路在此交汇。
                2. 门与水的连接：通往码头/渡口/村口的「门户路」必须是主干（level=major，宽 4~6 米）。
                3. 主街贯穿：一条主街从门户串到核心区，商铺沿它两侧分布。
                4. 支路成网：民居群用巷道（level=minor，宽 2~3 米）连主街，不要每户单独接主干
                   （那会在图上形成梳齿状的假路网）。
                5. 生产性道路：通往农田/林地/盐田的路是土路（surface=dirt），宽度 2~3 米，
                   数量少而长，从聚落边缘放射出去。
                6. 跨水必须成桥：若路要过河，kind 给 bridge（后续会自动在该处生成桥）。
                7. 不要生成「环岛」「立交」「宽阔大道」这类超出技术代差的东西。
                8. 路的总条数 8~18 条：主干 2~3 条，巷道 5~12 条，生产路 2~4 条。

                —— 节点角色 role ——
                core（中心枢纽）/ gate（对外门户/码头/渡口）/ waterfront（临水）/ residential（民居群）/
                workshop（工坊区）/ farm（生产区）/ landmark（地标）
                —— prefer（放置偏好，给寻路器定位用） ——
                settlement-center / coast / river / settlement-edge / high-ground / farmland / forest

                输出严格 JSON 对象（键名严格照抄，禁止输出任何其他文字）：
                {
                  "nodes": [
                    { "code": "plaza_center", "name": "镇中心小广场", "role": "core",
                      "prefer": "settlement-center", "note": "一口老井，镇上人都在这里碰面" },
                    { "code": "harbor_gate", "name": "码头口", "role": "gate",
                      "prefer": "coast", "note": "渔船靠岸、货物上岸的地方" }
                  ],
                  "roads": [
                    { "code": "main_street", "name": "正街", "kind": "street", "level": "major",
                      "width": 5.0, "surface": "stone", "from": "harbor_gate", "to": "plaza_center",
                      "bend": 0.2, "priority": 10 }
                  ]
                }
                kind 只能取 street / lane / path / bridge / stairs / pier；surface 取 stone / dirt / wood / gravel。
                bend 是「弯曲倾向」0~1（0 笔直，1 随地形大绕）。priority 1~10（越大越优先保证走通）。
                铁律：不给坐标；from/to 必须是已定义节点；node code 全局唯一。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）
                """, "你是村镇道路规划师：只输出严格 JSON 对象，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ==================== 轨道：位置移动与地图生成系统（M3 途中轻量交互） ====================
        // 途中轻量交互（move_encounter）：SCHEMA_JSON 0.5/200（PromptSpecRegistry 登记）。
        // 移动者途中迎面遇到他角色时，AI 决定是否停下搭话——{greet:"一句话", stop:bool}；
        // stop=true 才打断移动进入对话/群聊（§4.6）；失败 fail-open（不交互继续走）。
        map.put(CODE_MOVE_ENCOUNTER, new BuiltinDef("途中轻量交互（是否停下搭话）",
                """
                你正走在路上，迎面遇到一个认识或不认识的人。按你的性格与当时处境，决定是否停下打个招呼——
                像真实的人一样：熟人或关系近的人更值得停下说两句；有急事赶路时可以点头致意继续走。

                —— 输出结构（严格 JSON，禁止输出其他文字） ——
                {
                  "greet": "一句话的招呼/寒暄（10-25 字；若不停下也要有基本礼貌，如「张掌柜，赶路呢？」）",
                  "stop": true 或 false
                }

                —— 语义 ——
                1. stop=true：停下与对方搭话（熟识、想聊、对方主动示好、此刻不赶时间等）；
                2. stop=false：点头致意/简单招呼后继续赶路（赶时间、不熟、心情不好、对方正忙等）。
                3. greet 始终要有——不停下也要自然说一句，不能沉默走过（真人感）。

                —— 要求 ——
                1. 决定必须符合【我的性格与处境】中的性格/关系/紧迫度，禁止 OOC；
                2. 与对方的关系（亲近/陌生/有过节）会明显影响是否停下与招呼的语气；
                3. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                —— 我的性格与处境 ——
                我：{{my_name}}；我此刻正在：{{my_situation}}

                —— 迎面遇到的人 ——
                {{other_name}}；与我的关系：{{relation_hint}}（无描述=路人）
                """, "你是走路途中的人物，只输出严格 JSON 对象，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ==================== 轨道：人格演化 + 社会面具（新建） ====================
        // ④ 人格偏移评估：SCHEMA_JSON。输入=角色骨架 + 当前 OCEAN/价值权重 + 证据摘要（L3/L2 记忆，
        // 已按证据强度筛选）；输出=各维偏移量与理由。铁律：宁缺毋滥（证据不足不偏移）、
        // 偏移幅度克制（人格不会一夜剧变）、只依据证据（不脑补）。程序化截断在服务层强制。
        map.put(CODE_PERSONA_SHIFT_EVAL, new BuiltinDef("人格偏移评估",
                """
                你是严谨的人格演化评估员。请基于【证据摘要】判断该角色的人格是否应发生偏移，
                输出严格 JSON 对象，禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 输出结构（键名严格照抄） ——
                {
                  "shifts": [
                    { "dim": "ocean", "field": "openness|conscientiousness|extraversion|agreeableness|neuroticism",
                      "delta": -0.04, "why": "该偏移的证据与理由（30 字内）" },
                    { "dim": "values", "field": "某个价值键（如 宗族/乡邻生计/名节/承诺/秩序/私利/信仰）",
                      "delta": -0.03, "why": "该偏移的证据与理由（30 字内）" }
                  ],
                  "summary": "一句话总评（30 字内）"
                }

                —— 铁律 ——
                1. 宁缺毋滥：证据不足时 shifts 可为空数组；绝不为了"看起来有变化"而编造偏移。
                2. 偏移幅度克制：单条 |delta| ≤ 0.05（人格不会一夜剧变）；负值=该特质/价值下降。
                3. 只依据证据：每条偏移必须能在证据摘要中找到支撑；与证据矛盾的偏移禁止输出。
                4. dim 只能是 ocean 或 values；ocean 的 field 必须是上述五键之一，values 的 field
                   必须是【价值权重】中已存在的键，不得新增键。
                5. 不替角色发言、不做心理分析腔：只判定"该偏移多少、为什么"。

                —— 角色 ——
                {{name}}（{{age}} 岁，{{stage}}）

                —— 当前 OCEAN ——
                {{personality_current}}

                —— 当前价值权重 ——
                {{values_current}}

                —— 证据摘要（L3 核心记忆 + L2 语义/情节记忆，已按证据强度筛选） ——
                {{evidence}}
                """, "你是严谨的人格演化评估员：只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ==================== 轨道：遗传婚育谱系（新建） ====================
        // ⑤ 心动判定：SCHEMA_JSON {attracted,confidence,reason}。输入=本人 + 候选对象 + 互动证据。
        // 铁律：宁缺毋滥（证据不足不动心）；判定依据只能来自互动证据，禁止脑补身份/财富/外貌；
        // 契约与 NanoJev boolean question 同构（N 轨道替换实现零重写主流程）。
        map.put(CODE_ROMANCE_DECISION, new BuiltinDef("心动判定",
                """
                你是这个世界的观察者，深谙人情世故。请判断角色 {{npc_name}} 是否对 {{partner_name}} 产生了浪漫兴趣（动心），
                输出严格 JSON 对象，禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 输出结构（键名严格照抄） ——
                {
                  "attracted": true,
                  "confidence": 0.6,
                  "reason": "动心/不动心的理由（40 字内，必须基于互动证据）"
                }

                —— 铁律 ——
                1. 宁缺毋滥：互动证据不足时 attracted=false；绝不为了推进剧情而编造心动。
                2. 只依据证据：理由必须能在【互动证据】中找到支撑；涉及身份/财产/外貌的臆测禁止出现。
                3. 身份地位差异、家族/势力恩怨是真实世界最常见的克制理由——该克制时就克制。

                —— 角色 ——
                {{npc_name}}（本人）

                —— 候选对象 ——
                {{partner_name}}

                —— 互动证据（好感/信任/关系备注） ——
                {{interaction_evidence}}
                """, "你是这个世界的观察者，深谙人情世故：只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ⑥ 求婚判定：SCHEMA_JSON {propose,target_ref,reason}。输入=恋爱时长 + 候选对象摘要。
        // 铁律：婚姻是大事，propose=true 必须有恋爱基础与合理理由；target_ref 必须来自候选列表。
        map.put(CODE_PROPOSAL_DECISION, new BuiltinDef("求婚判定",
                """
                你是这个世界的观察者，深谙人情世故。请判断角色 {{npc_name}} 是否应向恋爱对象求婚，
                输出严格 JSON 对象，禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 输出结构（键名严格照抄） ——
                {
                  "propose": true,
                  "target_ref": "npc:123",
                  "reason": "求婚/暂缓的理由（40 字内）"
                }

                —— 铁律 ——
                1. target_ref 必须严格等于【候选对象】列表中某一项的完整引用（照抄，不得改写）。
                2. propose=true 需要合理理由（感情深厚/年龄到了/家族有意），理由写在 reason。
                3. 关系不稳（好感低/性格不合迹象）或时机不利（时局动荡/服丧期）→ propose=false。
                4. 不要替对方发言：只判定求婚方自己的意愿。

                —— 角色 ——
                {{npc_name}}（恋爱 {{romance_days}} 游戏日）

                —— 候选对象 ——
                {{candidates}}
                """, "你是这个世界的观察者，深谙人情世故：只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ⑦ 生育意愿判定：SCHEMA_JSON {want,reason}。输入=婚姻时长/已有子女/年龄/人口。
        // 铁律：生育观念由角色所处的时代与文化背景决定，但不得预设任何特定文化；
        // 身体/家境/子女负担是克制理由。
        map.put(CODE_FERTILITY_DECISION, new BuiltinDef("生育意愿判定",
                """
                你是这个世界的观察者，深谙人情世故。请判断已婚角色 {{npc_name}} 此刻是否想要孩子，
                输出严格 JSON 对象，禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 输出结构（键名严格照抄） ——
                {
                  "want": true,
                  "reason": "想要/暂缓的理由（40 字内）"
                }

                —— 铁律 ——
                1. 生育观念由角色所处的时代与文化背景决定：在多数社会中添丁是人之常情，want=true 不需要特别理由；
                   但不得预设任何特定文化——判断依据是角色本人的具体处境与观念。
                2. 但已有多个子女（负担重）、年岁渐长（身子弱）、或时局艰难（动荡/战乱/饥荒）→ want=false。
                3. 只判定 {{npc_name}} 本人意愿，不要替配偶发言。

                —— 角色 ——
                {{npc_name}}（{{age_summary}}；已婚 {{marriage_days}} 游戏日；已有子女 {{existing_children}} 人）

                —— 所在聚落 ——
                当前人口约 {{population}} 人。
                """, "你是这个世界的观察者，深谙人情世故：只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ⑧ 新生儿档案：SCHEMA_JSON {given_name,appearance}。 分阶段揭示——出生只填起名 + 外貌继承，
        // 其余维（语言/习惯/价值观/经历）留待阶段跨越揭示（AI 不提前给出成人人格）。
        map.put(CODE_NEWBORN_PERSONA, new BuiltinDef("新生儿档案",
                """
                你是这个世界中见证新生儿降生的长辈（接生婆/助产者/医者/家族长者，按世界设定自拟身份）。
                新生婴儿刚落地，请你给这孩子取名并描述其相貌，
                输出严格 JSON 对象，禁止输出任何其他文字。
                DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

                —— 输出结构（键名严格照抄） ——
                {
                  "given_name": "名字（1-2 个字，不含姓）",
                  "appearance": "相貌继承描述（60 字内：像爹还是像娘、眉眼/哭声/头发等可感知细节）"
                }

                —— 铁律 ——
                1. given_name 只给出名（姓随家族传承：{{surname}}），须符合本世界的命名习惯
                   （参考父母姓名风格与所属文化；避讳长辈名讳；男女用字可有别）。
                2. appearance 必须基于【父母相貌】描述继承特征（婴儿可感知：眉眼/哭声/发量/胎记），
                   不得出现"气质/性格/聪慧"等婴儿不可观察的评价。
                3. 不要写身份、职业、经历、价值观——这些随成长才会显现。

                —— 婴儿 ——
                {{gender}}婴，姓 {{surname}}

                —— 父亲 ——
                {{father_name}}（{{father_occupation}}）；相貌：{{father_appearance}}

                —— 母亲 ——
                {{mother_name}}（{{mother_occupation}}）；相貌：{{mother_appearance}}
                """, "你是这个世界中见证新生儿降生的长辈（身份按世界设定自拟）：只输出严格 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON."));

        // ⑨ 婚礼叙事：正文 PLAIN_TEXT。喜庆但生活化——有具体场景与细节，不铺张辞藻；
        // 场景细节须贴合世界设定（现代都市/仙侠/科幻等皆可），不预设任何特定文化。
        map.put(CODE_WEDDING_NARRATIVE, new BuiltinDef("婚礼叙事",
                """
                你是世界编年史的作者。请为{{world_name}}中的一场婚礼写一段叙事（100-200 字），
                只输出正文，不要标题、不要 JSON、不要任何解释。

                —— 叙事要求 ——
                1. 用朴素真切的中文，像世界编年史里的一页：有具体场景与细节（时辰/天气/宴席/亲友道贺）。
                2. 基调喜气但不铺张：如实写婚礼场面，可以有新郎新娘的局促与欢喜。
                3. 忠实于本世界的生活：婚俗、随礼、宴席这些细节要贴合世界设定。

                —— 新人 ——
                新郎：{{husband}}；新娘：{{wife}}
                """, "你是这个世界朴素真挚的叙事者，用符合世界设定的口吻讲述，只输出叙事正文。"));
        // 世界常识摘要（V2.0 段 1A 稳定世界观注入源，2026-10-08 外网冒烟发现 worldBrief 硬截断残缺后新增）：
        // 依最新世界观提炼 300-500 字「该世界角色都知道的世界常识」，纯文本正文，禁止罗列/截断。
        map.put(CODE_WORLD_COMMON_KNOWLEDGE, new BuiltinDef("世界常识摘要（300-500 字精炼常识）",
                """
                你是世界设定提炼师。请阅读下方这个世界的完整设定，提炼出一段【世界常识】（300-500 字）——
                即生活在这个世界的角色都知道的基础常识，用于让 AI 在对话与决策时准确理解这个世界。

                —— 提炼要求 ——
                1. 只写「这个世界的居民都知道的常识」：世界格局与大致地貌、主要聚居地/区域、
                   各方势力与地位、基本规则与力量体系、日常文化风俗、世代相传的共识。
                2. 精炼概括，不要罗列全部设定条目，不要出现表格、编号清单或章节标题；
                   用连贯的叙述性中文写成一到两段话。
                3. 必须贴合下方设定原文，不得虚构设定之外的内容。
                4. 控制在 300-500 字。只输出常识正文，不要任何前后缀、标题或解释。

                —— 世界设定 ——
                {{world_setting}}
                """, "你是世界设定提炼师，把下方世界设定浓缩成一段 300-500 字的常识性叙述，只输出正文。"));
        // ===== 项目级 AI 助手（2026-10-10 第 50 次 · M1+M2 基础对话 + 世界/角色/宏观讲解 + 双模式）=====
        // assistant_tool：工具决策一步（jsonMode 结构化）——模型判断是否需要查询数据并声明工具调用；
        // 运行时由 AssistantChatService 动态组装 system prompt（含工具清单 JSON 与模式约束），
        // 本模板为版本留痕与「设置-提示词模板」可见（对齐 PromptSpecRegistry 登记 assistant_tool）。
        map.put(CODE_ASSISTANT_TOOL, new BuiltinDef("项目级 AI 助手-工具决策（结构化）",
                """
                你是 HolzynActor 的项目级 AI 助手「世界与你之间的对话入口」的工具决策器。
                根据【可用工具】判断是否需要查询数据：需要 → 只输出 JSON：{"tool_calls":[{"name":"工具名","arguments":{参数}}]}；
                不需要（闲聊/已有足够信息）→ 只输出 JSON：{"no_tool":true}。不要输出任何其他文字。
                如果前一轮的工具结果已包含所需数据，请直接输出 {"no_tool":true} 并据此作答，不要重复调用已查询过的工具。
                工具清单由运行时注入（world_overview/character_state/location_overview/character_profile/
                macro_overview/recent_events/project_guide/search_world_knowledge/search_project_docs）。
                """, "你是项目级 AI 助手的工具决策器：判断是否需要查询世界/知识/文档数据，只输出 JSON 工具调用声明。"));
        // assistant_chat：最终回答（PLAIN_TEXT 流式正文）——工具结果回喂后按当前模式（叙事/事实）组织回答。
        map.put(CODE_ASSISTANT_CHAT, new BuiltinDef("项目级 AI 助手-最终回答（叙事/事实双模式）",
                """
                你是 HolzynActor 的项目级 AI 助手「世界与你之间的对话入口」。
                【零 OOC 铁律】你是元层工具：不进入世界叙事体系，角色不感知你；你只读查询、绝不修改世界状态。
                【叙事模式】以世界叙事组织语言，事实为骨、文学为肉：数值必须来自工具结果，不得编造；
                角色用词与其性格一致；时间用游戏时间；数据缺失明确说「暂无相关记录」。
                【严格事实模式】逐项引用数据源、输出结构化字段与数值并标注来源；不输出精确坐标。
                直接输出回答正文（不输出 JSON 外壳，末尾不附加「来源：」清单）。
                """, "你是项目级 AI 助手：按当前模式（叙事/事实）直接输出回答正文，事实为骨、文学为肉、零 OOC。"));
        // ===== 对谈模式（2026-10-12 第 54 次 · M1 对谈地基）=====
        // dialogue_reply：单聊回复（PLAIN_TEXT 流式正文）——「像真人一样」的促膝长谈；
        // 运行时由 DialogueContextAssembler 动态组装（L0 稳定 system + L1 动态 user），
        // 本模板为版本留痕与「设置-提示词模板」可见（对齐 assistant_* 先例）。
        map.put(CODE_DIALOGUE_REPLY, new BuiltinDef("对谈-单聊回复（流式正文）",
                """
                你是{{character_name}}，正在与{{player_name}}促膝长谈。世界已静止于此刻。
                【角色铁律】你是{{character_name}}，绝不能跳出角色。以第一人称说话，只说自己想说的话：
                1. 不替其他角色（或玩家）发言、不代答、不转述或标注其他角色的台词；
                2. 只回复这一轮，不要替对方做决定，不要跳出角色。
                【世界观】{{world_context}}
                【我的身份与处境】{{identity_context}}
                【与{{player_name}}的关系】{{relation_context}}
                【冻结时刻】{{frozen_context}}
                【相关记忆】{{memory_context}}
                【对话历史】
                {{history}}
                【本轮】{{player_name}}对你说：{{user_message}}
                请直接以{{character_name}}的口吻回复正文（第一人称，像真人一样自然回应）：
                1. 【回复克制·务必简短】像真人聊天一样，一般 1~2 句话（约 60~120 字），动作/神态描写从简或省略，
                不要展开长篇、不要复述前文；确有要事再稍长；
                2. 不要输出任何 JSON 外壳、不要标注角色名、不要替{{player_name}}说话。
                """, "你是对谈中的角色：以第一人称自然回应玩家，回复务必简短（1~2 句话）、像真人促膝长谈，绝不跳出角色。"));
        // dialogue_greeting：AI 动态开场白（PLAIN_TEXT 正文）——新会话角色第一句话（≤80 字）。
        map.put(CODE_DIALOGUE_GREETING, new BuiltinDef("对谈-AI 动态开场白",
                """
                你是{{character_name}}，正在与{{player_name}}促膝长谈。世界已静止于此刻。
                【角色铁律】你是{{character_name}}，绝不能跳出角色。以第一人称说话，只说自己想说的话。
                【世界观】{{world_context}}
                【我的身份与处境】{{identity_context}}
                【与{{player_name}}的关系】{{relation_context}}
                【冻结时刻】{{frozen_context}}
                【场景】{{player_name}}来到了你身边，想与你聊聊天。
                请以{{character_name}}的口吻说一句开场白（第一人称，不超过 80 字，自然亲切，像真人见面打招呼），
                不要输出任何 JSON 外壳、不要标注角色名。
                """, "你是对谈中的角色：对来到身边的玩家说一句自然亲切的开场白（第一人称，≤80 字）。"));
        // player_profile_gen：玩家 persona 六维 AI 补充生成（新用户引导「我的形象」页）——
        // 用户写一段自我介绍 → 生成六维 JSON（identity/background/personality/voice/goals/ability），
        // 各维 1-2 句「可感知的细节」，回填表单预览后由用户确认保存（版本留痕 + 设置页可见）。
        map.put(CODE_PLAYER_PROFILE_GEN, new BuiltinDef("玩家档案-AI 补充生成",
                """
                你是「角色设定师」：根据用户写下的一段自我介绍，帮他/她补全一份真实、有质感的玩家 persona。
                【自我介绍】{{self_intro}}
                请只输出一个 JSON 对象，键严格为 identity / background / personality / voice / goals / ability
                （各自为一段中文文本，1~2 句，具体到可感知的细节，不要泛泛而谈；不要输出 JSON 以外的任何内容）：
                {"identity":"身份（如：云游四方的旅行编辑）","background":"背景经历（一两句）","personality":"性格（如：温和慢热，重承诺）","voice":"说话风格（如：慢条斯理，爱引经据典）","goals":"目标（如：收集九州民间故事）","ability":"能力（如：善写、善辨音）"}
                """, "你是角色设定师：把一段自我介绍扩写为玩家 persona 六维 JSON（identity/background/personality/voice/goals/ability）。"));
        // dialogue_summary（M4 长对话滚动窗口自动摘要）：超窗前段折叠为 AI 摘要——
        // 忠实浓缩前情（关键事实/约定/承诺/情绪），与既有摘要合并；本模板为版本留痕与
        // 「设置-提示词模板」可见（对齐 dialogue_reply/greeting 先例），运行时由
        // DialogueSummaryService 组装输入（既有摘要 + 新增超窗批）。
        map.put(CODE_DIALOGUE_SUMMARY, new BuiltinDef("对谈-超窗自动摘要",
                """
                你是对谈摘要记录员：把对话内容浓缩成简洁的中文摘要（保留关键事实、约定、承诺与情绪变化），
                作为后续对话的前置记忆使用。只输出摘要正文，不要任何前后缀或解释。
                【既有摘要】{{existing_summary}}
                【新增对话】{{new_messages}}
                请输出合并既有摘要后的完整摘要（≤300 字）。
                """, "你是对谈摘要记录员：把超窗对话浓缩成简洁中文摘要（≤300 字），只输出摘要正文。"));
        // dialogue_group_reply（M5 群聊逐角色回复）：每回应独立 AI 调用、复用单聊流式正文形态
        // （实施抓修：设计稿 §8.3 拟 DIALOG_JSON 整轮契约 → PLAIN_TEXT 流式正文，见常量注释）。
        // 运行时由 DialogueGroupChatService 装配（composeGroup），本模板为版本留痕与
        // 「设置-提示词模板」可见（对齐 dialogue_reply/greeting 先例）。
        map.put(CODE_DIALOGUE_GROUP_REPLY, new BuiltinDef("对谈-群聊逐角色回复（流式正文）",
                """
                你是{{character_name}}，正身处一场在场多人的对谈。世界已静止于此刻。
                【角色铁律】你是{{character_name}}，绝不能跳出角色。以第一人称说话，只说自己想说的话：
                1. 不替其他角色（或玩家）发言、不代答、不转述或标注其他角色的台词；
                2. 只回复这一轮，不要替对方做决定，不要跳出角色。
                【世界观】{{world_context}}
                【我的身份与处境】{{identity_context}}
                【与{{player_name}}的关系】{{relation_context}}
                【场景】{{scene_context}}
                【相关记忆】{{memory_context}}
                【对家】{{counterpart_context}}
                【对话历史】
                {{history}}
                【本轮】{{player_name}}对大家说：{{user_message}}
                请以{{character_name}}的口吻回应（第一人称，像真人一样自然、简短地接话，一般一两句话、尽量不超过 100 字），
                可以回应{{player_name}}，也可以接刚才其他角色的话，但不要替其他角色或{{player_name}}说话，
                不要输出任何 JSON 外壳、不要标注角色名。
                """, "你是群聊中的角色：像真人一样简短自然地接话（可回应玩家，也可回应其他角色），绝不跳出角色。"));
        // dialogue_consolidation（M6 沉淀收尾）：关系 delta + 记忆要点抽取——
        // 「沉淀时写什么」是克制的严谨判定：关系变化宁缺毋滥（无明显变化用 0）、记忆要点只写
        // 明确发生的活动/共识/承诺（1~3 条）。运行时由 DialogueConsolidationService 组装
        // （会话角色清单 + 当前关系 + 最近对话窗），jsonMode=true 只输出 relations 数组；
        // 本模板为版本留痕与「设置-提示词模板」可见。
        map.put(CODE_DIALOGUE_CONSOLIDATION, new BuiltinDef("对谈-沉淀（关系 delta + 记忆要点）",
                """
                你是对谈沉淀记录员：从玩家与对谈角色的对话中，为每位对谈角色判定关系变化与值得记住的记忆要点。
                【对谈角色】
                {{characters_context}}
                【玩家】{{player_name}}
                【最近对话】
                {{recent_dialog}}
                【任务】
                从这段对话中，为每位对谈角色抽取关系变化与记忆要点，只输出 JSON：
                {"relations":[{"character_ref":"角色引用","affinity_delta":整数,"trust_delta":整数,"stage":"新关系阶段或保持","note":"备注或省略","state_summary":"跨会话关系状态摘要(≤80字)","memory_points":["要点1","要点2"]}]}
                约束：
                1. affinity_delta/trust_delta 为 -20~20 的整数，无明显变化用 0，不要臆测或大幅波动；
                2. stage 无变化则不填（保持现状）；note/state_summary 无内容可省略；
                3. memory_points 只写明确发生的与玩家的活动/共识/承诺（每条 ≤60 字，1~3 条），宁缺毋滥；
                4. 无实质变化时 relations 可为空数组；只输出 JSON，不要任何其他文字。
                """, "你是对谈沉淀记录员：克制地判定每位对谈角色的关系变化与记忆要点，只输出 JSON。"));
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
    }
}
