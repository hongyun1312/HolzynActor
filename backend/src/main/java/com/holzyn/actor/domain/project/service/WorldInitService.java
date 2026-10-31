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

package com.holzyn.actor.domain.project.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.character.dto.RelationBatchDTO;
import com.holzyn.actor.domain.character.dto.RelationGenerateDTO;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.character.service.CharacterCardService;
import com.holzyn.actor.domain.character.service.CharacterRelationService;
import com.holzyn.actor.domain.character.vo.RelationDraftVO;
import com.holzyn.actor.domain.crowd.dto.OrdinaryNpcDTO;
import com.holzyn.actor.domain.crowd.service.OrdinaryNpcService;
import com.holzyn.actor.domain.crowd.vo.FieldDictPreviewVO;
import com.holzyn.actor.domain.crowd.vo.FieldDictVO;
import com.holzyn.actor.domain.crowd.vo.OrdinaryNpcDraftVO;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.knowledge.service.CharacterKnowledgeCardSyncService;
import com.holzyn.actor.domain.knowledge.service.KnowledgeService;
import com.holzyn.actor.domain.knowledge.service.WorldKnowledgeSyncService;
import com.holzyn.actor.domain.project.model.WorldInitStep;
import com.holzyn.actor.domain.project.vo.WorldInitResultVO;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.dto.WorldLocationDTO;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import com.holzyn.actor.domain.world.service.WorldLocationService;
import com.holzyn.actor.domain.world.vo.WorldLocationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 世界初始化工作流服务（新建项目解析重构配套）。
 * <p>职责：按 7 步自动初始化一个已落库的世界（全部程序运行同时输出后端终端日志 + 工作流回调日志）：
 * ① 根据世界观地理设定生成结构化地点（复用地点 AI 提取 + 全量/合并入库）；
 * ② 生成角色结构化角色卡及其 Prompt（复用角色卡生成，默认跳过已生成 / 重建时全量）；
 * ③ 生成普通型 NPC 字段字典，再生成 5 个普通 NPC（复用字段字典拟定保存 + 居民生成入库）；
 * ④ 生成全局关系拓扑（复用关系生成预览 + 批量入库）；
 * ⑤ 生成世界地图 + 角色住所（AI 地图生成 + 同居共宅分组，机制兜底）；
 * ⑥ 根据世界观信息（历史脉络/时代背景）由 AI 推断当前世界历时间点并设置（不从零开始）；
 * ⑦ 将知识库中的世界观文件尝试向量化。</p>
 * <p>执行策略：全自动一次跑完；任一步骤失败不阻断后续（记录日志后跳过继续）。
 * rebuild=false 时对已生成数据采取幂等跳过（地点合并新增/角色卡补缺/字段字典与居民已存在则跳过/关系补充模式/已向量化跳过）；
 * rebuild=true 时全量重建（地点整表替换/角色卡全量重生成/字段字典与居民重建/关系重建模式/世界时间覆盖/向量化重跑）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldInitService {

    /** 初始化生成的普通型 NPC 数量（默认 5 个） */
    static final int INIT_NPC_COUNT = 5;

    /** AI 世界时间推断最大输出 token */
    private static final int TIME_MAX_TOKENS = 1024;

    /** AI 调用最大重试次数 */
    private static final int MAX_RETRY = 2;

    /** 历史脉络送入 AI 推断时间的最大长度 */
    private static final int HISTORY_MAX = 8000;

    private final CharacterCardService characterCardService;
    private final CharacterRelationService relationService;
    private final OrdinaryNpcService ordinaryNpcService;
    private final WorldLocationService worldLocationService;
    private final WorldClockService worldClockService;
    private final KnowledgeService knowledgeService;
    /** 世界设定知识同步器（RAG 适配：第 6 步前把世界设定分段同步为项目级知识文档，再统一向量化） */
    private final WorldKnowledgeSyncService worldKnowledgeSyncService;
    /** 人物知识卡同步器（知识检索质量深化：第 6 步全角色补结构化知识卡，覆盖旧路径角色卡生成） */
    private final CharacterKnowledgeCardSyncService characterKnowledgeCardSyncService;
    private final ActorCharacterRepository characterRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final AiSpringChatGateway aiChatGateway;
    private final PromptTemplateService templateService;
    private final UsageLogService usageLogService;
    private final com.holzyn.actor.domain.world.service.MacroStateService macroStateService;
    private final ObjectMapper objectMapper;

    /** 世界地图生成服务（M1：AI 地图生成 + 住所；初始化第 5 步调用，机制兜底不阻断） */
    private final com.holzyn.actor.domain.worldmap.service.WorldMapGenerationService worldMapGenerationService;

    /**
     * 开局家族与基因建档（第 ⑤ 步）。
     *
     * <p>字段注入（{@code required=false}）而不是构造器参数：本类构造器由
     * {@code @RequiredArgsConstructor} 生成，AGENTS 8.1.2 禁止改既有构造器签名
     * （会打断既有测试的构造点）。未装配时第 ⑤ 步直接跳过，不影响其余步骤。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.lineage.FamilyGenesisService familyGenesisService;

    /**
     * 初始化状态服务（步骤状态行 + 完成度判定 + 严格顺序门禁的数据源）。
     *
     * <p>字段注入 {@code required=false}：本类构造器由 {@code @RequiredArgsConstructor} 生成，
     * AGENTS §8.1.2 禁止改既有构造器签名（会打断既有测试的构造点）。未装配时（单测直接 new、
     * 或做一些与初始化无关的静态工具调用）跳过状态记录与门禁，各步骤照常执行，
     * 行为退化回旧版"只跑不记账"。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldInitStatusService worldInitStatusService;

    /**
     * 世界程度（控制第 9 步「宏观社会模板」要不要生成）。
     *
     * <p>为什么在这一步门禁而不是在宏观调度器里事后过滤：休闲档的世界<b>根本不该有</b>
     * 势力/经济/人口这些宏观设定，生成了再隐藏等于把冲突留在库里；从源头不生成才是设定一致。</p>
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldToneService worldToneService;

    /**
     * 世界初始化主入口：按 {@link WorldInitStep} 定义的固定顺序逐步执行。
     *
     * <p><b>严格顺序（本版语义变更）</b>：必需步骤失败即<b>中止</b>后续步骤（旧版是
     * "失败静默跳过、后面照跑"，结果用户看到的是一堆半成品）。中止后用户修复问题、
     * 在初始化向导里从失败那一步继续，不会重复烧前面的 AI。</p>
     *
     * <p><b>幂等</b>：{@code rebuild=false} 时已满足的步骤直接跳过（判定含实时数据校验，
     * 见 {@link WorldInitStatusService#satisfied}）；{@code rebuild=true} 时全部重跑。</p>
     *
     * <p>标注"含 AI 调用"：本方法内会调用网关，<b>不得</b>加 {@code @Transactional}
     * （AGENTS §8.1.1，H2 行锁跨 AI 时长会锁超时并丢掉已付费的结果）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param rebuild true=全量重建 / false=跳过已生成（幂等）
     *
     * @param wf 工作流日志回调（可为 null；后端终端日志始终输出）
     *
     * @return 初始化结果 VO
     */
    public WorldInitResultVO runInit(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        long taskStart = System.currentTimeMillis();
        info(wf, "[世界初始化] 任务开始：项目=" + projectId + "，模式=" + (rebuild ? "全量重建" : "跳过已生成（幂等）"));
        RunAccumulator acc = new RunAccumulator();
        boolean aborted = false;
        for (WorldInitStep step : WorldInitStep.values()) {
            if (aborted) {
                info(wf, "[世界初始化] 已中止，跳过第 " + step.index() + " 步「" + step.label()
                        + "」（前置必需步骤未完成，严格顺序不允许继续）");
                continue;
            }
            if (!rebuild && satisfied(projectId, step)) {
                info(wf, "[世界初始化] 第 " + step.index() + " 步「" + step.label()
                        + "」数据已存在，跳过（需要重做请切「全量重建」）");
                continue;
            }
            stage(wf, step.label(), step.index(), WorldInitStep.total());
            StepOutcome outcome = executeStep(userId, projectId, step, rebuild, wf, acc);
            if (!outcome.ok() && step.required()) {
                aborted = true;
                info(wf, "[世界初始化] 第 " + step.index() + " 步「" + step.label()
                        + "」是必需步骤且执行失败：后续步骤不再执行（修复后可从本步继续）");
            }
        }
        info(wf, "[世界初始化] 任务结束：耗时 " + (System.currentTimeMillis() - taskStart) + "ms，" + acc.summary());
        return new WorldInitResultVO(projectId, acc.locations, acc.cards, acc.npcs, acc.relations,
                acc.gameTimeText == null ? "未设置" : acc.gameTimeText, acc.vectorized);
    }

    /**
     * 执行单个初始化步骤（初始化向导「执行本步」入口）。
     *
     * <p><b>严格顺序门禁</b>：执行第 N 步前，所有 {@code index < N} 的必需步骤必须已满足，
     * 否则抛 400「请先完成第 K 步」——向导据此把按钮置灰并提示上一步，
     * 避免用户跳着点出一堆半成品数据。</p>
     *
     * <p>含 AI 调用，<b>不得</b>加 {@code @Transactional}（AGENTS §8.1.1）。</p>
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param stepCode 步骤编码（{@link WorldInitStep#code()}）
     *
     * @param rebuild true=全量重建 / false=跳过已生成（幂等）
     *
     * @param wf 工作流日志回调（可为 null）
     *
     * @return 本步执行结果（其余字段为 0）
     *
     * @throws BizException 步骤编码未知 / 前置步骤未完成 / 本步执行失败
     */
    public WorldInitResultVO runStep(Long userId, Long projectId, String stepCode, boolean rebuild, WorkflowLog wf) {
        WorldInitStep step = WorldInitStep.of(stepCode)
                .orElseThrow(() -> new BizException(400, "未知的初始化步骤：" + stepCode));
        ensurePreviousRequiredSatisfied(projectId, step);
        RunAccumulator acc = new RunAccumulator();
        stage(wf, step.label(), step.index(), WorldInitStep.total());
        StepOutcome outcome = executeStep(userId, projectId, step, rebuild, wf, acc);
        if (!outcome.ok()) {
            throw new BizException(400, "第 " + step.index() + " 步「" + step.label() + "」执行失败："
                    + outcome.message());
        }
        return new WorldInitResultVO(projectId, acc.locations, acc.cards, acc.npcs, acc.relations,
                acc.gameTimeText == null ? "未设置" : acc.gameTimeText, acc.vectorized);
    }

    /**
     * 跳过某个可选步骤（初始化向导「跳过此步」入口）。
     *
     * <p>必需步骤<b>不允许</b>跳过：它们是"这个世界能不能跑起来"的最低配置，
     * 允许跳过等于允许交付一个空世界。跳过后该步骤视为已满足，不再阻塞后续步骤。</p>
     *
     * @param projectId 项目 ID
     *
     * @param stepCode 步骤编码
     *
     * @return 跳过后的最新初始化状态
     *
     * @throws BizException 步骤编码未知 / 该步骤是必需步骤（不能跳过）
     */
    public com.holzyn.actor.domain.project.vo.WorldInitStatusVO skipStep(Long projectId, String stepCode) {
        WorldInitStep step = WorldInitStep.of(stepCode)
                .orElseThrow(() -> new BizException(400, "未知的初始化步骤：" + stepCode));
        if (step.required()) {
            throw new BizException(400, "「" + step.label() + "」是必需步骤，不能跳过；请修复后重试");
        }
        WorldInitStatusService state = worldInitStatusService;
        if (state == null) {
            throw new BizException(500, "初始化状态服务未装配，无法跳过步骤");
        }
        state.markSkipped(projectId, step, "用户在初始化向导中跳过");
        return state.status(projectId);
    }

    /**
     * 计算世界初始化状态（"是否完全初始化完成"的唯一判定入口）。
     *
     * @param projectId 项目 ID
     *
     * @return 初始化状态（含逐步状态与结论）
     */
    public com.holzyn.actor.domain.project.vo.WorldInitStatusVO status(Long projectId) {
        WorldInitStatusService state = worldInitStatusService;
        if (state == null) {
            throw new BizException(500, "初始化状态服务未装配，无法读取初始化状态");
        }
        // 走 refreshStatus：世界已挂载时顺手校正目录库快照（画廊角标），未挂载时 service 自己跳过写入
        return state.refreshStatus(projectId);
    }

    /**
     * 严格顺序门禁：检查本步之前的所有<b>必需</b>步骤是否已完成。
     *
     * <p>为什么只拦必需步骤：可选步骤（家族/地图/宏观/向量化）本就是"能跳过"的，
     * 把它们也纳入门禁会让用户永远走不到世界时间——那不是严格，是卡死。</p>
     *
     * @param projectId 项目 ID
     *
     * @param step      待执行步骤
     *
     * @throws BizException 存在未完成的必需前置步骤
     */
    private void ensurePreviousRequiredSatisfied(Long projectId, WorldInitStep step) {
        WorldInitStatusService state = worldInitStatusService;
        if (state == null) {
            return;
        }
        for (WorldInitStep prev : WorldInitStep.values()) {
            if (prev.index() >= step.index()) {
                break;
            }
            if (!prev.required()) {
                continue;
            }
            if (!state.satisfied(projectId, prev)) {
                throw new BizException(400, "请先完成第 " + prev.index() + " 步「" + prev.label()
                        + "」，再执行第 " + step.index() + " 步「" + step.label() + "」");
            }
        }
    }

    /**
     * 执行单步的公共骨架：标记运行中 → 分派具体步骤 → 落状态行 → 输出日志。
     *
     * <p>把"落状态 + 打日志"从各步骤里提出来，是为了保证 10 个步骤的状态语义完全一致
     * （漏一处就会出现"向导显示未跑到、实际数据已生成"的错位）。</p>
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID
     * @param step      步骤
     * @param rebuild   是否全量重建
     * @param wf        日志回调
     * @param acc       结果累加器（本步产出写回）
     *
     * @return 单步执行结果
     */
    private StepOutcome executeStep(Long userId, Long projectId, WorldInitStep step, boolean rebuild,
                                    WorkflowLog wf, RunAccumulator acc) {
        WorldInitStatusService state = worldInitStatusService;
        if (state != null) {
            state.markRunning(projectId, step);
        }
        long startAt = System.currentTimeMillis();
        int count = 0;
        boolean ok;
        String message;
        try {
            switch (step) {
                case WORLDVIEW: {
                    ok = liveSatisfied(projectId, step);
                    count = ok ? 1 : 0;
                    message = ok ? "世界观设定已就绪（名称与分节内容完整）"
                            : "世界观设定不完整：请至少填写名称与 3 个分节（地理/势力/规则/文化/历史/补充）";
                    break;
                }
                case LOCATIONS: {
                    int n = initLocations(userId, projectId, rebuild, wf);
                    acc.locations = n;
                    count = n;
                    ok = n > 0 || liveSatisfied(projectId, step);
                    message = ok ? "已生成世界观地点 " + n + " 个"
                            : "未生成任何地点：请先补全世界观「地理设定」（为空或 AI 未返回有效结果）";
                    break;
                }
                case CHARACTERS: {
                    int n = initCharacterCards(userId, projectId, rebuild, wf);
                    acc.cards = n;
                    count = n;
                    ok = n > 0 || liveSatisfied(projectId, step);
                    message = ok ? "已生成角色卡 " + n + " 张"
                            : "项目内还没有角色：请先到「角色」页新增角色（会同时生成角色卡），再回来执行本步";
                    break;
                }
                case NPCS: {
                    int n = initFieldDictAndNpcs(userId, projectId, rebuild, wf);
                    acc.npcs = n;
                    count = n;
                    ok = n > 0 || liveSatisfied(projectId, step);
                    message = ok ? "已生成普通 NPC " + n + " 个"
                            : "未生成普通 NPC：字段字典或居民生成失败（需要先有地点与角色卡）";
                    break;
                }
                case RELATIONS: {
                    int n = initRelations(userId, projectId, rebuild, wf);
                    acc.relations = n;
                    count = n;
                    ok = n > 0 || liveSatisfied(projectId, step);
                    message = ok ? "已生成关系 " + n + " 条"
                            : "未生成任何关系：AI 未返回有效关系草稿（需要先有角色卡）";
                    break;
                }
                case FAMILY: {
                    com.holzyn.actor.domain.growth.lineage.FamilyGenesisService.Result family =
                            initFamilyGenesis(userId, projectId, wf);
                    acc.family = family;
                    count = family == null ? 0 : family.families();
                    ok = true;
                    message = family == null ? "家族与基因步骤已跳过（服务未装配）"
                            : "家族 " + family.families() + " 簇（婚姻 " + family.marriages()
                            + " 条 / 亲子 " + family.parentEdges() + " 条 / 基因 " + family.genomes() + " 份）";
                    break;
                }
                case WORLD_MAP: {
                    int[] map = initWorldMap(userId, projectId, wf);
                    acc.map = map;
                    count = map[0];
                    ok = true;
                    message = "世界地图 " + map[0] + " 个区域 / " + map[1] + " 间住所（"
                            + map[2] + " 人入住，" + (map[3] == 1 ? "AI" : "机制") + "）";
                    break;
                }
                case WORLD_TIME: {
                    String text = initWorldTime(userId, projectId, wf);
                    acc.gameTimeText = text;
                    count = text == null || "未设置".equals(text) ? 0 : 1;
                    ok = count > 0 || liveSatisfied(projectId, step);
                    message = ok ? "世界时间已设置为 " + text
                            : "世界时间推断失败：请检查世界观「历史脉络 / 时代背景」后重试";
                    break;
                }
                case MACRO: {
                    // 世界程度门禁：休闲/轻度档不生成宏观模板——"不该有的内容一次都不出现"，
                    // 所以这里不是失败而是"按档位跳过"，跳过不算未完成（WorldInitStep.MACRO 本就是可选步）。
                    if (worldToneService != null && worldToneService.tone(projectId).skipsMacroTemplate()) {
                        String toneLabel = worldToneService.tone(projectId).label();
                        String skipMsg = "该档位不生成宏观模板（世界程度：" + toneLabel + "），已按设定跳过";
                        info(wf, "[世界初始化] 第 " + step.index() + " 步「" + step.label()
                                + "」已跳过：" + skipMsg);
                        // 直接落 SKIPPED 而不是 DONE：向导里要显示"跳过"而不是"完成"，
                        // 但它不是失败，也不影响"世界已初始化完成"的判定（可选步）。
                        if (state != null) {
                            state.markSkipped(projectId, step, skipMsg);
                        }
                        return new StepOutcome(true, 0, skipMsg);
                    }
                    boolean done = false;
                    if (macroStateService != null) {
                        macroStateService.stateOf(projectId);
                        info(wf, "[世界初始化] 宏观社会模板已推导落位（"
                                + macroStateService.templateOf(projectId).name() + "）");
                        done = true;
                    }
                    count = done ? 1 : 0;
                    ok = true;
                    message = done ? "宏观社会模板已落位" : "宏观模板服务未装配，已跳过";
                    break;
                }
                case KNOWLEDGE: {
                    int synced = 0;
                    try {
                        synced = worldKnowledgeSyncService.syncWorldSetting(userId, projectId);
                        if (synced > 0) {
                            info(wf, "[世界初始化-知识同步] 世界设定已同步为项目级知识文档 " + synced + " 份");
                        }
                    } catch (Exception e) {
                        log.warn("[世界初始化-知识同步] 失败（跳过继续）：{}", e.getMessage());
                        info(wf, "[世界初始化-知识同步] 失败（跳过继续）：" + e.getMessage());
                    }
                    acc.knowledgeSynced = synced;
                    int vectorized = initKnowledgeVectorize(userId, projectId, wf);
                    acc.vectorized = vectorized;
                    int cardCount = 0;
                    try {
                        cardCount = characterKnowledgeCardSyncService.syncAllCards(userId, projectId);
                        if (cardCount > 0) {
                            info(wf, "[世界初始化-知识卡] 人物知识卡已同步 " + cardCount + " 张");
                        }
                    } catch (Exception e) {
                        log.warn("[世界初始化-知识卡] 失败（跳过继续）：{}", e.getMessage());
                        info(wf, "[世界初始化-知识卡] 失败（跳过继续）：" + e.getMessage());
                    }
                    acc.knowledgeCards = cardCount;
                    count = Math.max(vectorized, cardCount);
                    ok = true;
                    message = "知识文档 " + synced + " 份 / 向量化 " + vectorized + " 条 / 人物知识卡 "
                            + cardCount + " 张" + (vectorized == 0 ? "（未配置向量服务时属正常降级）" : "");
                    break;
                }
                default: {
                    ok = false;
                    message = "未实现的初始化步骤：" + step.code();
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[世界初始化] 第 {} 步「{}」执行异常：{}", step.index(), step.label(), e.getMessage());
            ok = false;
            count = 0;
            message = "执行异常：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        long cost = System.currentTimeMillis() - startAt;
        if (state != null) {
            if (ok) {
                state.markDone(projectId, step, count, message + "（" + cost + "ms）");
            } else {
                state.markFailed(projectId, step, message);
            }
        }
        log.info("[世界初始化] 第 {}/{} 步「{}」{}：{}（{}ms）", step.index(), WorldInitStep.total(),
                step.label(), ok ? "完成" : "失败", message, cost);
        info(wf, "[世界初始化] 第 " + step.index() + "/" + WorldInitStep.total() + " 步「" + step.label()
                + "」" + (ok ? "完成" : "失败") + "：" + message);
        return new StepOutcome(ok, count, message);
    }

    /** 单步执行结果（ok=是否成功；count=产出条数；message=面向用户的结论） */
    private record StepOutcome(boolean ok, int count, String message) {
    }

    /**
     * 初始化结果累加器：各步骤产出写回此处，最后统一汇总成一条日志与结果 VO。
     *
     * <p>为什么用可变累加器而不是让每步返回结果对象：步骤签名各异（int / int[] / String /
     * Result），统一改成同一返回类型会改动 8 个既有步骤方法的签名与所有日志，收益不抵风险。</p>
     */
    private static final class RunAccumulator {

        /** 本次入库地点数 */
        int locations;

        /** 本次生成角色卡数 */
        int cards;

        /** 本次生成普通 NPC 数 */
        int npcs;

        /** 本次写入关系数 */
        int relations;

        /** 家族与基因结果（未执行为 null） */
        com.holzyn.actor.domain.growth.lineage.FamilyGenesisService.Result family;

        /** 世界地图结果 [区域, 住所, 入住, 是否AI] */
        int[] map;

        /** 世界时间文本 */
        String gameTimeText;

        /** 同步的知识文档数 */
        int knowledgeSynced;

        /** 人物知识卡数 */
        int knowledgeCards;

        /** 向量化文档数 */
        int vectorized;

        /** 汇总文本（与原「任务结束」日志同格式，便于老日志检索） */
        String summary() {
            int[] m = map == null ? new int[]{0, 0, 0, 0} : map;
            String familyText = family == null ? "0 簇（婚姻 0 条 / 亲子 0 条 / 基因 0 份）"
                    : family.families() + " 簇（婚姻 " + family.marriages() + " 条 / 亲子 "
                    + family.parentEdges() + " 条 / 基因 " + family.genomes() + " 份）";
            return "地点 " + locations + " 个 / 角色卡 " + cards + " 张 / 普通 NPC " + npcs + " 个 / 关系 "
                    + relations + " 条 / 家族 " + familyText + " / 世界地图 " + m[0] + " 个区域·" + m[1]
                    + " 间住所（" + m[2] + " 人入住，" + (m[3] == 1 ? "AI" : "机制") + "） / 世界时间 "
                    + (gameTimeText == null ? "未设置" : gameTimeText) + " / 知识卡 " + knowledgeCards
                    + " 张 / 向量化 " + vectorized + " 条";
        }
    }

    /** 某步骤是否已满足（状态行或实时数据；状态服务未装配时一律 false） */
    private boolean satisfied(Long projectId, WorldInitStep step) {
        WorldInitStatusService state = worldInitStatusService;
        return state != null && state.satisfied(projectId, step);
    }

    /** 某步骤的实时数据是否已存在（状态服务未装配时一律 false） */
    private boolean liveSatisfied(Long projectId, WorldInitStep step) {
        WorldInitStatusService state = worldInitStatusService;
        return state != null && state.liveSatisfied(projectId, step);
    }

    // ==================== ① 世界观地点 ====================

    /**
     * 第 1 步：根据地理设定 AI 生成结构化地点并入库。
     * 无地理设定 → 跳过；rebuild 或原无地点 → 整表替换；否则按名称合并追加（幂等）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param rebuild 是否全量重建
     *
     * @param wf 日志回调
     *
     * @return 本次入库地点数
     */
    private int initLocations(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        String geography = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(ActorWorldSetting::getGeography).orElse("");
        if (geography == null || geography.isBlank()) {
            log.warn("[世界初始化-地点] 项目 {} 暂无地理设定，跳过地点生成", projectId);
            info(wf, "[世界初始化-地点] 当前项目暂无「地理设定」，跳过地点生成");
            return 0;
        }
        try {
            info(wf, "[世界初始化-地点] 从地理设定 AI 提取地点…（输入 " + geography.length() + " 字）");
            List<WorldLocationDTO> extracted = worldLocationService.extractFromGeography(userId, geography);
            List<WorldLocationVO> existing = worldLocationService.list(projectId);
            List<WorldLocationDTO> toSave;
            if (existing.isEmpty() || rebuild) {
                toSave = extracted;
                info(wf, "[世界初始化-地点] AI 提取 " + extracted.size() + " 个地点，" + (rebuild ? "全量重建" : "首次生成") + " 入库");
            } else {
                // 幂等：合并去重（保留已有 + 追加新名称）
                List<WorldLocationDTO> merged = new ArrayList<>();
                Set<String> names = new LinkedHashSet<>();
                existing.forEach(l -> {
                    names.add(norm(l.getName()));
                    WorldLocationDTO dto = new WorldLocationDTO();
                    dto.setName(l.getName());
                    dto.setType(l.getType());
                    dto.setIntro(l.getIntro());
                    dto.setImportance(l.getImportance());
                    merged.add(dto);
                });
                int added = 0;
                for (WorldLocationDTO d : extracted) {
                    if (d == null || d.getName() == null || !names.add(norm(d.getName()))) {
                        continue;
                    }
                    merged.add(d);
                    added++;
                }
                toSave = merged;
                info(wf, "[世界初始化-地点] AI 提取 " + extracted.size() + " 个地点，合并新增 " + added + " 个（其余已存在）");
            }
            if (toSave.isEmpty()) {
                info(wf, "[世界初始化-地点] 未识别到明确地点（AI 返回空），跳过入库");
                return 0;
            }
            worldLocationService.batchReplace(projectId, toSave);
            info(wf, "[世界初始化-地点] 完成：入库地点 " + toSave.size() + " 个");
            return toSave.size();
        } catch (Exception e) {
            log.warn("[世界初始化-地点] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-地点] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    /** 地点名称归一（去空格，判重用）。 */
    private String norm(String name) {
        return name == null ? "" : name.trim();
    }

    // ==================== ② 角色卡 ====================

    /**
     * 第 2 步：生成角色结构化角色卡及其 Prompt。
     * rebuild=false → 只补生成未生成的角色卡；rebuild=true → 全量重新生成。
     *
     * @param userId 归属用户 ID（日志用）
     *
     * @param projectId 项目 ID
     *
     * @param rebuild 是否全量重建
     *
     * @param wf 日志回调
     *
     * @return 本次新生成角色卡数
     */
    private int initCharacterCards(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        List<ActorCharacter> chars = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        if (chars.isEmpty()) {
            info(wf, "[世界初始化-角色卡] 当前项目暂无角色，跳过角色卡生成");
            return 0;
        }
        try {
            info(wf, "[世界初始化-角色卡] 开始生成角色结构化角色卡（角色 " + chars.size() + " 位，"
                    + (rebuild ? "全量重新生成" : "仅补生成未生成的") + "）…");
            List<Map<String, Object>> results = rebuild
                    ? characterCardService.generateAllCards(projectId)
                    : characterCardService.generateMissingCards(projectId);
            int ok = 0;
            int skipped = 0;
            for (Map<String, Object> r : results) {
                if (Boolean.TRUE.equals(r.get("success"))) {
                    ok++;
                } else if (Boolean.TRUE.equals(r.get("skipped"))) {
                    skipped++;
                }
            }
            info(wf, "[世界初始化-角色卡] 完成：新生成 " + ok + " 张" + (skipped > 0 ? "（已存在跳过 " + skipped + " 张）" : "")
                    + "（共 " + chars.size() + " 位角色）");
            return ok;
        } catch (Exception e) {
            log.warn("[世界初始化-角色卡] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-角色卡] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    // ==================== ③ 字段字典 + 5 个普通 NPC ====================

    /**
     * 第 3 步：生成普通型 NPC 字段字典，再生成 5 个普通 NPC（复用现有逻辑）。
     * 字段字典已存在且非重建 → 跳过拟定；居民生成需字段字典（种族+归属）与世界观。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param rebuild 是否全量重建
     *
     * @param wf 日志回调
     *
     * @return 本次入库普通 NPC 数
     */
    private int initFieldDictAndNpcs(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        try {
            Map<String, List<FieldDictVO>> dict = ordinaryNpcService.fieldDict(projectId);
            boolean hasDict = !dict.getOrDefault("race", List.of()).isEmpty()
                    && !dict.getOrDefault("affiliation", List.of()).isEmpty();
            if (hasDict && !rebuild) {
                info(wf, "[世界初始化-字段字典] 字段字典已存在（race=" + dict.getOrDefault("race", List.of()).size()
                        + " / affiliation=" + dict.getOrDefault("affiliation", List.of()).size()
                        + "），跳过拟定");
            } else {
                info(wf, "[世界初始化-字段字典] AI 依据世界观拟定标准字段数据（种族/归属/职业）…");
                FieldDictPreviewVO preview = ordinaryNpcService.generateFieldDict(projectId);
                ordinaryNpcService.saveFieldDict(projectId, preview);
                info(wf, "[世界初始化-字段字典] 完成：race="
                        + preview.fields().getOrDefault("race", List.of()).size()
                        + " / affiliation=" + preview.fields().getOrDefault("affiliation", List.of()).size()
                        + " / occupation=" + preview.fields().getOrDefault("occupation", List.of()).size()
                        + "，主分类=" + preview.primaryField() + "，次分类=" + preview.secondaryField());
            }
        } catch (Exception e) {
            log.warn("[世界初始化-字段字典] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-字段字典] 失败（跳过继续）：" + e.getMessage());
        }

        // 生成 5 个普通 NPC（依赖字段字典，失败不阻断）
        try {
            info(wf, "[世界初始化-普通NPC] 开始 AI 生成 " + INIT_NPC_COUNT + " 个普通 NPC…");
            List<OrdinaryNpcDraftVO> drafts = new ArrayList<>();
            ordinaryNpcService.generateStream(projectId, INIT_NPC_COUNT, new OrdinaryNpcService.GenerateProgress() {
                @Override
                public void onStart(int total, int batchSize) {
                    info(wf, "[世界初始化-普通NPC] 生成开始：目标 " + total + " 个");
                }

                @Override
                public void onNpc(OrdinaryNpcDraftVO d, int index) {
                    drafts.add(d);
                    info(wf, "[世界初始化-普通NPC] 已生成 #" + index + "：" + d.name()
                            + "（" + (d.race() == null ? "" : d.race()) + " / "
                            + (d.affiliation() == null ? "" : d.affiliation()) + "）");
                }

                @Override
                public void onDone(int total, int generated, int failedBatches) {
                    info(wf, "[世界初始化-普通NPC] 生成结束：共 " + generated + " 个（失败批次 " + failedBatches + "）");
                }
            });
            if (drafts.isEmpty()) {
                info(wf, "[世界初始化-普通NPC] 未生成到有效居民，跳过入库");
                return 0;
            }
            List<OrdinaryNpcDTO> dtos = drafts.stream()
                    .map(d -> new OrdinaryNpcDTO(d.name(), d.gender(), d.race(), d.subRace(), d.age(),
                            d.affiliation(), d.location(), d.occupation(), d.detail()))
                    .toList();
            int saved = ordinaryNpcService.batchSave(projectId, dtos);
            info(wf, "[世界初始化-普通NPC] 完成：普通 NPC 已入库 " + saved + " 个");
            return saved;
        } catch (Exception e) {
            log.warn("[世界初始化-普通NPC] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-普通NPC] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    // ==================== ④ 全局关系拓扑 ====================

    /**
     * 第 4 步：生成全局关系拓扑（复用关系生成预览 + 批量入库）。
     * rebuild=false → 补充模式（追加）；rebuild=true → 重建模式（清空后写入）。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param rebuild 是否全量重建
     *
     * @param wf 日志回调
     *
     * @return 本次写入关系数
     */
    private int initRelations(Long userId, Long projectId, boolean rebuild, WorkflowLog wf) {
        try {
            String mode = rebuild ? "rebuild" : "supplement";
            info(wf, "[世界初始化-关系] 开始 AI 生成全局关系拓扑（范围=全部角色，" + (rebuild ? "重建" : "补充") + "模式）…");
            List<RelationDraftVO> drafts = relationService.generate(projectId,
                    new RelationGenerateDTO("project", null, null, mode));
            if (drafts.isEmpty()) {
                info(wf, "[世界初始化-关系] AI 未识别到角色关系（可能角色过少），跳过入库");
                return 0;
            }
            Map<String, Object> saved = relationService.batchSave(projectId,
                    new RelationBatchDTO(mode, null, null, drafts));
            int added = saved == null ? 0 : ((Number) saved.getOrDefault("added", 0)).intValue();
            info(wf, "[世界初始化-关系] 完成：识别 " + drafts.size() + " 条，入库 " + added + " 条");
            return added;
        } catch (Exception e) {
            log.warn("[世界初始化-关系] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-关系] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    // ==================== ⑤ 开局家族与基因建档 ====================

    /**
     * 第 5 步：开局家族与基因建档（G4 遗传婚育谱系）。
     *
     * <p>做四件事（都在 {@code FamilyGenesisService.ensure} 里，幂等可重入）：
     * ① 补齐每个角色的 {@code gender/age/birth_game_second/stage}（缺这一步婚配永远不会发生，
     * 因为婚龄判定把 null 当 0 岁）；② 按姓氏 +「同族」关系聚成家族簇并分三代；
     * ③ 落婚姻边与亲子边；④ 给每个角色建基因档案（有父母的按孟德尔重组，创始人按 OCEAN 反推）。</p>
     *
     * <p>位置讲究：排在「关系拓扑」之后（要读同族/合作关系做聚类）、「世界地图+住所」之前
     * （住所分配能看见家庭结构，同族才可能同宅）。失败不阻断后续步骤。</p>
     *
     * @param userId 归属用户 ID（AI 精修用量归属）
     *
     * @param projectId 项目 ID
     *
     * @param wf 日志回调
     *
     * @return 建档结果（未装配服务/失败时返回空结果）
     */
    private com.holzyn.actor.domain.growth.lineage.FamilyGenesisService.Result initFamilyGenesis(
            Long userId, Long projectId, WorkflowLog wf) {
        if (familyGenesisService == null) {
            info(wf, "[世界初始化-家族] 未装配家庭建档服务，跳过");
            return com.holzyn.actor.domain.growth.lineage.FamilyGenesisService.Result.EMPTY;
        }
        try {
            info(wf, "[世界初始化-家族] 补齐成长档案 → 建立家族谱系 → 基因建档…");
            var r = familyGenesisService.ensure(userId, projectId, worldClockService.currentEngineGameSecond(projectId));
            info(wf, "[世界初始化-家族] 完成：补档案 " + r.profilesFixed() + " 人 / 家族 " + r.families()
                    + " 簇 / 婚姻边 " + r.marriages() + " 条 / 亲子边 " + r.parentEdges()
                    + " 条 / 基因档案 " + r.genomes() + " 份");
            return r;
        } catch (Exception e) {
            log.warn("[世界初始化-家族] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-家族] 失败（跳过继续）：" + e.getMessage());
            return com.holzyn.actor.domain.growth.lineage.FamilyGenesisService.Result.EMPTY;
        }
    }

    // ==================== ⑥ 世界地图 + 住所（同居共宅） ====================

    /**
     * 第 5 步：生成世界地图 + 角色住所（AI 主通道 + 机制兜底，静默不发布事件；失败不阻断后续）。
     * 依赖前 4 步（地点/角色卡/普通 NPC/关系）就绪——住所归属需要角色与亲密关系（同居分组）。
     *
     * @param userId 归属用户 ID（AI 用量归属）
     *
     * @param projectId 项目 ID
     *
     * @param wf 日志回调
     *
     * @return [区域数, 住所数, 入住角色数, 是否 AI（1/0）]；无地点时全 0
     */
    private int[] initWorldMap(Long userId, Long projectId, WorkflowLog wf) {
        try {
            info(wf, "[世界初始化-地图] 开始生成世界地图与角色住所（AI 主通道 + 机制兜底）…");
            var vo = worldMapGenerationService.generateForInit(userId, projectId);
            if ("skipped".equals(vo.getSource())) {
                info(wf, "[世界初始化-地图] " + vo.getMessage());
                return new int[]{0, 0, 0, 0};
            }
            info(wf, "[世界初始化-地图] 完成：来源=" + ("ai".equals(vo.getSource()) ? "AI" : "机制")
                    + "，区域 " + vo.getRegionCount() + " 个 / 道路 " + vo.getRoadCount() + " 条 / 摆放 "
                    + vo.getPlacementCount() + " 个 / 住所 " + vo.getHomeCount() + " 间（" + vo.getMemberCount() + " 人入住）");
            return new int[]{vo.getRegionCount(), vo.getHomeCount(), vo.getMemberCount(),
                    "ai".equals(vo.getSource()) ? 1 : 0};
        } catch (Exception e) {
            log.warn("[世界初始化-地图] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-地图] 失败（跳过继续）：" + e.getMessage());
            return new int[]{0, 0, 0, 0};
        }
    }

    // ==================== ⑥ 世界时间 ====================

    /**
     * 第 5 步：根据世界观信息（历史脉络/时代背景）由 AI 推断当前世界历时间点并设置
     * （不从零开始计算）。换算为 gameSecond 后设置起始游戏时刻（worldStartGameHour）与真实锚点
     * （worldStartAt 反推补足时分秒），使「当前」世界时间即推断结果并继续流动。
     *
     * @param userId 归属用户 ID
     *
     * @param projectId 项目 ID
     *
     * @param wf 日志回调
     *
     * @return 设置后的世界历时间文本（失败返回「未设置」）
     */
    private String initWorldTime(Long userId, Long projectId, WorkflowLog wf) {
        ActorWorldSetting ws = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId).orElse(null);
        if (ws == null) {
            info(wf, "[世界初始化-时间] 当前项目无世界观设定，跳过世界时间设置");
            return "未设置";
        }
        long start = System.currentTimeMillis();
        log.info("[世界时间推断] 任务开始：项目={}", projectId);
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                String history = (ws.getHistory() == null || ws.getHistory().isBlank())
                        ? (ws.getFreeText() == null ? "" : ws.getFreeText()) : ws.getHistory();
                Map<String, Object> ph = Map.of(
                        "world_name", nvl(ws.getName()),
                        "genre", nvl(ws.getGenre()),
                        "era", nvl(ws.getEra()),
                        "history", truncate(history, HISTORY_MAX));
                String prompt = templateService.render(userId, projectId, PromptTemplateService.CODE_WORLD_TIME_INFER, ph);
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", templateService.systemMessage(userId, projectId,
                                PromptTemplateService.CODE_WORLD_TIME_INFER, ph)),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, TIME_MAX_TOKENS, true);
                AiChatResult result = AiCallContext.run("world_time_infer", null, () ->
                        aiChatGateway.chatCompletion(userId, projectId, null, req));
                usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "world_time_infer",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                int[] t = parseWorldTime(result.content());
                long gameSecond = WorldClockService.calendarToGameSecond(t[0], t[1], t[2], t[3], t[4], t[5]);
                // 设置世界起点 = 推断时间（时间口径统一）：世界时间权威 = 引擎推进位置，
                // 故此处直接校准引擎位置并把真实锚点重基到该时刻——此前只设锚点（引擎位置仍为 0），
                // 世界实际从 0001年01月01日 00时00分 起跑而时钟显示推断时间，AI 提示词时间感 / 昼夜
                // 光照 / 深夜晚判定与玩家可见时钟互不一致（双时间源缺陷的起点）。
                worldClockService.alignWorldTimeSeconds(projectId, gameSecond);
                String text = String.format("%d 年 %d 月 %d 日 %02d 时 %02d 分", t[0], t[1], t[2], t[3], t[4]);
                log.info("[世界时间推断] 第 {} 次成功：耗时 {}ms，gameSecond={}，当前世界时间={}",
                        attempt, System.currentTimeMillis() - start, gameSecond, text);
                info(wf, "[世界初始化-时间] 完成：AI 依据世界观推断当前世界时间 = " + text
                        + "（世界起点已设为该时刻：引擎推进位置与时钟同步，不再从零开始计算）");
                return text;
            } catch (Exception e) {
                lastError = e;
                log.warn("[世界时间推断] 第 {} 次失败: {}", attempt, e.getMessage());
            }
        }
        log.warn("[世界时间推断] 任务失败：{}", lastError == null ? "未知错误" : lastError.getMessage());
        info(wf, "[世界初始化-时间] 世界时间推断失败（跳过）：" + (lastError == null ? "未知错误" : lastError.getMessage()));
        return "未设置";
    }

    /**
     * 解析 AI 世界时间推断输出为 [年, 月, 日, 时, 分, 秒]（容错：字段缺失/非法回退默认值）。
     *
     * @param content AI 输出
     *
     * @return 长度为 6 的数组
     */
    static int[] parseWorldTime(String content) {
        String json = JsonUtil.extractJson(content);
        if (json == null) {
            throw new BizException("AI 未返回有效 JSON");
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new BizException("AI 世界时间输出 JSON 解析失败");
        }
        return new int[]{
                clampInt(root, "year", 1, 1, 999999),
                clampInt(root, "month", 1, 1, 12),
                clampInt(root, "day", 1, 1, 30),
                clampInt(root, "hour", 0, 0, 23),
                clampInt(root, "minute", 0, 0, 59),
                clampInt(root, "second", 0, 0, 59)
        };
    }

    /** 取整数节点并夹取范围（缺省用 def）。 */
    private static int clampInt(JsonNode root, String field, int def, int min, int max) {
        int v = root.path(field).asInt(def);
        return Math.max(min, Math.min(max, v));
    }

    // ==================== ⑥ 知识向量化 ====================

    /**
     * 第 6 步：将知识库中的世界观文件尝试向量化（embedding 未配置时内部降级空数组，不抛错）。
     *
     * @param userId 归属用户 ID（日志用）
     *
     * @param projectId 项目 ID
     *
     * @param wf 日志回调
     *
     * @return 本次完成向量化的文档数
     */
    private int initKnowledgeVectorize(Long userId, Long projectId, WorkflowLog wf) {
        try {
            info(wf, "[世界初始化-向量化] 开始尝试向量化知识库文档…");
            int done = knowledgeService.vectorizeAll(projectId);
            info(wf, "[世界初始化-向量化] 完成：" + done + " 条文档已向量化"
                    + (done == 0 ? "（无文档或已全部向量化/未配置 embedding）" : ""));
            return done;
        } catch (Exception e) {
            log.warn("[世界初始化-向量化] 失败（跳过继续）：{}", e.getMessage());
            info(wf, "[世界初始化-向量化] 失败（跳过继续）：" + e.getMessage());
            return 0;
        }
    }

    // ==================== 工具 ====================

    /** 工作流日志（后端终端已输出）。 */
    private void info(WorkflowLog wf, String message) {
        if (wf != null) {
            try {
                wf.info(message);
            } catch (Exception e) {
                log.warn("工作流日志推送失败: {}", e.getMessage());
            }
        }
    }

    /** 工作流阶段（同时后端终端输出）。 */
    private void stage(WorkflowLog wf, String name, int index, int total) {
        log.info("[工作流] 阶段 {}/{}：{}", index, total, name);
        if (wf != null) {
            try {
                wf.stage(name, index, total);
            } catch (Exception e) {
                log.warn("工作流阶段推送失败: {}", e.getMessage());
            }
        }
    }

    /** null 安全取字符串。 */
    private String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 字符串截断（null 安全）。 */
    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
