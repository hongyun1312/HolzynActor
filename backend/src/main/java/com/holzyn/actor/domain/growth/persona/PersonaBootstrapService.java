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

package com.holzyn.actor.domain.growth.persona;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.prompt.PromptSpecRegistry;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.growth.config.GrowthPersonaProperties;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.npc.relation.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldClockRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 角色档案 AI 两段式建档服务。
 * <p><b>职责</b>：给一个<b>已存在</b>{@code actor_profile} 行的 NPC，用两段式 AI 生成完整 11 维档案并落库。
 * 这是"AI 全量生成 20 人 11 维档案"的核心链路（青禾村基线世界），也是世界创建期"创意发散生成全量初始化世界"
 * 与存量迁移补全的共用通道——<b>全系统只有这一条 AI 建档链路</b>（旧的 {@code character_card_gen} 角色卡
 * Schema 已在 废弃，模板已迁到 v2 输出契约；{@code CharacterCardService} 的生成路径应 delegate 到本服务）。</p>
 * <p><b>两段式分工</b>：</p>
 * <ul>
 * <li><b>段 A</b>（{@code persona_bootstrap_fill_a}）→ ①身份与社会、②外貌与体质、④气质与人格、
 * ⑤价值观与动机、⑨经历与创伤；</li>
 * <li><b>段 B</b>（{@code persona_bootstrap_fill_b}）→ ③生理基线、⑥能力与知识、⑦语言与表达、
 * ⑧习惯与日常、⑩发育·遗传·谱系、⑪社会面具。</li>
 * </ul>
 * <p>为什么要分段：一次让模型产出 11 维会同时触发"上下文锚定"与"个体发明"两种相反的要求，
 * 输出会被压扁成泛泛之谈；分段后每段只带一种主导要求，
 * 且单段可独立重试（段 A 失败不影响其它 NPC，段 B 失败可单独补跑）。</p>
 * <p><b>分层约束（"受控幻觉"，Prompt 内显式传达两种强度）</b>：</p>
 * <ul>
 * <li><b>非耦合维</b>（②外貌体质、④人格、⑦语言、⑪面具）→ <b>鼓励大胆创造</b>，具体到可感知细节
 * （疤痕/老花/口音/称呼/小动作/面具切换阈值）——这是"个体辨识度"的唯一来源；</li>
 * <li><b>耦合维</b>（①身份与社会、③生理基线、⑥能力知识、⑧习惯日常、⑨经历、⑩发育谱系）→
 * <b>严格锚定既有模块数据</b>：① 贴世界观 + 角色主表结构列 + 地点表；③ 与状态模块生理参数口径一致
 * （睡眠/代谢/餐制取值在合理范围，且<b>说明它是 per-NPC 参数来源</b>）；⑥ 落在世界观允许的知识范围内
 * （不得出现超出时代的技术/概念）；⑧ 与计划模块的日程粒度兼容；⑨ 能挂到记忆/编年史上；
 * ⑩ 与关系的谱系类型兼容。</li>
 * </ul>
 * <p>约束强度由模板正文承担（模板是本规则的唯一载体），本类负责把"锚定材料"（世界观/骨架/锚定文本/
 * 地点/关系/段 A 结果）如实喂进去——<b>模型只能锚定它看得见的东西</b>。</p>
 * <p><b>落库路径（唯一）</b>：一律
 * 由它按 {@code _sources} 分派：③ 生理基线 → {@code actor_state.physio_json}（并立即换算生效系数）、
 * ⑤ 目标 → {@code actor_goal}、⑥ 知识领域/盲区 → {@code actor_knowledge_doc}，其余 PERSONA 字段留在
 * {@code actor_profile.persona_json}。<b>本类绝不自己拼 persona_json</b>（一旦自拼就会出现"两套落库"，
 * 生理基线/目标/知识立刻产生双源）。</p>
 * <p><b>零阻断</b>：AI 超时、返回非法 JSON、指定维度全空、角色行不存在、savePersona 未生效——
 * 一律 {@code log.warn} 后返回 {@code null}，<b>不抛异常</b>（建档是批处理的一环，单个 NPC 的模型抖动
 * 不允许打断整批 20 人）。解析失败的原始输出会截断 500 字符记 warn，便于定位是模板问题还是模型问题。</p>
 * <p><b>事务说明</b>：本类公开方法<b>不加</b>{@code @Transactional}——两段 AI 调用耗时可达数十秒，
 * 事务内持连接会堵住世界 tick 与其它写库；落库由 {@link PersonaService#savePersona} 自身的短事务承担。
 * 因此"段 A 已落库、段 B 失败"是可能的中间态，这是刻意的（保留已得维度，符合 §7 的 partial 口径），
 * 可单独重跑 {@link #fillB} 补齐。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaBootstrapService {

    /** 段 A 场景码（= Prompt 模板编码 = PromptSpecRegistry 登记码 = AiCallTrace scene） */
    public static final String SCENE_FILL_A = "persona_bootstrap_fill_a";
    /** 段 B 场景码（同上；三段口径统一是"改模板必须同步登记"的前提） */
    public static final String SCENE_FILL_B = "persona_bootstrap_fill_b";

    /** 落库来源标记（PersonaService.savePersona 的 source 入参；与 manual/edited 区分写入审计） */
    private static final String SOURCE_GENERATED = "generated";
    /** AI 补全缺失维入口的落库来源标记（与整段重生成区分，审计可回溯"只补缺"路径） */
    private static final String SOURCE_COMPLETE_DIMS = "complete_dims";
    /** 档案 generated_by 取值（JSON Schema 枚举：ai_bootstrap） */
    private static final String GENERATED_BY = "ai_bootstrap";

    /** 单段最大尝试次数（§7：失败 → 单段重试，最多 2 次；仍失败保留已得维度、不阻断） */
    private static final int MAX_RETRY = 2;

    /** 解析失败时原始 AI 输出的日志截断长度（超出部分丢弃，避免日志爆行） */
    private static final int RAW_LOG_LIMIT = 500;

    /** 段 A 负责的维度（与模板输出契约严格一致；用于"维度全空"判定） */
    private static final List<String> DIMS_A = List.of(
            PersonaSchema.Dims.IDENTITY, PersonaSchema.Dims.APPEARANCE, PersonaSchema.Dims.PERSONALITY,
            PersonaSchema.Dims.VALUES, PersonaSchema.Dims.EXPERIENCE);

    /** 段 B 负责的维度（同上） */
    private static final List<String> DIMS_B = List.of(
            PersonaSchema.Dims.PHYSIO, PersonaSchema.Dims.ABILITY, PersonaSchema.Dims.VOICE,
            PersonaSchema.Dims.HABITS, PersonaSchema.Dims.DEVELOPMENT, PersonaSchema.Dims.MASK);

    // ==================== 喂给模型的外部材料截断上限（控 Prompt 体量，超出部分宁缺勿爆） ====================

    /** 世界观自由文本上限 */
    private static final int WORLD_MAX = 4000;
    /** 锚定文本（开发者已写内容）上限 */
    private static final int ANCHOR_MAX = 4000;
    /** 骨架（结构列）上限 */
    private static final int SKELETON_MAX = 1200;
    /** 地点清单上限 */
    private static final int LOCATION_MAX = 800;
    /** 关系清单上限 */
    private static final int RELATION_MAX = 1200;
    /** 段 A 结果回灌段 B 的上限 */
    private static final int PERSONA_A_MAX = 6000;

    /** 锚定文本缺失时进 Prompt 的占位提示（"没有锚定"本身也是一条必须传达给模型的约束） */
    private static final String ANCHOR_MISSING_HINT =
            "（开发者未提供锚定文本：本角色只有骨架结构列，请在骨架与世界观允许的范围内合理构建，"
                    + "不得发明影响他人或世界史的经历）";

    /** 角色统一主表仓库（读结构列/锚定文本/当前档案版本） */
    private final ActorCharacterRepository characterRepository;
    /** 世界观仓库（① 的锚定依据） */
    private final ActorWorldSettingRepository worldSettingRepository;
    /** 地点仓库（① 的地名/活动范围的锚定依据） */
    private final WorldLocationRepository worldLocationRepository;
    /** 社会关系仓库（⑨ 经历与 ⑩ 谱系的兼容依据） */
    private final ActorCharacterRelationRepository relationRepository;
    /** 世界时钟仓库（只读：记录生成时的世界时刻，供审计回溯） */
    private final ActorWorldClockRepository worldClockRepository;
    /** 档案服务（唯一的落库出口：按 _sources 分派 + 版本自增 + 落库前剥离模块权威维） */
    private final PersonaService personaService;
    /** 档案校验器 */
    private final PersonaSchemaValidator personaSchemaValidator;
    /** Prompt 模板服务（内置模板 + 三级覆盖解析 + {{占位符}} 渲染） */
    private final PromptTemplateService promptTemplateService;
    /** AI 网关（与全项目同一套调用入口，不新写 HTTP 通道） */
    private final AiSpringChatGateway aiChatGateway;
    /** 当前用户（模板覆盖解析与用量归属） */
    private final CurrentUserProvider currentUserProvider;
    /** 用量日志（token 消耗入账；AI 调用成功即记，失败重试各记一条） */
    private final UsageLogService usageLogService;
    /** 建档幂等配置（默认 true=非 force 路径复用既有档案，基线还原不再重跑 43 次引导） */
    private final GrowthPersonaProperties growthPersonaProperties;

    //
    // 公开 API
    //

    /**
     * 两段式建档（完整链路）：段 A → 落库 → 段 B → 合并落库。
     * <p>失败口径（零阻断）：</p>
     * <ul>
     * <li>角色行不存在 / 段 A 两次都拿不到有效输出 → 未产生任何写入，返回 {@code null}；</li>
     * <li>段 A 成功、段 B 失败 → <b>段 A 的档案已落库</b>（保留已得维度），但本方法仍返回 {@code null}
     * （对调用方而言"本次未完成建档"），可稍后单独调用 {@link #fillB} 补齐；</li>
     * <li>任何异常都不外抛。</li>
     * </ul>
     *
     * @param projectId 项目 ID（可空；为空时以角色行自身的 {@code project_id} 为准）
     *
     * @param profileId actor_profile.id（统一身份 {@code npc:{id}} 的数字部分）
     *
     * @param anchorText 锚定文本（开发者已写的角色档案文本；为空时自动回退 {@code actor_profile.detail}）
     *
     * @return 落库后的完整档案（回读态，③ 维已按契约归属 {@code actor_state} 而不在档案内）；
     * 两段未全部成功时返回 {@code null}
     */
    public PersonaProfile bootstrap(Long projectId, Long profileId, String anchorText) {
        return bootstrap(projectId, profileId, anchorText, false);
    }

    /**
     * 两段式建档（完整链路）——force 变体。
     * <p><b>force=false（默认，批处理/启动补档路径）</b>：受 {@code GrowthPersonaProperties#bootstrapIdempotent}
     * 幂等开关约束——段 A / 段 B 各自在该段维度已全覆盖（含模块投影）时<b>跳过 AI 调用</b>、直接复用既有档案。
     * 这是 待收口项③的落地：恢复基线启动 / 世界初始化补档不再重跑 43 次人格引导
     * （avg 72–96s、与世界启动并发），档案已完整的角色一律复用基线既有 11 维人格。</p>
     * <p><b>force=true（显式"重新生成"路径）</b>：无视幂等开关，两段强制重跑（编辑器「重新生成」按钮）。</p>
     * <p>失败口径与 {@link #bootstrap(Long, Long, String)} 完全一致（零阻断）。</p>
     *
     * @param projectId 项目 ID（可空；为空时以角色行自身的 {@code project_id} 为准）
     *
     * @param anchorText 锚定文本（可空，回退 {@code actor_profile.detail}）
     *
     * @param force true=强制重跑（忽略幂等跳过）；false=档案已覆盖则复用
     *
     * @return 落库后的完整档案；两段未全部成功时返回 {@code null}
     */
    public PersonaProfile bootstrap(Long projectId, Long profileId, String anchorText, boolean force) {
        if (profileId == null) {
            log.warn("[AI建档] 参数非法（profileId 为空），已跳过");
            return null;
        }
        long start = System.currentTimeMillis();
        PersonaProfile afterA = fillSegment(projectId, profileId, anchorText, true, force, Set.of(), SOURCE_GENERATED);
        if (afterA == null) {
            log.warn("[AI建档] 段 A 未产出有效档案，本次建档中止（零阻断）：profileId={}", profileId);
            return null;
        }
        PersonaProfile afterB = fillSegment(projectId, profileId, anchorText, false, force, Set.of(), SOURCE_GENERATED);
        if (afterB == null) {
            log.warn("[AI建档] 段 B 失败，段 A 的 5 维已落库（可单独重跑 fillB 补齐）：profileId={} 耗时={}ms",
                    profileId, System.currentTimeMillis() - start);
            return null;
        }
        PersonaProfile stored = personaService.profileOf(profileId);
        PersonaProfile result = stored == null ? afterB : stored;
        log.info("[AI建档] 两段式建档完成：profileId={} version={} 维度覆盖={}/11 耗时={}ms",
                profileId, result.getPersonaVersion(),
                personaService.validate(profileId).getCoveredDimCount(), System.currentTimeMillis() - start);
        return result;
    }

    /**
     * 段 A（①身份与社会、②外貌与体质、④气质与人格、⑤价值观与动机、⑨经历与创伤）——单段执行并落库。
     * <p>落库走 {@link PersonaService#savePersona}：本段产出的 ⑤ 目标会分派到 {@code actor_goal}，
     * 其余 PERSONA 字段留在 {@code persona_json}。</p>
     * <p><b>单独调用会留下"只有 5 维"的中间态档案</b>（版本已自增）——这是分段调试与重试的刻意设计，
     * 生产批量建档请用 {@link #bootstrap} 或 {@link #bootstrapBatch}（两段都成功才算完成）。</p>
     *
     * @param projectId 项目 ID（可空，以角色行为准）
     *
     * @param anchorText 锚定文本（可空，回退 {@code actor_profile.detail}）
     *
     * @return 落库后的档案（回读态）；未取得有效输出时返回 {@code null}
     */
    public PersonaProfile fillA(Long projectId, Long profileId, String anchorText) {
        return fillSegment(projectId, profileId, anchorText, true, false, Set.of(), SOURCE_GENERATED);
    }

    /**
     * 段 B（③生理基线、⑥能力与知识、⑦语言与表达、⑧习惯与日常、⑩发育·遗传·谱系、⑪社会面具）——
     * 单段执行并落库。
     * <p><b>段 B 的输入 = 第一段结果 + 同一骨架</b>：档案内已有的 ① ② ④ ⑤ ⑨ 会作为
     * {@code persona_a_json} 回灌 Prompt，模型据此保持年龄/职业/体质/创伤的一致性；若档案内尚无段 A 的维度，
     * 会记 warn 但仍继续（模型只能靠骨架与世界观对齐，锚定强度下降）。</p>
     * <p>落库走 {@link PersonaService#savePersona}：③ 生理基线写入 {@code actor_state}（per-NPC 参数 + 生效系数）、
     * ⑥ 知识领域/盲区写入 {@code actor_knowledge_doc}，其余字段落在 {@code persona_json}。</p>
     *
     * @param projectId 项目 ID（可空，以角色行为准）
     *
     * @param anchorText 锚定文本（可空，回退 {@code actor_profile.detail}）
     *
     * @return 落库后的档案（回读态）；未取得有效输出时返回 {@code null}
     */
    public PersonaProfile fillB(Long projectId, Long profileId, String anchorText) {
        return fillSegment(projectId, profileId, anchorText, false, false, Set.of(), SOURCE_GENERATED);
    }

    /**
     * AI 补全缺失维。
     * <p><b>语义</b>：只对"真正缺失"的维发起补全，<b>已覆盖的维原样保留</b>（不被新 AI 输出覆盖）——
     * 与 {@link #bootstrap} 的"整段重生成"不同，这是把 11 维档案逐维补到无空维的最小代价路径。</p>
     * <p><b>缺失判定</b>：{@code PersonaService.validate} 的 11 维覆盖率——{@code stored}（档案已存）与
     * {@code projected}（模块可投影，如 ③ 生理基线在 {@code actor_state}）都算"已覆盖"，
     * 只有 {@code missing} 才是真空维。缺哪些维就只跑覆盖它的那一段（段 A：① ② ④ ⑤ ⑨；段 B：③ ⑥ ⑦ ⑧ ⑩ ⑪），
     * 且该段内<b>未缺失的维在合并前回写原值</b>（preserveDims）——AI 输出只允许填缺，不允许擅自改已有内容。</p>
     * <p><b>幂等与零阻断</b>：无缺失维 → 直接返回当前档案、零 AI 调用；段失败 → 保留已得维度、返回 null（可重试）；
     * 任何异常不外抛。含 AI 调用，本方法<b>不带 {@code @Transactional}</b>（架构不变量），落库由 savePersona 短事务承担。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 补全后的档案（回读态）；无缺失返回当前档案；全失败返回 null
     */
    public PersonaProfile completeMissingDims(Long projectId, Long profileId) {
        return completeDims(projectId, profileId, PersonaSchema.ALL_DIMS);
    }

    /**
     * AI 补全缺失维 · 限定维集变体。
     * <p><b>语义</b>：只对 {@code targetDims} 中"真正缺失"的维发起补全；段内其它维（含已覆盖）原样保留。
     * 用于新生儿成长档案逐步揭示：出生只建 ①②③④⑩ 维，TODDLER 揭示 ⑤⑦⑧、CHILD 揭示 ⑥、
     * ADOLESCENT 揭示 ⑨⑪——每个阶段只填"这个年纪该显现的内容"，绝不提前给出完整的成人人格。</p>
     *
     * @param projectId 项目 ID
     *
     * @param targetDims 本次允许补全的维键集（仅处理其中的缺失维）
     *
     * @return 补全后的档案；无缺失返回当前档案；失败返回 null（零阻断）
     */
    public PersonaProfile completeDims(Long projectId, Long profileId, List<String> targetDims) {
        if (profileId == null || targetDims == null || targetDims.isEmpty()) {
            return personaService.profileOrEmpty(profileId);
        }
        PersonaProfile current = personaService.profileOrEmpty(profileId);
        PersonaRenderContext ctx = personaService.contextOf(profileId);
        Set<String> missing = new LinkedHashSet<>();
        for (String dim : targetDims) {
            if (!dimCovered(current, ctx, dim)) {
                missing.add(dim);
            }
        }
        if (missing.isEmpty()) {
            log.info("[AI补全-限定] 指定维 {} 已全覆盖，无需补全（零 AI 调用）：profileId={}", targetDims, profileId);
            return current;
        }
        Set<String> missingA = new LinkedHashSet<>();
        Set<String> missingB = new LinkedHashSet<>();
        for (String dim : missing) {
            if (DIMS_A.contains(dim)) {
                missingA.add(dim);
            } else if (DIMS_B.contains(dim)) {
                missingB.add(dim);
            }
        }
        // 保留段内非目标的已覆盖维（只许填缺）
        Set<String> preserveA = preserveWithin(DIMS_A, missingA, current);
        Set<String> preserveB = preserveWithin(DIMS_B, missingB, current);
        long start = System.currentTimeMillis();
        log.info("[AI补全-限定] 缺失维 {}（A 段 {}，B 段 {}）：profileId={}", missing.size(), missingA, missingB, profileId);
        if (!missingA.isEmpty()) {
            PersonaProfile afterA = fillSegment(projectId, profileId, null, true, false, preserveA,
                    SOURCE_COMPLETE_DIMS, missingA);
            if (afterA == null) {
                log.warn("[AI补全-限定] 段 A 补全失败（保留已得维度，可重试）：profileId={}", profileId);
                return null;
            }
        }
        if (!missingB.isEmpty()) {
            PersonaProfile afterB = fillSegment(projectId, profileId, null, false, false, preserveB,
                    SOURCE_COMPLETE_DIMS, missingB);
            if (afterB == null) {
                log.warn("[AI补全-限定] 段 B 补全失败（保留已得维度，可重试）：profileId={}", profileId);
                return null;
            }
        }
        PersonaProfile result = personaService.profileOf(profileId);
        log.info("[AI补全-限定] 完成：profileId={} 补全维度={} 覆盖={}/11 耗时={}ms",
                profileId, missing.size(),
                personaService.validate(profileId).getCoveredDimCount(), System.currentTimeMillis() - start);
        return result;
    }

    /** 段内"需保留原值"的维 = 段内所有维 − 本次缺失维（AI 只许填缺）。 */
    private Set<String> preserveWithin(List<String> segmentDims, Set<String> missingInSegment,
                                       PersonaProfile current) {
        Set<String> preserve = new LinkedHashSet<>();
        for (String dim : segmentDims) {
            if (!missingInSegment.contains(dim) && current.getDims() != null && current.getDims().has(dim)) {
                preserve.add(dim);
            }
        }
        return preserve;
    }

    /**
     * 批量建档（顺序执行、逐个落库、单个失败不影响其余）。
     * <p>用途：青禾村 20 人基线建档 / 存量 NPC 批量补全。串行是刻意的——AI 通道有并发与预算门控，
     * 而且串行能让"每个 NPC 一个版本号"的落库顺序稳定可回溯。每个 NPC 结束后回调一次进度
     * （供 SSE/前端进度条），回调自身抛异常不影响后续建档。</p>
     *
     * @param projectId 项目 ID
     *
     * @param profileIds 待建档的 actor_profile.id 列表（可空/空列表 → 直接返回 true）
     *
     * @param progress 进度回调：{@code accept(已完成序号(1 起), profileId)}，每处理完一个（含失败）调一次；可空
     *
     * @return true=全部成功；false=至少一个失败（失败原因见日志，不中断批处理）
     */
    public boolean bootstrapBatch(Long projectId, List<Long> profileIds, BiConsumer<Integer, Long> progress) {
        if (profileIds == null || profileIds.isEmpty()) {
            log.info("[AI建档-批量] 待建档列表为空，跳过：projectId={}", projectId);
            return true;
        }
        long start = System.currentTimeMillis();
        int ok = 0;
        int index = 0;
        log.info("[AI建档-批量] 任务开始：projectId={} 目标={} 人", projectId, profileIds.size());
        for (Long profileId : profileIds) {
            index++;
            boolean success = false;
            long itemStart = System.currentTimeMillis();
            try {
                success = bootstrap(projectId, profileId, null) != null;
            } catch (Exception e) {
                // bootstrap 自身零阻断，此处是最后一道保险：批量链路绝不因单人行中断
                log.warn("[AI建档-批量] 第 {} 人建档异常（已跳过、不中断批处理）：profileId={} 原因={}",
                        index, profileId, e.getMessage());
            }
            if (success) {
                ok++;
            }
            log.info("[AI建档-批量] 进度 {}/{}：profileId={} 结果={} 耗时={}ms",
                    index, profileIds.size(), profileId, success ? "成功" : "失败/跳过",
                    System.currentTimeMillis() - itemStart);
            if (progress != null) {
                try {
                    progress.accept(index, profileId);
                } catch (Exception e) {
                    log.warn("[AI建档-批量] 进度回调异常（已忽略）：{}", e.getMessage());
                }
            }
        }
        log.info("[AI建档-批量] 任务结束：projectId={} 成功={}/{} 耗时={}ms",
                projectId, ok, profileIds.size(), System.currentTimeMillis() - start);
        return ok == profileIds.size();
    }

    //
    // 单段执行：组装锚定材料 → AI → 解析 → 归一化 → 合并 → 落库
    //

    /**
     * 单段执行的完整链路（段 A / 段 B 共用；scene 与输出维度集由 isSegmentA 决定）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param anchorText 锚定文本（可空）
     *
     * @param isSegmentA true=段 A；false=段 B
     *
     * @param force true=强制重跑（忽略幂等跳过）；false=该段维度已全覆盖则复用既有档案
     *
     * @param preserveDims 该段内需保留原值的维键集（AI 补全缺失维用：已覆盖维不允许被新 AI 输出覆盖；空=不保留）
     *
     * @param source 落库来源标记（generated=整段重生成；complete_dims=AI 补全缺失维）
     *
     * @return 落库后的档案（回读态）；失败返回 null（零阻断）
     */
    private PersonaProfile fillSegment(Long projectId, Long profileId, String anchorText, boolean isSegmentA,
                                       boolean force, Set<String> preserveDims, String source) {
        return fillSegment(projectId, profileId, anchorText, isSegmentA, force, preserveDims, source, null);
    }

    /**
     * 单段执行的完整链路（段 A / 段 B 共用；scene 与输出维度集由 isSegmentA 决定）。
     *
     * @param projectId 项目 ID（可空）
     *
     * @param anchorText 锚定文本（可空）
     *
     * @param isSegmentA true=段 A；false=段 B
     *
     * @param force true=强制重跑（忽略幂等跳过）；false=该段维度已全覆盖则复用既有档案
     *
     * @param preserveDims 该段内需保留原值的维键集（AI 补全缺失维用：已覆盖维不允许被新 AI 输出覆盖；空=不保留）
     *
     * @param source 落库来源标记（generated=整段重生成；complete_dims=AI 补全缺失维）
     *
     * @param targetDims 限定本次允许补全的维键集（null=段内全部维； 分阶段揭示用：
     * 只填指定维，AI 输出的其它维在合并前被剔除，绝不提前给出完整成人人格）
     *
     * @return 落库后的档案（回读态）；失败返回 null（零阻断）
     */
    private PersonaProfile fillSegment(Long projectId, Long profileId, String anchorText, boolean isSegmentA,
                                       boolean force, Set<String> preserveDims, String source,
                                       Set<String> targetDims) {
        String scene = isSegmentA ? SCENE_FILL_A : SCENE_FILL_B;
        if (profileId == null) {
            log.warn("[AI建档] {} 参数非法（profileId 为空），已跳过", scene);
            return null;
        }
        ActorCharacter ch = characterRepository.findById(profileId).orElse(null);
        if (ch == null || (ch.getDeleted() != null && ch.getDeleted() != 0)) {
            log.warn("[AI建档] {} 角色行不存在或已删除，已跳过：profileId={}", scene, profileId);
            return null;
        }
        Long pid = ch.getProjectId();
        if (projectId != null && pid != null && !projectId.equals(pid)) {
            log.warn("[AI建档] {} 传入 projectId={} 与角色行归属 {} 不一致，以角色行为准：profileId={}",
                    scene, projectId, pid, profileId);
        }
        List<String> dims = targetDims == null || targetDims.isEmpty()
                ? (isSegmentA ? DIMS_A : DIMS_B)
                : targetDims.stream().filter(d -> (isSegmentA ? DIMS_A : DIMS_B).contains(d)).toList();
        // 段 B 的基底 = 档案内现有形态（段 A 的 5 维 + 默认来源声明）
        PersonaProfile base = isSegmentA ? PersonaProfile.empty(pid, profileId) : personaService.profileOrEmpty(profileId);
        if (!isSegmentA && !hasAnyDimContent(base, DIMS_A)) {
            log.warn("[AI建档] {} 档案内尚无段 A 的 5 维（① ② ④ ⑤ ⑨），本次仅靠骨架与世界观对齐，"
                    + "锚定强度下降：profileId={}（建议先跑 fillA）", scene, profileId);
        }
        // 幂等跳过（待收口项③）：非 force 且开关打开、且本段维度已全覆盖（含模块投影）→ 复用既有档案，零 AI 调用。
        // 这是"恢复基线启动不再重跑 43 次人格引导"的机制层落地：基线档案既有 11 维人格被直接复用。
        if (!force && growthPersonaProperties.isBootstrapIdempotent()
                && ch.getPersonaVersion() != null && ch.getPersonaVersion() >= 1
                && segmentFullyCovered(profileId, dims)) {
            log.info("[AI建档] {} 幂等跳过：该段 {} 维已全覆盖（复用既有档案，零 AI 调用）：profileId={} version={}",
                    scene, dims.size(), profileId, ch.getPersonaVersion());
            return personaService.profileOrEmpty(profileId);
        }
        // 锚定文本：原文（写进 _legacy 供回溯）与进 Prompt 的版本（无原文时给占位提示，不含占位符语义）
        String anchorRaw = resolveAnchorRaw(ch, anchorText);
        String anchorForPrompt = anchorRaw == null ? ANCHOR_MISSING_HINT : truncate(anchorRaw, ANCHOR_MAX);
        Long uid = currentUserProvider.currentUserId();
        String prompt = renderPrompt(uid, pid, scene, ch, base, anchorForPrompt, isSegmentA);
        String systemMessage = promptTemplateService.systemMessage(uid, pid, scene, null);
        if (systemMessage.isBlank()) {
            // 模板未配系统提示词时的兜底（不阻断）：输出契约措辞在模板正文里已有一份
            systemMessage = "你只输出严格的 JSON，不输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.";
        }
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            long attemptStart = System.currentTimeMillis();
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", systemMessage),
                        new AiChatRequest.ChatMessage("user", prompt)),
                        PromptSpecRegistry.temperatureOf(scene),
                        PromptSpecRegistry.maxOutputTokensOf(scene),
                        true);
                AiChatResult result = AiCallContext.run(scene, profileId, attempt, () ->
                        aiChatGateway.chatCompletion(uid, pid, null, req));
                // 用量入账：AI 调用成功即记（token 已真实消耗），解析/落库失败也应当计费。
                // 阶段④ 加固：**观测数据不得反噬业务**——落账单独 try/catch，
                // 任何失败只告警，绝不参与"本段成功/失败"的判定，更不得让已产出的 AI 内容作废。
                // 背景：真库实测 `actor_usage_log.scene` 列宽不足（VARCHAR(20) vs 场景码 24 字符）时，
                // 落账异常会在外层 catch 被当作"AI 调用失败"→ 重试 2 次 → bootstrap 返回 null →
                // 20 位 NPC 一个都建不出档案，每人白烧约 140 秒真实 AI 时间与 token。
                try {
                    usageLogService.record(uid, pid, profileId, result.providerId(), result.model(), scene,
                            result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - attemptStart));
                } catch (Exception usageError) {
                    log.warn("[AI建档] {} 用量入账失败（已忽略，不影响本段成败与已产出内容）：profileId={} 原因={}",
                            scene, profileId, usageError.getMessage());
                }

                // —— 文本 → 11 维视图（零阻断：解析不出/维度全空 → warn 后重试，绝不抛异常）——
                PersonaProfile parsed = PersonaJson.readQuietly(extractJson(result.content()));
                if (parsed == null) {
                    log.warn("[AI建档] {} 第 {} 次：AI 输出无法解析为 11 维档案 JSON（记原文前 {} 字符，已重试）："
                                    + "profileId={} 原文={}",
                            scene, attempt, RAW_LOG_LIMIT, profileId, truncate(result.content(), RAW_LOG_LIMIT));
                    continue;
                }
                if (!hasAnyDimContent(parsed, dims)) {
                    log.warn("[AI建档] {} 第 {} 次：解析成功但本段 {} 个维度全无内容（已重试）：profileId={} 原文={}",
                            scene, attempt, dims.size(), profileId, truncate(result.content(), RAW_LOG_LIMIT));
                    continue;
                }
                // 归一化顶层契约（版本/来源声明/审计）——AI 的输出不可信，键位一律由程序补齐
                normalizeTopLevel(parsed, pid, profileId, scene, ch,
                        anchorRaw == null ? null : truncate(anchorRaw, ANCHOR_MAX));
                // AI 补全缺失维：先快照"已覆盖维"原值（mergeDims 原地改 base，必须在 merge 前复制），
                // merge 后再换回——AI 只许填缺、不许改已有
                Map<String, Object> preserved = snapshotPreserved(base, preserveDims);
                // 分阶段揭示：限定目标维时，AI 输出的其它维在合并前剔除（绝不让 AI 一次性
                // 填出完整成人人格——TODDLER 不会说话时不该有语言指纹，青少年之前不该有经历创伤）
                if (targetDims != null && !targetDims.isEmpty() && parsed.getDims() != null) {
                    for (String dim : PersonaSchema.ALL_DIMS) {
                        if (!targetDims.contains(dim)) {
                            switch (dim) {
                                case PersonaSchema.Dims.IDENTITY -> parsed.getDims().setIdentity(null);
                                case PersonaSchema.Dims.APPEARANCE -> parsed.getDims().setAppearance(null);
                                case PersonaSchema.Dims.PHYSIO -> parsed.getDims().setPhysio(null);
                                case PersonaSchema.Dims.PERSONALITY -> parsed.getDims().setPersonality(null);
                                case PersonaSchema.Dims.VALUES -> parsed.getDims().setValues(null);
                                case PersonaSchema.Dims.ABILITY -> parsed.getDims().setAbility(null);
                                case PersonaSchema.Dims.VOICE -> parsed.getDims().setVoice(null);
                                case PersonaSchema.Dims.HABITS -> parsed.getDims().setHabits(null);
                                case PersonaSchema.Dims.EXPERIENCE -> parsed.getDims().setExperience(null);
                                case PersonaSchema.Dims.DEVELOPMENT -> parsed.getDims().setDevelopment(null);
                                default -> parsed.getDims().setMask(null);
                            }
                        }
                    }
                }
                // 段 B：把本段 6 维合并进既有档案；段 A：parsed 即完整基底
                PersonaProfile merged = isSegmentA ? parsed : mergeDims(base, parsed);
                if (!preserved.isEmpty()) {
                    applyPreserved(merged, preserved);
                }
                int expected = expectedVersion(ch, 1);
                int version = personaService.savePersona(profileId, merged, source);
                if (version <= 0) {
                    log.warn("[AI建档] {} 档案未落库（savePersona 返回 {}，已跳过、不阻断）：profileId={}",
                            scene, version, profileId);
                    return null;
                }
                if (version != expected) {
                    // 并发写入时版本预估会偏（_legacy.persona_version 是预估值，不阻断）
                    log.warn("[AI建档] {} 版本预估 {} 与实际 {} 不一致（并发写入？）：profileId={}",
                            scene, expected, version, profileId);
                }
                List<String> coverDims = isSegmentA ? DIMS_A : concat(DIMS_A, DIMS_B);
                PersonaProfile stored = personaService.profileOf(profileId);
                log.info("[AI建档] {} 落库成功：profileId={} version={} 本段有内容维度={}/{} 档案覆盖={}/11",
                        scene, profileId, version, countDimContent(merged, dims), dims.size(),
                        stored == null ? -1 : countDimContent(stored, coverDims));
                return stored == null ? merged : stored;
            } catch (Exception e) {
                // AI 调用本身失败（网络/鉴权/超时）：重试；重试耗尽后返回 null，不抛给批处理
                log.warn("[AI建档] {} 第 {} 次调用失败（零阻断）：profileId={} 原因={}",
                        scene, attempt, profileId, e.getMessage());
            }
        }
        log.warn("[AI建档] {} 重试 {} 次后仍未取得有效输出，本次跳过（不抛异常、不阻断批处理）：profileId={}",
                scene, MAX_RETRY, profileId);
        return null;
    }

    /**
     * 渲染单段 Prompt（锚定材料的唯一入口：模型只能锚定这里给出的东西）。
     *
     * @param userId 当前用户 ID（模板三级覆盖解析）
     *
     * @param projectId 项目 ID
     *
     * @param scene 场景码（persona_bootstrap_fill_a/b）
     *
     * @param ch 角色行
     *
     * @param base 段 B 的基底档案（段 A 时为空档案）
     *
     * @param anchor 锚定文本（已解析，非空兜底）
     *
     * @param isSegmentA true=段 A
     *
     * @return 渲染后的 Prompt 文本
     */
    private String renderPrompt(Long userId, Long projectId, String scene, ActorCharacter ch,
                               PersonaProfile base, String anchor, boolean isSegmentA) {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("world_setting", renderWorld(projectId));
        ph.put("character_skeleton", buildSkeleton(ch));
        ph.put("anchor_text", anchor);
        ph.put("locations", renderLocations(projectId));
        ph.put("relations", renderRelations(projectId, ch.getId()));
        if (!isSegmentA) {
            // 段 B 的输入 = 第一段结果（只回灌 A 的 5 维，不带 _sources 等噪音）
            ph.put("persona_a_json", renderSegmentAJson(base));
        }
        return promptTemplateService.render(userId, projectId, scene, ph);
    }

    //
    // 锚定材料渲染（世界观 / 骨架 / 地点 / 关系 / 段 A 结果）
    //

    /**
     * 世界观自由文本（① 的锚定依据）。
     *
     * @param projectId 项目 ID
     *
     * @return 世界观文本；无设定时返回占位提示（不返回空串，避免模型以为"没有约束"）
     */
    private String renderWorld(Long projectId) {
        String text = projectId == null ? null : worldSettingRepository
                .findTopByProjectIdOrderByVersionDesc(projectId)
                .map(ActorWorldSetting::getFreeText)
                .orElse(null);
        if (text == null || text.isBlank()) {
            return "（本项目暂无世界观设定文本：请按角色骨架中的种族/归属/职业推断一个自洽的时代与地理，"
                    + "并保持全档案内部一致）";
        }
        return truncate(text, WORLD_MAX);
    }

    /**
     * 角色骨架（结构列，权威在 {@code actor_profile}，模型不可修改，只能与之相容）。
     *
     * @param ch 角色行
     *
     * @return 骨架文本（每行一项，缺列省略）
     */
    private String buildSkeleton(ActorCharacter ch) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "姓名", ch.getName());
        appendLine(sb, "性别", ch.getGender());
        appendLine(sb, "种族", ch.getRace());
        appendLine(sb, "次级种族", ch.getSubRace());
        appendLine(sb, "头衔", ch.getTitle());
        appendLine(sb, "归属", ch.getAffiliation());
        appendLine(sb, "职业", ch.getOccupation());
        appendLine(sb, "当前所在地点", ch.getLocation());
        appendLine(sb, "当前活动", ch.getCurrentActivity());
        appendLine(sb, "年龄", ch.getAge() == null ? null : String.valueOf(ch.getAge()));
        appendLine(sb, "发育阶段", ch.getStage());
        appendLine(sb, "NPC 类型", "common".equalsIgnoreCase(ch.getType()) ? "普通型（common）" : "特殊型（special）");
        appendLine(sb, "主角", Integer.valueOf(1).equals(ch.getIsProtagonist()) ? "是" : null);
        appendLine(sb, "重要度", ch.getImportance() == null ? null : String.valueOf(ch.getImportance()));
        return truncate(sb.toString(), SKELETON_MAX);
    }

    /**
     * 锚定文本原文：优先用调用方显式传入的 {@code anchorText}，为空时回退 {@code actor_profile.detail}。
     * <p>返回<b>原文</b>（可能为 null）——进 Prompt 的占位兜底与写入 {@code _legacy.anchor_text} 的回溯值
     * 用的是两种形态，不能混为一谈（回溯要的是"开发者到底写了什么"，不能把占位提示当成开发者原文）。</p>
     *
     * @param ch 角色行
     *
     * @param anchorText 调用方传入的锚定文本
     *
     * @return 锚定文本原文；两者皆空返回 null
     */
    private String resolveAnchorRaw(ActorCharacter ch, String anchorText) {
        return firstNonBlank(anchorText, ch == null ? null : ch.getDetail());
    }

    /**
     * 地点清单（① 的地名/活动范围的锚定依据）。
     *
     * @param projectId 项目 ID
     *
     * @return 地点清单文本
     */
    private String renderLocations(Long projectId) {
        if (projectId == null) {
            return "（无地点数据）";
        }
        List<ActorWorldLocation> list = worldLocationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        if (list == null || list.isEmpty()) {
            return "（本项目暂无地点清单：可用骨架中的所在地点，或按世界观合理命名，但不要与世界观地理冲突）";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ActorWorldLocation loc : list) {
            if (loc == null || loc.getName() == null || loc.getName().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(loc.getName());
            if (loc.getType() != null && !loc.getType().isBlank()) {
                sb.append('（').append(loc.getType()).append('）');
            }
            shown++;
            if (sb.length() >= LOCATION_MAX) {
                break;
            }
        }
        if (shown < (list.size())) {
            sb.append(" 等 ").append(list.size()).append(" 处");
        }
        return truncate(sb.toString(), LOCATION_MAX);
    }

    /**
     * 已有关系清单（⑨ 经历不得与关系矛盾；⑩ 谱系只能引用其中的亲属）。
     *
     * @param projectId 项目 ID
     *
     * @param profileId 本角色 ID
     *
     * @return 关系清单文本（只列与本角色相关的边）
     */
    private String renderRelations(Long projectId, Long profileId) {
        if (projectId == null || profileId == null) {
            return "（无关系数据）";
        }
        List<ActorCharacterRelation> all = relationRepository.findByProjectId(projectId);
        if (all == null || all.isEmpty()) {
            return "（本项目暂无角色关系数据：⑩ 谱系请留空，不要凭空发明亲属）";
        }
        StringBuilder sb = new StringBuilder();
        for (ActorCharacterRelation r : all) {
            if (r == null) {
                continue;
            }
            boolean from = profileId.equals(r.getFromCharacterId());
            boolean to = profileId.equals(r.getToCharacterId());
            if (!from && !to) {
                continue;
            }
            String other = from ? r.getToName() : r.getFromName();
            if (other == null || other.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('；');
            }
            sb.append(other).append('（').append(r.getRelationType() == null ? "未注明" : r.getRelationType());
            if (r.getRelationState() != null && !r.getRelationState().isBlank()) {
                sb.append('，').append(r.getRelationState());
            }
            sb.append('）');
            if (sb.length() >= RELATION_MAX) {
                break;
            }
        }
        if (sb.length() == 0) {
            return "（本角色暂无已登记的社会关系：⑩ 谱系请留空，不要凭空发明亲属）";
        }
        return truncate(sb.toString(), RELATION_MAX);
    }

    /**
     * 段 A 结果回灌（段 B 的一致性依据）：只序列化 A 的 5 维，序列化口径与落库完全一致（snake_case）。
     *
     * @param base 档案（段 B 的基底）
     *
     * @return 段 A 的 5 维 JSON；无内容时返回占位提示
     */
    private String renderSegmentAJson(PersonaProfile base) {
        if (base == null || base.getDims() == null) {
            return "（第一段结果缺失）";
        }
        PersonaProfile slim = new PersonaProfile();
        PersonaProfile.Dims dims = new PersonaProfile.Dims();
        dims.setIdentity(base.getDims().getIdentity());
        dims.setAppearance(base.getDims().getAppearance());
        dims.setPersonality(base.getDims().getPersonality());
        dims.setValues(base.getDims().getValues());
        dims.setExperience(base.getDims().getExperience());
        slim.setDims(dims);
        if (!hasAnyDimContent(slim, DIMS_A)) {
            return "（第一段结果缺失）";
        }
        String json;
        try {
            json = PersonaJson.write(slim);
        } catch (RuntimeException e) {
            return "（第一段结果序列化失败：" + e.getMessage() + "）";
        }
        return truncate(json, PERSONA_A_MAX);
    }

    //
    // 解析与归一化
    //

    /**
     * 从 AI 输出中提取 JSON 文本（剥 Markdown 代码块 + 取首个左花括号到末个右花括号之间的内容）。
     *
     * @param content AI 输出（可空）
     *
     * @return 提取出的 JSON 文本（content 为空返回 null）
     */
    private String extractJson(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 归一化顶层契约：AI 只负责 {@code dims} 内容，其余键位一律由程序补齐（避免模型编造版本号/来源声明）。
     * <ul>
     * <li>{@code generated_by}=ai_bootstrap、{@code generated_scene}=本段场景码、{@code generated_game_second} → 只读世界时钟；</li>
     * <li>{@code _sources} → 完整 11 键才采信，否则整体回退 {@link PersonaSchema#defaultSources()}
     * （写入权限与落库分派不允许残缺声明）；</li>
     * <li>{@code _legacy} → 写入 {@code persona_version}（本次落库后的版本预估）与 {@code anchor_text}
     * （"这份档案是基于什么生成的"）。</li>
     * </ul>
     *
     * @param p 解析出的档案（原地修改）
     *
     * @param projectId 项目 ID
     *
     * @param profileId 角色 ID
     *
     * @param scene 场景码
     *
     * @param ch 角色行（取当前档案版本）
     *
     * @param anchor 锚定文本
     */
    private void normalizeTopLevel(PersonaProfile p, Long projectId, Long profileId, String scene,
                                   ActorCharacter ch, String anchor) {
        int expected = expectedVersion(ch, 1);
        p.setSchemaVersion(PersonaSchema.SCHEMA_VERSION);
        p.setProfileId(profileId);
        p.setProjectId(projectId);
        p.setPersonaVersion(expected);
        p.setGeneratedBy(GENERATED_BY);
        p.setGeneratedScene(scene);
        p.setGeneratedGameSecond(currentGameSecond(projectId));
        p.setSources(resolveSources(p));
        PersonaProfile.Legacy legacy = p.getLegacy() == null ? new PersonaProfile.Legacy() : p.getLegacy();
        legacy.setPersonaVersion(expected);
        legacy.setAnchorText(anchor);
        p.setLegacy(legacy);
    }

    /**
     * 解析分维来源声明：AI 覆盖全部 11 键才采信，否则整体回退默认声明
     * （{@code _sources} 是落库分派与写入权限的唯一依据，残缺声明会导致"某维无人可写"或错误分派）。
     *
     * @param p 解析出的档案
     *
     * @return 11 键来源声明
     */
    private Map<String, PersonaProfile.PersonaSource> resolveSources(PersonaProfile p) {
        Map<String, PersonaProfile.PersonaSource> declared = p.getSources();
        if (declared != null && !declared.isEmpty()) {
            boolean complete = true;
            for (String dim : PersonaSchema.ALL_DIMS) {
                PersonaProfile.PersonaSource s = declared.get(dim);
                if (s == null || s.getKind() == null || s.getKind().isBlank()) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return declared;
            }
            log.warn("[AI建档] AI 输出的 _sources 声明不完整（{} 键），已整体回退默认分维来源声明：profileId={}",
                    declared.size(), p.getProfileId());
        }
        return PersonaSchema.defaultSources();
    }

    /**
     * 合并维度：把 overlay 的非空维覆盖到 base（段 B 的 6 维并入段 A 已落库的 5 维）。
     *
     * @param base 基底档案（原地修改并返回）
     *
     * @param overlay 新维来源
     *
     * @return 合并后的档案
     */
    private PersonaProfile mergeDims(PersonaProfile base, PersonaProfile overlay) {
        if (base == null) {
            return overlay;
        }
        if (overlay == null || overlay.getDims() == null) {
            return base;
        }
        if (base.getDims() == null) {
            base.setDims(new PersonaProfile.Dims());
        }
        PersonaProfile.Dims b = base.getDims();
        PersonaProfile.Dims o = overlay.getDims();
        if (o.getIdentity() != null) {
            b.setIdentity(o.getIdentity());
        }
        if (o.getAppearance() != null) {
            b.setAppearance(o.getAppearance());
        }
        if (o.getPhysio() != null) {
            b.setPhysio(o.getPhysio());
        }
        if (o.getPersonality() != null) {
            b.setPersonality(o.getPersonality());
        }
        if (o.getValues() != null) {
            b.setValues(o.getValues());
        }
        if (o.getAbility() != null) {
            b.setAbility(o.getAbility());
        }
        if (o.getVoice() != null) {
            b.setVoice(o.getVoice());
        }
        if (o.getHabits() != null) {
            b.setHabits(o.getHabits());
        }
        if (o.getExperience() != null) {
            b.setExperience(o.getExperience());
        }
        if (o.getDevelopment() != null) {
            b.setDevelopment(o.getDevelopment());
        }
        if (o.getMask() != null) {
            b.setMask(o.getMask());
        }
        // 段 B 归一化后的顶层契约（版本/审计/_sources/_legacy）需覆盖到合并结果上
        base.setSchemaVersion(overlay.getSchemaVersion());
        base.setProfileId(overlay.getProfileId());
        base.setProjectId(overlay.getProjectId());
        base.setPersonaVersion(overlay.getPersonaVersion());
        base.setGeneratedBy(overlay.getGeneratedBy());
        base.setGeneratedScene(overlay.getGeneratedScene());
        base.setGeneratedGameSecond(overlay.getGeneratedGameSecond());
        base.setSources(overlay.getSources());
        base.setLegacy(overlay.getLegacy());
        return base;
    }

    /**
     * 指定维集中是否有任一维装了实际内容（空对象/空数组/空白字符串不算内容）。
     *
     * @param p 档案（可空）
     *
     * @param dims 维键集
     *
     * @return true=至少一维有内容
     */
    private boolean hasAnyDimContent(PersonaProfile p, List<String> dims) {
        return countDimContent(p, dims) > 0;
    }

    /**
     * 统计指定维集中"有实际内容"的维数（用于日志与"维度全空"判定）。
     *
     * @param p 档案（可空）
     *
     * @param dims 维键集
     *
     * @return 有内容的维数
     */
    private int countDimContent(PersonaProfile p, List<String> dims) {
        if (p == null || p.getDims() == null) {
            return 0;
        }
        int count = 0;
        for (String dim : dims) {
            Object value = p.getDims().byKey(dim);
            if (value == null) {
                continue;
            }
            try {
                JsonNode node = PersonaJson.mapper().readTree(PersonaJson.mapper().writeValueAsString(value));
                if (hasContent(node)) {
                    count++;
                }
            } catch (Exception e) {
                // 序列化异常理论上不会发生：按"有内容"处理，交给后续落库/渲染链路自行降级
                count++;
            }
        }
        return count;
    }

    /**
     * 递归判定 JSON 节点是否有实际内容（对象/数组只要有任一非空白叶子即为有内容）。
     *
     * @param node JSON 节点
     *
     * @return true=有实际内容
     */
    private static boolean hasContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                if (hasContent(child)) {
                    return true;
                }
            }
            return false;
        }
        // Jackson 3：文本取值走 asString（asText(String) 已废弃）
        return !node.asString("").isBlank();
    }

    //
    // 幂等与保留工具
    //

    /**
     * 指定维集是否已全覆盖。
     *
     * @param dims 维键集（段 A / 段 B）
     *
     * @return true=全部已覆盖（无需 AI 补全）
     */
    private boolean segmentFullyCovered(Long profileId, List<String> dims) {
        PersonaProfile current = personaService.profileOrEmpty(profileId);
        PersonaRenderContext ctx = personaService.contextOf(profileId);
        for (String dim : dims) {
            if (!dimCovered(current, ctx, dim)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 单维"已覆盖"判定：档案内该维有实际内容，或模块可投影补齐。
     * <p>与覆盖率统计的区别：档案内是<b>内容</b>而非对象存在（空对象维算未覆盖），
     * 保证 AI 补全缺失维 / 建档幂等跳过不会放过"存了个空壳"的维。</p>
     *
     * @param current 当前档案（可空）
     *
     * @param ctx 模块投影上下文（可空）
     *
     * @param dimKey 维键
     *
     * @return true=已覆盖
     */
    private boolean dimCovered(PersonaProfile current, PersonaRenderContext ctx, String dimKey) {
        if (current != null && current.getDims() != null) {
            Object value = current.getDims().byKey(dimKey);
            if (value != null) {
                try {
                    JsonNode node = PersonaJson.mapper().readTree(PersonaJson.mapper().writeValueAsString(value));
                    if (hasContent(node)) {
                        return true;
                    }
                } catch (Exception e) {
                    // 序列化异常理论上不会发生：按"有内容"处理（保守不误判为空）
                    return true;
                }
            }
        }
        return personaSchemaValidator.isDimProjectable(ctx, dimKey);
    }

    /**
     * 快照"已覆盖维"的原值（AI 补全缺失维的"只许填缺"约束：merge 前复制，merge 后换回）。
     *
     * @param base 原档案（merge 会被原地修改，故必须提前复制）
     *
     * @param preserveDims 需保留原值的维键集
     *
     * @return 维键 → 原值（仅含 base 内非空的值）
     */
    private Map<String, Object> snapshotPreserved(PersonaProfile base, Set<String> preserveDims) {
        if (base == null || base.getDims() == null || preserveDims == null || preserveDims.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String dim : preserveDims) {
            Object value = base.getDims().byKey(dim);
            if (value != null) {
                out.put(dim, value);
            }
        }
        return out;
    }

    /**
     * 把快照的原值换回合并结果（与 {@link #snapshotPreserved} 配对）。
     *
     * @param target 合并结果（原地修改）
     *
     * @param preserved 维键 → 原值
     */
    private void applyPreserved(PersonaProfile target, Map<String, Object> preserved) {
        if (target == null || preserved.isEmpty()) {
            return;
        }
        if (target.getDims() == null) {
            target.setDims(new PersonaProfile.Dims());
        }
        PersonaProfile.Dims t = target.getDims();
        for (Map.Entry<String, Object> e : preserved.entrySet()) {
            String dim = e.getKey();
            Object value = e.getValue();
            if (PersonaSchema.Dims.IDENTITY.equals(dim)) {
                t.setIdentity((PersonaDims.Identity) value);
            } else if (PersonaSchema.Dims.APPEARANCE.equals(dim)) {
                t.setAppearance((PersonaDims.Appearance) value);
            } else if (PersonaSchema.Dims.PHYSIO.equals(dim)) {
                t.setPhysio((PersonaDims.Physio) value);
            } else if (PersonaSchema.Dims.PERSONALITY.equals(dim)) {
                t.setPersonality((PersonaDims.Personality) value);
            } else if (PersonaSchema.Dims.VALUES.equals(dim)) {
                t.setValues((PersonaDims.Values) value);
            } else if (PersonaSchema.Dims.ABILITY.equals(dim)) {
                t.setAbility((PersonaDims.Ability) value);
            } else if (PersonaSchema.Dims.VOICE.equals(dim)) {
                t.setVoice((PersonaDims.Voice) value);
            } else if (PersonaSchema.Dims.HABITS.equals(dim)) {
                t.setHabits((PersonaDims.Habits) value);
            } else if (PersonaSchema.Dims.EXPERIENCE.equals(dim)) {
                t.setExperience((PersonaDims.Experience) value);
            } else if (PersonaSchema.Dims.DEVELOPMENT.equals(dim)) {
                t.setDevelopment((PersonaDims.Development) value);
            } else if (PersonaSchema.Dims.MASK.equals(dim)) {
                t.setMask((PersonaDims.Mask) value);
            }
        }
    }

    //
    // 小工具
    //

    /**
     * 本次落库后的档案版本号预估（= 当前行版本 + 本次保存次数）。
     * <p>与 {@code PersonaService.savePersona} 的自增口径一致（每次保存 +1）；写入
     * {@code _legacy.persona_version} 与 {@code persona_version}，供"第几版是谁生成的"回溯。
     * 并发写入时可能与实际版本偏差，届时记 warn（不阻断）。</p>
     *
     * @param ch 角色行
     *
     * @param savesAhead 本次调用内的保存次数（单段 = 1）
     *
     * @return 预估版本号
     */
    private int expectedVersion(ActorCharacter ch, int savesAhead) {
        int current = ch == null || ch.getPersonaVersion() == null ? 0 : ch.getPersonaVersion();
        return current + Math.max(1, savesAhead);
    }

    /**
     * 生成时的世界时刻（游戏秒；只读世界时钟，任何异常返回 null——审计字段不值得阻断建档）。
     *
     * @param projectId 项目 ID
     *
     * @return 游戏秒或 null
     */
    private Long currentGameSecond(Long projectId) {
        if (projectId == null) {
            return null;
        }
        try {
            return worldClockRepository.findByProjectId(projectId)
                    .map(clock -> clock.getLastGameSecond())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 一行 {@code 标签：值}（值为空则跳过）。 */
    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append('：').append(value.trim()).append('\n');
    }

    /** 字符串截断（保留头部；null 安全）。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…（已截断）";
    }

    /** 取首个非空白字符串（null 安全）。 */
    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    /** 拼接维键集（日志用）。 */
    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }
}