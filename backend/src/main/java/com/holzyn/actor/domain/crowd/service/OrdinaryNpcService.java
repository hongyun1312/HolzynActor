package com.holzyn.actor.domain.crowd.service;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.crowd.dto.OrdinaryNpcDTO;
import com.holzyn.actor.domain.crowd.entity.ActorCrowdRuntime;
import com.holzyn.actor.domain.crowd.entity.ActorNpcFieldDict;
import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;
import com.holzyn.actor.domain.crowd.repository.ActorCrowdRuntimeRepository;
import com.holzyn.actor.domain.crowd.repository.ActorNpcFieldDictRepository;
import com.holzyn.actor.domain.crowd.repository.ActorOrdinaryNpcRepository;
import com.holzyn.actor.domain.crowd.vo.FieldDictPreviewVO;
import com.holzyn.actor.domain.crowd.vo.FieldDictVO;
import com.holzyn.actor.domain.crowd.vo.OrdinaryNpcDraftVO;
import com.holzyn.actor.domain.crowd.vo.OrdinaryNpcVO;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 普通型 NPC 服务（2026-08-19 分类体系重构后的核心服务）。
 * <p>职责：承载普通型 NPC 的全部业务逻辑——
 * ① 单表 CRUD + 多字段筛选/排序/分页/统计；
 * ② 标准字段数据（字段字典）：AI 依据世界观一次性拟定种族(含次级种族)/归属/职业的标准值
 * （每条含出处、严格符合世界观），并选出主/次分类字段（一主一次，供人群分组/调度）；
 *    所在地复用世界观地点表（生成时可合理补充）；预览确认后整体保存，可重新生成/手动增删；
 * ③ AI 批量生成：依据世界观 + 字段字典 + 地点清单生成居民（≤500，每批 30，SSE 逐条推送），
 *    字段必须从字典/地点中选取，避免 OOC；不含关系（关系在 NPC 生成后单独生成，写入关系表）；
 * ④ 调度：定时任务走程序化状态机（零成本），手动「AI 调度」走两级 AI
 *   （项目级：按主/次分类字段分组 + 归属 下发指令 → 归属级：合并归属指令 + 相关主分类分组指令
 *    逐人输出状态/行动，失败降级程序化）；
 * ⑤ 环境摘要：聚合项目级调度快照供对话/行动注入。</p>
 * <p>纯逻辑（字段字典清洗/草稿解析/状态机/行动描述/分组概览）抽为静态方法便于单元测试。</p>
 * <p>所属模块：service/crowd（普通型人群子域）</p>
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
    /** 世界观输入截断 */
    private static final int WORLD_MAX = 6000;
    /** 生成居民单次 AI 输出 token 上限（每批 30 人 × 详情 200 字） */
    private static final int NPC_MAX_TOKENS = 16384;
    /** 字段字典拟定单次 AI 输出 token 上限 */
    private static final int DICT_MAX_TOKENS = 8192;
    /** 调度单次 AI 输出 token 上限 */
    private static final int SCHEDULE_MAX_TOKENS = 8192;
    /** 名单截断 */
    private static final int ROSTER_MAX = 3000;
    /** 概况截断 */
    private static final int OVERVIEW_MAX = 4000;
    /** 分组指令截断 */
    private static final int DIRECTIVE_MAX = 500;
    /** 字段字典每字段一级上限 / race 二级每类上限 */
    private static final int DICT_L1_MAX = 30;
    private static final int DICT_L2_MAX = 20;

    // 字段名常量（字段字典 + 主/次分类字段共用）
    public static final String FIELD_RACE = "race";
    public static final String FIELD_AFFILIATION = "affiliation";
    public static final String FIELD_OCCUPATION = "occupation";
    private static final Set<String> CLASSIFY_FIELDS = Set.of(FIELD_RACE, FIELD_AFFILIATION, FIELD_OCCUPATION);

    // 字段长度上限（与实体/DTO 对齐，服务层兜底截断）
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

    private final ActorOrdinaryNpcRepository npcRepository;
    private final ActorNpcFieldDictRepository fieldDictRepository;
    private final ActorCrowdRuntimeRepository runtimeRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final WorldLocationRepository worldLocationRepository;
    private final ActorCharacterRepository characterRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AiProviderRouter aiProviderRouter;
    private final UsageLogService usageLogService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    /** 静态 ObjectMapper（纯逻辑静态解析复用，避免重复创建） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 查询条件（多字段筛选 + 排序） ====================

    /**
     * 普通型 NPC 查询条件（列表/筛选/排序；2026-08-19 改为全部字段可筛可排序）。
     *
     * @param gender     性别（精确）
     * @param race       种族（一级，精确）
     * @param subRace    次级种族（二级，精确）
     * @param affiliation 归属（精确）
     * @param occupation 职业（精确）
     * @param location   当前所在地（精确）
     * @param keyword    关键词（匹配名称/种族/次级种族/归属/职业/地点/详情）
     * @param ageMin     年龄下限（含）
     * @param ageMax     年龄上限（含）
     * @param sortBy     排序字段白名单：id/name/age/race/subRace/affiliation/occupation/location（空=id）
     * @param sortDir    asc/desc（默认 asc）
     */
    public record NpcQuery(String gender, String race, String subRace, String affiliation,
                           String occupation, String location, String keyword,
                           Integer ageMin, Integer ageMax, String sortBy, String sortDir) {
    }

    // ==================== CRUD ====================

    /**
     * 分页查询（多字段筛选 + 排序）。
     * <p>单项目 ≤500 量级，内存筛选 + 排序 + 分页（稳定简单）。</p>
     *
     * @param projectId 项目 ID
     * @param q         查询条件（字段筛选/关键词/年龄区间/排序）
     * @param page      页码（从 1 开始）
     * @param size      每页条数
     * @return 分页结果
     */
    @Transactional(readOnly = true)
    public PageResult<OrdinaryNpcVO> page(Long projectId, NpcQuery q, int page, int size) {
        requireProject(projectId);
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        List<ActorOrdinaryNpc> all = npcRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(n -> match(n, q))
                .sorted(sorter(q))
                .toList();
        int total = all.size();
        int from = Math.min(total, Math.max(0, (p - 1) * s));
        int to = Math.min(total, from + s);
        List<OrdinaryNpcVO> list = all.subList(from, to).stream().map(OrdinaryNpcVO::of).toList();
        return PageResult.of(list, total, p, s);
    }

    /** 筛选匹配：各字段精确 + 年龄区间 + 关键词（纯静态可测） */
    static boolean match(ActorOrdinaryNpc n, NpcQuery q) {
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
    static Comparator<ActorOrdinaryNpc> sorter(NpcQuery q) {
        String sortBy = q == null || q.sortBy() == null ? "" : q.sortBy().trim();
        Function<ActorOrdinaryNpc, Object> value = switch (sortBy) {
            case "name" -> ActorOrdinaryNpc::getName;
            case "age" -> ActorOrdinaryNpc::getAge;
            case "race" -> ActorOrdinaryNpc::getRace;
            case "subRace" -> ActorOrdinaryNpc::getSubRace;
            case "affiliation" -> ActorOrdinaryNpc::getAffiliation;
            case "occupation" -> ActorOrdinaryNpc::getOccupation;
            case "location" -> ActorOrdinaryNpc::getLocation;
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
     *
     * @param projectId 项目 ID
     * @param dto       档案入参（名称必填）
     * @return 新增后的 VO
     */
    @Transactional
    public OrdinaryNpcVO create(Long projectId, OrdinaryNpcDTO dto) {
        requireProject(projectId);
        validate(dto);
        ActorOrdinaryNpc e = new ActorOrdinaryNpc();
        e.setProjectId(projectId);
        apply(e, dto);
        return OrdinaryNpcVO.of(npcRepository.save(e));
    }

    /**
     * 修改普通型 NPC（归属校验）。
     *
     * @param id  主键
     * @param dto 档案入参
     * @return 更新后的 VO
     */
    @Transactional
    public OrdinaryNpcVO update(Long id, OrdinaryNpcDTO dto) {
        ActorOrdinaryNpc e = requireOwned(id);
        validate(dto);
        apply(e, dto);
        return OrdinaryNpcVO.of(npcRepository.save(e));
    }

    /**
     * 删除单个普通型 NPC。
     *
     * @param id 主键
     */
    @Transactional
    public void delete(Long id) {
        npcRepository.delete(requireOwned(id));
    }

    /**
     * 批量删除（多选删除）。
     *
     * @param projectId 项目 ID
     * @param ids       待删除主键列表
     * @return 实际删除数
     */
    @Transactional
    public int batchDelete(Long projectId, List<Long> ids) {
        requireProject(projectId);
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) {
            if (id != null && npcRepository.findByIdAndProjectId(id, projectId).isPresent()) {
                npcRepository.deleteById(id);
                n++;
            }
        }
        log.info("[普通NPC] 项目 {} 批量删除 {} 条", projectId, n);
        return n;
    }

    /**
     * 批量入库（AI 生成预览确认后保存选中的草稿）。
     * 按名称与已存在居民去重（重名跳过），逐条落库。
     *
     * @param projectId 项目 ID
     * @param items     确认后的居民档案列表
     * @return 实际入库数
     */
    @Transactional
    public int batchSave(Long projectId, List<OrdinaryNpcDTO> items) {
        requireProject(projectId);
        if (items == null || items.isEmpty()) return 0;
        Set<String> used = new LinkedHashSet<>();
        npcRepository.findByProjectIdOrderByIdAsc(projectId).forEach(n -> used.add(n.getName()));
        int saved = 0;
        for (OrdinaryNpcDTO dto : items) {
            if (dto == null || dto.name() == null || dto.name().isBlank()) continue;
            String nm = dto.name().trim();
            if (!used.add(nm)) continue;
            ActorOrdinaryNpc e = new ActorOrdinaryNpc();
            e.setProjectId(projectId);
            apply(e, dto);
            npcRepository.save(e);
            saved++;
        }
        log.info("[普通NPC] 项目 {} 批量入库 {} 条（提交 {} 条）", projectId, saved, items.size());
        return saved;
    }

    /**
     * 统计（总数/主次分类字段分布/归属分布），供页面头部统计展示。
     * 主/次分类字段来自项目调度运行时（AI 选出）。
     *
     * @param projectId 项目 ID
     * @return {total, primaryField, secondaryField, byPrimary, bySecondary, byAffiliation}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(Long projectId) {
        requireProject(projectId);
        List<ActorOrdinaryNpc> all = npcRepository.findByProjectIdOrderByIdAsc(projectId);
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
    private static Map<String, Long> fieldDistribution(List<ActorOrdinaryNpc> npcs, String field) {
        Map<String, Long> by = new LinkedHashMap<>();
        if (field == null || !CLASSIFY_FIELDS.contains(field)) return by;
        for (ActorOrdinaryNpc n : npcs) {
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
                ph("world_name", worldName(projectId), "world_setting", truncate(world, WORLD_MAX)));
        log.info("[字段字典] 任务开始：项目={}", projectId);
        long start = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", promptTemplateService.systemMessage(userId, projectId,
                                PromptTemplateService.CODE_CROWD_CATEGORY,
                                ph("world_name", worldName(projectId), "world_setting", truncate(world, WORLD_MAX)))),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, DICT_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null, req);
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
     * @param preview   确认后的预览（fields + classification）
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
     * @param entry     条目（field/level1/level2/source）
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
     * @param field     字段名
     * @param l1        一级值
     * @param l2        二级值（可空）
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

    /** 字段名归一（race/affiliation/occupation，其余返回 null） */
    private static String normalizeField(String field) {
        if (field == null) return null;
        String f = field.trim().toLowerCase();
        return CLASSIFY_FIELDS.contains(f) ? f : null;
    }

    /**
     * 解析 AI 拟定的字段字典预览（纯静态可测）。
     * <p>输出结构：{ "classification": {"primary":"race","secondary":"affiliation"},
     * "fields": { "race":[{"level1":"人族","level2":"汉族","source":"..."}], "affiliation":[...], "occupation":[...] } }。
     * 清洗：字段名归一、一级非空、race 二级容错、去重、数量与长度兜底。</p>
     *
     * @param content AI 输出文本
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
     * <p>流程：校验字段字典存在 + 世界观存在 → 按每批 {@link #BATCH_SIZE} 循环调用 AI
     * （依据世界观 + 字段字典 + 地点清单 + 已存在名单），解析并过滤重名 → 逐条回调（SSE 逐条推送 +
     * 后端逐条日志）→ 全部完成后回调 done。单批失败自动重试 {@link #MAX_RETRY} 次后跳过继续。
     * 生成字段（种族/次级种族/归属/职业/所在地）必须从字段字典/地点清单中选取，避免 OOC；
     * 不含关系（关系在 NPC 生成后单独生成）。</p>
     *
     * @param projectId 项目 ID
     * @param count     目标生成数量（1~500）
     * @param progress  进度回调（可为 null）
     */
    public void generateStream(Long projectId, int count, GenerateProgress progress) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        int target = Math.max(1, Math.min(MAX_NPC, count));
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
        // 已存在姓名（普通型 NPC + 特殊 NPC 角色，避免重名）
        Set<String> usedNames = new LinkedHashSet<>();
        npcRepository.findByProjectIdOrderByIdAsc(projectId)
                .forEach(n -> usedNames.add(n.getName() == null ? "" : n.getName().trim()));
        characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)
                .forEach(c -> {
                    if (c.getName() != null && !c.getName().isBlank()) usedNames.add(c.getName().trim());
                });

        log.info("[居民生成] 任务开始：项目={} 目标={} 每批={}", projectId, target, BATCH_SIZE);
        long start = System.currentTimeMillis();
        if (progress != null) progress.onStart(target, BATCH_SIZE);
        int generated = 0;
        int batchNo = 0;
        int failedBatches = 0;
        for (int remaining = target; remaining > 0; ) {
            batchNo++;
            int need = Math.min(BATCH_SIZE, remaining);
            remaining -= need;
            boolean batchOk = false;
            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                try {
                    String existingText = usedNames.isEmpty() ? "（暂无）" : String.join("、", usedNames);
                    Map<String, Object> placeholders = ph(
                            "world_name", worldName(projectId),
                            "world_setting", truncate(world, WORLD_MAX),
                            "field_dict", truncate(dictText, ROSTER_MAX),
                            "locations", truncate(locations, ROSTER_MAX),
                            "existing_names", truncate(existingText, ROSTER_MAX),
                            "count", String.valueOf(need));
                    String prompt = promptTemplateService.render(userId, projectId,
                            PromptTemplateService.CODE_CROWD_NPC_GEN, placeholders);
                    AiChatRequest req = new AiChatRequest(null, List.of(
                            new AiChatRequest.ChatMessage("system", promptTemplateService.systemMessage(userId, projectId,
                                    PromptTemplateService.CODE_CROWD_NPC_GEN, placeholders)),
                            new AiChatRequest.ChatMessage("user", prompt)), 0.5, NPC_MAX_TOKENS, true);
                    AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null, req);
                    List<OrdinaryNpcDraftVO> parsed = parseDrafts(result.content());
                    // 过滤重名（已存在 + 本次已生成），去重后逐条回调
                    List<OrdinaryNpcDraftVO> valid = new ArrayList<>();
                    for (OrdinaryNpcDraftVO d : parsed) {
                        String nm = d.name() == null ? "" : d.name().trim();
                        if (nm.isEmpty() || !usedNames.add(nm)) continue;
                        valid.add(d);
                    }
                    for (OrdinaryNpcDraftVO d : valid) {
                        generated++;
                        log.info("[居民生成] 已生成居民 #{}：{}（{} / {} / {}）", generated,
                                d.name(), d.race(), d.subRace(), d.affiliation());
                        if (progress != null) progress.onNpc(d, generated);
                    }
                    int tokenIn = result.promptTokens() == null ? 0 : result.promptTokens();
                    int tokenOut = result.completionTokens() == null ? 0 : result.completionTokens();
                    log.info("[居民生成] 第 {} 批输出：{}", batchNo, result.content());
                    log.info("[居民生成] 第 {} 批完成：识别 {} 条 有效 {} 条 已生成 {}/{} 耗时={}ms tokens={}/{}",
                            batchNo, parsed.size(), valid.size(), generated, target,
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
         * @param total     目标生成数量
         * @param batchSize 每批大小
         */
        default void onStart(int total, int batchSize) {
        }

        /**
         * 每生成一条居民回调一次。
         *
         * @param draft 已生成并清洗的草稿
         * @param index 当前累计序号（从 1 开始）
         */
        void onNpc(OrdinaryNpcDraftVO draft, int index);

        /**
         * 全部完成。
         *
         * @param total         目标生成数量
         * @param generated     实际生成数量
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
     * @return 调度结果 Map（mode/hour/states/summary）
     */
    @Transactional
    public Map<String, Object> scheduleProgrammatic(Long projectId) {
        return scheduleProgrammaticByGameHour(projectId, LocalDateTime.now().getHour());
    }

    /**
     * 程序化状态机调度（世界模拟用，按游戏小时驱动，替代真实小时）。
     *
     * @param projectId 项目 ID
     * @param hour      推进用的小时（0-23，游戏小时）
     * @return 调度结果 Map（mode/hour/states/summary）
     */
    @Transactional
    public Map<String, Object> scheduleProgrammaticByGameHour(Long projectId, int hour) {
        requireProject(projectId);
        List<ActorOrdinaryNpc> npcs = npcRepository.findByProjectIdOrderByIdAsc(projectId);
        if (npcs.isEmpty()) {
            return Map.of("mode", "programmatic", "hour", hour, "states", Map.of(), "summary", "（暂无普通型 NPC）");
        }
        Random rng = new Random();
        Map<String, Long> stateCounts = new LinkedHashMap<>();
        for (ActorOrdinaryNpc n : npcs) {
            String state = computeState(hour, rng);
            n.setState(state);
            n.setLastAction(describeAction(n, state, hour));
            stateCounts.merge(state, 1L, Long::sum);
        }
        npcRepository.saveAll(npcs);
        String summary = buildSnapshot(npcs, stateCounts, hour);
        ActorCrowdRuntime rt = runtime(projectId);
        rt.setLatestSummary(summary);
        rt.setLastScheduleAt(LocalDateTime.now());
        runtimeRepository.save(rt);
        log.info("[人群调度] 程序化推进：项目={} 居民={} 时段={}点 状态={}", projectId, npcs.size(), hour, stateCounts);
        return Map.of("mode", "programmatic", "hour", hour, "states", stateCounts, "summary", summary);
    }

    /**
     * 两级 AI 集体调度（手动触发，2026-08-19 分类体系重构）。
     * <p>① 项目级 AI：输入含【主分类字段分组概况 + 次分类字段分组概况 + 归属概况 + 世界观 + 当前时刻】，
     * 输出 summary + 按主分类字段的分组指令（如“人族：午后集会游行”）+ 按归属的分组指令（不同归属立场不同）；
     * ② 归属级 AI：为每个归属把「该归属指令 + 该归属成员所属主分类分组的指令」合并注入，
     * 逐人输出状态/行动；单归属失败自动降级程序化，整体失败抛出后由调用方降级。</p>
     *
     * @param projectId 项目 ID
     * @return 调度结果 Map（mode/summary/hour/updated/affiliations/primaryGroups）
     */
    @Transactional
    public Map<String, Object> scheduleWithAi(Long projectId) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        List<ActorOrdinaryNpc> npcs = npcRepository.findByProjectIdOrderByIdAsc(projectId);
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
        String primaryOverview = fieldGroupOverview(npcs, primary);
        String secondaryOverview = fieldGroupOverview(npcs, secondary);
        String affOverview = affiliationOverview(npcs);

        // ① 项目级 AI 调度（一次调用：主分类分组指令 + 归属指令 + 全局 summary）
        log.info("[人群AI调度] 任务开始：项目={} 阶段=项目级 居民={} 主字段={} 次字段={}",
                projectId, npcs.size(), primary, secondary);
        String projectPrompt = promptTemplateService.render(userId, projectId,
                PromptTemplateService.CODE_CROWD_SCHEDULE_PROJECT, ph(
                        "world_name", worldName(projectId),
                        "world_setting", truncate(world, WORLD_MAX),
                        "hour", String.valueOf(hour),
                        "primary_field", fieldLabel(primary),
                        "primary_overview", truncate(primaryOverview, OVERVIEW_MAX),
                        "secondary_field", fieldLabel(secondary),
                        "secondary_overview", truncate(secondaryOverview, OVERVIEW_MAX),
                        "affiliation_overview", truncate(affOverview, OVERVIEW_MAX)));
        ProjectSchedule projectPlan = callProjectSchedule(userId, projectId, projectPrompt, start);

        // 主分类分组指令索引（按分组名）
        Map<String, String> groupDirectives = projectPlan.primaryGroups().stream()
                .collect(Collectors.toMap(ProjectGroup::group, ProjectGroup::directive, (a, b) -> a, LinkedHashMap::new));

        // ② 归属级 AI 调度（每归属一次调用；合并归属指令 + 该归属成员所属主分类分组的指令）
        int updated = 0;
        Map<String, Object> affResults = new LinkedHashMap<>();
        for (ProjectAffiliation af : projectPlan.affiliations()) {
            List<ActorOrdinaryNpc> group = npcs.stream()
                    .filter(n -> af.affiliation().equals(normalizeAffiliation(n)))
                    .toList();
            if (group.isEmpty()) {
                log.warn("[人群AI调度] 归属 {} 无匹配居民，跳过", af.affiliation());
                continue;
            }
            // 该归属成员涉及的主分类分组指令（去重合并）
            Set<String> groupDirectiveTexts = new LinkedHashSet<>();
            for (ActorOrdinaryNpc n : group) {
                String g = fieldValue(n, primary);
                String dir = groupDirectives.get(g);
                if (dir != null && !dir.isBlank()) groupDirectiveTexts.add("- " + g + "：" + dir);
            }
            String groupDirectivesText = groupDirectiveTexts.isEmpty() ? "（无相关人群指令）"
                    : String.join("\n", groupDirectiveTexts);

            String membersText = renderMembers(group);
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
            Map<String, ActorOrdinaryNpc> byName = indexByName(group, ActorOrdinaryNpc::getName);
            try {
                List<NpcAction> actions = callAffiliationSchedule(userId, projectId, af.affiliation(), affPrompt, afStart);
                int applied = 0;
                for (NpcAction a : actions) {
                    ActorOrdinaryNpc n = byName.get(a.name());
                    if (n == null) continue;
                    n.setState(a.state());
                    n.setLastAction(truncate(a.action(), ACTION_MAX));
                    applied++;
                }
                npcRepository.saveAll(group);
                updated += applied;
                affResults.put(af.affiliation(), Map.of("residents", group.size(), "applied", applied));
            } catch (Exception e) {
                log.warn("[人群AI调度] 归属级失败降级程序化：归属={} 原因={}", af.affiliation(), e.getMessage());
                degradeGroup(group, hour);
                affResults.put(af.affiliation(), Map.of("residents", group.size(), "applied", 0, "degraded", true));
            }
        }

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
     *
     * @param projectId 项目 ID
     * @return {projectId, summary, total, hasSnapshot}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> envSummary(Long projectId) {
        requireProject(projectId);
        List<ActorOrdinaryNpc> npcs = npcRepository.findByProjectIdOrderByIdAsc(projectId);
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
     * @param enabled   是否启用
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
     * @return {enabled, primaryField, secondaryField, lastScheduleAt, latestSummary}
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
                AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null, req);
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
                AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null, req);
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

    /** 某归属 AI 调度失败时降级：程序化状态机推进该组 */
    private void degradeGroup(List<ActorOrdinaryNpc> group, int hour) {
        Random rng = new Random();
        for (ActorOrdinaryNpc n : group) {
            String state = computeState(hour, rng);
            n.setState(state);
            n.setLastAction(describeAction(n, state, hour));
        }
        npcRepository.saveAll(group);
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
     * @param rng  随机源（个体差异）
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
     * 生成普通型 NPC 行动描述（结合职业/所在地与状态）。
     *
     * @param npc   普通型 NPC
     * @param state 当前状态
     * @param hour  当前小时
     * @return 行动描述文本（≤255）
     */
    static String describeAction(ActorOrdinaryNpc npc, String state, int hour) {
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
     * @param npcs        项目全部普通型 NPC
     * @param stateCounts 状态人数统计
     * @param hour        当前小时
     * @return 快照文本
     */
    static String buildSnapshot(List<ActorOrdinaryNpc> npcs, Map<String, Long> stateCounts, int hour) {
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
    static String defaultSummary(List<ActorOrdinaryNpc> npcs) {
        if (npcs.isEmpty()) return "（暂无普通型 NPC）";
        Map<String, Long> byAff = new LinkedHashMap<>();
        Map<String, Long> byOcc = new LinkedHashMap<>();
        for (ActorOrdinaryNpc n : npcs) {
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
    static String fieldGroupOverview(List<ActorOrdinaryNpc> npcs, String field) {
        if (field == null || !CLASSIFY_FIELDS.contains(field)) return "（未配置该分类字段）";
        Map<String, List<ActorOrdinaryNpc>> by = npcs.stream()
                .collect(Collectors.groupingBy(n -> fieldValue(n, field), LinkedHashMap::new, Collectors.toList()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<ActorOrdinaryNpc>> e : by.entrySet()) {
            List<ActorOrdinaryNpc> group = e.getValue();
            Map<String, Long> occ = new LinkedHashMap<>();
            Map<String, Long> sub = new LinkedHashMap<>();
            for (ActorOrdinaryNpc n : group) {
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
    static String fieldValue(ActorOrdinaryNpc n, String field) {
        String v = switch (field == null ? "" : field) {
            case FIELD_RACE -> n.getRace();
            case FIELD_AFFILIATION -> n.getAffiliation();
            case FIELD_OCCUPATION -> n.getOccupation();
            default -> null;
        };
        return v == null || v.isBlank() ? (FIELD_OCCUPATION.equals(field) ? "未知职业" : "未分类") : v.trim();
    }

    /** 各归属概况文本（项目级调度 AI 输入）：按归属分组统计人数/职业/状态。 */
    static String affiliationOverview(List<ActorOrdinaryNpc> npcs) {
        Map<String, List<ActorOrdinaryNpc>> byAff = npcs.stream()
                .collect(Collectors.groupingBy(OrdinaryNpcService::normalizeAffiliation, LinkedHashMap::new, Collectors.toList()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<ActorOrdinaryNpc>> e : byAff.entrySet()) {
            List<ActorOrdinaryNpc> group = e.getValue();
            Map<String, Long> occ = new LinkedHashMap<>();
            Map<String, Long> states = new LinkedHashMap<>();
            for (ActorOrdinaryNpc n : group) {
                String o = n.getOccupation() == null || n.getOccupation().isBlank() ? "未知职业" : n.getOccupation();
                occ.merge(o, 1L, Long::sum);
                states.merge(n.getState() == null ? "idle" : n.getState(), 1L, Long::sum);
            }
            sb.append("- ").append(e.getKey()).append("：共 ").append(group.size()).append(" 人，职业构成 ")
                    .append(occ).append("，当前状态 ").append(states).append("\n");
        }
        return sb.toString();
    }

    /** 归属归一（null → 未知归属） */
    private static String normalizeAffiliation(ActorOrdinaryNpc n) {
        return n.getAffiliation() == null || n.getAffiliation().isBlank() ? "未知归属" : n.getAffiliation().trim();
    }

    /** 该归属居民名单文本（归属级调度 AI 输入） */
    private static String renderMembers(List<ActorOrdinaryNpc> group) {
        StringBuilder sb = new StringBuilder();
        for (ActorOrdinaryNpc n : group) {
            sb.append("- ").append(n.getName())
                    .append("（种族：").append(nvl(n.getRace())).append(" / ").append(nvl(n.getSubRace()))
                    .append("，职业：").append(n.getOccupation() == null || n.getOccupation().isBlank() ? "未知" : n.getOccupation())
                    .append("，归属：").append(nvl(n.getAffiliation()))
                    .append("，状态：").append(n.getState() == null ? "idle" : n.getState())
                    .append("）\n");
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
     * 解析 AI 输出的居民草稿数组（纯静态可测）。
     * <p>兼容：顶层数组 / 顶层对象含 residents 数组 / Markdown 代码块 / 首尾说明文字；
     * 清洗：名称非空、字段截断、subRace 兼容 sub_race 命名。</p>
     *
     * @param content AI 输出文本
     * @return 居民草稿列表
     */
    static List<OrdinaryNpcDraftVO> parseDrafts(String content) {
        if (content == null || content.isBlank()) return List.of();
        JsonNode node = parseQuiet(JsonUtil.extractJson(content));
        if (node == null || (node.isObject() && !node.path("residents").isArray())) {
            JsonNode wider = parseQuiet(extractArrayOrObject(content));
            if (wider != null) node = wider;
        }
        if (node == null) return List.of();
        JsonNode arr = node.isArray() ? node : node.path("residents");
        if (arr == null || !arr.isArray()) return List.of();
        List<OrdinaryNpcDraftVO> out = new ArrayList<>();
        for (JsonNode it : arr) {
            if (it == null || !it.isObject()) continue;
            String name = text(it, "name").trim();
            if (name.isEmpty()) continue;
            out.add(new OrdinaryNpcDraftVO(
                    truncate(name, NAME_MAX),
                    truncate(text(it, "gender"), GENDER_MAX),
                    truncate(text(it, "race"), RACE_MAX),
                    truncate(text(it, "subRace"), SUB_RACE_MAX),
                    intOrNull(it, "age"),
                    truncate(text(it, "affiliation"), AFFILIATION_MAX),
                    truncate(text(it, "location"), LOCATION_MAX),
                    truncate(text(it, "occupation"), OCCUPATION_MAX),
                    truncate(text(it, "detail"), DETAIL_MAX)));
        }
        return out;
    }

    /**
     * 解析项目级调度结果（纯静态可测）。
     * <p>输出结构：{ summary, primaryGroups: [{group,directive}], affiliations: [{affiliation,directive}] }。
     * 非法抛出中文异常。</p>
     *
     * @param content AI 输出文本
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

    // ==================== 内部工具 ====================

    /** 校验 DTO：名称必填 + 非空 */
    private void validate(OrdinaryNpcDTO dto) {
        if (dto == null || dto.name() == null || dto.name().isBlank()) {
            throw new BizException(400, "名称不能为空");
        }
    }

    /** DTO → 实体字段（截断兜底） */
    private void apply(ActorOrdinaryNpc e, OrdinaryNpcDTO dto) {
        e.setName(truncate(dto.name(), NAME_MAX));
        e.setGender(truncate(dto.gender(), GENDER_MAX));
        e.setRace(truncate(dto.race(), RACE_MAX));
        e.setSubRace(truncate(dto.subRace(), SUB_RACE_MAX));
        e.setAge(dto.age() == null ? null : Math.max(0, Math.min(10000, dto.age())));
        e.setAffiliation(truncate(dto.affiliation(), AFFILIATION_MAX));
        e.setLocation(truncate(dto.location(), LOCATION_MAX));
        e.setOccupation(truncate(dto.occupation(), OCCUPATION_MAX));
        e.setDetail(truncate(dto.detail(), DETAIL_MAX));
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

    /** 按 id + 项目归属定位实体（不存在或越权抛 404） */
    private ActorOrdinaryNpc requireOwned(Long id) {
        ActorOrdinaryNpc n = npcRepository.findById(id)
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
