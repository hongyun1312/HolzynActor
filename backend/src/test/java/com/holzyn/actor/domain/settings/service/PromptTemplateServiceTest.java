package com.holzyn.actor.domain.settings.service;

import com.holzyn.actor.domain.settings.entity.ActorPromptTemplate;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromptTemplateService 逻辑单元测试（P2 阶段三 + V2.0 项目化）。
 * <p>职责：验证内置模板完整性（13 个模板 + 占位符）与「项目覆盖 > 用户覆盖 > 内置」的合并规则。</p>
 */
class PromptTemplateServiceTest {

    /**
     * 内置模板共 24 个且编码齐全（P4-1 起新增 memory_extract / memory_summarize；V2.1 新增 evolution_orchestrator；
     * vP5-7.6 新增 scene_generate 场景 AI 自动生成；vP5-7.9 新增 evolution_schedule 演化逐拍调度；
     * 角色关系拓扑轮新增 relation_gen 角色关系生成；普通人群重构轮新增 crowd_category_gen 职业分类拟定 /
     * ordinary_npc_gen 居民批量生成 / crowd_schedule_project 项目级调度 / crowd_schedule_affiliation 归属级调度；
     * 2026-08-19 新建项目解析重构轮新增 world_segment 世界观总结分段 / world_segment_characters 角色完整分离 /
     * world_time_infer 世界时间推断；2026-08-21 B3（F3 世界推进引擎）新增 world_event_polish 跨日事件润色；
     * 2026-08-24 D1（F6 动态关系）新增 relation_refine 角色关系 AI 精修）。
     */
    @Test
    void builtinsContainsAllTemplates() {
        var builtins = PromptTemplateService.builtins();
        // O6 G1a（2026-09-21）：新增 persona_bootstrap_fill_a / persona_bootstrap_fill_b 两段式建档模板 → 57 → 59
        // O6 G2（2026-09-21）：新增 growth_space_generate 空间生成 / growth_stage_transition_narrative
        // 阶段跨越叙事 / growth_death_chronicle 死亡编年史叙事 三模板 → 59 → 62
        // O6 G3（2026-09-22）：新增 growth_persona_shift_eval 人格偏移评估模板 → 62 → 63
        // O6 G4（2026-09-22）：新增 growth_romance_decision 心动判定 / growth_proposal_decision 求婚判定 /
        // growth_fertility_decision 生育意愿判定 / growth_newborn_persona 新生儿建档 / growth_wedding_narrative
        // 婚礼叙事 五模板 → 63 → 68
        // N 轨道 N2（2026-09-22）：新增 nanojev_ab_judge NanoJev 判定合理性评分模板 → 68 → 69
        // 工作流适配（2026-09-29_05）：M4 角色工作流新增 worldgen_character_roster 角色阵容设计 /
        // worldgen_character_depth 逐角色深度档案 两模板 → 69 → 71
        // 工作流适配（2026-09-29_06）：M4 story-plan 工作流新增 worldgen_story_plan 故事宏观规划 /
        // worldgen_style_profile 风格档案 两模板 → 71 → 73
        // O6 发育演化（2026-10-02）：新增 growth_stage_behavior 发育阶段行为润色模板 → 73 → 74
        // 自省-行为一致性校验（2026-10-02）：新增 self_check 自省漂移判定模板 → 74 → 75
        // 主动记忆检索（2026-10-02）：新增 proactive_retrieval 判定模板 → 75 → 76
        // 能力自评卡（2026-10-02）：新增 self_efficacy_update 能力自评微调模板 → 76 → 77
        // MUSE 策略切换（2026-10-02）：新增 muse_strategy_check AI 档位复核模板 → 77 → 78
        // 潜在世界预测注入（2026-10-03）：新增 world_outlook 世界走向预测模板 → 78 → 79；
        // 记忆-知识桥接（2026-10-03）：新增 knowledge_bridge 判定模板 → 79 → 80
        // 位置移动与地图生成系统 M1（2026-10-06）：新增 world_map_generate 世界地图生成模板 → 80 → 81
        // 位置移动与地图生成系统 M3（2026-10-06）：新增 move_encounter 途中轻量交互模板 → 81 → 82
        // V2.0 规范化结构段 1A（2026-10-08 外网冒烟）：新增 world_common_knowledge 世界常识摘要模板 → 82 → 83
        // 项目级 AI 助手（2026-10-10 第 50 次）：新增 assistant_tool 工具决策 / assistant_chat 最终回答
        // 两模板 → 83 → 85
        // 对谈模式（2026-10-12 第 54 次 · M1）：新增 dialogue_reply 单聊回复 / dialogue_greeting AI 开场白
        // 两模板 → 85 → 87
        // 对谈 M4（2026-10-12 第 57 次）：新增 dialogue_summary 超窗自动摘要模板 → 87 → 88
        // 对谈 M5（2026-10-13 第 58 次）：新增 dialogue_group_reply 群聊逐角色回复模板 → 88 → 89
        // 对谈 M6（2026-10-13 第 59 次）：新增 dialogue_consolidation 沉淀（关系 delta + 记忆要点）模板 → 89 → 90
        assertEquals(90, builtins.size());
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CARD_GEN));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_DIALOG));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_GROUP));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_EVENT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_ACTION));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_GROUP_REPLY));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PROJECT_IMPORT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PROJECT_IMPORT_CHARACTERS));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PROJECT_IMPORT_FIELD));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MEMORY_EXTRACT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MEMORY_SUMMARIZE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_EVOLUTION));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_SCENE_GENERATE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_EVOLUTION_SCHEDULE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_RELATION_GEN));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_RELATION_REFINE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD_CATEGORY));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD_NPC_GEN));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD_SCHEDULE_PROJECT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CROWD_SCHEDULE_AFFILIATION));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_SEGMENT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_SEGMENT_CHARACTERS));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_TIME_INFER));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_EVENT_POLISH));
        // D2 F7（目标/计划/反思）新增 4 个模板
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_GOAL_GEN));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PLAN_DAY));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PLAN_REPLAN));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_REFLECTION));
        // D4 F12（无玩家群聊轮）新增退场/收尾编排模板
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_AUTONOMOUS_CHAT_BEAT));
        // M2（工作流世界观生成 build 落地）新增 6 个维度模板（worldgen_*）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_CORELAWS));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_SPACETIME));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_SOCIETY));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_HISTORYCULTURE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_EXISTENCE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_INFORMATION));
        // 工作流适配（2026-09-29_05）：M4 角色工作流（worldgen_character_v1）新增 2 模板
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_CHARACTER_ROSTER));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_CHARACTER_DEPTH));
        // M4 story-plan 工作流（2026-09-29_06）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_STORY_PLAN));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLDGEN_STYLE_PROFILE));
        // M3（记忆系统分层重构 L3 核心记忆）新增 core_memory_update 自编辑模板
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CORE_MEMORY_UPDATE));
        // 状态模块 M2（NPC 情绪模型）新增 npc_emotion_eval 情绪 AI 评估模板
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_NPC_EMOTION_EVAL));
        // 状态模块 M3（分层驱动）新增 npc_crowd_decision 人群行动 AI 决策模板（37→38）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_NPC_CROWD_DECISION));
        // 实时行为决策系统 M2（打断与恢复）新增 interrupt_resume 恢复决策模板（38→39）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_INTERRUPT_RESUME));
        // 世界演化与游戏时间系统 M4（每日世界帧体系）新增 world_frame_daily 每日世界帧生成模板（39→40）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_FRAME_DAILY));
        // 世界演化与游戏时间系统 M5（信息扩散与三层过滤）新增 info_distortion 信息认知失真改写模板（40→41）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_INFO_DISTORTION));
        // 实时行为决策系统 M3（个性注入与社交差异化）新增 interrupt_probe 打断前探针模板（41→42）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_INTERRUPT_PROBE));
        // 记忆系统重构 M1（2026-09-12）：memory_extract 重构 + 新增 memory_extract_review 按需复核 /
        // chronicle_distill 编年史提炼 / entity_state_update 实体状态更新判定（42→45）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MEMORY_EXTRACT_REVIEW));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_CHRONICLE_DISTILL));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_ENTITY_STATE_UPDATE));
        // 时间季节系统 M2（2026-09-15）：新增 environment_desc 环境描述润色模板（45→46）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_ENVIRONMENT_DESC));
        // 时间季节系统 M4（2026-09-15）：chronicle_distill 模板加 {{season_context}} 季节上下文段（计数不变）
        assertTrue(builtins.get(PromptTemplateService.CODE_CHRONICLE_DISTILL).template()
                .contains("{{season_context}}"));
        // 宏观社会演化系统 M2（2026-09-15）：新增 macro_event 宏观事件叙事 / macro_impact 重大行动
        // 宏观影响判定 两模板（46→48）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MACRO_EVENT));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MACRO_IMPACT));
        // 宏观社会演化系统 M6.5a/M6.6：新增 meso_directive 中观组织指令 / macro_emergence 二阶涌现
        // 叙事 两模板（48→50）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MESO_DIRECTIVE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MACRO_EMERGENCE));
        // O1 记忆内核扩展（2026-09-16）：新增 memory_fuzz 模糊化 / memory_sleep_merge 睡眠琐事合并 /
        // memory_distill 跨层蒸馏 / memory_tier 注入档位判定 四模板（50→54）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MEMORY_FUZZ));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MEMORY_SLEEP_MERGE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MEMORY_DISTILL));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MEMORY_TIER));
        // O2 决策与协调 P1-3（2026-09-16）：新增 negotiate 冲突二次协商模板（54→55）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_NEGOTIATE));
        // O2.5 行为质量评估 P2-1（2026-09-19）：新增 behavior_judge LLM-as-Judge 评分模板（55→56）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_BEHAVIOR_JUDGE));
        assertTrue(builtins.get(PromptTemplateService.CODE_BEHAVIOR_JUDGE).template().contains("{{stimulus}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_BEHAVIOR_JUDGE).template().contains("{{response}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_BEHAVIOR_JUDGE).template().contains("consistency"));
        // O6 G1a（2026-09-21）：新增 persona_bootstrap_fill_a / persona_bootstrap_fill_b（57→59）
        // ⚠️ 2026-09-22 起本行为历史中间检查点：G2/G3/G4/N2 陆续新增模板后总数已到 69，
        // 工作流适配（2026-09-29_05）：M4 角色工作流新增 2 模板 → 71，
        // 工作流适配（2026-09-29_06）：M4 story-plan 工作流新增 2 模板 → 73，
        // O6 发育演化（2026-10-02）：新增 growth_stage_behavior 发育阶段行为润色模板 → 74，
        // 自省-行为一致性校验（2026-10-02）：新增 self_check 自省漂移判定模板 → 75，
        // 主动记忆检索（2026-10-02）：新增 proactive_retrieval 判定模板 → 76，
        // 能力自评卡（2026-10-02）：新增 self_efficacy_update 能力自评微调模板 → 77，
        // MUSE 策略切换（2026-10-02）：新增 muse_strategy_check AI 档位复核模板 → 78，
        // 潜在世界预测注入（2026-10-03）：新增 world_outlook 世界走向预测模板 → 79 → 80，
        // 位置移动与地图生成系统 M1（2026-10-06）：新增 world_map_generate 世界地图生成模板 → 80 → 81，
        // 位置移动与地图生成系统 M3（2026-10-06）：新增 move_encounter 途中轻量交互模板 → 81 → 82，
        // V2.0 规范化结构段 1A（2026-10-08 外网冒烟）：新增 world_common_knowledge 世界常识摘要模板 → 82 → 83，
        // 项目级 AI 助手（2026-10-10 第 50 次）：新增 assistant_tool / assistant_chat 两模板 → 83 → 85，
        // 对谈模式（2026-10-12 第 54 次 · M1）：新增 dialogue_reply / dialogue_greeting 两模板 → 85 → 87，
        // 对谈 M4（2026-10-12 第 57 次）：新增 dialogue_summary 超窗自动摘要模板 → 87 → 88，
        // 对谈 M5（2026-10-13 第 58 次）：新增 dialogue_group_reply 群聊逐角色回复模板 → 88 → 89，
        // 对谈 M6（2026-10-13 第 59 次）：新增 dialogue_consolidation 沉淀模板 → 89 → 90，
        // 此处的逐段计数断言与最终断言保持一致，避免历史注释计数误导。
        assertEquals(90, builtins.size());
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PERSONA_FILL_A));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PERSONA_FILL_B));
        // 世界常识摘要（2026-10-08）：world_common_knowledge 模板含世界观占位符 + 300-500 字要求
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_COMMON_KNOWLEDGE));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_COMMON_KNOWLEDGE).template()
                .contains("{{world_setting}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_COMMON_KNOWLEDGE).template()
                .contains("300-500"));
        // 项目级 AI 助手（2026-10-10 第 50 次）：assistant_tool 含工具决策 JSON 契约；assistant_chat 含零 OOC 铁律
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_ASSISTANT_TOOL));
        assertTrue(builtins.get(PromptTemplateService.CODE_ASSISTANT_TOOL).template().contains("tool_calls"));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_ASSISTANT_CHAT));
        assertTrue(builtins.get(PromptTemplateService.CODE_ASSISTANT_CHAT).template().contains("零 OOC"));
        // 对谈模式（2026-10-12 第 54 次 · M1）：dialogue_reply 含角色铁律/占位符；dialogue_greeting 含开场白契约
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_DIALOGUE_REPLY));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_REPLY).template().contains("{{character_name}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_REPLY).template().contains("{{user_message}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_REPLY).template().contains("绝不能跳出角色"));
        // 第 61 轮冒烟抓修：回复软约束（简短克制 ≤120 字）已入模板
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_REPLY).template().contains("回复克制"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_REPLY).template().contains("120 字"));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_DIALOGUE_GREETING));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_GREETING).template().contains("开场白"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_GREETING).template().contains("80 字"));
        // 对谈 M4（2026-10-12 第 57 次）：dialogue_summary 超窗自动摘要模板含既有摘要/新增对话占位符 + ≤300 字契约
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_DIALOGUE_SUMMARY));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_SUMMARY).template().contains("{{existing_summary}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_SUMMARY).template().contains("{{new_messages}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_SUMMARY).template().contains("300 字"));
        // 对谈 M5（2026-10-13 第 58 次）：dialogue_group_reply 群聊逐角色回复模板含群聊占位符 + 不替他人说话契约
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_DIALOGUE_GROUP_REPLY));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_GROUP_REPLY).template().contains("{{scene_context}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_GROUP_REPLY).template().contains("{{counterpart_context}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_GROUP_REPLY).template().contains("{{user_message}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_GROUP_REPLY).template().contains("不要替其他角色"));
        // 对谈 M6（2026-10-13 第 59 次）：dialogue_consolidation 沉淀模板含角色清单/最近对话占位符 + JSON 契约
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_DIALOGUE_CONSOLIDATION));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_CONSOLIDATION).template()
                .contains("{{characters_context}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_CONSOLIDATION).template()
                .contains("{{recent_dialog}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_CONSOLIDATION).template()
                .contains("\"relations\""));
        assertTrue(builtins.get(PromptTemplateService.CODE_DIALOGUE_CONSOLIDATION).template()
                .contains("memory_points"));
        // 自省-行为一致性校验（2026-10-02）：self_check 模板含判定输入占位符 + 输出契约
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_SELF_CHECK));
        assertTrue(builtins.get(PromptTemplateService.CODE_SELF_CHECK).template().contains("{{recent_insights}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_SELF_CHECK).template().contains("{{decision}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_SELF_CHECK).template().contains("consistent"));
        // 主动记忆检索判定（2026-10-02）：proactive_retrieval 模板 + 占位符
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PROACTIVE_RETRIEVAL));
        assertTrue(builtins.get(PromptTemplateService.CODE_PROACTIVE_RETRIEVAL).template().contains("{{situation}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_PROACTIVE_RETRIEVAL).template().contains("\"need\""));
        // 能力自评卡（2026-10-02）：self_efficacy_update 模板 + 占位符（当前自评/技能/反思证据）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_SELF_EFFICACY_UPDATE));
        assertTrue(builtins.get(PromptTemplateService.CODE_SELF_EFFICACY_UPDATE).template()
                .contains("{{self_efficacy_current}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_SELF_EFFICACY_UPDATE).template()
                .contains("{{reflection_context}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_SELF_EFFICACY_UPDATE).template()
                .contains("\"self_efficacy\""));
        // MUSE 策略切换（2026-10-02）：muse_strategy_check 模板 + 输入占位符 + 三档位契约
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MUSE_STRATEGY_CHECK));
        assertTrue(builtins.get(PromptTemplateService.CODE_MUSE_STRATEGY_CHECK).template()
                .contains("{{situation}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MUSE_STRATEGY_CHECK).template()
                .contains("{{domain_info}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MUSE_STRATEGY_CHECK).template()
                .contains("{{initial_posture}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MUSE_STRATEGY_CHECK).template()
                .contains("\"posture\""));
        // 潜在世界预测注入（2026-10-03）：world_outlook 模板 + 输入占位符（趋势/宏观简报）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_OUTLOOK));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_OUTLOOK).template()
                .contains("{{trend_summary}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_OUTLOOK).template()
                .contains("{{macro_summary}}"));
        // 记忆-知识桥接（2026-10-03，P1 ②-3）：knowledge_bridge 判定模板 + 输入占位符（档案/洞察）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_KNOWLEDGE_BRIDGE));
        assertTrue(builtins.get(PromptTemplateService.CODE_KNOWLEDGE_BRIDGE).template()
                .contains("{{character_info}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_KNOWLEDGE_BRIDGE).template()
                .contains("{{insights}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_KNOWLEDGE_BRIDGE).template()
                .contains("knowledge_candidates"));
        // 位置移动与地图生成系统 M1（2026-10-06）：world_map_generate 模板 + 输入占位符 + 输出契约
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WORLD_MAP_GENERATE));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_MAP_GENERATE).template()
                .contains("{{world_setting}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_MAP_GENERATE).template()
                .contains("{{locations}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_MAP_GENERATE).template()
                .contains("{{characters}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_MAP_GENERATE).template()
                .contains("{{preset_layout}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_MAP_GENERATE).template()
                .contains("{{scene_size}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_MAP_GENERATE).template()
                .contains("\"members\""));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_MAP_GENERATE).template()
                .contains("regions"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_MAP_GENERATE).template()
                .contains("homes"));
        // 位置移动与地图生成系统 M3（2026-10-06）：move_encounter 模板 + 输入占位符 + 输出契约
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_MOVE_ENCOUNTER));
        assertTrue(builtins.get(PromptTemplateService.CODE_MOVE_ENCOUNTER).template()
                .contains("{{my_name}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MOVE_ENCOUNTER).template()
                .contains("{{my_situation}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MOVE_ENCOUNTER).template()
                .contains("{{other_name}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MOVE_ENCOUNTER).template()
                .contains("\"greet\""));
        assertTrue(builtins.get(PromptTemplateService.CODE_MOVE_ENCOUNTER).template()
                .contains("\"stop\""));
        // O6 G2（2026-09-21）：新增空间生成 + 阶段跨越叙事 + 死亡编年史叙事（59→62）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_SPACE_GENERATE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_STAGE_TRANSITION_NARRATIVE));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_DEATH_CHRONICLE));
        // O6 G3（2026-09-22）：新增人格偏移评估模板（62→63）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PERSONA_SHIFT_EVAL));
        // O6 G4（2026-09-22）：新增 5 个 G4 模板（63→68）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_ROMANCE_DECISION));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_PROPOSAL_DECISION));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_FERTILITY_DECISION));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_NEWBORN_PERSONA));
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_WEDDING_NARRATIVE));
        // N 轨道 N2（2026-09-22）：新增 nanojev_ab_judge NanoJev 判定合理性评分模板（68→69）
        assertTrue(builtins.containsKey(PromptTemplateService.CODE_NANOJEV_AB_JUDGE));
        // G4 判定类模板为 SCHEMA_JSON 契约（NanoJev 序列化对齐）
        assertTrue(builtins.get(PromptTemplateService.CODE_ROMANCE_DECISION).template().contains("attracted"));
        assertTrue(builtins.get(PromptTemplateService.CODE_PROPOSAL_DECISION).template().contains("target_ref"));
        assertTrue(builtins.get(PromptTemplateService.CODE_FERTILITY_DECISION).template().contains("want"));
        // macro_event 模板含输入占位符（子系统/事件类型/指标/规则草稿）
        assertTrue(builtins.get(PromptTemplateService.CODE_MACRO_EVENT).template().contains("{{macro_type}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MACRO_EVENT).template().contains("{{event_type}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MACRO_EVENT).template().contains("{{rule_draft}}"));
        // macro_impact 模板含行动输入占位符（行动者/行动/世界观背景）
        assertTrue(builtins.get(PromptTemplateService.CODE_MACRO_IMPACT).template().contains("{{npc_name}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MACRO_IMPACT).template().contains("{{action_text}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MACRO_IMPACT).template().contains("{{world_setting}}"));
        // meso_directive 模板含组织/事件输入占位符
        assertTrue(builtins.get(PromptTemplateService.CODE_MESO_DIRECTIVE).template().contains("{{group_name}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MESO_DIRECTIVE).template().contains("{{event_type}}"));
        // macro_emergence 模板含涌现输入占位符
        assertTrue(builtins.get(PromptTemplateService.CODE_MACRO_EMERGENCE).template().contains("{{emergence_type}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_MACRO_EMERGENCE).template().contains("{{member_count}}"));
    }

    /**
     * 内置模板内容含占位符（可由 render 替换）。
     */
    @Test
    void builtinsContainPlaceholders() {
        var builtins = PromptTemplateService.builtins();
        assertTrue(builtins.get(PromptTemplateService.CODE_CARD_GEN).template().contains("{{world_setting}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_GROUP).template().contains("{{members_summary}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_WORLD_EVENT).template().contains("{{world_setting}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_ACTION).template().contains("{{situation}}"));
        // 主动记忆检索（2026-10-03）：action_gen / reflection 模板新增 {{proactive_memory}} 占位符
        assertTrue(builtins.get(PromptTemplateService.CODE_ACTION).template().contains("{{proactive_memory}}"));
        // MUSE 策略切换（2026-10-02）：action_gen 模板新增 {{strategy_preference}} 占位符
        assertTrue(builtins.get(PromptTemplateService.CODE_ACTION).template().contains("{{strategy_preference}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_CROWD).template().contains("{{crowd_config}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_CROWD).template().contains("{{hour}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_RELATION_GEN).template().contains("{{world_setting}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_RELATION_GEN).template().contains("{{roster}}"));
        assertTrue(builtins.get(PromptTemplateService.CODE_RELATION_GEN).template().contains("{{task_requirement}}"));
        // 自省-行为一致性校验（2026-10-02）：反思模板含自省漂移闭环输入占位符
        assertTrue(builtins.get(PromptTemplateService.CODE_REFLECTION).template().contains("{{self_drift_summary}}"));
        // 主动记忆检索（2026-10-03）：reflection 模板新增 {{proactive_memory}} 占位符
        assertTrue(builtins.get(PromptTemplateService.CODE_REFLECTION).template().contains("{{proactive_memory}}"));
    }

    /**
     * 内置模板含系统提示词（2026-08-19：生成类模板的系统提示词随模板落库，代码不再硬编码）。
     */
    @Test
    void builtinsCarrySystemMessage() {
        var builtins = PromptTemplateService.builtins();
        // O4 Prompt 工程（2026-09-21）：JSON 类系统提示词统一追加全大写强制句
        // （DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.）；「数组」变体保持原文（渲染期规范化统一）
        assertEquals("你只输出严格的 JSON 数组，不输出任何其他文字。",
                builtins.get(PromptTemplateService.CODE_CROWD_NPC_GEN).systemMessage());
        assertEquals("你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.",
                builtins.get(PromptTemplateService.CODE_CROWD_CATEGORY).systemMessage());
        assertEquals("你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.",
                builtins.get(PromptTemplateService.CODE_CROWD_SCHEDULE_PROJECT).systemMessage());
        assertEquals("你只输出严格的 JSON 数组，不输出任何其他文字。",
                builtins.get(PromptTemplateService.CODE_CROWD_SCHEDULE_AFFILIATION).systemMessage());
        assertEquals("你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.",
                builtins.get(PromptTemplateService.CODE_RELATION_GEN).systemMessage());
    }

    /**
     * 居民生成模板含性别铁律（2026-08-19：性别只允许 男/女/无性，禁止雄雌与括号补充说明）。
     */
    @Test
    void ordinaryNpcGenContainsGenderRule() {
        String tpl = PromptTemplateService.builtins().get(PromptTemplateService.CODE_CROWD_NPC_GEN).template();
        assertTrue(tpl.contains("男 / 女 / 无性"), "模板应限定性别取值为 男/女/无性");
        assertTrue(tpl.contains("禁止 雄性/雌性"), "模板应禁止 雄性/雌性 表述");
        assertTrue(tpl.contains("禁止在性别后附加括号"), "模板应禁止性别括号补充");
    }

    /**
     * 合并规则：用户覆盖覆盖同编码内置，其余保留内置。
     */
    @Test
    void mergeEffectiveOverrideWins() {
        ActorPromptTemplate builtin = new ActorPromptTemplate();
        builtin.setUserId(0L); builtin.setCode("dialog_system"); builtin.setName("内置对话"); builtin.setTemplate("内置内容");
        ActorPromptTemplate override = new ActorPromptTemplate();
        override.setUserId(5L); override.setCode("dialog_system"); override.setName("用户对话"); override.setTemplate("用户覆盖内容");
        ActorPromptTemplate other = new ActorPromptTemplate();
        other.setUserId(0L); other.setCode("action_gen"); other.setName("行动"); other.setTemplate("内置行动");

        List<ActorPromptTemplate> merged = PromptTemplateService.mergeEffective(List.of(builtin, other), List.of(override), List.of());
        assertEquals(2, merged.size());
        ActorPromptTemplate dialog = merged.stream().filter(t -> "dialog_system".equals(t.getCode())).findFirst().orElseThrow();
        assertEquals("用户覆盖内容", dialog.getTemplate());
        assertTrue(merged.stream().anyMatch(t -> "action_gen".equals(t.getCode())));
    }

    /**
     * 合并规则（V2.0 项目化）：项目级覆盖 > 用户覆盖 > 内置。
     */
    @Test
    void mergeEffectiveProjectOverrideWins() {
        ActorPromptTemplate builtin = new ActorPromptTemplate();
        builtin.setUserId(0L); builtin.setCode("dialog_system"); builtin.setName("内置对话"); builtin.setTemplate("内置内容");
        ActorPromptTemplate userOverride = new ActorPromptTemplate();
        userOverride.setUserId(5L); userOverride.setCode("dialog_system"); userOverride.setName("用户对话"); userOverride.setTemplate("用户覆盖内容");
        ActorPromptTemplate projectOverride = new ActorPromptTemplate();
        projectOverride.setUserId(5L); projectOverride.setProjectId(10L); projectOverride.setCode("dialog_system"); projectOverride.setName("项目对话"); projectOverride.setTemplate("项目覆盖内容");

        List<ActorPromptTemplate> merged = PromptTemplateService.mergeEffective(
                List.of(builtin), List.of(userOverride), List.of(projectOverride));
        assertEquals(1, merged.size());
        assertEquals("项目覆盖内容", merged.get(0).getTemplate());
    }
}