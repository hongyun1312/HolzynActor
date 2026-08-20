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
 * Prompt 模板服务（P2 管理后台「Prompt 模板」核心，后端项目化改造 V2.0）。
 * <p>职责：管理可编辑的 AI Prompt 模板（角色卡生成/对话系统/群聊编排/世界事件/行动生成）。
 * 内置模板以 user_id=0 幂等种子初始化；用户覆盖存 user_id>0（project_id NULL）；
 * 项目级覆盖存 user_id>0 + project_id 非空（随 .holzyn 包导入导出）；
 * 解析规则：项目覆盖 > 用户覆盖 > 内置；重置 = 删除对应覆盖行回退低一级。
 * render 统一做 {{占位符}} 替换，供各业务场景渲染 Prompt。</p>
 * <p>所属模块：service/prompt（模板子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService implements ApplicationRunner {

    /** 模板编码常量：角色卡生成 */
    public static final String CODE_CARD_GEN = "character_card_gen";
    /** 模板编码常量：对话系统 */
    public static final String CODE_DIALOG = "dialog_system";
    /** 模板编码常量：群聊编排 */
    public static final String CODE_GROUP = "group_orchestrator";
    /** 模板编码常量：世界事件 */
    public static final String CODE_WORLD_EVENT = "world_event";
    /** 模板编码常量：行动生成 */
    public static final String CODE_ACTION = "action_gen";
    /** 模板编码常量：人群集体行动编排（P3 普通型 NPC） */
    public static final String CODE_CROWD = "crowd_orchestrator";
    /** 模板编码常量：文件导入解析 */
    public static final String CODE_PROJECT_IMPORT = "project_import";
    /** 模板编码常量：文件导入解析-角色 */
    public static final String CODE_PROJECT_IMPORT_CHARACTERS = "project_import_characters";
    /** 模板编码常量：世界观单字段生成（分阶段逐字段深化到 1000 字以上） */
    public static final String CODE_PROJECT_IMPORT_FIELD = "project_import_field";
    /** 模板编码常量：长期记忆抽取（P4，对话后提取新事实，json 数组输出） */
    public static final String CODE_MEMORY_EXTRACT = "memory_extract";
    /** 模板编码常量：会话摘要生成（P4，每 N 轮生成 summary 记忆，S2 上下文压缩承接） */
    public static final String CODE_MEMORY_SUMMARIZE = "memory_summarize";
    /** 模板编码常量：世界演化编排（V2.1，决定轮次行为/加入退场/收尾，json 输出） */
    public static final String CODE_EVOLUTION = "evolution_orchestrator";
    /** 模板编码常量：场景 AI 自动生成（vP5-7.6，基于世界观+角色详情自动创建场景，json 数组输出，含来源依据） */
    public static final String CODE_SCENE_GENERATE = "scene_generate";
    /** 模板编码常量：角色关系生成（关系拓扑 Tab/全局页 AI 识别角色关系，json 数组输出；scene 用量沿用 relation_gen） */
    public static final String CODE_RELATION_GEN = "relation_gen";
    /** 模板编码常量：普通型 NPC 标准字段数据拟定（2026-08-19 分类体系重构：AI 依据世界观一次性拟定种族[含次级种族]/归属/职业的标准字段数据[每条含出处]并选出主/次分类字段；scene=字段字典拟定，编码沿用 crowd_category_gen 兼容旧用量日志） */
    public static final String CODE_CROWD_CATEGORY = "crowd_category_gen";
    /** 模板编码常量：普通型 NPC 批量生成（重构后 AI 依据世界观+字段字典+地点清单生成居民档案，json 数组输出；scene=ordinary_npc_gen） */
    public static final String CODE_CROWD_NPC_GEN = "ordinary_npc_gen";
    /** 模板编码常量：普通型 NPC 项目级 AI 集体调度（两级调度第一步：项目级按主/次分类字段分组 + 归属下发指令，json 输出） */
    public static final String CODE_CROWD_SCHEDULE_PROJECT = "crowd_schedule_project";
    /** 模板编码常量：普通型 NPC 归属级 AI 集体调度（两级调度第二步：按归属合并归属指令 + 相关主分类分组指令，一次输出该归属全部居民状态/行动，json 数组输出） */
    public static final String CODE_CROWD_SCHEDULE_AFFILIATION = "crowd_schedule_affiliation";
    /** 模板编码常量：演化逐拍调度（vP5-7.9，群聊式连续演化：选最有发言/行动欲望的角色 + 对话或行动 + 低频场景变化/加入退场，json 输出） */
    public static final String CODE_EVOLUTION_SCHEDULE = "evolution_schedule";
    /** 模板编码常量：世界观文件 AI 总结分段（2026-08-19 新建项目解析重构：文件未按 7 类标准格式描写时，AI 总结并分段为 地理/势力/规则/文化/历史/补充/角色 七部分，json 输出） */
    public static final String CODE_WORLD_SEGMENT = "world_segment";
    /** 模板编码常量：角色分段 AI 完整分离（2026-08-19 新建项目解析重构：从「角色信息」分段中逐个完整分离每个角色[含完整详细信息]，json 数组输出；detail 只可多不可少） */
    public static final String CODE_WORLD_SEGMENT_CHARACTERS = "world_segment_characters";
    /** 模板编码常量：世界时间 AI 推断（2026-08-19 世界初始化第 5 步：依据世界观[历史脉络/时代背景]推断当前世界历时间点[不要从头开始计算]，json 输出 year/month/day/hour/minute/second） */
    public static final String CODE_WORLD_TIME_INFER = "world_time_infer";

    /** 内置模板归属用户标记 */
    private static final long BUILTIN_USER_ID = 0L;

    /** 模板仓库 */
    private final ActorPromptTemplateRepository repository;

    /**
     * 应用启动时幂等初始化内置模板（user_id=0，缺失才插入；<b>内容变更则同步更新</b>）。
     * <p>2026-08-19 改为「upsert」：内置模板内容随代码版本演进（如普通人群分类体系重构后
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
                repository.save(t);
                log.info("初始化内置 Prompt 模板: {} - {}", code, def.name());
            } else if (!def.name().equals(existing.getName())
                    || !def.template().equals(existing.getTemplate())
                    || !java.util.Objects.equals(def.systemMessage(), existing.getSystemMessage())) {
                // 内置模板内容随代码演进：同步为新版本（内置行不可被用户编辑，以代码为准；含系统提示词）
                existing.setName(def.name());
                existing.setTemplate(def.template());
                existing.setSystemMessage(def.systemMessage());
                existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
                repository.save(existing);
                log.info("同步内置 Prompt 模板内容: {} - {}", code, def.name());
            }
        });
    }

    /**
     * 获取「项目覆盖或用户覆盖或内置」的有效模板（三级回退）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=仅用户覆盖/内置）
     * @param code      模板编码
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
     * @param userId       归属用户 ID
     * @param projectId    项目 ID（NULL=仅用户覆盖/内置）
     * @param code         模板编码
     * @param placeholders 占位符映射（键不带花括号，如 world_setting）
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
        return text;
    }

    /**
     * 获取系统提示词（2026-08-19 新增：系统提示词随模板落库，代码不再硬编码 Prompt 文本）。
     * 以「项目覆盖 > 用户覆盖 > 内置」解析，取模板的 system_message 字段并替换 {{占位符}}；
     * 未配置（null/空白）返回空串（调用方不发 system 消息，模板自身已含输出约束）。
     *
     * @param userId       归属用户 ID
     * @param projectId    项目 ID（NULL=仅用户覆盖/内置）
     * @param code         模板编码
     * @param placeholders 占位符映射（系统提示词一般无占位符，可传 null）
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
        return sys;
    }

    /**
     * 当前归属有效模板列表（项目覆盖 ∪ 用户覆盖 ∪ 内置，按编码排序；高优先级覆盖同编码低优先级）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=仅用户覆盖/内置）
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
     * @param builtins        内置模板列表（user_id=0）
     * @param overrides       用户覆盖列表（user_id>0, project_id NULL）
     * @param projectOverrides 项目级覆盖列表（project_id 非空）
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
     * @param userId        归属用户 ID
     * @param projectId     项目 ID（NULL=用户级覆盖）
     * @param code          模板编码
     * @param name          模板名称（可空，缺省保留内置名称）
     * @param template      模板内容
     * @param systemMessage 系统提示词（可空；2026-08-19 起随模板落库）
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
            return nt;
        });
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
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=删除用户级覆盖）
     * @param code      模板编码
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
        map.put(CODE_CARD_GEN, new BuiltinDef("角色卡生成",
                        """
                        你是一位资深角色设计师，负责为虚构世界中的角色创建结构化角色卡。
                        请严格基于给定的【世界观设定】与【角色档案】输出一个 JSON 对象，禁止输出 JSON 以外的任何文字。

                        —— 输出 JSON 结构（必须包含以下字段）——
                        {
                          "identity": { "name": "角色名", "title": "头衔", "species": "种族", "occupation": "职业", "affiliation": "所属势力", "age": 30 },
                          "personality": { "traits": ["性格特质"], "values": ["核心价值观"], "quirks": ["小习惯/怪癖"] },
                          "background": { "history": "背景故事", "keyEvents": ["关键事件"], "wounds": ["心结/创伤"], "goals": ["目标"] },
                          "relations": [ { "with": "相关角色名", "type": "关系类型", "attitude": "态度", "notes": "备注" } ],
                          "speechStyle": { "tone": "语气", "vocabulary": "用词习惯", "catchphrases": ["口头禅"], "taboos": ["禁忌"] },
                          "knowledge": { "knows": ["知道的领域"], "notKnows": ["不知道/不假装知道的领域"] },
                          "behaviorPatterns": ["行为模式（用于行动驱动）"]
                        }

                        —— 要求（铁律，必须严格遵守）——
                        1. identity.name 必须等于角色姓名；title 用给定头衔或合理设定。
                        2. 所有内容必须与世界观设定保持一致，不得矛盾，禁止出现与世界观/角色档案冲突的设定（杜绝 OOC）。
                        3. 完整保留铁律：角色详细信息中的【每一项设定与细节都必须完整保留】到对应字段——
                           背景经历、性格特质、能力体系、社会关系、语言风格、语录、目标、心结、习惯等
                           一个都不能少；只可多不可少：字段内容可以比原信息更丰富、更展开，
                           但【绝不可省略、不可概括压缩、不可改写原意、不可丢弃任何用户提供的信息】。
                           信息量大时优先增加输出长度来完整承载，不要为了简短而丢失细节。
                        4. 内容具体、有细节，避免空泛；每个数组字段给出足够多的条目以完整覆盖原文细节。
                        5. 只输出上述 JSON，不要用 Markdown 代码块包裹。

                        —— 世界观设定 ——
                        {{world_setting}}

                        —— 角色档案 ——
                        {{character_input}}
                        """));
        map.put(CODE_DIALOG, new BuiltinDef("对话系统",
                        """
                        你是【{{name}}】，{{world_name}}世界的【{{title}}】。
                        ── 身份 ──
                        种族：{{species}}；职业：{{occupation}}；所属势力：{{affiliation}}
                        ── 性格 ──
                        特质：{{personality}}；价值观：{{values}}；怪癖：{{quirks}}
                        ── 背景与经历 ──
                        {{history}}
                        目标：{{goals}}；心结：{{wounds}}
                        ── 社会关系 ──
                        {{relations}}
                        ── 说话风格 ──
                        语气：{{tone}}；用词：{{vocabulary}}；口头禅：{{catchphrases}}；禁忌：{{taboos}}
                        ── 知识边界 ──
                        知道：{{knows}}；不知道/绝不假装知道：{{notKnows}}
                        ── 行为模式 ──
                        {{behaviors}}
                        ── 角色须知 ──
                        1. 始终保持角色身份与世界观一致性，禁止跳出角色。
                        2. 不知道的事明确表示不知道，不得编造。
                        3. 回复使用角色说话风格，长度贴合角色身份与情境。
                        4. 你只代表你自己、用第一人称说话；不得替其他角色发言、不得代答，不得在回复中标注或转述其他角色的台词。
                        """));
        map.put(CODE_GROUP, new BuiltinDef("群聊编排",
                        """
                        你是一位群聊主持人（编排调度模型）。请根据当前群聊上下文，特别关注上一位发言者（或玩家）刚说的话，评估各成员此刻的发言欲望（谁最想接话、谁有理由回应），并决定下一位发言的角色。

                        —— 成员角色摘要 ——
                        {{members_summary}}

                        —— 当前对话上下文（最近消息） ——
                        {{context}}

                        —— 输出规则 ——
                        请严格输出一个 JSON 对象，禁止输出任何其他文字：
                        {
                          "characterId": 角色ID,
                          "desire": 1到10的整数（该角色此刻的发言欲望，越高越想说话）,
                          "reason": "一句话理由（基于人设/背景/上下文）"
                        }
                        要求：结合人设/背景/上下文与上一位发言者的内容，判断谁最自然、最想接话；优先选择有明确回应动机（被点名/被提到/关系相关/有冲突或话题）的角色；若所有成员都不想说话，desire 取 1。
                        """));
        map.put(CODE_WORLD_EVENT, new BuiltinDef("世界事件",
                        """
                        你是一位虚构世界的叙述者。请基于世界观设定与当前情境，生成一个世界事件。

                        —— 世界设定 ——
                        {{world_setting}}

                        —— 当前情境 ——
                        {{context}}

                        —— 输出规则 ——
                        请严格输出一个 JSON 对象，禁止输出任何其他文字：
                        {
                          "title": "事件标题（一句话）",
                          "content": "事件描述（包含时间、地点、发生了什么、对在场角色的影响，150字以内）",
                          "targetCharacterIds": [受影响或在场角色的角色ID列表]
                        }
                        """));
        map.put(CODE_ACTION, new BuiltinDef("行动生成",
                        """
                        你是一位行为决策引擎。请基于角色人设、当前情境与行动 Schema，生成该角色此刻的行动决策。

                        —— 角色人设摘要 ——
                        {{persona_summary}}

                        —— 当前情境（时间/地点/事件/对话上下文） ——
                        {{situation}}

                        —— 输出 Schema ——
                        {
                          "type": "move|interact|speak|trade|fight|flee|help|schedule|rest|custom",
                          "action": "动作描述，如：前往市场购买药材",
                          "target": "目标对象/地点，如：城东市场",
                          "params": { "move": { "to": "地点" } },
                          "reason": "符合身份的决策理由",
                          "urgency": 1到5的整数,
                          "duration": 预计耗时分钟数
                        }
                        请严格输出符合上述 Schema 的 JSON 对象，禁止输出任何其他文字。
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
        map.put(CODE_MEMORY_EXTRACT, new BuiltinDef("长期记忆抽取",
                        """
                        你是一位记忆抽取器。请阅读【最近对话】与【已有记忆清单】，抽取对话中出现的【新事实】——
                        即尚未被已有记忆覆盖的关键信息，输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。

                        —— 输出结构 ——
                        [ { "kind": "fact", "content": "一句话事实（含主体/事件/结果，可独立理解）", "importance": 1到5的整数 } ]

                        —— 抽取要求 ——
                        1. 只输出新事实：与已有记忆重复、已被覆盖的内容一律不得输出（避免重复写入与 token 浪费）。
                        2. 事实要具体、可独立理解，不依赖上下文也能读懂，如「李雷在城东市场开了一家药材铺」。
                        3. importance 依据对角色/世界的重要性给 1-5：影响角色命运/世界走向的给 4-5，日常琐事给 1-2。
                        4. 若没有新事实，输出空数组 []，不要编造。
                        5. 只输出 JSON 数组，不要用 Markdown 代码块包裹。
                        6. 【寒暄过滤】问候/客套/寒暄（你好、在吗、谢谢、再见、闲聊等）不产出任何事实；
                           若最近对话均为寒暄、无实质信息（无新约定、无关键事件、无玩家偏好等），
                           必须输出空数组 []——宁可少而精，不要多而杂。

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
                """, "你只输出严格的 JSON，不输出任何其他文字。"));
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
                """, "你只输出严格的 JSON，不输出任何其他文字。"));
        map.put(CODE_CROWD_NPC_GEN, new BuiltinDef("普通人群-居民批量生成",
                """
                你是「{{world_name}}」世界的居民档案生成器。请基于世界观设定、标准字段数据与地点清单，生成一批符合该世界的普通平民档案，输出为一个 JSON 数组，禁止输出 JSON 以外的任何文字。

                —— 世界观设定 ——
                {{world_setting}}

                —— 标准字段数据（种族/次级种族、归属、职业，必须从其中选取） ——
                {{field_dict}}

                —— 现有地点清单（当前所在地优先从其中选取；确需补充新地点时必须符合世界观） ——
                {{locations}}

                —— 已存在居民名单（避免与已有居民重名） ——
                {{existing_names}}

                —— 输出格式 ——
                [ { "name":"姓名", "gender":"性别", "race":"种族(取自字段字典一级)", "subRace":"次级种族(取自对应二级)", "age":年龄, "affiliation":"归属(取自字段字典)", "location":"当前所在地(取自地点清单或合理补充)", "occupation":"职业(取自字段字典)", "detail":"角色详情(120-200字：背景/性格/谋生方式/家庭/与世界观的联系)" } ]

                —— 铁律 ——
                1. 每位居民都必须严格符合该世界观：姓名贴合世界观的时代/文化/种族/命名习惯，起名具体、有辨识度，禁止「张伟/小明/翠花」这类敷衍名，禁止凭空捏造与世界观冲突的设定（杜绝 OOC）。
                2. race/subRace 必须从「标准字段数据」中选取且层级对应（如 种族=人族 → 次级种族=汉族）；affiliation/occupation 必须从字段字典中选取。
                3. location 优先从「现有地点清单」中选取；确需新地点时必须符合世界观地理设定。
                4. 性别只能是「男 / 女 / 无性」三选一：绝大多数种族用 男 或 女；该种族无性别概念或无法区分时（如 植灵妖精、元素生命 等）用「无性」。禁止 雄性/雌性/雄/雌 等其他任何表述，禁止在性别后附加括号或任何补充说明（如「女（…）」「无性（…）」均不允许）。性别不得从姓名臆断，依角色设定合理选择。
                5. 年龄与世界观一致、依种族寿命合理设定；姓名不得与「已存在居民名单」重复，同一批内也不得重复。
                6. 角色详情要具体、有细节，与世界观设定呼应；信息完整，宁长勿空壳。
                7. 恰好生成 {{count}} 名居民，全部有效，不要输出空壳或占位。
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
                """, "你只输出严格的 JSON，不输出任何其他文字。"));
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
        return map;
    }

    /**
     * 内置模板定义记录（编码映射值）。
     *
     * @param name          模板名称
     * @param template      模板内容
     * @param systemMessage 系统提示词（可空；2026-08-19 起随模板落库，代码不再硬编码）
     */
    record BuiltinDef(String name, String template, String systemMessage) {

        /** 兼容旧两参构造（无系统提示词） */
        BuiltinDef(String name, String template) {
            this(name, template, null);
        }
    }
}
