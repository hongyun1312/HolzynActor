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

package com.holzyn.actor.domain.crowd.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.spring.AiSpringChatGateway;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.NpcRefs;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.event.EventQuery;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.agent.runtime.RunModes;
import com.holzyn.actor.domain.growth.persona.PersonaBootstrapService;
import com.holzyn.actor.domain.growth.persona.PersonaJson;
import com.holzyn.actor.domain.growth.persona.PersonaProfile;
import com.holzyn.actor.domain.growth.persona.PersonaSchema;
import com.holzyn.actor.domain.growth.persona.PersonaService;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.crowd.dto.OrdinaryNpcDTO;
import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.entity.ActorNpcFieldDict;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.state.service.CrowdDecisionParse;
import com.holzyn.actor.domain.npc.state.service.NpcActivityStateService;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.crowd.repository.ActorNpcFieldDictRepository;
import com.holzyn.actor.domain.crowd.vo.FieldDictPreviewVO;
import com.holzyn.actor.domain.crowd.vo.FieldDictVO;
import com.holzyn.actor.domain.crowd.vo.OrdinaryNpcDraftVO;
import com.holzyn.actor.domain.crowd.vo.OrdinaryNpcVO;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 普通型 NPC 服务。
 * <p>职责：承载普通型 NPC 的全部业务逻辑——
 * ① 单表 CRUD + 多字段筛选/排序/分页/统计；
 * ② 标准字段数据（字段字典）：AI 依据世界观一次性拟定种族(含次级种族)/归属/职业的标准值
 * （每条含出处、严格符合世界观），并选出主/次分类字段（一主一次，供人群分组/调度）；
 * 所在地复用世界观地点表（生成时可合理补充）；预览确认后整体保存，可重新生成/手动增删；
 * ③ AI 批量生成：依据世界观 + 字段字典 + 地点清单生成居民（≤500；带档案生成时每批
 * {@code holzyn.actor.crowd.npc-persona-batch-size} 人、默认 5，SSE 逐条推送），
 * 字段必须从字典/地点中选取，避免 OOC；不含关系（关系在 NPC 生成后单独生成，写入关系表）；
 * <b>每批同时产出每位居民的 11 维档案</b>（{@code ordinary_npc_gen} 模板 v2 契约），
 * 档案在确认落库时随行写入（见下方「居民 11 维档案的落库链路」）；
 * ④ 调度：定时任务走程序化状态机（零成本），手动「AI 调度」走两级 AI
 * （项目级：按主/次分类字段分组 + 归属 下发指令 → 归属级：合并归属指令 + 相关主分类分组指令
 * 逐人输出状态/行动，失败降级程序化）；
 * ⑤ 环境摘要：聚合项目级调度快照供对话/行动注入。</p>
 * <p><b>「角色档案统一重构」适配口径（本文件强制遵守）</b>：</p>
 * <ul>
 * <li><b>单一主表</b>：普通型 NPC = {@code actor_profile} 中 {@code type='common'} 的行
 * （历史 {@code actor_profile_ordinary} 表与 {@code ActorOrdinaryNpc} 实体已物理废弃）；
 * 本服务所有"普通型"查询/计数/删除一律带 {@link NpcRefs#TYPE_COMMON} 类型过滤；</li>
 * <li><b>运行态唯一家</b>：作息状态机的 {@code state} / {@code lastAction} / {@code dailyPlanJson}
 * 三个字段已从档案迁出，权威在 {@code actor_state.activity_state} / {@code .last_action} /
 * {@code .daily_plan_json}——本服务一律经 {@link NpcActivityStateService} 读写，
 * <b>绝不</b>再从 {@link ActorCharacter} 上取这三个字段；</li>
 * <li><b>批量优先</b>：调度/计划循环先用 {@link NpcActivityStateService#activityStateByRef}、
 * 各取一次全量，循环内只读内存 Map，循环结束用
 * 一次性写回（避免 500 居民 × 逐小时 tick 的 N+1 查库）；</li>
 * <li><b>统一身份</b>：跨模块引用一律 {@link NpcRefs#of(Long)} → {@code npc:{id}}，
 * 不手写 {@code "crowd:"}/{@code "special:"} 前缀。</li>
 * </ul>
 * <p><b>居民 11 维档案的落库链路</b></p>
 * <ol>
 * <li><b>谁调 AI</b>：{@link #generateStream}——批量生成路径不变，仍是一次调用生成一批居民
 * （有效批大小见 {@link #effectiveBatchSize()}）；</li>
 * <li><b>谁解析</b>：{@link #parseGeneratedResidents}（模板元素 = 9 个结构列键 + 契约三键
 * {@code schema_version/dims/_sources} 同处元素顶层，故元素 JSON 可直接交
 * {@code PersonaJson.readQuietly} 解析为 11 维档案）；</li>
 * <li><b>谁暂存</b>：{@link #pendingPersonaJson}（键 = 项目 + 姓名）。为什么必须暂存：生成阶段只有草稿，
 * {@code actor_profile} 行（profileId）要等用户确认后 {@link #batchSave} 才创建，而
 * <li><b>谁落库</b>：{@link #batchSave}（每落一行：取回暂存 → {@code PersonaJson.readQuietly}
 * → {@code PersonaService.savePersona(profileId, profile, "generated")}），③⑤⑥ 由档案服务分派到
 * 各自权威模块、其余留在 {@code persona_json}；<b>本服务绝不自己拼 persona_json</b>。</li>
 * </ol>
 * <p><b>零阻断</b>：解析失败 / 维度全空 / 暂存缺失 → {@code log.warn}（原文截断 {@link #RAW_LOG_LIMIT}
 * 字符）后跳过该人——不抛异常、不阻断批量、不写脏档案，居民结构列照常入库。</p>
 * <p><b>成本口径</b>：档案与结构列同批产出，<b>不增加 AI 调用次数</b>；代价是单批人数从 30 收窄到
 * {@link #GEN_PERSONA_BATCH_DEFAULT}（默认 5；单人 11 维 JSON 约 1.5-2.2k token，30 人必撞 16384 输出上限），
 * 例如「生成 15 人 = 3 次调用」（纯结构列口径为 1 次）。兜底开关 {@link #npcPersonaFallbackBootstrap}
 * （默认关）打开后，暂存缺失的居民逐人回退两段式建档 = <b>2 次 AI 调用/人</b>。</p>
 * <p>纯逻辑（字段字典清洗/草稿解析/状态机/行动描述/分组概览）抽为静态方法便于单元测试。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrdinaryNpcService {

    /** AI 生成单批居民数（控制单次输出 token，保证质量） */
    public static final int BATCH_SIZE = 30;
    /** AI 生成数量上限 */
    public static final int MAX_NPC = 500;
    /** AI 调用重试次数 */
    private static final int MAX_RETRY = 2;
    /** 生成居民单次 AI 输出 token 上限（每批 30 人 × 详情 200 字） */
    private static final int NPC_MAX_TOKENS = 16384;
    /** 字段字典拟定单次 AI 输出 token 上限 */
    private static final int DICT_MAX_TOKENS = 8192;
    /** 调度单次 AI 输出 token 上限 */
    private static final int SCHEDULE_MAX_TOKENS = 8192;
    /** 分组指令截断（解析回填存储保护） */
    private static final int DIRECTIVE_MAX = 500;
    /** 字段字典每字段一级上限 / race 二级每类上限 */
    private static final int DICT_L1_MAX = 30;
    private static final int DICT_L2_MAX = 20;

    // 字段名常量（字段字典 + 主/次分类字段共用）
    public static final String FIELD_RACE = "race";
    public static final String FIELD_AFFILIATION = "affiliation";
    public static final String FIELD_OCCUPATION = "occupation";
    private static final Set<String> CLASSIFY_FIELDS = Set.of(FIELD_RACE, FIELD_AFFILIATION, FIELD_OCCUPATION);

    /** 字段长度上限（与实体/DTO 对齐，服务层兜底截断） */
    private static final int NAME_MAX = 50;
    private static final int GENDER_MAX = 20;
    private static final int RACE_MAX = 50;
    private static final int SUB_RACE_MAX = 50;
    private static final int AFFILIATION_MAX = 100;
    private static final int LOCATION_MAX = 100;
    private static final int OCCUPATION_MAX = 50;
    private static final int FIELD_MAX = 20;
    private static final int SOURCE_MAX = 1000;
    private static final int DETAIL_MAX = 4000;
    private static final int ACTION_MAX = 255;

    // ==================== 收口：居民 11 维档案（契约 §7 缺口） ====================

    /** 带档案的居民生成单批人数默认值（与 {@code holzyn.actor.crowd.npc-persona-batch-size} 默认值一致） */
    private static final int GEN_PERSONA_BATCH_DEFAULT = 5;

    /**
     * 生成 → 确认之间暂存的 11 维档案上限（条；超出按插入顺序淘汰最旧的一批）。
     * <p>为什么需要上限：暂存只是"预览确认"这个交互窗口的过渡态（正常几分钟内就被 {@link #batchSave}
     * 取走），但用户放弃确认时它不会自动清空，故必须有硬上限兜底（500 居民 × 单人 1.5-2.2k token
     * 的档案 ≈ 1-3MB 量级，2000 条足够覆盖多项目并发）。</p>
     */
    private static final int PENDING_PERSONA_MAX = 2000;

    /** 解析失败时原始 AI 输出的日志截断长度（超出丢弃，避免日志爆行） */
    private static final int RAW_LOG_LIMIT = 500;

    /** 居民 11 维档案的落库来源标记（{@link PersonaService#savePersona} 的 source 入参） */
    private static final String SOURCE_GENERATED = "generated";

    // ====================：批量决策 Prompt 分片输入（本轮升级） ====================

    /**
     * 归属级批量调度的档案分片场景码（{@code PersonaService.renderShardsForScene} 的场景码）：
     * 组合为 S1 身份锚定 + S2 人格与价值观 + S4 身体与生理 + S6 习惯与日常 + S8 社会面具
     * （见 {@code PersonaSchema.SCENE_SHARDS}；约 1049 字符 ≈ 650 token/人）。
     */
    static final String SCENE_NPC_CROWD_DECISION = "npc_crowd_decision";

    /**
     * 对外契约「成员类型 / 事件类型」值（<b>全文件唯一一处</b>，不散落字面量）。
     * <p><b>收敛完成：值 = {@code common}</b>。 曾保留历史 {@code crowd}
     * （当时事件总线白名单与沙盘前端按 {@code crowd} 判定，改值会静默错档）。 同步收敛三处：</p>
     * <ul>
     * <li>事件总线：{@code ActorEventFields.KINDS / SOURCES} 白名单已同时登记 {@code common} 与
     * {@code crowd}（新写入一律 {@code common}，历史 {@code crowd} 行读取兼容，两值都按"居民活动"渲染）；</li>
     * <li>世界沙盘前端：{@code sandbox3d/npcMarkers.js}、{@code labelLayer.js} 判定已改为
     * {@code npcType !== 'crowd' && npcType !== 'common'}（两种值都按普通居民渲染）；</li>
     * <li>群聊成员：前端成员筛选与「居民」标签已兼容两种值。</li>
     * </ul>
     * <p>本文件内其余位置一律引用本常量，改值只在此一处。</p>
     */
    static final String CONTRACT_TYPE_COMMON = NpcRefs.TYPE_COMMON;

    private final ActorCharacterRepository npcRepository;
    private final ActorNpcFieldDictRepository fieldDictRepository;
    private final ActorCrowdRuntimeRepository runtimeRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final WorldLocationRepository worldLocationRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AiSpringChatGateway aiChatGateway;
    private final UsageLogService usageLogService;
    private final PromptTemplateService promptTemplateService;
    private final WorldClockService worldClockService;
    private final WorldEventBus worldEventBus;
    private final ObjectMapper objectMapper;
    /** BrainProfile：普通 NPC 生成入库自动建脑力配置行（ensureProfile 失败仅告警，不阻断业务） */
    private final BrainProfileService brainProfileService;
    /** 运行态访问器：作息状态机 state/lastAction/dailyPlanJson 唯一读写入口（actor_state） */
    private final NpcActivityStateService npcActivityStateService;
    /** 档案服务：批量决策 Prompt 读 11 维分片（无档案返回空串 → 回退扁平字段兜底） */
    private final PersonaService personaService;

    /**
     * 两段式建档服务（{@code PersonaBootstrapService}）。
     * <p><b>只作兜底</b>：默认链路是"居民批量生成同批产出档案"（零额外 AI 调用）；仅当某位新居民在
     * {@link #pendingPersonaJson} 里找不到档案、且 {@link #npcPersonaFallbackBootstrap} 开关打开时，
     * 才逐人回退到它（段 A + 段 B = <b>2 次 AI 调用/人</b>，且发生在确认请求的事务内）。</p>
     */
    private final PersonaBootstrapService personaBootstrapService;

    /** 世界引擎配置仓库（注入：run_mode 决定普通 NPC 日计划机制化/AI；字段注入不改构造器） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.ActorWorldEngineRepository engineRepository;

    /** 预算守卫（注入：ultimate 模式普通 NPC AI 日计划预算门控） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.agent.runtime.BudgetGuard budgetGuard;

    /** 世界沙盘 S1：人群逐时调度后同步沙盘状态（字段注入保持构造器不变；居民状态/动作 → actor_sandbox_state） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.sandbox.service.WorldSandboxStateService worldSandboxStateService;

    /** 状态；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.npc.state.service.NpcStateService npcStateService;

    /**
     * 宏观社会演化系统：人群行动微观入账挂钩（驱动/程序化双路径 → 宏观指标
     * 折算流水；字段注入保持构造器不变，null=未装配跳过）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.MacroLedgerService macroLedgerService;

    /** 静态 ObjectMapper（纯逻辑静态解析复用，避免重复创建） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 生命周期配置（dispatch.all-npc-hourly=true 时普通 NPC 改由世界引擎逐人决策，
     * crowd 批量 AI（npc_crowd_decision 归属级）运行时停用——仅保留程序化作息作 dormant 兜底；
     * null=未装配[单测]按既有行为）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.growth.config.GrowthProperties growthProperties;

    /**
     * crowd 批量 AI（npc_crowd_decision 归属级）运行时停用判定——all-npc-hourly=true 时
     * 普通 NPC 已由世界引擎逐人决策（感知/思考/行动/空间全量消费），本批次调度不再做 AI 驱动，
     * 保留程序化状态机作 dormant 兜底（零 AI；手动调试端点仍可显式调 {@link #scheduleWithAi}）。
     */
    private boolean crowdBatchAiDisabled() {
        return growthProperties != null && growthProperties.isEnabled()
                && growthProperties.getDispatch().isAllNpcHourly();
    }

    /** 事件感知阈值：事件重要度 ≥ 该值视为可打断普通 NPC 当日计划的「重大事件」（默认 3） */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.crowd.event-disrupt-importance-threshold:3}")
    private int crowdEventImportanceThreshold;

    /**
     * 带档案的居民生成单批人数（默认 {@link #GEN_PERSONA_BATCH_DEFAULT}=5；上限为 {@link #BATCH_SIZE}）。
     * <p>为什么比纯结构列的 30/批小得多：每位居民要同时产出 11 维档案（单人 JSON 约 1.5-2.2k token），
     * 30 人会直接撞上 {@link #NPC_MAX_TOKENS}=16384 的输出上限并被截断，而截断=整批 JSON 报废；
     * 5 人约 8-11k token，留出模型超额与重试的余量。调大前请先确认所选模型的实际输出上限。</p>
     */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.crowd.npc-persona-batch-size:5}")
    private int npcPersonaBatchSize;

    /**
     * 暂存档案缺失时是否逐人回退两段式 AI 建档（默认 <b>关</b>）。
     * <p><b>成本</b>：两段式 = 2 次 AI 调用/人（15 位居民 = 30 次调用），且发生在 {@link #batchSave}
     * 的事务内（AI 调用数十秒级，会长时间占住数据库连接与确认请求），因此默认关闭，
     * 由运维按预算显式打开（{@code holzyn.actor.crowd.npc-persona-fallback-bootstrap=true}）。
     * 打开后只对"暂存缺失"的居民生效——正常链路零额外调用。</p>
     */
    @org.springframework.beans.factory.annotation.Value("${holzyn.actor.crowd.npc-persona-fallback-bootstrap:false}")
    private boolean npcPersonaFallbackBootstrap;

    /**
     * 生成 → 确认之间的 11 维档案暂存（键 = projectId + '\u0000' + 姓名 → 档案 JSON 原文）。
     * <p><b>为什么需要它</b>：{@link #generateStream} 只产出"草稿"（不落库），{@code actor_profile} 行
     * 要等用户在预览里确认后 {@link #batchSave} 才创建——而
     * 故把同批产出的档案原文暂存在服务端（Spring 单例、进程内），落库时按"项目 + 姓名"取回，
     * <b>零额外 AI 调用</b>地补上 11 维档案。</p>
     * <p>上限 {@link #PENDING_PERSONA_MAX} 条、插入顺序淘汰；取走即消费（重复落库不写第二遍）。
     * 服务重启或用户在预览里改名会取不到——届时跳过档案、结构列照常入库（零阻断，可稍后补建）。</p>
     */
    private final Map<String, String> pendingPersonaJson = new LinkedHashMap<>();

    // ==================== 查询条件（多字段筛选 + 排序） ====================

    /**
     * 普通型 NPC 查询条件（列表/筛选/排序； 改为全部字段可筛可排序）。
     *
     * @param gender 性别（精确）
     *
     * @param race 种族（一级，精确）
     *
     * @param subRace 次级种族（二级，精确）
     *
     * @param affiliation 归属（精确）
     *
     * @param occupation 职业（精确）
     *
     * @param location 当前所在地（精确）
     *
     * @param keyword 关键词（匹配名称/种族/次级种族/归属/职业/地点/详情）
     *
     * @param ageMin 年龄下限（含）
     *
     * @param ageMax 年龄上限（含）
     *
     * @param sortBy 排序字段白名单：id/name/age/race/subRace/affiliation/occupation/location（空=id）
     *
     * @param sortDir asc/desc（默认 asc）
     */
    public record NpcQuery(String gender, String race, String subRace, String affiliation,
                           String occupation, String location, String keyword,
                           Integer ageMin, Integer ageMax, String sortBy, String sortDir) {
    }

    //

    /**
     * 分页查询（多字段筛选 + 排序）。
     * <p>单项目 ≤500 量级，内存筛选 + 排序 + 分页（稳定简单）。</p>
     * <p>只取主表 {@code type='common'} 的行；{@code state}/{@code lastAction}
     * 由 {@link NpcActivityStateService#activityByRef} <b>一次批量取全</b>后回填 VO
     * （不从实体读、不逐行查库）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param q 查询条件（字段筛选/关键词/年龄区间/排序）
     *
     * @param page 页码（从 1 开始）
     *
     * @param size 每页条数
     *
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<OrdinaryNpcVO> page(Long projectId, NpcQuery q, int page, int size) {
        requireProject(projectId);
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        List<ActorCharacter> all = commonNpcs(projectId).stream()
                .filter(n -> match(n, q))
                .sorted(sorter(q))
                .toList();
        // 运行态一次取全（actor_state → npcRef 索引），分页切片后按 ref 回填
        Map<String, NpcActivityStateService.ActivityUpdate> acts = npcActivityStateService.activityByRef(projectId);
        int total = all.size();
        int from = Math.min(total, Math.max(0, (p - 1) * s));
        int to = Math.min(total, from + s);
        List<OrdinaryNpcVO> list = all.subList(from, to).stream().map(n -> toVO(n, acts)).toList();
        return PageResult.of(list, total, p, s);
    }

    /**
     * 项目普通型 NPC（统一主表 {@code type='common'} 且未软删，按主键升序）。
     * <p>纪律：<b>任何"普通型 NPC"的查询都必须带类型过滤</b>——
     * 单表之后不带 type 的查询会把特殊型角色一起捞出来（人群调度/统计/生成去重全部受污染）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return 普通型 NPC 列表（type=common、deleted=0）
     */
    private List<ActorCharacter> commonNpcs(Long projectId) {
        return npcRepository.findByProjectIdAndTypeAndDeletedOrderByIdAsc(projectId, NpcRefs.TYPE_COMMON, 0);
    }

    /** 主表实体 + 运行态 → VO（运行态缺失按 idle 兜底） */
    private static OrdinaryNpcVO toVO(ActorCharacter n,
                                      Map<String, NpcActivityStateService.ActivityUpdate> acts) {
        NpcActivityStateService.ActivityUpdate u = acts == null ? null : acts.get(NpcRefs.of(n.getId()));
        return OrdinaryNpcVO.of(n, u == null ? null : u.state(), u == null ? null : u.lastAction());
    }

    /** 筛选匹配：各字段精确 + 年龄区间 + 关键词（纯静态可测） */
    static boolean match(ActorCharacter n, NpcQuery q) {
        if (q == null) return true;
        if (!blankOrEq(q.gender(), n.getGender())) return false;
        if (!blankOrEq(q.race(), n.getRace())) return false;
        if (!blankOrEq(q.subRace(), n.getSubRace())) return false;
        if (!blankOrEq(q.affiliation(), n.getAffiliation())) return false;
        if (!blankOrEq(q.occupation(), n.getOccupation())) return false;
        if (!blankOrEq(q.location(), n.getLocation())) return false;
        if (q.ageMin() != null && (n.getAge() == null || n.getAge() < q.ageMin())) return false;
        if (q.ageMax() != null && (n.getAge() == null || n.getAge() > q.ageMax())) return false;
        if (q.keyword() != null && !q.keyword().isBlank()) {
            String k = q.keyword().trim();
            boolean hit = contains(n.getName(), k) || contains(n.getGender(), k) || contains(n.getRace(), k)
                    || contains(n.getSubRace(), k) || contains(n.getAffiliation(), k)
                    || contains(n.getOccupation(), k) || contains(n.getLocation(), k) || contains(n.getDetail(), k);
            if (!hit) return false;
        }
        return true;
    }

    /** 空值匹配辅助：过滤值空白=不筛；否则与实体字段 trim 相等（实体为空按空串处理） */
    private static boolean blankOrEq(String filter, String value) {
        if (filter == null || filter.isBlank()) return true;
        String v = value == null ? "" : value.trim();
        return filter.trim().equals(v);
    }

    /** 排序比较器（白名单字段，null 值恒排最后——asc/desc 均如此；纯静态可测） */
    static Comparator<ActorCharacter> sorter(NpcQuery q) {
        String sortBy = q == null || q.sortBy() == null ? "" : q.sortBy().trim();
        Function<ActorCharacter, Object> value = switch (sortBy) {
            case "name" -> ActorCharacter::getName;
            case "age" -> ActorCharacter::getAge;
            case "race" -> ActorCharacter::getRace;
            case "subRace" -> ActorCharacter::getSubRace;
            case "affiliation" -> ActorCharacter::getAffiliation;
            case "occupation" -> ActorCharacter::getOccupation;
            case "location" -> ActorCharacter::getLocation;
            default -> n -> n.getId();
        };
        String dir = q == null || q.sortDir() == null ? "asc" : q.sortDir().trim();
        boolean desc = "desc".equalsIgnoreCase(dir);
        return (a, b) -> {
            Object va = value.apply(a);
            Object vb = value.apply(b);
            // null 值恒排最后（注意：直接 reversed() 会把 nullsLast 反转成 null 排最前，故手动处理）
            if (va == null && vb == null) return 0;
            if (va == null) return 1;
            if (vb == null) return -1;
            int c = compareValues(va, vb);
            return desc ? -c : c;
        };
    }

    /** 值比较（Comparable 直接比，否则字符串兜底） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a instanceof Comparable ca && b instanceof Comparable cb) {
            return ca.compareTo(cb);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /**
     * 手动新增单个普通型 NPC。
     * <p>落库行为统一主表 {@code actor_profile} 且 <b>{@code type=common}</b>
     * （实体默认值是 special，必须显式指定，否则新居民不会出现在任何普通型查询里）；
     * 作息运行态暂无行，VO 按 {@code idle} 回填（首次调度时由状态模块惰性建行）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param dto 档案入参（名称必填）
     *
     * @return 新增后的 VO
     */
    @Transactional
    public OrdinaryNpcVO create(Long projectId, OrdinaryNpcDTO dto) {
        requireProject(projectId);
        validate(dto);
        ActorCharacter e = new ActorCharacter();
        e.setProjectId(projectId);
        // 统一主表路由列：普通型 NPC 必须显式打 type=common
        e.setType(NpcRefs.TYPE_COMMON);
        apply(e, dto);
        ActorCharacter saved = npcRepository.save(e);
        // BrainProfile：单条生成/手动新增普通 NPC 自动建脑力配置行（普通→light/auto(standard)/per_interval；
        // 幂等 ensureProfile，失败仅告警不阻断业务）
        ensureProfileQuietly(saved.getId());
        // 世界沙盘 S2：新增居民 → npc:join 广播 + 惰性建状态行（失败隔离不阻断新增）
        publishJoinQuietly(projectId, saved);
        return toVO(saved, Map.of());
    }

    /**
     * 普通型 NPC（归属校验 + 类型校验）。
     * <p>运行态 {@code state}/{@code lastAction} 不在主表，
     * 回填 VO 时从 {@link NpcActivityStateService} 单条取（编辑回显用，非批量场景）。</p>
     *
     * @param id 主键
     *
     * @param dto 档案入参
     *
     * @return 更新后的 VO
     */
    @Transactional
    public OrdinaryNpcVO update(Long id, OrdinaryNpcDTO dto) {
        ActorCharacter e = requireOwned(id);
        validate(dto);
        apply(e, dto);
        ActorCharacter saved = npcRepository.save(e);
        String ref = NpcRefs.of(saved.getId());
        NpcActivityStateService.ActivityUpdate u = npcActivityStateService.activityOf(saved.getProjectId(), ref);
        return OrdinaryNpcVO.of(saved, u.state(), u.lastAction());
    }

    /**
     * 删除单个普通型 NPC（类型校验：只有 type=common 的行可经此入口删除）。
     *
     * @param id 主键
     */
    @Transactional
    public void delete(Long id) {
        ActorCharacter e = requireOwned(id);
        // 世界沙盘 S2：删除居民 → npc:leave 广播 + 移除状态行（失败隔离不阻断删除）
        publishLeaveQuietly(e.getProjectId(), e);
        npcRepository.delete(e);
    }

    /**
     * 批量删除（多选删除；逐条做 type=common 校验，特殊型角色一律跳过）。
     *
     * @param projectId 项目 ID
     *
     * @param ids 待删除主键列表
     *
     * @return 实际删除数
     */
    @Transactional
    public int batchDelete(Long projectId, List<Long> ids) {
        requireProject(projectId);
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) {
            if (id == null) continue;
            // 统一主表：按 id + 项目定位后必须再校验 type=common，避免误删特殊型角色
            ActorCharacter e = npcRepository.findByIdAndProjectId(id, projectId)
                    .filter(ActorCharacter::isCommon)
                    .orElse(null);
            if (e == null) continue;
            // 世界沙盘 S2：批量删除同样广播 npc:leave + 移除状态行（失败隔离不阻断删除）
            publishLeaveQuietly(projectId, e);
            npcRepository.deleteById(id);
            n++;
        }
        log.info("[普通NPC] 项目 {} 批量删除 {} 条", projectId, n);
        return n;
    }

    /**
     * 批量入库（AI 生成预览确认后保存选中的草稿）。
     * 按名称与已存在 NPC（主表全类型）去重（重名跳过），逐条落库为 {@code type=common}。
     * <p><b>收口</b>：每落一行就取回生成期暂存的 11 维档案（键=项目+姓名），交
     * ③⑤⑥ 由档案服务分派到各自权威模块，本服务<b>绝不</b>自拼 persona_json；
     * 暂存缺失 / 解析失败 / 维度全空一律 {@code log.warn} 后跳过该人
     * （不抛异常、不阻断整批、不写脏档案）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param items 确认后的居民档案列表
     *
     * @return 实际入库数
     */
    public int batchSave(Long projectId, List<OrdinaryNpcDTO> items) {
        requireProject(projectId);
        if (items == null || items.isEmpty()) return 0;
        Set<String> used = new LinkedHashSet<>();
        // 统一主表后普通型与特殊型同表：重名判定一次取全（不再分两次查询）
        npcRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .forEach(n -> used.add(n.getName()));
        int saved = 0;
        for (OrdinaryNpcDTO dto : items) {
            if (dto == null || dto.name() == null || dto.name().isBlank()) continue;
            String nm = dto.name().trim();
            if (!used.add(nm)) continue;
            ActorCharacter e = new ActorCharacter();
            e.setProjectId(projectId);
            // 统一主表路由列：批量入库的居民同样必须显式打 type=common
            e.setType(NpcRefs.TYPE_COMMON);
            apply(e, dto);
            ActorCharacter persisted = npcRepository.save(e);
            // 收口（契约 §7 缺口）：把生成期暂存的 11 维档案交档案服务落库
            // （③→actor_state、⑤→actor_goal、⑥→actor_knowledge_doc，其余留 persona_json；零额外 AI 调用）
            persistPersonaQuietly(projectId, persisted);
            // BrainProfile：AI 生成批量入库的普通 NPC 自动建脑力配置行（幂等 ensureProfile，
            // 失败仅告警不阻断入库——单条建行失败不影响该批其他居民）
            ensureProfileQuietly(persisted.getId());
            // 世界沙盘 S2：批量入库同样广播 npc:join + 惰性建状态行（失败隔离不阻断入库）
            publishJoinQuietly(projectId, persisted);
            saved++;
        }
        log.info("[普通NPC] 项目 {} 批量入库 {} 条（提交 {} 条）", projectId, saved, items.size());
        return saved;
    }

    /**
     * 为新建普通 NPC 幂等建脑力配置行。
     * <p>不建行不阻断业务：ensureProfile 失败仅告警（对齐方案「新建接入失败仅告警」）。</p>
     *
     * @param npcId 普通 NPC ID
     */
    private void ensureProfileQuietly(Long npcId) {
        try {
            brainProfileService.ensureProfile(BrainProfileService.TYPE_ORDINARY, npcId);
        } catch (Exception e) {
            // 脑力配置行建行失败不影响普通 NPC 入库（仅告警，启动补行可兜底）
            log.warn("[脑力配置] 新建普通 NPC 自动建行失败（不影响入库，启动补行兜底）: npc={} : {}",
                    npcId, e.getMessage());
        }
    }

    /**
     * 世界沙盘 S2：新增居民 → npc:join 广播 + 惰性建状态行（失败隔离，不阻断新增/入库）。
     * <p>身份用 {@link NpcRefs#of(Long)}（{@code npc:{id}}）；成员类型用
     * {@link #CONTRACT_TYPE_COMMON}（沙盘 SSE 与前端 3D 契约值，见该常量注释）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npc 已落库的居民
     */
    private void publishJoinQuietly(Long projectId, ActorCharacter npc) {
        try {
            if (worldSandboxStateService != null) {
                worldSandboxStateService.publishJoin(projectId, NpcRefs.of(npc.getId()), CONTRACT_TYPE_COMMON, npc.getName());
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] 居民 join 广播失败（隔离继续）: npc={} : {}", npc.getId(), e.getMessage());
        }
    }

    /**
     * 世界沙盘 S2：删除居民 → npc:leave 广播 + 移除状态行（失败隔离，不阻断删除）。
     *
     * @param projectId 项目 ID
     *
     * @param npc 待删除的居民
     */
    private void publishLeaveQuietly(Long projectId, ActorCharacter npc) {
        try {
            if (worldSandboxStateService != null) {
                worldSandboxStateService.publishLeave(projectId, NpcRefs.of(npc.getId()), CONTRACT_TYPE_COMMON, npc.getName());
            }
        } catch (Exception e) {
            log.warn("[沙盘状态] 居民 leave 广播失败（隔离继续）: npc={} : {}", npc.getId(), e.getMessage());
        }
    }

    /**
     * 统计（总数/主次分类字段分布/归属分布），供页面头部统计展示。
     * 主/次分类字段来自项目调度运行时（AI 选出）。
     * <p>只统计主表 {@code type='common'} 的行。</p>
     *
     * @param projectId 项目 ID
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(Long projectId) {
        requireProject(projectId);
        List<ActorCharacter> all = commonNpcs(projectId);
        ActorCrowdRuntime rt = runtimeRepository.findByProjectId(projectId).orElse(null);
        String primary = rt == null ? null : rt.getPrimaryField();
        String secondary = rt == null ? null : rt.getSecondaryField();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", all.size());
        r.put("primaryField", primary);
        r.put("secondaryField", secondary);
        r.put("byPrimary", fieldDistribution(all, primary));
        r.put("bySecondary", fieldDistribution(all, secondary));
        r.put("byAffiliation", fieldDistribution(all, FIELD_AFFILIATION));
        return r;
    }

    /** 按字段统计分布（值→人数；未配置字段返回空） */
    private static Map<String, Long> fieldDistribution(List<ActorCharacter> npcs, String field) {
        Map<String, Long> by = new LinkedHashMap<>();
        if (field == null || !CLASSIFY_FIELDS.contains(field)) return by;
        for (ActorCharacter n : npcs) {
            String v = fieldValue(n, field);
            by.merge(v, 1L, Long::sum);
        }
        return by;
    }

    // ==================== 标准字段数据（字段字典） ====================

    /**
     * 查询项目字段字典（按字段分组：race/affiliation/occupation，含出处）。
     *
     * @param projectId 项目 ID
     *
     * @return { race: [...], affiliation: [...], occupation: [...] }（无则空列表）
     */
    @Transactional(readOnly = true)
    public Map<String, List<FieldDictVO>> fieldDict(Long projectId) {
        requireProject(projectId);
        Map<String, List<FieldDictVO>> out = new LinkedHashMap<>();
        for (ActorNpcFieldDict d : fieldDictRepository.findByProjectIdOrderByFieldAscSortOrderAscIdAsc(projectId)) {
            out.computeIfAbsent(d.getField(), k -> new ArrayList<>())
                    .add(new FieldDictVO(d.getField(), d.getLevel1(), d.getLevel2(), d.getSource()));
        }
        return out;
    }

    /**
     * AI 依据世界观一次性拟定全部字段字典（race 含次级种族/affiliation/occupation，每条含出处）
     * 并选出主/次分类字段（不落库，供预览确认）。
     *
     * @param projectId 项目 ID
     *
     * @return 拟定预览（fields + classification）
     */
    public FieldDictPreviewVO generateFieldDict(Long projectId) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        String world = renderWorld(projectId);
        if (world.isBlank()) {
            throw new BizException(400, "当前项目暂无世界观设定，请先完善世界观再拟定标准字段数据");
        }
        String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_CROWD_CATEGORY,
                ph("world_name", worldName(projectId), "world_setting", world));
        log.info("[字段字典] 任务开始：项目={}", projectId);
        long start = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", promptTemplateService.systemMessage(userId, projectId,
                                PromptTemplateService.CODE_CROWD_CATEGORY,
                                ph("world_name", worldName(projectId), "world_setting", world))),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, DICT_MAX_TOKENS, true);
                AiChatResult result = AiCallContext.run("crowd_category_gen", null, () ->
                        aiChatGateway.chatCompletion(userId, projectId, null, req));
                FieldDictPreviewVO preview = parseFieldDict(result.content());
                int tokenIn = result.promptTokens() == null ? 0 : result.promptTokens();
                int tokenOut = result.completionTokens() == null ? 0 : result.completionTokens();
                log.info("[字段字典] AI 输出：{}", result.content());
                log.info("[字段字典] 任务结束：项目={} 耗时={}ms tokens={}/{} 主字段={} 次字段={} race={} affiliation={} occupation={}",
                        projectId, System.currentTimeMillis() - start, tokenIn, tokenOut,
                        preview.primaryField(), preview.secondaryField(),
                        preview.fields().getOrDefault(FIELD_RACE, List.of()).size(),
                        preview.fields().getOrDefault(FIELD_AFFILIATION, List.of()).size(),
                        preview.fields().getOrDefault(FIELD_OCCUPATION, List.of()).size());
                usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "crowd_category_gen",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                return preview;
            } catch (Exception e) {
                lastError = e;
                log.warn("[字段字典] 项目 {} 第 {} 次失败: {}", projectId, attempt, e.getMessage());
            }
        }
        log.warn("[字段字典] 任务失败：项目={} 耗时={}ms：{}", projectId, System.currentTimeMillis() - start,
                lastError == null ? "未知错误" : lastError.getMessage());
        throw new BizException(400, "字段字典拟定失败：" + friendlyError(lastError) + "，请稍后重试");
    }

    /**
     * 保存字段字典（预览确认后整体替换）+ 保存主/次分类字段（写 actor_crowd_runtime）。
     *
     * @param projectId 项目 ID
     *
     * @param preview 确认后的预览（fields + classification）
     *
     * @return 保存后的字段字典分组
     */
    @Transactional
    public Map<String, List<FieldDictVO>> saveFieldDict(Long projectId, FieldDictPreviewVO preview) {
        requireProject(projectId);
        if (preview == null) throw new BizException(400, "字段字典内容不能为空");
        String primary = normalizeField(preview.primaryField());
        String secondary = normalizeField(preview.secondaryField());
        if (primary == null || secondary == null) {
            throw new BizException(400, "请选择主/次分类字段（从 种族/归属/职业 中各选一个）");
        }
        if (primary.equals(secondary)) {
            throw new BizException(400, "主分类字段与次分类字段不能相同");
        }
        fieldDictRepository.deleteByProjectId(projectId);
        Map<String, List<FieldDictVO>> cleaned = cleanFieldDict(preview.fields());
        int sort = 0;
        for (Map.Entry<String, List<FieldDictVO>> e : cleaned.entrySet()) {
            for (FieldDictVO v : e.getValue()) {
                ActorNpcFieldDict d = new ActorNpcFieldDict();
                d.setProjectId(projectId);
                d.setField(e.getKey());
                d.setLevel1(v.level1());
                d.setLevel2(v.level2());
                d.setSource(v.source());
                d.setSortOrder(sort++);
                fieldDictRepository.save(d);
            }
        }
        ActorCrowdRuntime rt = runtime(projectId);
        rt.setPrimaryField(primary);
        rt.setSecondaryField(secondary);
        runtimeRepository.save(rt);
        log.info("[字段字典] 项目 {} 保存 {} 条（主={} 次={}）", projectId, cleaned.values().stream()
                .mapToInt(List::size).sum(), primary, secondary);
        return fieldDict(projectId);
    }

    /**
     * 手动新增一条字段字典。
     *
     * @param projectId 项目 ID
     *
     * @param entry 条目（field/level1/level2/source）
     *
     * @return 新增后的条目
     */
    @Transactional
    public FieldDictVO addFieldDictEntry(Long projectId, FieldDictVO entry) {
        requireProject(projectId);
        String field = normalizeField(entry == null ? null : entry.field());
        if (field == null) throw new BizException(400, "字段名不合法（应为 race/affiliation/occupation）");
        String l1 = truncate(entry.level1(), RACE_MAX);
        String l2 = field.equals(FIELD_RACE) ? truncate(entry.level2(), SUB_RACE_MAX) : null;
        if (l1 == null || l1.isBlank()) throw new BizException(400, "一级值不能为空");
        int maxSort = fieldDictRepository.findByProjectIdAndFieldOrderBySortOrderAscIdAsc(projectId, field).stream()
                .mapToInt(d -> d.getSortOrder() == null ? 0 : d.getSortOrder()).max().orElse(-1);
        ActorNpcFieldDict d = new ActorNpcFieldDict();
        d.setProjectId(projectId);
        d.setField(field);
        d.setLevel1(l1);
        d.setLevel2(l2);
        d.setSource(truncate(entry.source(), SOURCE_MAX));
        d.setSortOrder(maxSort + 1);
        fieldDictRepository.save(d);
        return new FieldDictVO(field, l1, l2, d.getSource());
    }

    /**
     * 手动删除一条字段字典（race 二级删除需传 level2；其余字段 level2 空）。
     *
     * @param projectId 项目 ID
     *
     * @param field 字段名
     *
     * @param l1 一级值
     *
     * @param l2 二级值（可空）
     */
    @Transactional
    public void deleteFieldDictEntry(Long projectId, String field, String l1, String l2) {
        requireProject(projectId);
        String f = normalizeField(field);
        if (f == null) return;
        fieldDictRepository.findByProjectIdAndFieldOrderBySortOrderAscIdAsc(projectId, f).stream()
                .filter(d -> d.getLevel1() != null && d.getLevel1().equals(l1)
                        && (l2 == null || l2.isBlank() || "undefined".equals(l2) || (d.getLevel2() != null && d.getLevel2().equals(l2))))
                .findFirst()
                .ifPresent(d -> {
                    fieldDictRepository.delete(d);
                    log.info("[字段字典] 项目 {} 删除 {} / {} / {}", projectId, f, l1, l2);
                });
    }

    /**
     * 字段名归一（race/affiliation/occupation，其余返回 null）
     * <p>收口：白名单与归一逻辑收敛到 {@link com.holzyn.actor.common.NpcClassifyFields}，
     * 导入路径与运行路径共用一份口径（此前导入会把白名单外的字段名原样落库）。</p>
     */
    private static String normalizeField(String field) {
        return com.holzyn.actor.common.NpcClassifyFields.normalize(field);
    }

    /**
     * 解析 AI 拟定的字段字典预览（纯静态可测）。
     * <p>输出结构：{ "classification": {"primary":"race","secondary":"affiliation"},
     * 清洗：字段名归一、一级非空、race 二级容错、去重、数量与长度兜底。</p>
     *
     * @param content AI 输出文本
     *
     * @return 拟定预览（fields 为空时也返回空的 race/affiliation/occupation 列表）
     */
    static FieldDictPreviewVO parseFieldDict(String content) {
        String primary = null;
        String secondary = null;
        Map<String, List<FieldDictVO>> fields = new LinkedHashMap<>();
        fields.put(FIELD_RACE, new ArrayList<>());
        fields.put(FIELD_AFFILIATION, new ArrayList<>());
        fields.put(FIELD_OCCUPATION, new ArrayList<>());
        if (content == null || content.isBlank()) {
            return new FieldDictPreviewVO(primary, secondary, fields);
        }
        JsonNode node = parseQuiet(JsonUtil.extractJson(content));
        if (node == null || !node.isObject()) {
            node = parseQuiet(extractArrayOrObject(content));
        }
        if (node == null || !node.isObject()) {
            return new FieldDictPreviewVO(primary, secondary, fields);
        }
        JsonNode cls = node.path("classification");
        if (cls.isObject()) {
            primary = normalizeField(text(cls, "primary"));
            secondary = normalizeField(text(cls, "secondary"));
        }
        JsonNode flds = node.path("fields");
        if (flds.isObject()) {
            for (String f : List.of(FIELD_RACE, FIELD_AFFILIATION, FIELD_OCCUPATION)) {
                JsonNode arr = flds.path(f);
                if (!arr.isArray()) continue;
                Set<String> seen = new LinkedHashSet<>();
                for (JsonNode it : arr) {
                    if (it == null || !it.isObject()) continue;
                    String l1 = text(it, "level1").trim();
                    if (l1.isEmpty()) continue;
                    String l2 = f.equals(FIELD_RACE) ? text(it, "level2").trim() : "";
                    String key = l1 + "|" + l2;
                    if (!seen.add(key)) continue;
                    if (seen.size() > DICT_L1_MAX + 200) break;
                    String src = text(it, "source").trim();
                    if (src.isEmpty()) src = "取自世界观设定";
                    fields.get(f).add(new FieldDictVO(f,
                            truncate(l1, RACE_MAX),
                            l2.isEmpty() ? null : truncate(l2, SUB_RACE_MAX),
                            truncate(src, SOURCE_MAX)));
                }
            }
        }
        return new FieldDictPreviewVO(primary, secondary, fields);
    }

    /** 清洗字段字典（保存前：字段名归一、一级非空、去重、数量兜底） */
    static Map<String, List<FieldDictVO>> cleanFieldDict(Map<String, List<FieldDictVO>> raw) {
        Map<String, List<FieldDictVO>> out = new LinkedHashMap<>();
        out.put(FIELD_RACE, new ArrayList<>());
        out.put(FIELD_AFFILIATION, new ArrayList<>());
        out.put(FIELD_OCCUPATION, new ArrayList<>());
        if (raw == null) return out;
        for (String f : List.of(FIELD_RACE, FIELD_AFFILIATION, FIELD_OCCUPATION)) {
            List<FieldDictVO> list = raw.get(f);
            if (list == null) continue;
            Set<String> seen = new LinkedHashSet<>();
            Map<String, Integer> l1Count = new LinkedHashMap<>();
            for (FieldDictVO v : list) {
                if (v == null) continue;
                String l1 = truncate(v.level1(), RACE_MAX);
                if (l1 == null || l1.isBlank()) continue;
                String l2 = f.equals(FIELD_RACE) ? truncate(v.level2(), SUB_RACE_MAX) : null;
                String key = l1 + "|" + (l2 == null ? "" : l2);
                if (!seen.add(key)) continue;
                if (l1Count.merge(l1, 1, Integer::sum) > DICT_L2_MAX) continue;
                out.get(f).add(new FieldDictVO(f, l1, l2, truncate(v.source(), SOURCE_MAX)));
            }
        }
        return out;
    }

    // ==================== AI 批量生成（SSE） ====================

    /**
     * AI 分批生成普通型 NPC（预览，不落库）。
     * <p>流程：校验字段字典存在 + 世界观存在 → 按每批 {@link #effectiveBatchSize()} 人（默认
     * {@link #GEN_PERSONA_BATCH_DEFAULT}）循环调用 AI（依据世界观 + 字段字典 + 地点清单 + 已存在名单），
     * 解析（结构列草稿 + 每位居民的 11 维档案原文）并过滤重名 → 档案原文暂存
     * （见 {@link #pendingPersonaJson}）→ 逐条回调（SSE 逐条推送 + 后端逐条日志）→ 全部完成后回调 done。
     * 单批失败自动重试 {@link #MAX_RETRY} 次后跳过继续。</p>
     * <p>生成字段（种族/次级种族/归属/职业/所在地）必须从字段字典/地点清单中选取，避免 OOC；
     * 不含关系（关系在 NPC 生成后单独生成）。<b>本方法只产出预览</b>：profileId（{@code actor_profile} 行）
     * 要等确认后 {@link #batchSave} 才创建，11 维档案因此暂存到那时落库（零额外 AI 调用）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param count 目标生成数量（1~500）
     *
     * @param progress 进度回调（可为 null）
     */
    public void generateStream(Long projectId, int count, GenerateProgress progress) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        int target = Math.max(1, Math.min(MAX_NPC, count));
        // 收口：带档案生成的有效批大小（默认 5；由单人 11 维档案的 token 预算决定，见 effectiveBatchSize）
        int batchSize = effectiveBatchSize();
        Map<String, List<ActorNpcFieldDict>> dict = fieldDictEntities(projectId);
        if (dict.get(FIELD_RACE).isEmpty() || dict.get(FIELD_AFFILIATION).isEmpty()) {
            throw new BizException(400, "请先拟定并确认标准字段数据（至少包含种族与归属），再生成居民");
        }
        String dictText = renderFieldDict(dict);
        String locations = renderLocations(projectId);
        String world = renderWorld(projectId);
        if (world.isBlank()) {
            throw new BizException(400, "当前项目暂无世界观设定，请先完善世界观再生成居民");
        }
        // 已存在姓名
        Set<String> usedNames = new LinkedHashSet<>();
        npcRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).forEach(c -> {
            if (c.getName() != null && !c.getName().isBlank()) {
                usedNames.add(c.getName().trim());
            }
        });

        log.info("[居民生成] 任务开始：项目={} 目标={} 每批={}", projectId, target, batchSize);
        long start = System.currentTimeMillis();
        if (progress != null) progress.onStart(target, batchSize);
        int generated = 0;
        int batchNo = 0;
        int failedBatches = 0;
        for (int remaining = target; remaining > 0; ) {
            batchNo++;
            int need = Math.min(batchSize, remaining);
            remaining -= need;
            boolean batchOk = false;
            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                try {
                    String existingText = usedNames.isEmpty() ? "（暂无）" : String.join("、", usedNames);
                    Map<String, Object> placeholders = ph(
                            "world_name", worldName(projectId),
                            "world_setting", world,
                            "field_dict", dictText,
                            "locations", locations,
                            "existing_names", existingText,
                            "count", String.valueOf(need));
                    String prompt = promptTemplateService.render(userId, projectId,
                            PromptTemplateService.CODE_CROWD_NPC_GEN, placeholders);
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", promptTemplateService.systemMessage(userId, projectId,
                                    PromptTemplateService.CODE_CROWD_NPC_GEN, placeholders)),
                            new AiChatRequest.ChatMessage("user", prompt)), 0.5, NPC_MAX_TOKENS, true);
                    AiChatResult result = AiCallContext.run("ordinary_npc_gen", null, () ->
                            aiChatGateway.chatCompletion(userId, projectId, null, req));
                    // 收口：数组元素 = 结构列草稿 + 该居民的 11 维档案原文（同批产出，零额外 AI 调用）
                    List<GeneratedResident> parsed = parseGeneratedResidents(result.content());
                    // 过滤重名（已存在 + 本次已生成），去重后逐条回调
                    List<GeneratedResident> valid = new ArrayList<>();
                    for (GeneratedResident r : parsed) {
                        String nm = r.draft().name() == null ? "" : r.draft().name().trim();
                        if (nm.isEmpty() || !usedNames.add(nm)) continue;
                        valid.add(r);
                    }
                    int withPersona = 0;
                    for (GeneratedResident r : valid) {
                        OrdinaryNpcDraftVO d = r.draft();
                        generated++;
                        // 档案原文暂存：profileId 要等"确认落库"时才存在（见 pendingPersonaJson 注释）
                        if (r.personaJson() == null || r.personaJson().isBlank()) {
                            log.warn("[居民生成] 居民 {} 的 AI 输出未含 11 维档案 dims"
                                    + "（结构列照常入库、档案本次跳过）：项目={}", d.name(), projectId);
                        } else {
                            stashPersona(projectId, d.name(), r.personaJson());
                            withPersona++;
                        }
                        log.info("[居民生成] 已生成居民 #{}：{}（{} / {} / {}）", generated,
                                d.name(), d.race(), d.subRace(), d.affiliation());
                        if (progress != null) progress.onNpc(d, generated);
                    }
                    int tokenIn = result.promptTokens() == null ? 0 : result.promptTokens();
                    int tokenOut = result.completionTokens() == null ? 0 : result.completionTokens();
                    log.info("[居民生成] 第 {} 批输出：{}", batchNo, result.content());
                    log.info("[居民生成] 第 {} 批完成：识别 {} 条 有效 {} 条（含档案 {} 条）已生成 {}/{} 耗时={}ms tokens={}/{}",
                            batchNo, parsed.size(), valid.size(), withPersona, generated, target,
                            System.currentTimeMillis() - start, tokenIn, tokenOut);
                    usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "ordinary_npc_gen",
                            result.promptTokens(), result.completionTokens(),
                            result.cacheHitTokens(), result.cacheMissTokens(),
                            (int) (System.currentTimeMillis() - start));
                    batchOk = true;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    log.warn("[居民生成] 项目 {} 第 {} 批第 {} 次失败: {}", projectId, batchNo, attempt, e.getMessage());
                }
            }
            if (!batchOk) {
                failedBatches++;
                log.warn("[居民生成] 第 {} 批失败（已重试 {} 次），跳过继续：{}", batchNo, MAX_RETRY,
                        lastError == null ? "未知错误" : lastError.getMessage());
            }
        }
        log.info("[居民生成] 任务结束：项目={} 目标={} 实际生成={} 失败批次={} 耗时={}ms",
                projectId, target, generated, failedBatches, System.currentTimeMillis() - start);
        if (progress != null) progress.onDone(target, generated, failedBatches);
    }

    /**
     * 普通型 NPC 生成进度回调（供 SSE 流式推送：任务开始 / 每条居民 / 完成）。
     */
    public interface GenerateProgress {
        /**
         * 任务开始。
         *
         * @param total 目标生成数量
         *
         * @param batchSize 每批大小
         */
        default void onStart(int total, int batchSize) {
        }

        /**
         * 每生成一条居民回调一次。
         *
         * @param draft 已生成并清洗的草稿
         *
         * @param index 当前累计序号（从 1 开始）
         */
        void onNpc(OrdinaryNpcDraftVO draft, int index);

        /**
         * 全部完成。
         *
         * @param total 目标生成数量
         *
         * @param generated 实际生成数量
         *
         * @param failedBatches 失败批次（可能少于目标，可再次生成补齐）
         */
        default void onDone(int total, int generated, int failedBatches) {
        }
    }

    // ==================== 调度 ====================

    /**
     * 程序化状态机调度（定时任务 + AI 失败降级路径，零 AI 成本）。
     * 按当前真实小时推进项目全部普通型 NPC 状态（walk/stop/talk/rest）与行动描述，并生成环境快照。
     *
     * @param projectId 项目 ID
     *
     * @return 调度结果 Map（mode/hour/states/summary）
     */
    // ⚠️ 世界运行抓修：本方法体内有 AI 调用（人群分层驱动的 npc_crowd_decision，
// 实测 avg 6.2s/max 7.1s × 每次调度多个居民；ultimate 档还会走 generateCrowdPlanAi 生成日计划），
// 原 @Transactional 让整个「一批居民的调度」共用一个事务——事务从第一次写 actor_state 起就一直
// 持有行锁，直到最后一个居民的 AI 决策返回才提交。
// 后果（jstack/日志实证）：情绪评估（NpcStateService 的 virtual-* 线程）写 actor_state 时反复
// 撞上 10s 锁超时 → 重试 3 次仍失败 → 本轮 AI 情绪白做（世界运行实测 17 次锁超时、2 次情绪丢失）。
// 修法与 PlanningService 同口径：「AI 在事务外，写阶段短事务」——去掉本层事务后，循环内的每个
// 写入点各自成短事务（applyCrowdAction/onCrowdSchedule/setActivities/setDailyPlans/
// syncCrowdStates 均自带 @Transactional），锁只在自己那次写入的毫秒级窗口内存在。
    public Map<String, Object> scheduleProgrammatic(Long projectId) {
        return scheduleProgrammaticByGameHour(projectId, LocalDateTime.now().getHour());
    }

    /**
     * 程序化状态机调度（世界模拟用，按游戏小时驱动，替代真实小时）。
     * <p>B3（世界推进引擎）：保留双参数版供手动调试入口调用（事件 game_hour 回退世界时钟当前值）；
     * 引擎逐时推进请用三参数版。
     * <p>时间口径统一：手动入口的时间基准也改取<b>引擎推进位置</b>
     * （currentEngineGameHour=lastGameHour）而非流动时钟（currentGameHour=速率×真实流逝的显示钟）——
     * 流动时钟在锚点久远/高速率下可能远超引擎位置（本冒烟：455h vs 引擎 47h），会把 crowd 状态行
     * 一次性虚假衰减数百小时（hunger/energy 归零、mood 崩盘），与状态模块「单一时间源=引擎推进位置」
     * 原则冲突；事件 game_hour 一并取引擎位置，与三参数版口径一致。</p>
     *
     * @param projectId 项目 ID
     *
     * @param hour 推进用的小时（0-23，游戏小时）
     *
     * @return 调度结果 Map（mode/hour/states/summary）
     */
    // ⚠️ 世界运行抓修：同上——本方法委托三参版（内含人群 AI 决策），不得在本层开事务
    // （否则 AI 又在事务里跑，锁争用问题原样复现）。
    public Map<String, Object> scheduleProgrammaticByGameHour(Long projectId, int hour) {
        return scheduleProgrammaticByGameHour(projectId, hour, worldClockService.currentEngineGameHour(projectId));
    }

    /**
     * 程序化状态机调度（世界推进引擎用，按<b>引擎传入的游戏时刻</b>驱动，B3）。
     * <p>与双参数版差异：居民活动聚合事件的 game_hour/source_key 使用引擎推进到的游戏时刻
     * （显式 gameHour），而非世界时钟当前值——逐时回补推进时事件时刻与推进位置一致
     * （修 -2 真实钟分叉；source_key={@code npc:{projectId}:{gameHour}} 幂等防重复发布，
     * 统一前缀见 {@link NpcRefs#of(Long)}）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param hour 推进用的小时（0-23，游戏小时）
     *
     * @param gameHour 引擎推进到的游戏时刻（小时数，自纪元起；事件 game_hour 与幂等键用）
     *
     * @return 调度结果 Map（mode/hour/states/summary）
     */
    // ⚠️ 世界运行抓修：本方法是「AI 调用在事务内」的最长持有者，去掉 @Transactional。
    // 证据：世界运行日志出现 17 次 `Timeout trying to lock table "actor_state"`，全部来自
    // 情绪评估的 virtual-* 线程（每次锁超时 = 10s 无进展），其中 2 次重试 3 次后彻底失败、
    // 已付费的 AI 情绪结果被丢弃；同一时刻本方法正在循环里为多个居民做 AI 决策，
    // 事务持锁等待 AI（每个 6s 级、多个累加）。
    // 语义不变性：本方法自身只做「读 → 决策 → 委托写入」，所有写都由下游自带事务的服务完成，
    // 去掉外层事务不会产生半截写入；代价是极端失败下「部分居民已调度、部分未调度」，
    // 但下一游戏小时会重新调度（幂等），远优于持锁数十秒拖垮整世界的状态写回。
    public Map<String, Object> scheduleProgrammaticByGameHour(Long projectId, int hour, Long gameHour) {
        requireProject(projectId);
        // ：居民 = 统一主表 type='common' 且未软删的行
        List<ActorCharacter> npcs = commonNpcs(projectId);
        if (npcs.isEmpty()) {
            return Map.of("mode", "programmatic", "hour", hour, "states", Map.of(), "summary", "（暂无普通型 NPC）");
        }
        // 运行态批量取全（1 次查询）：计划（当日计划判定）+ 状态（收尾计数）循环内只读内存
        // （拷贝为可变 Map：ensureDailyPlan 会把本轮新生成的计划同步进来，供本轮状态机立即读到）
        Map<String, String> planByRef = new LinkedHashMap<>(npcActivityStateService.dailyPlanByRef(projectId));
        // ：先确保「当日计划」（机制化/AI，run_mode=ultimate 时 AI），状态机优先读计划（行为不再重复单一化）
        ensureDailyPlan(projectId, npcs, gameHour, planByRef);
        // 事件感知：查询本游戏小时内的重要事件（重要度≥阈值），计划被事件打断一次（当日小时行动变更）
        Long disruptEventId = significantEventAt(projectId, gameHour);
        Random rng = new Random();
        Map<String, Long> stateCounts = new LinkedHashMap<>();
        // ：本轮程序化路径的运行态写回缓冲（循环内只写内存，循环结束一次性批量落 actor_state）
        Map<String, NpcActivityStateService.ActivityUpdate> writes = new LinkedHashMap<>();
        // 分层驱动（§8.2）：驱动动作统一按引擎推进到的游戏时刻（秒）应用
        Long driveGameSecond = gameHour == null ? null : gameHour * 3600L;
        // ：本轮已由分层驱动（AI/概率化）完成行动恢复的 NPC id 集合——作息挂钩对其跳过
        // rest 双恢复（驱动 rest 动作已经 onNpcAction 恢复睡眠， 冒烟抓修）
        java.util.Set<Long> actionRecoveredIds = new java.util.HashSet<>();
        for (ActorCharacter n : npcs) {
            String npcRef = NpcRefs.of(n.getId());
            // 分层驱动：场景人数 ≤ ai-crowd-threshold → AI 建议式（BudgetGuard 门控，失败回退程序化）；
            // 场景人数 > 阈值 → 概率化需求满足 + 需求-地点/对象评分（Sims 模型，零 AI 成本）；
            // 状态模块未启用/无候选 → 程序化状态机兜底（对齐既有 computeState）
            if (npcStateService != null) {
                CrowdDecisionParse.CrowdAction drive = null;
                // ：all-npc-hourly=true 时 crowd 批量 AI 运行时停用——
                // 普通 NPC 由世界引擎逐人决策，本批次只走程序化状态机兜底（dormant 语义）
                if (!crowdBatchAiDisabled() && npcStateService.isCrowdAiEligible(projectId, npcRef)) {
                    // AI 建议式：预算不足 → 程序化兜底（预算即成本控制，不冻结）
                    if (budgetGuard == null || budgetGuard.tryConsume(projectId)) {
                        drive = npcStateService.decideCrowdAction(projectId, n, driveGameSecond);
                    }
                } else if (npcStateService.isProbabilisticDriveEnabled()) {
                    drive = npcStateService.probabilisticDrive(projectId, n, driveGameSecond, rng);
                }
                if (drive != null) {
                    // 应用驱动动作：状态模块把 state/lastAction 写 actor_state + onNpcAction 恢复需求/情绪
                    //
                    npcStateService.applyCrowdAction(projectId, n, drive, driveGameSecond);
                    // 宏观社会演化系统：人群行动微观入账（规则映射；旁路增强
                    // 失败仅告警不阻断调度主流程）
                    if (macroLedgerService != null) {
                        try {
                            macroLedgerService.recordCrowdAction(projectId, npcRef,
                                    n.getName(), drive.action(), drive.actionType(), driveGameSecond);
                        } catch (Exception e) {
                            log.warn("[人群] 宏观入账挂钩失败（隔离继续）: npc={} : {}", n.getId(), e.getMessage());
                        }
                    }
                    actionRecoveredIds.add(n.getId());
                    // 状态计数不在此处合并：驱动分支的状态由状态模块写入 actor_state，收尾统一批量读口径
                    continue;
                }
            }
            String state = computeState(planByRef.get(npcRef), hour, rng, disruptEventId);
            String action = describeAction(n, state, hour);
            // ：只进内存缓冲，循环结束批量写 actor_state（替代原逐 NPC 实体回写 + saveAll）
            writes.put(npcRef, new NpcActivityStateService.ActivityUpdate(state, action));
            // 宏观社会演化系统：程序化作息路径同样入账（按行动文本关键词映射；
            // 旁路增强失败仅告警）
            if (macroLedgerService != null) {
                try {
                    macroLedgerService.recordCrowdAction(projectId, npcRef, n.getName(), action, null, driveGameSecond);
                } catch (Exception e) {
                    log.warn("[人群] 宏观入账挂钩失败（隔离继续）: npc={} : {}", n.getId(), e.getMessage());
                }
            }
        }
        // ：程序化路径运行态批量写回（1 次载入 + 1 次 saveAll；驱动分支由状态模块各自落库）
        npcActivityStateService.setActivities(projectId, writes);
        // 状态计数：以 actor_state 为唯一权威，收尾批量读一次（含驱动分支刚写入的行），
        // 避免逐 NPC 查库、也避免从实体读已迁走的字段
        Map<String, String> finalStates = npcActivityStateService.activityStateByRef(projectId);
        for (ActorCharacter n : npcs) {
            stateCounts.merge(finalStates.getOrDefault(NpcRefs.of(n.getId()), NpcActivityStateService.DEFAULT_ACTIVITY),
                    1L, Long::sum);
        }
        // 世界沙盘 S1：人群调度后同步沙盘状态（惰性建行 + 动作/游戏时刻刷新；失败隔离不阻断调度）
        if (worldSandboxStateService != null) {
            try {
                worldSandboxStateService.syncCrowdStates(projectId, gameHour == null ? null : gameHour * 3600L);
            } catch (Exception e) {
                log.warn("[人群调度] 沙盘状态同步失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        // 状态；失败隔离不阻断调度）
        if (npcStateService != null) {
            try {
                npcStateService.onCrowdSchedule(projectId, gameHour == null ? null : gameHour * 3600L, npcs, actionRecoveredIds);
            } catch (Exception e) {
                log.warn("[人群调度] 状态衰减/恢复挂钩失败（隔离继续）: project={} : {}", projectId, e.getMessage());
            }
        }
        String summary = buildSnapshot(npcs, stateCounts, hour);
        ActorCrowdRuntime rt = runtime(projectId);
        rt.setLatestSummary(summary);
        rt.setLastScheduleAt(LocalDateTime.now());
        runtimeRepository.save(rt);
        log.info("[人群调度] 程序化推进：项目={} 居民={} 时段={}点 状态={} 事件打断={}",
                projectId, npcs.size(), hour, stateCounts, disruptEventId != null);
        // B1（世界事件总线）：居民活动聚合事件（kind=居民活动类型，每小时 1 条/项目，不逐人）——
        // 修断链3（L-03）：普通 NPC 产出进事件流，他角色可查询/感知；source_key={npc:{projectId}}:{gameHour}
        // 幂等防重复发布；事件发布开关关闭时 publish 返回 null（回到 B1 前：仅 latestSummary 字符串）。
        publishCrowdEvent(projectId, hour, stateCounts, summary, gameHour);
        return Map.of("mode", "programmatic", "hour", hour, "states", stateCounts, "summary", summary);
    }

    /**
     * 发布居民活动聚合事件（B1）。
     * <p>kind/source={@link #CONTRACT_TYPE_COMMON}（对外事件契约值，见该常量注释）、visibility=global
     * 、payload=居民状态计数 JSON、game_hour=世界时钟当前游戏时刻（统一时间口径）、
     * source_key={code npc:{projectId}:{gameHour}}（每小时 1 条幂等，双推进线并发不重复发布）。</p>
     * <p>B3：新增显式 gameHour 重载——引擎逐时推进时传入引擎推进到的游戏时刻，
     * 事件时刻与推进位置一致；双参数版回退世界时钟当前值（手动调试入口用）。</p>
     * <p>发布失败仅告警不阻断状态机推进（事件流是旁路增强）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param hour 推进用小时（0-23，游戏小时）
     *
     * @param stateCounts 状态计数（walk/stop/talk/rest → 人数）
     *
     * @param summary 环境快照文本（buildSnapshot 产出，作事件内容）
     *
     * @param gameHour 引擎推进到的游戏时刻（小时数；null=回退世界时钟当前值）
     */
    private void publishCrowdEvent(Long projectId, int hour, Map<String, Long> stateCounts, String summary, Long gameHour) {
        try {
            Long gh = gameHour != null ? gameHour : worldClockService.currentGameHour(projectId);
            ActorProject project = projectRepository.findById(projectId).orElse(null);
            Long ownerId = project == null ? currentUserProvider.currentUserId() : project.getUserId();
            worldEventBus.publish(ownerId, projectId, EventSpec.builder()
                    .kind(CONTRACT_TYPE_COMMON)
                    .source(CONTRACT_TYPE_COMMON)
                    .visibility("global")
                    .title("居民活动")
                    .content(summary == null || summary.isBlank() ? "居民按作息正常活动。" : summary)
                    .payloadJson(writeJson(stateCounts))
                    .gameHour(gh)
                    .sourceKey(NpcRefs.of(projectId) + ":" + (gh == null ? hour : gh))
                    .build());
        } catch (Exception e) {
            log.warn("[人群调度] 居民活动事件发布失败: project={} : {}", projectId, e.getMessage());
        }
    }

    /**
     * 发布居民活动聚合事件（双参数版，手动调试入口用：game_hour 回退世界时钟当前值）。
     *
     * @param projectId 项目 ID
     *
     * @param hour 推进用小时（0-23，游戏小时）
     *
     * @param stateCounts 状态计数
     *
     * @param summary 环境快照文本
     */
    private void publishCrowdEvent(Long projectId, int hour, Map<String, Long> stateCounts, String summary) {
        publishCrowdEvent(projectId, hour, stateCounts, summary, null);
    }

    // ====================：普通 NPC 当日计划 + 事件感知 ====================

    /**
     * 确保项目全部普通 NPC 的「当日计划」（{@code actor_state.daily_plan_json}，）：
     * 计划缺失或游戏日过期时重新生成——run_mode=ultimate 时优先 AI 生成（预算门控、失败回退机制），
     * 否则机制化确定性生成（零 AI 成本）；生成后 computeState 优先读计划（行为不再重复单一化）。
     * <p><b>批量口径</b>：既有计划由调用方用
     * {@link NpcActivityStateService#dailyPlanByRef} 一次取全后以 {@code planByRef} 传入（内存判定），
     * 本轮新生成/更新的计划收集到 {@code writes} 后<b>一次性批量写回</b>
     * （{@link NpcActivityStateService#setDailyPlans}）——不再逐 NPC 查库、也不再把计划写回主表实体。
     * 新计划同时<b>回填进 {@code planByRef}</b>，保证本轮状态机（{@code computeState}）立刻读到当日计划，
     * 与改造前"生成即可用"的行为一致。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npcs 项目全部普通 NPC（type=common）
     *
     * @param gameHour 当前游戏小时（计划所属游戏日编号基准；可空=世界时钟）
     *
     * @param planByRef 既有当日计划（npcRef → daily_plan_json；可变 Map，本轮新计划会回填进来）
     */
    private void ensureDailyPlan(Long projectId, List<ActorCharacter> npcs, Long gameHour, Map<String, String> planByRef) {
        if (npcs == null || npcs.isEmpty()) {
            return;
        }
        Long gh = gameHour != null ? gameHour : worldClockService.currentGameHour(projectId);
        if (gh == null) {
            gh = 0L;
        }
        long gameDay = Math.floorDiv(gh, 24);
        boolean ultimate = RunModes.isUltimate(runModeOf(projectId));
        Map<String, String> writes = new LinkedHashMap<>();
        for (ActorCharacter n : npcs) {
            String npcRef = NpcRefs.of(n.getId());
            String existing = planByRef == null ? null : planByRef.get(npcRef);
            if (existing != null && !existing.isBlank() && dailyPlanForDay(existing, gameDay)) {
                continue; // 当日计划已存在
            }
            String plan;
            if (ultimate && budgetGuard != null && budgetGuard.tryConsume(projectId)) {
                plan = generateCrowdPlanAi(projectId, n, gameDay);
                if (plan == null) {
                    plan = mechanismDailyPlan(gameDay, crowdSeed(n));
                }
            } else {
                plan = mechanismDailyPlan(gameDay, crowdSeed(n));
            }
            writes.put(npcRef, plan);
            if (planByRef != null) {
                planByRef.put(npcRef, plan);
            }
        }
        // ：本轮生成/更新的计划一次性批量落 actor_state（1 次载入 + 1 次 saveAll）
        npcActivityStateService.setDailyPlans(projectId, writes);
    }

    /**
     * 普通 NPC 机制化当日计划（，零 AI 成本，静态可测）：确定性生成 5~8 个时段条目
     * （对齐既有作息规则），个体差异由 seed（npc.id + 游戏日）微调——同一小时不再反复重算相同状态。
     *
     * @param gameDay 游戏日编号（>=0）
     *
     * @param seed 个体稳定种子（npc.id）
     */
    static String mechanismDailyPlan(long gameDay, int seed) {
        Random rng = new Random(seed * 31L + gameDay);
        int n = 5 + rng.nextInt(4); // 5~8 条
        List<Map<String, Object>> items = new ArrayList<>();
        int[] anchors = {6, 8, 11, 13, 15, 18, 20, 23};
        for (int i = 0; i < n && i < anchors.length; i++) {
            int hour = anchors[i];
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hour", hour);
            switch (i) {
                case 0 -> {
                    item.put("state", "walk");
                    item.put("action", "晨起出门，前往日常活动地点");
                }
                case 1 -> {
                    item.put("state", "stop");
                    item.put("action", "从事上午的劳作");
                }
                case 2 -> {
                    item.put("state", rng.nextBoolean() ? "talk" : "stop");
                    item.put("action", rng.nextBoolean() ? "与邻里交谈交换消息" : "采购或处理生活事务");
                }
                case 3 -> {
                    item.put("state", "rest");
                    item.put("action", "午间小憩歇息");
                }
                case 4 -> {
                    item.put("state", "stop");
                    item.put("action", "继续下午的事务");
                }
                case 5 -> {
                    item.put("state", "walk");
                    item.put("action", "收工归途，返回住处");
                }
                case 6 -> {
                    item.put("state", "talk");
                    item.put("action", "在茶楼/广场与熟人攀谈");
                }
                default -> {
                    item.put("state", "rest");
                    item.put("action", "就寝歇息");
                }
            }
            items.add(item);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("date_game_day", gameDay);
        plan.put("items", items);
        try {
            return MAPPER.writeValueAsString(plan);
        } catch (Exception e) {
            return "{\"date_game_day\":" + gameDay + ",\"items\":[]}";
        }
    }

    /**
     * 判定计划 JSON 是否属于指定游戏日（静态可测）。
     *
     * @param planJson 计划 JSON（可空）
     *
     * @param gameDay 目标游戏日编号
     *
     * @return true=属于该游戏日
     */
    static boolean dailyPlanForDay(String planJson, long gameDay) {
        if (planJson == null || planJson.isBlank()) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(planJson);
            return node != null && node.path("date_game_day").asLong(-1) == gameDay;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读取计划在指定小时的条目（静态可测）：返回 {state,action}；无匹配返回 null。
     *
     * @param planJson 计划 JSON（可空）
     *
     * @param hour 当日小时（0-23）
     *
     * @return 匹配条目（state/action 可空）或 null
     */
    static Map<String, Object> planItemAt(String planJson, int hour) {
        if (planJson == null || planJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(planJson);
            JsonNode items = node == null ? null : node.path("items");
            if (items == null || !items.isArray()) {
                return null;
            }
            for (JsonNode it : items) {
                if (it.path("hour").asInt(-1) == hour) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("state", it.path("state").asText(null));
                    m.put("action", it.path("action").asText(null));
                    return m;
                }
            }
        } catch (Exception e) {
            // 解析失败视为无计划
        }
        return null;
    }

    /**
     * 事件感知：查询本游戏小时内的重要事件（重要度 ≥ 事件感知阈值），
     * 命中则当日计划可被事件打断一次（该小时行动变更为应对事件）。
     *
     * @param projectId 项目 ID
     *
     * @param gameHour 当前游戏小时
     *
     * @return 命中事件 ID（无命中返回 null）
     */
    private Long significantEventAt(Long projectId, Long gameHour) {
        if (gameHour == null) {
            return null;
        }
        try {
            return worldEventBus.query(projectId,
                            new EventQuery(null, null, null, null, gameHour, gameHour, 10))
                    .stream()
                    .filter(e -> e.getImportance() != null && e.getImportance() >= crowdEventImportanceThreshold)
                    .map(com.holzyn.actor.domain.world.entity.ActorEvent::getId)
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("[人群调度] 事件感知查询失败: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    /** 项目运行模式（无引擎行回退 standard） */
    private String runModeOf(Long projectId) {
        com.holzyn.actor.domain.agent.runtime.ActorWorldEngine engine =
                engineRepository == null ? null : engineRepository.findByProjectId(projectId).orElse(null);
        return engine == null ? com.holzyn.actor.domain.agent.runtime.RunModes.STANDARD
                : com.holzyn.actor.domain.agent.runtime.RunModes.normalize(engine.getRunMode());
    }

    /** 普通 NPC 稳定种子（npc.id） */
    private int crowdSeed(ActorCharacter n) {
        return n.getId() == null ? 1 : (int) (n.getId() & 0x7fffffff);
    }

    /**
     * 普通 NPC AI 日计划（ultimate 模式，scene=plan_day 用量）：按居民档案+世界观生成当日计划 JSON。
     * 失败返回 null（调用方回退机制计划）。
     */
    private String generateCrowdPlanAi(Long projectId, ActorCharacter n, long gameDay) {
        Long userId = currentUserProvider.currentUserId();
        String world = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(ActorWorldSetting::getFreeText).orElse("");
        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("character_info", crowdInfo(n));
        placeholders.put("world_setting", world.isBlank() ? "（项目暂无世界观设定）" : world);
        placeholders.put("current_state", "当前游戏日：" + (gameDay + 1)
                + "；职业：" + (n.getOccupation() == null || n.getOccupation().isBlank() ? "未知" : n.getOccupation())
                + "；所在地：" + (n.getLocation() == null || n.getLocation().isBlank() ? "未知" : n.getLocation()));
        placeholders.put("goals", "（普通居民，无长期目标）");
        placeholders.put("date_game_day", gameDay + 1);
        try {
            String prompt = promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_PLAN_DAY, placeholders);
            String sys = promptTemplateService.systemMessage(userId, projectId, PromptTemplateService.CODE_PLAN_DAY, null);
            AiChatResult result = AiCallContext.run("plan_day", null, () ->
                    aiChatGateway.chatCompletion(userId, projectId, null,
                            new AiChatRequest(null, chatMessages(prompt, sys), 0.3, 1024, true)));
            usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "plan_day",
                    result.promptTokens(), result.completionTokens(),
                    result.cacheHitTokens(), result.cacheMissTokens(), 0);
            String json = JsonUtil.extractJson(result.content());
            if (json == null) {
                return null;
            }
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.path("items").isArray() || node.path("items").size() == 0) {
                return null;
            }
            // 重写游戏日并保持结构（AI 可能给出不同日号）
            JsonNode arr = node.path("items");
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode it : arr) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("hour", Math.floorMod(it.path("hour").asInt(8), 24));
                m.put("state", validCrowdState(it.path("state").asText("stop")));
                m.put("action", truncate(it.path("action").asText("日常活动"), ACTION_MAX));
                items.add(m);
            }
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("date_game_day", gameDay);
            plan.put("items", items);
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            log.warn("[人群调度] 普通 NPC AI 日计划失败，回退机制: npc={} : {}", n.getName(), e.getMessage());
            return null;
        }
    }

    /** 普通居民档案摘要（AI 日计划输入） */
    private String crowdInfo(ActorCharacter n) {
        return "姓名：" + n.getName()
                + (n.getRace() == null || n.getRace().isBlank() ? "" : "；种族：" + n.getRace())
                + (n.getOccupation() == null || n.getOccupation().isBlank() ? "" : "；职业：" + n.getOccupation())
                + (n.getAffiliation() == null || n.getAffiliation().isBlank() ? "" : "；归属：" + n.getAffiliation())
                + (n.getDetail() == null || n.getDetail().isBlank() ? "" : "；详情：" + n.getDetail());
    }

    /** 合法状态过滤 */
    private String validCrowdState(String s) {
        return s != null && Set.of("idle", "walk", "stop", "talk", "rest").contains(s.trim()) ? s.trim() : "stop";
    }

    /** AI 消息组装（system 存在则前置） */
    private List<AiChatRequest.ChatMessage> chatMessages(String prompt, String sys) {
        List<AiChatRequest.ChatMessage> messages = new ArrayList<>();
        if (sys != null && !sys.isBlank()) {
            messages.add(new AiChatRequest.ChatMessage("system", sys));
        }
        messages.add(new AiChatRequest.ChatMessage("user", prompt));
        return messages;
    }

    /**
     * 对象 → JSON 文本（payload 载荷序列化；失败返回 null）。
     */
    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 两级 AI 集体调度。
     * <p>① 项目级 AI：输入含【主分类字段分组概况 + 次分类字段分组概况 + 归属概况 + 世界观 + 当前时刻】，
     * 输出 summary + 按主分类字段的分组指令（如“人族：午后集会游行”）+ 按归属的分组指令（不同归属立场不同）；
     * ② 归属级 AI：为每个归属把「该归属指令 + 该归属成员所属主分类分组的指令」合并注入，
     * 逐人输出状态/行动；单归属失败自动降级程序化，整体失败抛出后由调用方降级。</p>
     * <p><b>三项口径</b>：</p>
     * <ul>
     * <li>居民取自主表 {@code type='common'}；</li>
     * <li><b>输入升级</b>：归属级名单里每个居民不再只有扁平字段，而是拼入
     * {@code PersonaService.renderShardsForScene(id, "npc_crowd_decision")} 的 11 维档案分片
     * （S1 身份/S2 人格/S4 生理/S6 习惯/S8 面具），无档案（空串）才回退扁平字段；</li>
     * <li><b>状态读写</b>：运行态（state/lastAction）循环内只读
     * {@link NpcActivityStateService#activityStateByRef} 的批量 Map、只写内存缓冲，
     * 全部归属处理完一次性 {@link NpcActivityStateService#setActivities} 落 actor_state。</li>
     * </ul>
     *
     * @param projectId 项目 ID
     *
     * @return 调度结果 Map（mode/summary/hour/updated/affiliations/primaryGroups）
     */
    @Transactional
    public Map<String, Object> scheduleWithAi(Long projectId) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        List<ActorCharacter> npcs = commonNpcs(projectId);
        if (npcs.isEmpty()) {
            throw new BizException(400, "项目暂无普通型 NPC，请先生成");
        }
        ActorCrowdRuntime rt = runtime(projectId);
        String primary = rt.getPrimaryField();
        String secondary = rt.getSecondaryField();
        if (primary == null || secondary == null || !CLASSIFY_FIELDS.contains(primary) || !CLASSIFY_FIELDS.contains(secondary)) {
            throw new BizException(400, "请先拟定并确认标准字段数据（含主/次分类字段），再执行 AI 调度");
        }
        int hour = LocalDateTime.now().getHour();
        long start = System.currentTimeMillis();
        String world = renderWorld(projectId);
        // ：运行态与当日计划各批量取一次（循环内零查库）
        Map<String, String> stateByRef = npcActivityStateService.activityStateByRef(projectId);
        Map<String, String> planByRef = npcActivityStateService.dailyPlanByRef(projectId);
        // ：本轮运行态写回缓冲（所有归属处理完一次性落 actor_state）
        Map<String, NpcActivityStateService.ActivityUpdate> writes = new LinkedHashMap<>();
        String primaryOverview = fieldGroupOverview(npcs, primary);
        String secondaryOverview = fieldGroupOverview(npcs, secondary);
        String affOverview = affiliationOverview(npcs, stateByRef);

        // ① 项目级 AI 调度（一次调用：主分类分组指令 + 归属指令 + 全局 summary）
        log.info("[人群AI调度] 任务开始：项目={} 阶段=项目级 居民={} 主字段={} 次字段={}",
                projectId, npcs.size(), primary, secondary);
        String projectPrompt = promptTemplateService.render(userId, projectId,
                PromptTemplateService.CODE_CROWD_SCHEDULE_PROJECT, ph(
                        "world_name", worldName(projectId),
                        "world_setting", world,
                        "hour", String.valueOf(hour),
                        "primary_field", fieldLabel(primary),
                        "primary_overview", primaryOverview,
                        "secondary_field", fieldLabel(secondary),
                        "secondary_overview", secondaryOverview,
                        "affiliation_overview", affOverview));
        ProjectSchedule projectPlan = callProjectSchedule(userId, projectId, projectPrompt, start);

        // 主分类分组指令索引（按分组名）
        Map<String, String> groupDirectives = projectPlan.primaryGroups().stream()
                .collect(Collectors.toMap(ProjectGroup::group, ProjectGroup::directive, (a, b) -> a, LinkedHashMap::new));

        // ② 归属级 AI 调度（每归属一次调用；合并归属指令 + 该归属成员所属主分类分组的指令）
        int updated = 0;
        Map<String, Object> affResults = new LinkedHashMap<>();
        for (ProjectAffiliation af : projectPlan.affiliations()) {
            List<ActorCharacter> group = npcs.stream()
                    .filter(n -> af.affiliation().equals(normalizeAffiliation(n)))
                    .toList();
            if (group.isEmpty()) {
                log.warn("[人群AI调度] 归属 {} 无匹配居民，跳过", af.affiliation());
                continue;
            }
            // 该归属成员涉及的主分类分组指令（去重合并）
            Set<String> groupDirectiveTexts = new LinkedHashSet<>();
            for (ActorCharacter n : group) {
                String g = fieldValue(n, primary);
                String dir = groupDirectives.get(g);
                if (dir != null && !dir.isBlank()) groupDirectiveTexts.add("- " + g + "：" + dir);
            }
            String groupDirectivesText = groupDirectiveTexts.isEmpty() ? "（无相关人群指令）"
                    : String.join("\n", groupDirectiveTexts);

            // ：名单条目升级为「扁平画像 + 11 维档案分片（npc_crowd_decision，每条目 ≤400 字符）」
            String membersText = renderMembers(group, stateByRef);
            String affPrompt = promptTemplateService.render(userId, projectId,
                    PromptTemplateService.CODE_CROWD_SCHEDULE_AFFILIATION, ph(
                            "world_name", worldName(projectId),
                            "affiliation", af.affiliation(),
                            "directive", af.directive(),
                            "group_directives", groupDirectivesText,
                            "hour", String.valueOf(hour),
                            "members", membersText));
            log.info("[人群AI调度] 任务开始：项目={} 阶段=归属级 归属={} 居民={}", projectId, af.affiliation(), group.size());
            long afStart = System.currentTimeMillis();
            Map<String, ActorCharacter> byName = indexByName(group, ActorCharacter::getName);
            try {
                List<NpcAction> actions = callAffiliationSchedule(userId, projectId, af.affiliation(), affPrompt, afStart);
                int applied = 0;
                for (NpcAction a : actions) {
                    ActorCharacter n = byName.get(a.name());
                    if (n == null) continue;
                    // ：运行态写内存缓冲（循环内零查库/零 flush），收尾批量落 actor_state
                    writes.put(NpcRefs.of(n.getId()),
                            new NpcActivityStateService.ActivityUpdate(a.state(), truncate(a.action(), ACTION_MAX)));
                    applied++;
                }
                updated += applied;
                affResults.put(af.affiliation(), Map.of("residents", group.size(), "applied", applied));
            } catch (Exception e) {
                log.warn("[人群AI调度] 归属级失败降级程序化：归属={} 原因={}", af.affiliation(), e.getMessage());
                degradeGroup(group, hour, planByRef, writes);
                affResults.put(af.affiliation(), Map.of("residents", group.size(), "applied", 0, "degraded", true));
            }
        }
        // ：全部归属处理完一次性批量写回运行态（1 次载入 + 1 次 saveAll）
        npcActivityStateService.setActivities(projectId, writes);

        rt.setLatestSummary(projectPlan.summary());
        rt.setLastScheduleAt(LocalDateTime.now());
        runtimeRepository.save(rt);
        log.info("[人群AI调度] 任务结束：项目={} 主分组={} 归属={} 更新={} 耗时={}ms summary={}",
                projectId, projectPlan.primaryGroups().size(), projectPlan.affiliations().size(),
                updated, System.currentTimeMillis() - start, projectPlan.summary());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", "ai");
        r.put("hour", hour);
        r.put("summary", projectPlan.summary());
        r.put("updated", updated);
        r.put("affiliations", affResults);
        r.put("primaryGroups", projectPlan.primaryGroups());
        return r;
    }

    /**
     * 环境摘要（对话/行动注入）：优先取项目级调度快照，无快照时给静态概况。
     * <p>只统计统一主表 {@code type='common'} 的行。</p>
     *
     * @param projectId 项目 ID
     */
    @Transactional(readOnly = true)
    public Map<String, Object> envSummary(Long projectId) {
        requireProject(projectId);
        List<ActorCharacter> npcs = commonNpcs(projectId);
        ActorCrowdRuntime rt = runtimeRepository.findByProjectId(projectId).orElse(null);
        boolean hasSnapshot = rt != null && rt.getLatestSummary() != null && !rt.getLatestSummary().isBlank();
        String summary = hasSnapshot ? rt.getLatestSummary() : defaultSummary(npcs);
        return Map.of(
                "projectId", projectId,
                "summary", summary,
                "total", npcs.size(),
                "hasSnapshot", hasSnapshot
        );
    }

    /**
     * 设置项目级定时调度开关。
     *
     * @param projectId 项目 ID
     *
     * @param enabled 是否启用
     */
    @Transactional
    public void setEnabled(Long projectId, boolean enabled) {
        requireProject(projectId);
        ActorCrowdRuntime rt = runtime(projectId);
        rt.setEnabled(enabled ? 1 : 0);
        runtimeRepository.save(rt);
        log.info("[人群调度] 项目 {} 定时调度开关 → {}", projectId, enabled ? "开启" : "关闭");
    }

    /**
     * 项目级调度运行时信息（开关/主次分类字段/上次调度/环境快照）。
     *
     * @param projectId 项目 ID
     */
    @Transactional(readOnly = true)
    public Map<String, Object> runtimeInfo(Long projectId) {
        requireProject(projectId);
        ActorCrowdRuntime rt = runtimeRepository.findByProjectId(projectId).orElse(null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("enabled", rt != null && Integer.valueOf(1).equals(rt.getEnabled()));
        r.put("primaryField", rt == null ? null : rt.getPrimaryField());
        r.put("secondaryField", rt == null ? null : rt.getSecondaryField());
        r.put("lastScheduleAt", rt == null ? null : rt.getLastScheduleAt());
        r.put("latestSummary", rt == null ? null : rt.getLatestSummary());
        return r;
    }

    // ==================== AI 调度私有方法 ====================

    /** 项目级 AI 调度调用（含用量记录 + 控制台日志，对齐 [对话] 风格；失败重试后抛出） */
    private ProjectSchedule callProjectSchedule(Long userId, Long projectId, String prompt, long start) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", promptTemplateService.systemMessage(userId, projectId,
                                PromptTemplateService.CODE_CROWD_SCHEDULE_PROJECT, null)),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.4, SCHEDULE_MAX_TOKENS, true);
                AiChatResult result = AiCallContext.run("crowd_ai", null, () ->
                        aiChatGateway.chatCompletion(userId, projectId, null, req));
                ProjectSchedule plan = parseProjectSchedule(result.content());
                int tokenIn = result.promptTokens() == null ? 0 : result.promptTokens();
                int tokenOut = result.completionTokens() == null ? 0 : result.completionTokens();
                log.info("[人群AI调度] 范围=project：{}", result.content());
                log.info("[人群AI调度] 任务结束：项目={} 阶段=项目级 耗时={}ms tokens={}/{} 主分组={} 归属={}",
                        projectId, System.currentTimeMillis() - start, tokenIn, tokenOut,
                        plan.primaryGroups().size(), plan.affiliations().size());
                usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "crowd_ai",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                return plan;
            } catch (Exception e) {
                lastError = e;
                log.warn("[人群AI调度] 项目 {} 项目级第 {} 次失败: {}", projectId, attempt, e.getMessage());
            }
        }
        log.warn("[人群AI调度] 任务失败：项目={} 阶段=项目级 耗时={}ms：{}", projectId,
                System.currentTimeMillis() - start, lastError == null ? "未知错误" : lastError.getMessage());
        throw new BizException(400, "项目级调度失败：" + friendlyError(lastError) + "，已降级程序化");
    }

    /** 归属级 AI 调度调用（含用量记录 + 控制台日志；失败重试后抛出，由调用方降级） */
    private List<NpcAction> callAffiliationSchedule(Long userId, Long projectId, String affiliation, String prompt, long start) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", promptTemplateService.systemMessage(userId, projectId,
                                PromptTemplateService.CODE_CROWD_SCHEDULE_AFFILIATION, null)),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.4, SCHEDULE_MAX_TOKENS, true);
                AiChatResult result = AiCallContext.run("crowd_ai", null, () ->
                        aiChatGateway.chatCompletion(userId, projectId, null, req));
                List<NpcAction> actions = parseAffiliationSchedule(result.content());
                int tokenIn = result.promptTokens() == null ? 0 : result.promptTokens();
                int tokenOut = result.completionTokens() == null ? 0 : result.completionTokens();
                log.info("[人群AI调度] 范围=affiliation 归属={}：{}", affiliation, result.content());
                log.info("[人群AI调度] 任务结束：项目={} 阶段=归属级 归属={} 耗时={}ms tokens={}/{} 行动={}",
                        projectId, affiliation, System.currentTimeMillis() - start, tokenIn, tokenOut, actions.size());
                usageLogService.record(userId, projectId, null, result.providerId(), result.model(), "crowd_ai",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                return actions;
            } catch (Exception e) {
                lastError = e;
                log.warn("[人群AI调度] 归属 {} 第 {} 次失败: {}", affiliation, attempt, e.getMessage());
            }
        }
        log.warn("[人群AI调度] 任务失败：归属={} 耗时={}ms：{}", affiliation,
                System.currentTimeMillis() - start, lastError == null ? "未知错误" : lastError.getMessage());
        throw new BizException(400, "归属级调度失败：" + friendlyError(lastError) + "，已降级程序化");
    }

    /**
     * 某归属 AI 调度失败时降级：程序化状态机推进该组。
     * <p>只写调用方传入的内存缓冲（{@code writes}），由 {@code scheduleWithAi} 收尾一次性
     * 批量落 actor_state；计划从批量 Map {@code planByRef} 取（循环内零查库）。</p>
     *
     * @param group 该归属居民
     *
     * @param hour 推进用小时（0-23）
     *
     * @param planByRef 当日计划批量 Map（npcRef → daily_plan_json，可空）
     *
     * @param writes 运行态写回缓冲（npcRef → {state,lastAction}）
     */
    private void degradeGroup(List<ActorCharacter> group, int hour,
                              Map<String, String> planByRef,
                              Map<String, NpcActivityStateService.ActivityUpdate> writes) {
        Random rng = new Random();
        for (ActorCharacter n : group) {
            String npcRef = NpcRefs.of(n.getId());
            String state = computeState(planByRef == null ? null : planByRef.get(npcRef), hour, rng, null);
            writes.put(npcRef, new NpcActivityStateService.ActivityUpdate(state, describeAction(n, state, hour)));
        }
    }

    /** 归属级调度行动记录（解析结果） */
    record NpcAction(String name, String state, String action) {
    }

    /** 项目级调度结果（解析结果） */
    record ProjectSchedule(String summary, List<ProjectGroup> primaryGroups, List<ProjectAffiliation> affiliations) {
    }

    /** 项目级调度：主分类字段分组指令 */
    record ProjectGroup(String group, String directive) {
    }

    /** 项目级调度：单归属指令 */
    record ProjectAffiliation(String affiliation, String directive) {
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 程序化状态机：按小时 + 个体随机变化计算成员状态。
     * <p>时段规则：0-5 休息 / 6-7 行走（出门）/ 8-11 停留或交谈（忙活）/
     * 12-13 休息（午休）/ 14-17 停留或交谈（继续忙活）/ 18-19 行走（收工回家）/
     * 20-22 交谈（茶馆串门）/ 23 休息（就寝）。</p>
     *
     * @param hour 当前小时（0-23）
     *
     * @param rng 随机源（个体差异）
     *
     * @return 状态：walk/stop/talk/rest
     */
    static String computeState(int hour, Random rng) {
        if (hour >= 0 && hour <= 5) return "rest";
        if (hour >= 6 && hour <= 7) return "walk";
        if (hour >= 8 && hour <= 11) {
            return rng.nextInt(10) < 2 ? "talk" : "stop";
        }
        if (hour >= 12 && hour <= 13) return "rest";
        if (hour >= 14 && hour <= 17) {
            return rng.nextInt(10) < 2 ? "talk" : "stop";
        }
        if (hour >= 18 && hour <= 19) return "walk";
        if (hour >= 20 && hour <= 22) return "talk";
        return "rest";
    }

    /**
     * 程序化状态机（计划优先版）：优先读该 NPC 当日计划（{@code actor_state.daily_plan_json}）
     * 当前小时条目，无计划条目回退纯时段规则 {@link #computeState(int, Random)}；
     * 事件打断（disruptEventId 非空）时该小时强制「talk（应对事件/议论）」一次。
     * <p>入参由"实体"改为<b>计划 JSON 文本</b>——计划已不在主表实体上，
     * 由调用方从 {@link NpcActivityStateService#dailyPlanByRef} 的批量 Map 中取（循环内零查库），
     * 纯静态逻辑与单测语义保持不变。</p>
     *
     * @param dailyPlanJson 该 NPC 当日计划 JSON（可空=无计划）
     *
     * @param hour 当前小时（0-23）
     *
     * @param rng 随机源（个体差异）
     *
     * @param disruptEventId 事件感知命中的打断事件 ID（null=无打断）
     *
     * @return 状态：walk/stop/talk/rest
     */
    static String computeState(String dailyPlanJson, int hour, Random rng, Long disruptEventId) {
        // 事件打断优先：重大事件发生时该小时居民停下议论/应对（感知到事件）
        if (disruptEventId != null) {
            return "talk";
        }
        Map<String, Object> item = planItemAt(dailyPlanJson, hour);
        if (item != null) {
            String state = (String) item.get("state");
            if (state != null && Set.of("walk", "stop", "talk", "rest").contains(state)) {
                return state;
            }
        }
        return computeState(hour, rng);
    }

    /**
     * 生成普通型 NPC 行动描述（结合职业/所在地与状态）。
     *
     * @param npc 普通型 NPC
     *
     * @param state 当前状态
     *
     * @param hour 当前小时
     *
     * @return 行动描述文本（≤255）
     */
    static String describeAction(ActorCharacter npc, String state, int hour) {
        String occ = npc.getOccupation() == null || npc.getOccupation().isBlank() ? "忙活生计" : npc.getOccupation();
        String loc = npc.getLocation() == null || npc.getLocation().isBlank() ? "附近" : npc.getLocation();
        return switch (state) {
            case "walk" -> "正在前往" + loc + "（" + occ + "）";
            case "stop" -> "在" + loc + "从事" + occ;
            case "talk" -> "在" + loc + "与旁人攀谈";
            case "rest" -> (hour >= 20 || hour <= 5) ? "歇息就寝" : "小憩歇息";
            default -> "闲逛中";
        };
    }

    /**
     * 构建群体快照文本（环境摘要）：按状态统计 + 时段总述。
     *
     * @param npcs 项目全部普通型 NPC
     *
     * @param stateCounts 状态人数统计
     *
     * @param hour 当前小时
     *
     * @return 快照文本
     */
    static String buildSnapshot(List<ActorCharacter> npcs, Map<String, Long> stateCounts, int hour) {
        long total = npcs.size();
        long walking = stateCounts.getOrDefault("walk", 0L);
        long talking = stateCounts.getOrDefault("talk", 0L);
        long resting = stateCounts.getOrDefault("rest", 0L);
        long stopping = stateCounts.getOrDefault("stop", 0L);
        return timeDesc(hour) + "，" + total + " 名普通居民中："
                + walking + " 人在路上，"
                + stopping + " 人驻足劳作，"
                + talking + " 人在攀谈，"
                + resting + " 人在歇息。";
    }

    /** 无调度快照时的静态概况（环境注入兜底） */
    static String defaultSummary(List<ActorCharacter> npcs) {
        if (npcs.isEmpty()) return "（暂无普通型 NPC）";
        Map<String, Long> byAff = new LinkedHashMap<>();
        Map<String, Long> byOcc = new LinkedHashMap<>();
        for (ActorCharacter n : npcs) {
            String aff = n.getAffiliation() == null || n.getAffiliation().isBlank() ? "未知归属" : n.getAffiliation();
            String occ = n.getOccupation() == null || n.getOccupation().isBlank() ? "未知职业" : n.getOccupation();
            byAff.merge(aff, 1L, Long::sum);
            byOcc.merge(occ, 1L, Long::sum);
        }
        String topAff = byAff.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("未知");
        return "项目共有 " + npcs.size() + " 名普通居民，涉及 " + byOcc.size() + " 种职业，主要归属 " + topAff + "。";
    }

    /** 时段描述 */
    private static String timeDesc(int hour) {
        if (hour >= 0 && hour <= 5) return "深夜";
        if (hour >= 6 && hour <= 7) return "清晨";
        if (hour >= 8 && hour <= 11) return "上午";
        if (hour >= 12 && hour <= 13) return "正午";
        if (hour >= 14 && hour <= 17) return "下午";
        if (hour >= 18 && hour <= 19) return "傍晚";
        if (hour >= 20 && hour <= 22) return "夜晚";
        return "深夜";
    }

    /** 按分类字段分组概况（项目级调度 AI 输入；纯静态可测） */
    static String fieldGroupOverview(List<ActorCharacter> npcs, String field) {
        if (field == null || !CLASSIFY_FIELDS.contains(field)) return "（未配置该分类字段）";
        Map<String, List<ActorCharacter>> by = npcs.stream()
                .collect(Collectors.groupingBy(n -> fieldValue(n, field), LinkedHashMap::new, Collectors.toList()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<ActorCharacter>> e : by.entrySet()) {
            List<ActorCharacter> group = e.getValue();
            Map<String, Long> occ = new LinkedHashMap<>();
            Map<String, Long> sub = new LinkedHashMap<>();
            for (ActorCharacter n : group) {
                String o = n.getOccupation() == null || n.getOccupation().isBlank() ? "未知职业" : n.getOccupation();
                occ.merge(o, 1L, Long::sum);
                String s = n.getSubRace() == null || n.getSubRace().isBlank() ? "无" : n.getSubRace();
                sub.merge(s, 1L, Long::sum);
            }
            sb.append("- ").append(e.getKey()).append("：共 ").append(group.size()).append(" 人，次级种族构成 ")
                    .append(sub).append("，职业构成 ").append(occ).append("\n");
        }
        return sb.toString();
    }

    /** 按字段取值（未配置/空值归一；纯静态可测） */
    static String fieldValue(ActorCharacter n, String field) {
        String v = switch (field == null ? "" : field) {
            case FIELD_RACE -> n.getRace();
            case FIELD_AFFILIATION -> n.getAffiliation();
            case FIELD_OCCUPATION -> n.getOccupation();
            default -> null;
        };
        return v == null || v.isBlank() ? (FIELD_OCCUPATION.equals(field) ? "未知职业" : "未分类") : v.trim();
    }

    /**
     * 各归属概况文本（项目级调度 AI 输入）：按归属分组统计人数/职业/状态。
     * <p>当前状态来自 {@code actor_state.activity_state} 的批量 Map
     * （{@code stateByRef}）——运行态已不在主表实体上，调用方一次取全后传入（纯静态可测）。</p>
     *
     * @param npcs 项目全部普通型 NPC
     *
     * @param stateByRef npcRef → 活动状态（批量读取结果，可空=全部按 idle）
     *
     * @return 各归属概况文本
     */
    static String affiliationOverview(List<ActorCharacter> npcs, Map<String, String> stateByRef) {
        Map<String, List<ActorCharacter>> byAff = npcs.stream()
                .collect(Collectors.groupingBy(OrdinaryNpcService::normalizeAffiliation, LinkedHashMap::new, Collectors.toList()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<ActorCharacter>> e : byAff.entrySet()) {
            List<ActorCharacter> group = e.getValue();
            Map<String, Long> occ = new LinkedHashMap<>();
            Map<String, Long> states = new LinkedHashMap<>();
            for (ActorCharacter n : group) {
                String o = n.getOccupation() == null || n.getOccupation().isBlank() ? "未知职业" : n.getOccupation();
                occ.merge(o, 1L, Long::sum);
                states.merge(stateOf(n, stateByRef), 1L, Long::sum);
            }
            sb.append("- ").append(e.getKey()).append("：共 ").append(group.size()).append(" 人，职业构成 ")
                    .append(occ).append("，当前状态 ").append(states).append("\n");
        }
        return sb.toString();
    }

    /** 归属归一（null → 未知归属） */
    private static String normalizeAffiliation(ActorCharacter n) {
        return n.getAffiliation() == null || n.getAffiliation().isBlank() ? "未知归属" : n.getAffiliation().trim();
    }

    /** 从批量运行态 Map 取状态（缺失按 idle 兜底） */
    private static String stateOf(ActorCharacter n, Map<String, String> stateByRef) {
        String s = stateByRef == null ? null : stateByRef.get(NpcRefs.of(n.getId()));
        return s == null || s.isBlank() ? NpcActivityStateService.DEFAULT_ACTIVITY : s;
    }

    /**
     * 该归属居民名单文本。
     * <p><b>条目结构</b>（每个 NPC 一条）：</p>
     * <pre>
     * - 姓名（种族：人族 / 汉族，职业：渔夫，归属：临江渔村，当前状态：stop）
     * 档案分片：S1 身份锚定…（PersonaService.renderShardsForScene(id, "npc_crowd_decision")）
     * </pre>
     * <p><b>2026-10-10 决策</b>：不设单条字数硬边界——档案分片全量注入（不再按条截断），
     * 确保 AI 拿到的每个居民身份信息完整（此前按 {@code MEMBER_ENTRY_MAX} 截断常切在分片中间）。</p>
     * <p><b>无档案兜底（数据缺失兜底，非兼容层）</b>：分片渲染返回空串（该 NPC 尚未建档/档案为空）时，
     * 回退到原有扁平字段画像，详情全量注入，保证 AI 至少拿到姓名/种族/职业/归属/状态/详情六项。</p>
     *
     * @param group 该归属居民
     *
     * @param stateByRef npcRef → 活动状态（批量读取结果，可空）
     *
     * @return 名单文本（每行一条居民）
     */
    private String renderMembers(List<ActorCharacter> group, Map<String, String> stateByRef) {
        StringBuilder sb = new StringBuilder();
        for (ActorCharacter n : group) {
            StringBuilder line = new StringBuilder();
            line.append("- ").append(n.getName())
                    .append("（种族：").append(nvl(n.getRace())).append(" / ").append(nvl(n.getSubRace()))
                    .append("，职业：").append(n.getOccupation() == null || n.getOccupation().isBlank() ? "未知" : n.getOccupation())
                    .append("，归属：").append(nvl(n.getAffiliation()))
                    .append("，当前状态：").append(stateOf(n, stateByRef))
                    .append("）");
            // 档案分片（11 维按 npc_crowd_decision 场景组合）；无档案返回空串 → 扁平字段兜底
            // 2026-10-10 决策：不设单条字数硬边界，档案分片全量注入（不再按 MEMBER_ENTRY_MAX 截断）
            String shards = personaShardsQuietly(n.getId());
            String detailLine;
            if (shards.isBlank()) {
                detailLine = "\n  画像：" + flatProfile(n);
            } else {
                detailLine = "\n  档案分片：" + shards;
            }
            sb.append(line).append(detailLine).append("\n");
        }
        return sb.toString();
    }

    /**
     * 读取某居民的 11 维档案分片（场景 {@link #SCENE_NPC_CROWD_DECISION}）。
     * <p>失败/无档案返回空串（调用方回退扁平字段），绝不阻断调度 Prompt 组装。</p>
     *
     * @param profileId actor_profile.id（即 npc:{id} 的数字部分）
     *
     * @return 分片文本（S1/S2/S4/S6/S8）；无档案或异常返回空串
     */
    private String personaShardsQuietly(Long profileId) {
        if (personaService == null || profileId == null) {
            return "";
        }
        try {
            String text = personaService.renderShardsForScene(profileId, SCENE_NPC_CROWD_DECISION);
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            log.warn("[人群AI调度] 档案分片渲染失败（回退扁平字段）: profile={} : {}", profileId, e.getMessage());
            return "";
        }
    }

    /** 扁平字段画像（无档案兜底）：种族/次级种族/职业/归属/详情（2026-10-10 决策：不设字数硬边界，详情全量） */
    private static String flatProfile(ActorCharacter n) {
        StringBuilder sb = new StringBuilder();
        sb.append("种族 ").append(nvl(n.getRace())).append("/").append(nvl(n.getSubRace()))
                .append("，职业 ").append(n.getOccupation() == null || n.getOccupation().isBlank() ? "未知" : n.getOccupation())
                .append("，归属 ").append(nvl(n.getAffiliation()));
        String detail = n.getDetail() == null ? "" : n.getDetail().trim();
        if (!detail.isEmpty()) {
            sb.append("，详情 ").append(detail);
        }
        return sb.toString();
    }

    // ==================== 字段字典/地点渲染 ====================

    /** 字段字典实体按字段分组（race/affiliation/occupation） */
    private Map<String, List<ActorNpcFieldDict>> fieldDictEntities(Long projectId) {
        Map<String, List<ActorNpcFieldDict>> out = new LinkedHashMap<>();
        out.put(FIELD_RACE, new ArrayList<>());
        out.put(FIELD_AFFILIATION, new ArrayList<>());
        out.put(FIELD_OCCUPATION, new ArrayList<>());
        for (ActorNpcFieldDict d : fieldDictRepository.findByProjectIdOrderByFieldAscSortOrderAscIdAsc(projectId)) {
            out.computeIfAbsent(d.getField(), k -> new ArrayList<>()).add(d);
        }
        return out;
    }

    /** 字段字典文本（生成居民 AI 输入）：种族含二级，归属/职业一级 */
    private static String renderFieldDict(Map<String, List<ActorNpcFieldDict>> dict) {
        StringBuilder sb = new StringBuilder();
        sb.append("【种族（含次级种族）】\n");
        Map<String, List<String>> byRace = new LinkedHashMap<>();
        for (ActorNpcFieldDict d : dict.getOrDefault(FIELD_RACE, List.of())) {
            byRace.computeIfAbsent(nvl(d.getLevel1()), k -> new ArrayList<>()).add(nvl(d.getLevel2()));
        }
        byRace.forEach((l1, l2s) -> sb.append("- ").append(l1).append("：")
                .append(l2s.isEmpty() ? "（无次级）" : String.join("、", l2s)).append("\n"));
        sb.append("【归属】\n");
        dict.getOrDefault(FIELD_AFFILIATION, List.of()).forEach(d -> sb.append("- ").append(nvl(d.getLevel1())).append("\n"));
        sb.append("【职业】\n");
        dict.getOrDefault(FIELD_OCCUPATION, List.of()).forEach(d -> sb.append("- ").append(nvl(d.getLevel1())).append("\n"));
        return sb.toString();
    }

    /** 世界观地点清单文本（生成居民 AI 输入：当前所在地优先选取，可合理补充） */
    private String renderLocations(Long projectId) {
        List<ActorWorldLocation> locs = worldLocationRepository.findByProjectIdOrderBySortOrderAscIdAsc(projectId);
        if (locs.isEmpty()) return "（暂无地点清单，可依据世界观合理设定所在地）";
        StringBuilder sb = new StringBuilder();
        for (ActorWorldLocation l : locs) {
            sb.append("- ").append(nvl(l.getName()))
                    .append(l.getType() == null || l.getType().isBlank() ? "" : "（" + l.getType() + "）")
                    .append("\n");
        }
        return sb.toString();
    }

    // ==================== 解析（纯静态可测） ====================

    /**
     * AI 生成的一条居民（结构列草稿 + 该居民的 11 维档案原文）。
     * <p>为什么档案存的是<b>原文</b>而不是解析后的 {@link PersonaProfile}：跨"预览 → 确认"两个请求的
     * 暂存只应当是不可变文本（不共享可变对象）；解析动作留在真正落库的那一刻
     * （{@code PersonaJson.readQuietly} → {@code PersonaService.savePersona}），与契约的落库口径一致。</p>
     *
     * @param draft 结构列草稿（预览 + 落 {@code actor_profile} 表列用）
     *
     * @param personaJson 该居民的 11 维档案 JSON 原文（契约三键在元素顶层，可直接解析）；
     * AI 未按 v2 契约输出 {@code dims} 时为 null（结构列照常可用）
     */
    record GeneratedResident(OrdinaryNpcDraftVO draft, String personaJson) {
    }

    /**
     * 解析 AI 输出的居民草稿数组（纯静态可测）。
     * <p>兼容：顶层数组 / 顶层对象含 residents 数组 / Markdown 代码块 / 首尾说明文字；
     * 清洗：名称非空、字段截断、subRace 兼容 sub_race 命名。</p>
     * <p>收口后模板元素还带 {@code schema_version/dims/_sources} 三个契约键，本方法
     * <b>只取结构列</b>（档案走 {@link #parseGeneratedResidents}）——既有语义与调用方零改动。</p>
     *
     * @param content AI 输出文本
     *
     * @return 居民草稿列表
     */
    static List<OrdinaryNpcDraftVO> parseDrafts(String content) {
        List<OrdinaryNpcDraftVO> out = new ArrayList<>();
        for (JsonNode it : draftElements(content)) {
            OrdinaryNpcDraftVO d = toDraft(it);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 解析 AI 输出的居民数组（结构列草稿 + 每人 11 维档案原文；纯静态可测）。
     * <p>元素形态（{@code ordinary_npc_gen} v2 契约）：9 个结构列键与契约三键
     * （{@code schema_version} / {@code dims} 11 键 / {@code _sources} 11 键）<b>同处元素顶层</b>——
     * 因此 {@code PersonaJson.readQuietly(元素 JSON)} 可直接把元素解析为 11 维档案
     * （多余的结构列键被 Jackson 忽略）。</p>
     * <p><b>零阻断</b>：元素缺 name → 丢弃该元素；元素没有非空 {@code dims} → {@code personaJson}=null
     * （结构列照常返回，落库时只跳过档案，绝不因为档案缺失而丢居民）。</p>
     *
     * @param content AI 输出文本
     *
     * @return 居民列表（顺序与 AI 输出一致）；无有效元素返回空列表
     */
    static List<GeneratedResident> parseGeneratedResidents(String content) {
        List<GeneratedResident> out = new ArrayList<>();
        for (JsonNode it : draftElements(content)) {
            OrdinaryNpcDraftVO d = toDraft(it);
            if (d == null) {
                continue;
            }
            out.add(new GeneratedResident(d, personaJsonOfElement(it)));
        }
        return out;
    }

    /**
     * 定位 AI 输出中的居民元素数组（顶层数组 / 顶层对象含 residents 数组 / 代码块 / 首尾说明文字）。
     *
     * @param content AI 输出文本
     *
     * @return 元素节点列表；无法定位返回空列表
     */
    private static List<JsonNode> draftElements(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        JsonNode node = parseQuiet(JsonUtil.extractJson(content));
        if (node == null || (node.isObject() && !node.path("residents").isArray())) {
            JsonNode wider = parseQuiet(extractArrayOrObject(content));
            if (wider != null) {
                node = wider;
            }
        }
        if (node == null) {
            return List.of();
        }
        JsonNode arr = node.isArray() ? node : node.path("residents");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode it : arr) {
            if (it != null && it.isObject()) {
                out.add(it);
            }
        }
        return out;
    }

    /**
     * 单个元素 → 结构列草稿（名称为空即无效；其余字段截断兜底）。
     *
     * @param it 元素节点
     *
     * @return 草稿；名称为空返回 null
     */
    private static OrdinaryNpcDraftVO toDraft(JsonNode it) {
        String name = text(it, "name").trim();
        if (name.isEmpty()) {
            return null;
        }
        return new OrdinaryNpcDraftVO(
                truncate(name, NAME_MAX),
                truncate(text(it, "gender"), GENDER_MAX),
                truncate(text(it, "race"), RACE_MAX),
                truncate(text(it, "subRace"), SUB_RACE_MAX),
                intOrNull(it, "age"),
                truncate(text(it, "affiliation"), AFFILIATION_MAX),
                truncate(text(it, "location"), LOCATION_MAX),
                truncate(text(it, "occupation"), OCCUPATION_MAX),
                truncate(text(it, "detail"), DETAIL_MAX));
    }

    /**
     * 取元素内的 11 维档案原文（元素顶层的 {@code dims} 为非空对象才算数）。
     *
     * @param it 元素节点
     *
     * @return 档案 JSON 原文；无 dims / 序列化失败返回 null
     */
    private static String personaJsonOfElement(JsonNode it) {
        JsonNode dims = it.path("dims");
        if (dims.isMissingNode() || dims.isNull() || !dims.isObject() || dims.size() == 0) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(it);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析项目级调度结果（纯静态可测）。
     * 非法抛出中文异常。</p>
     *
     * @param content AI 输出文本
     *
     * @return 项目调度结果（summary + 主分类分组指令 + 归属指令）
     */
    static ProjectSchedule parseProjectSchedule(String content) {
        JsonNode node = parseQuiet(JsonUtil.extractJson(content));
        if (node == null || !node.isObject()) {
            throw new BizException("AI 未返回有效项目级调度 JSON");
        }
        String summary = node.path("summary").asText("").trim();
        List<ProjectGroup> groups = new ArrayList<>();
        JsonNode gArr = node.path("primaryGroups");
        if (gArr != null && gArr.isArray()) {
            for (JsonNode it : gArr) {
                String g = text(it, "group").trim();
                String dir = text(it, "directive").trim();
                if (g.isEmpty() || dir.isEmpty()) continue;
                groups.add(new ProjectGroup(truncate(g, RACE_MAX), truncate(dir, DIRECTIVE_MAX)));
            }
        }
        List<ProjectAffiliation> affiliations = new ArrayList<>();
        JsonNode arr = node.path("affiliations");
        if (arr != null && arr.isArray()) {
            for (JsonNode it : arr) {
                String aff = text(it, "affiliation").trim();
                String dir = text(it, "directive").trim();
                if (aff.isEmpty() || dir.isEmpty()) continue;
                affiliations.add(new ProjectAffiliation(truncate(aff, AFFILIATION_MAX), truncate(dir, DIRECTIVE_MAX)));
            }
        }
        return new ProjectSchedule(summary, groups, affiliations);
    }

    /**
     * 解析归属级调度结果（纯静态可测）。
     *
     * @param content AI 输出文本
     *
     * @return 居民行动列表（name/state/action，state 非法值过滤）
     */
    static List<NpcAction> parseAffiliationSchedule(String content) {
        if (content == null || content.isBlank()) return List.of();
        JsonNode node = parseQuiet(JsonUtil.extractJson(content));
        if (node == null || (node.isObject() && !node.path("actions").isArray())) {
            JsonNode wider = parseQuiet(extractArrayOrObject(content));
            if (wider != null) node = wider;
        }
        if (node == null) return List.of();
        JsonNode arr = node.isArray() ? node : node.path("actions");
        if (arr == null || !arr.isArray()) return List.of();
        Set<String> VALID_STATES = Set.of("idle", "walk", "stop", "talk", "rest");
        List<NpcAction> out = new ArrayList<>();
        for (JsonNode it : arr) {
            if (it == null || !it.isObject()) continue;
            String name = text(it, "name").trim();
            if (name.isEmpty()) continue;
            String state = text(it, "state").trim();
            if (!VALID_STATES.contains(state)) continue;
            String action = text(it, "action").trim();
            out.add(new NpcAction(truncate(name, NAME_MAX), state, truncate(action, ACTION_MAX)));
        }
        return out;
    }

    /** 宽松提取 JSON 数组/对象：剥代码块 + 取首个 [..] 或 {..} */
    private static String extractArrayOrObject(String content) {
        String t = content.replaceAll("(?s)```[a-zA-Z0-9]*\\s*", "").replace("```", "");
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start >= 0 && end > start) return t.substring(start, end + 1);
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s >= 0 && e > s) return t.substring(s, e + 1);
        return null;
    }

    /** 静默解析 JSON（失败返回 null） */
    private static JsonNode parseQuiet(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 节点字段安全取文本（兼容 camelCase 与 snake_case） */
    private static String text(JsonNode item, String field) {
        JsonNode v = item.path(field);
        if (v.isMissingNode() || v.isNull()) {
            String snake = field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
            if (!snake.equals(field)) v = item.path(snake);
        }
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    /** 节点整数安全取值（缺失/非法返回 null） */
    private static Integer intOrNull(JsonNode item, String field) {
        JsonNode v = item.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isNumber()) return Math.max(0, Math.min(10000, v.asInt()));
        try {
            return Math.max(0, Math.min(10000, Integer.parseInt(v.asText().trim())));
        } catch (Exception e) {
            return null;
        }
    }

    /** 按名称建索引（首条胜出） */
    private static <T> Map<String, T> indexByName(List<T> list, Function<T, String> nameFn) {
        Map<String, T> index = new LinkedHashMap<>();
        for (T item : list) {
            String n = nameFn.apply(item);
            if (n == null || n.isBlank()) continue;
            index.putIfAbsent(n.trim(), item);
        }
        return index;
    }

    // ==================== 收口：居民 11 维档案的暂存与落库（契约 §7 缺口） ====================

    /**
     * 本次生成的有效批大小：配置值按 [1, {@link #BATCH_SIZE}] 夹紧（缺省/非法回退
     * {@link #GEN_PERSONA_BATCH_DEFAULT}），绝不放大到超过 30 人/批。
     *
     * @return 有效批大小（1..BATCH_SIZE）
     */
    private int effectiveBatchSize() {
        int configured = npcPersonaBatchSize <= 0 ? GEN_PERSONA_BATCH_DEFAULT : npcPersonaBatchSize;
        if (configured > BATCH_SIZE) {
            log.warn("[居民生成] npc-persona-batch-size={} 超过硬上限 {}（30 人 × 11 维会撞输出上限），已收窄",
                    configured, BATCH_SIZE);
            configured = BATCH_SIZE;
        }
        return configured;
    }

    /**
     * 暂存一位居民的 11 维档案原文（生成 → 确认之间的过渡态；见 {@link #pendingPersonaJson}）。
     *
     * @param projectId 项目 ID
     *
     * @param name 居民姓名（落库时按同名取回）
     *
     * @param personaJson 档案 JSON 原文（空则忽略）
     */
    private void stashPersona(Long projectId, String name, String personaJson) {
        if (projectId == null || name == null || name.isBlank() || personaJson == null || personaJson.isBlank()) {
            return;
        }
        String key = personaStashKey(projectId, name);
        synchronized (pendingPersonaJson) {
            // 上限兜底：用户放弃确认时暂存不会自动清空，超限按插入顺序淘汰最旧的一批
            while (pendingPersonaJson.size() >= PENDING_PERSONA_MAX) {
                Iterator<String> it = pendingPersonaJson.keySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
            pendingPersonaJson.put(key, personaJson);
        }
    }

    /**
     * 取回并移除一位居民的暂存档案（取走即消费：重复落库不会写第二遍）。
     *
     * @param projectId 项目 ID
     *
     * @param name 居民姓名
     *
     * @return 档案 JSON 原文；无暂存返回 null
     */
    private String takePendingPersona(Long projectId, String name) {
        if (projectId == null || name == null || name.isBlank()) {
            return null;
        }
        synchronized (pendingPersonaJson) {
            return pendingPersonaJson.remove(personaStashKey(projectId, name));
        }
    }

    /** 暂存键（项目 + 姓名；同一项目内重名在入库时已按名称去重，故不会互相覆盖）。 */
    private static String personaStashKey(Long projectId, String name) {
        return projectId + "\u0000" + name.trim();
    }

    /**
     * 落库一位新居民后补建 11 维档案（零阻断；这是"AI 生成 → 11 维档案落库"的最后一环）。
     * <p>链路：取回生成期暂存 → {@link PersonaJson#readQuietly} 解析为 {@link PersonaProfile} →
     * 维度全空判定 → {@link PersonaService#savePersona(Long, PersonaProfile, String)}
     * （source={@code generated}；③⑤⑥ 由它分派到各自权威模块，落库前剥离 ③ 维与 ⑩.lineage）。</p>
     * <p><b>零阻断</b>：暂存缺失、解析失败、维度全空、savePersona 未生效、任何异常——一律
     * {@code log.warn} 后返回，绝不抛异常、绝不阻断批量入库、绝不写脏档案（居民结构列已落库，
     * 档案可稍后由 {@code PersonaBootstrapService} 补建）。</p>
     * <p><b>事务说明</b>：兜底建档分支（{@link #bootstrapPersonaQuietly}）
     * 含数十秒级 AI 调用——因此 <b>{@link #batchSave} 已去掉 @Transactional</b>（架构不变量：
     * 任何含 AI 调用的方法都不得带事务；batchSave 的"整批原子性"让位于该不变量，单行写库各自
     * 原子，中途失败只影响该行，后续行继续）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param npc 刚落库的居民行（id 已生成）
     */
    private void persistPersonaQuietly(Long projectId, ActorCharacter npc) {
        String personaJson = takePendingPersona(projectId, npc.getName());
        if (personaJson == null || personaJson.isBlank()) {
            if (npcPersonaFallbackBootstrap) {
                bootstrapPersonaQuietly(projectId, npc);
            } else {
                log.warn("[居民入库] 无暂存的 11 维档案（结构列已入库、档案本次跳过；如需 100% 建档请开启 "
                        + "holzyn.actor.crowd.npc-persona-fallback-bootstrap，成本=2 次 AI 调用/人）：npc={} 名称={}",
                        npc.getId(), npc.getName());
            }
            return;
        }
        PersonaProfile profile = PersonaJson.readQuietly(personaJson);
        if (profile == null || !hasAnyDimContent(profile)) {
            log.warn("[居民入库] AI 输出的档案无法成为有效 11 维档案（已跳过、不落库、不阻断）："
                            + "npc={} 名称={} 原因={} 原文={}",
                    npc.getId(), npc.getName(),
                    profile == null ? "JSON 无法解析为 PersonaProfile" : "解析结果 11 维全无内容（空档案）",
                    truncate(personaJson, RAW_LOG_LIMIT));
            return;
        }
        try {
            // 落库唯一出口：档案服务按 _sources 分派（③→actor_state、⑤→actor_goal、⑥→actor_knowledge_doc）
            int version = personaService.savePersona(npc.getId(), profile, SOURCE_GENERATED);
            if (version <= 0) {
                log.warn("[居民入库] 档案未落库（savePersona 返回 {}，已跳过、不阻断）：npc={} 名称={}",
                        version, npc.getId(), npc.getName());
                return;
            }
            log.info("[居民入库] 11 维档案已落库：npc={} 名称={} 版本={}", npc.getId(), npc.getName(), version);
        } catch (Exception e) {
            log.warn("[居民入库] 档案落库异常（已跳过、不阻断入库）：npc={} 名称={} 原因={}",
                    npc.getId(), npc.getName(), e.getMessage());
        }
    }

    /**
     * 兜底路径：逐人两段式 AI 建档（<b>成本 2 次 AI 调用/人</b>；仅在
     * {@link #npcPersonaFallbackBootstrap} 打开且暂存缺失时触发）。
     *
     * @param projectId 项目 ID
     *
     * @param npc 居民行
     */
    private void bootstrapPersonaQuietly(Long projectId, ActorCharacter npc) {
        try {
            PersonaProfile profile = personaBootstrapService.bootstrap(projectId, npc.getId(), npc.getDetail());
            if (profile == null) {
                log.warn("[居民入库] 兜底两段式建档未产出有效档案（已跳过、不阻断）：npc={} 名称={}",
                        npc.getId(), npc.getName());
            } else {
                log.info("[居民入库] 兜底两段式建档完成：npc={} 名称={} 版本={}",
                        npc.getId(), npc.getName(), profile.getPersonaVersion());
            }
        } catch (Exception e) {
            log.warn("[居民入库] 兜底两段式建档异常（已跳过、不阻断）：npc={} 名称={} 原因={}",
                    npc.getId(), npc.getName(), e.getMessage());
        }
    }

    /**
     * 11 维中是否有任一维装了实际内容（空对象/空数组/空白字符串不算内容）。
     * <p>与 {@code PersonaBootstrapService} 的同名判定同口径：只判"档案存储态"，不判 MODULE/DER 维
     * ——③ 的权威在 {@code actor_state}、⑩.lineage 的权威在 {@code actor_relation}，
     * 档案内缺省是正常的，由落库分派与渲染器投影补齐。</p>
     *
     * @param p 解析出的档案（可空）
     *
     * @return true=至少一维有内容
     */
    private static boolean hasAnyDimContent(PersonaProfile p) {
        if (p == null || p.getDims() == null) {
            return false;
        }
        for (String dim : PersonaSchema.ALL_DIMS) {
            Object value = p.getDims().byKey(dim);
            if (value == null) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(value));
                if (jsonHasContent(node)) {
                    return true;
                }
            } catch (Exception e) {
                // 序列化异常理论上不会发生：按"有内容"处理，交给后续落库/渲染链路自行降级
                return true;
            }
        }
        return false;
    }

    /**
     * 递归判定 JSON 节点是否有实际内容（对象/数组只要有任一非空白叶子即为有内容）。
     *
     * @param node JSON 节点
     *
     * @return true=有实际内容
     */
    private static boolean jsonHasContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                if (jsonHasContent(child)) {
                    return true;
                }
            }
            return false;
        }
        // Jackson 3：文本取值走 asString（asText(String) 已废弃）
        return !node.asString("").isBlank();
    }

    // ==================== 内部工具 ====================

    /** 位置归一（字段注入保持构造器不变；null=归一关闭，不落 location_id） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.world.service.LocationNormalizationService locationNormalizationService;

    /** 校验 DTO：名称必填 + 非空 */
    private void validate(OrdinaryNpcDTO dto) {
        if (dto == null || dto.name() == null || dto.name().isBlank()) {
            throw new BizException(400, "名称不能为空");
        }
    }

    /** DTO → 实体字段（截断兜底） */
    private void apply(ActorCharacter e, OrdinaryNpcDTO dto) {
        e.setName(truncate(dto.name(), NAME_MAX));
        e.setGender(truncate(dto.gender(), GENDER_MAX));
        e.setRace(truncate(dto.race(), RACE_MAX));
        e.setSubRace(truncate(dto.subRace(), SUB_RACE_MAX));
        e.setAge(dto.age() == null ? null : Math.max(0, Math.min(10000, dto.age())));
        e.setAffiliation(truncate(dto.affiliation(), AFFILIATION_MAX));
        e.setLocation(truncate(dto.location(), LOCATION_MAX));
        // 位置归一：按地点表匹配落 location_id（未命中 null，自由文本兜底）
        e.setLocationId(normalizeLocationId(e.getProjectId(), e.getLocation()));
        e.setOccupation(truncate(dto.occupation(), OCCUPATION_MAX));
        e.setDetail(truncate(dto.detail(), DETAIL_MAX));
    }

    /**
     * 位置归一落地点 ID（；归一服务缺失/未命中返回 null，保留自由文本兜底）。
     *
     * @param projectId 项目 ID
     *
     * @param location 自由文本位置（可空）
     *
     * @return 地点表 ID 或 null
     */
    private Long normalizeLocationId(Long projectId, String location) {
        if (locationNormalizationService == null || projectId == null || location == null || location.isBlank()) {
            return null;
        }
        try {
            return locationNormalizationService.normalizedId(projectId, location);
        } catch (Exception e) {
            log.warn("[位置归一] 普通 NPC 位置归一失败（保留自由文本）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    /** 世界观实体 → 紧凑文本（名称/题材/时代/地理/势力/规则/文化/历史/补充） */
    private String renderWorld(Long projectId) {
        return worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(this::renderWorld)
                .orElse("");
    }

    private String renderWorld(ActorWorldSetting w) {
        StringBuilder t = new StringBuilder();
        appendIf(t, "名称", w.getName());
        appendIf(t, "题材", w.getGenre());
        appendIf(t, "时代", w.getEra());
        appendIf(t, "地理", w.getGeography());
        appendIf(t, "势力", w.getFactions());
        appendIf(t, "力量体系", w.getMagicSystem());
        appendIf(t, "文化", w.getCulture());
        appendIf(t, "历史", w.getHistory());
        appendIf(t, "补充设定", w.getFreeText());
        return t.length() == 0 ? "" : t.toString();
    }

    /** 世界观名称（模板占位） */
    private String worldName(Long projectId) {
        return worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(w -> w.getName() == null || w.getName().isBlank() ? "未知世界" : w.getName())
                .orElse("未知世界");
    }

    private void appendIf(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(label).append("：").append(value).append("\n");
    }

    /** 项目运行时行（不存在则创建） */
    private ActorCrowdRuntime runtime(Long projectId) {
        return runtimeRepository.findByProjectId(projectId).orElseGet(() -> {
            ActorCrowdRuntime rt = new ActorCrowdRuntime();
            rt.setProjectId(projectId);
            return runtimeRepository.save(rt);
        });
    }

    /** 校验项目归属当前用户（越权抛 404） */
    private void requireProject(Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 按 id + 项目归属定位实体（不存在/越权/非普通型 均抛 404）。
     * <p>统一主表后"普通型"不再由表区分，必须按 {@code type='common'} 校验——
     * 否则本服务的单条改删入口会误伤特殊型角色（type=special 的行）。</p>
     *
     * @param id 主键
     *
     * @return 普通型 NPC 实体
     */
    private ActorCharacter requireOwned(Long id) {
        ActorCharacter n = npcRepository.findById(id)
                .filter(ActorCharacter::isCommon)
                .orElseThrow(() -> new BizException(404, "普通型 NPC 不存在或无权访问"));
        requireProject(n.getProjectId());
        return n;
    }

    /** 字符串截断（null 安全，空串返回 null 便于存库为 NULL） */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 字符串包含（null 安全） */
    private static boolean contains(String s, String k) {
        return s != null && s.contains(k);
    }

    /**
     * null 安全的占位符 Map 构造（Prompt 渲染用）。
     * <p>理由：{@link Map#of} 不接受 null 值，而 truncate 对空串返回 null；
     * PromptTemplateService.render 对 null 占位符值会替换为空串，故必须用可变 Map 承载。</p>
     *
     * @param kv 键值对（偶数个参数：key, value, key, value, ...）
     *
     * @return LinkedHashMap（保持插入顺序，可含 null 值）
     */
    private static Map<String, Object> ph(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 分类字段中文标签（调度 Prompt 展示） */
    static String fieldLabel(String field) {
        return switch (field == null ? "" : field) {
            case FIELD_RACE -> "种族";
            case FIELD_AFFILIATION -> "归属";
            case FIELD_OCCUPATION -> "职业";
            default -> field == null ? "" : field;
        };
    }

    /** null 安全 */
    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** 供应商错误文案收敛（AI 调用失败 → 友好提示） */
    private String friendlyError(Exception e) {
        if (e instanceof BizException be) return be.getMessage();
        if (e instanceof com.holzyn.actor.ai.AiCallException ae && ae.getMessage() != null && !ae.getMessage().isBlank()) {
            return ae.getMessage();
        }
        return e == null || e.getMessage() == null || e.getMessage().isBlank() ? "未知错误" : e.getMessage();
    }
}
