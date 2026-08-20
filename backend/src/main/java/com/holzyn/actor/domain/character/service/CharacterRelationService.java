package com.holzyn.actor.domain.character.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatResult;
import com.holzyn.actor.ai.AiProviderRouter;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.character.dto.RelationBatchDTO;
import com.holzyn.actor.domain.character.dto.RelationGenerateDTO;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.character.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.character.repository.ActorCharacterRelationRepository;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.character.vo.RelationDraftVO;
import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;
import com.holzyn.actor.domain.crowd.repository.ActorOrdinaryNpcRepository;
import com.holzyn.actor.domain.settings.service.PromptTemplateService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import com.holzyn.actor.domain.world.entity.ActorWorldSetting;
import com.holzyn.actor.domain.world.repository.ActorWorldSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 角色关系 AI 生成与入库服务（角色页「关系拓扑」Tab + 全局拓扑页共用）。
 * <p>职责：① 生成预览（不落库）——按 scope 读取【项目世界观 + 项目现有角色/普通人群成员名单
 * （+单角色时的详细信息）】，AI 识别关系 JSON 数组后解析/清洗/去重返回预览；
 * ② 批量入库——mode=rebuild 先清空相关范围（单角色=该角色相关关系；全项目=整个项目关系表）
 * 再写入；写入时端点若命中已存在 NPC 则存 id+名，否则名称兜底（id=0 + from_name/to_name，
 * 后续在「补充新增角色」时按名称全表扫描回填 id）。</p>
 * <p>AI 用量记录 scene=relation_gen（用户/项目/角色维度）。</p>
 * <p>所属模块：service/character（角色/角色卡子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterRelationService {

    private final CharacterService characterService;
    private final ActorCharacterRelationRepository relationRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorOrdinaryNpcRepository ordinaryNpcRepository;
    private final ActorWorldSettingRepository worldSettingRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AiProviderRouter aiProviderRouter;
    private final UsageLogService usageLogService;
    private final PromptTemplateService promptTemplateService;

    /** AI 输出 token 上限（关系 JSON 数组） */
    private static final int RELATION_MAX_TOKENS = 4000;
    /** AI 调用重试次数 */
    private static final int MAX_RETRY = 2;
    /** 世界观输入截断 */
    private static final int WORLD_MAX = 6000;
    /** 名单输入截断 */
    private static final int ROSTER_MAX = 4000;
    /** 单角色详细信息截断 */
    private static final int TARGET_DETAIL_MAX = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * AI 生成关系预览（不落库；2026-08-19 支持普通型 NPC 范围）。
     *
     * @param projectId 项目 ID
     * @param dto       生成入参（scope/mode/characterId/crowdId）
     * @return 识别并清洗后的关系草稿列表
     */
    public List<RelationDraftVO> generate(Long projectId, RelationGenerateDTO dto) {
        Long userId = currentUserProvider.currentUserId();
        characterService.requireProjectOwned(projectId);
        String scope = dto == null || dto.scope() == null || dto.scope().isBlank() ? "character" : dto.scope();
        ActorCharacter target = null;
        ActorOrdinaryNpc crowdTarget = null;
        if ("character".equals(scope)) {
            if (dto.characterId() == null) {
                throw new BizException(400, "请先选择一个角色，再生成该角色的关系");
            }
            target = characterService.requireOwned(dto.characterId());
            if (!projectId.equals(target.getProjectId())) {
                throw new BizException(400, "所选角色不属于当前项目");
            }
        } else if ("crowd".equals(scope)) {
            if (dto.crowdId() == null) {
                throw new BizException(400, "请先选择一个普通型 NPC，再生成其关系");
            }
            crowdTarget = ordinaryNpcRepository.findByIdAndProjectId(dto.crowdId(), projectId)
                    .orElseThrow(() -> new BizException(400, "所选普通型 NPC 不属于当前项目"));
        }
        String prompt = buildGeneratePrompt(projectId, scope, target, crowdTarget);
        // 控制台日志（对齐 ChatService [对话] / WorldLocationService [地点提取] 风格）：任务开始
        String targetName = target != null ? target.getName()
                : (crowdTarget != null ? crowdTarget.getName() : "全部角色");
        log.info("[关系生成] 任务开始：项目={} 范围={} 角色={}", projectId, scope, targetName);
        long start = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                AiChatRequest req = new AiChatRequest(null, List.of(
                        new AiChatRequest.ChatMessage("system", promptTemplateService.systemMessage(userId, projectId,
                                PromptTemplateService.CODE_RELATION_GEN, null)),
                        new AiChatRequest.ChatMessage("user", prompt)), 0.3, RELATION_MAX_TOKENS, true);
                AiChatResult result = aiProviderRouter.chatCompletion(userId, projectId, null, req);
                List<RelationDraftVO> drafts = parseDrafts(result.content());
                // token 兜底为 0（供应商可能不返回 usage），对齐 [对话] tokens=x/y 的 int 展示
                int tokenIn = result.promptTokens() == null ? 0 : result.promptTokens();
                int tokenOut = result.completionTokens() == null ? 0 : result.completionTokens();
                // 控制台日志：AI 输出内容 + 任务结束（耗时 + token 消耗），对齐 [对话] 格式
                log.info("[关系生成] 范围={} 角色={}：{}", scope, targetName, result.content());
                log.info("[关系生成] 任务结束：项目={} 范围={} 角色={} 耗时={}ms tokens={}/{}",
                        projectId, scope, targetName, System.currentTimeMillis() - start, tokenIn, tokenOut);
                // 用量角色维度：特殊 NPC 记 id；普通型 NPC 不记角色 id（用量明细按特殊角色聚合，避免 id 冲突）
                usageLogService.record(userId, projectId, target == null ? null : target.getId(),
                        result.providerId(), result.model(), "relation_gen",
                        result.promptTokens(), result.completionTokens(),
                        result.cacheHitTokens(), result.cacheMissTokens(),
                        (int) (System.currentTimeMillis() - start));
                log.info("[关系生成] 项目 {} scope={} 第 {} 次成功：识别 {} 条", projectId, scope, attempt, drafts.size());
                return drafts;
            } catch (Exception e) {
                lastError = e;
                log.warn("[关系生成] 项目 {} 第 {} 次失败: {}", projectId, attempt, e.getMessage());
            }
        }
        log.warn("[关系生成] 任务失败：项目={} 范围={} 角色={} 耗时={}ms：{}",
                projectId, scope, targetName, System.currentTimeMillis() - start,
                lastError == null ? "未知错误" : lastError.getMessage());
        throw new BizException(400, "关系生成失败：" + friendlyError(lastError) + "，请稍后重试");
    }

    /**
     * 批量入库（预览确认后的写入）。
     *
     * @param projectId 项目 ID
     * @param dto       入库入参（mode/characterId/items）
     * @return {added 实际写入条数, total 提交条数}
     */
    @Transactional
    public Map<String, Object> batchSave(Long projectId, RelationBatchDTO dto) {
        characterService.requireProjectOwned(projectId);
        String mode = dto == null || dto.mode() == null || dto.mode().isBlank() ? "supplement" : dto.mode();
        List<RelationDraftVO> items = dto == null || dto.items() == null ? List.of() : dto.items();

        // ===== 重建：清空相关范围（特殊 NPC 按 id/名称；普通型 NPC 按名称；全项目整表） =====
        if ("rebuild".equals(mode)) {
            List<ActorCharacterRelation> all = relationRepository.findByProjectId(projectId);
            if (dto != null && dto.crowdId() != null) {
                // 单普通型 NPC 重建：清空与该普通 NPC（按名称匹配，其关系以名称兜底存储）相关的关系
                ActorOrdinaryNpc crowd = ordinaryNpcRepository.findByIdAndProjectId(dto.crowdId(), projectId)
                        .orElseThrow(() -> new BizException(400, "所选普通型 NPC 不属于当前项目"));
                List<ActorCharacterRelation> toDelete = all.stream()
                        .filter(r -> crowd.getName().equals(r.getFromName()) || crowd.getName().equals(r.getToName()))
                        .toList();
                if (!toDelete.isEmpty()) relationRepository.deleteAll(toDelete);
            } else if (dto != null && dto.characterId() != null) {
                // 单特殊角色重建：清空与该角色相关（id 或名称匹配）的关系
                ActorCharacter target = characterService.requireOwned(dto.characterId());
                List<ActorCharacterRelation> toDelete = all.stream()
                        .filter(r -> target.getId().equals(r.getFromCharacterId())
                                || target.getId().equals(r.getToCharacterId())
                                || target.getName().equals(r.getFromName())
                                || target.getName().equals(r.getToName()))
                        .toList();
                if (!toDelete.isEmpty()) relationRepository.deleteAll(toDelete);
            } else {
                // 全项目重建：清空整个项目关系表
                if (!all.isEmpty()) relationRepository.deleteAll(all);
            }
        }

        // ===== 写入：端点命中已存在 NPC → 存 id+名；否则名称兜底 id=0 =====
        Map<String, Long> npcIdByName = characterRepository
                .findByProjectIdAndDeletedOrderByIdAsc(projectId, 0).stream()
                .collect(Collectors.toMap(c -> c.getName() == null ? "" : c.getName().trim(),
                        ActorCharacter::getId, (a, b) -> a));
        int added = 0;
        for (RelationDraftVO item : items) {
            String from = item == null || item.from() == null ? "" : item.from().trim();
            String to = item == null || item.to() == null ? "" : item.to().trim();
            String type = item == null || item.relationType() == null || item.relationType().isBlank()
                    ? "未知关系" : item.relationType().trim();
            if (from.isEmpty() || to.isEmpty() || from.equals(to)) continue;
            ActorCharacterRelation r = new ActorCharacterRelation();
            r.setProjectId(projectId);
            Long fid = npcIdByName.get(from);
            r.setFromCharacterId(fid == null ? 0L : fid);
            r.setFromName(from);
            Long tid = npcIdByName.get(to);
            r.setToCharacterId(tid == null ? 0L : tid);
            r.setToName(to);
            r.setRelationType(type.length() > 50 ? type.substring(0, 50) : type);
            String desc = item.description() == null ? "" : item.description().trim();
            r.setDescription(desc.length() > 255 ? desc.substring(0, 255) : (desc.isEmpty() ? null : desc));
            relationRepository.save(r);
            added++;
        }
        log.info("[关系入库] 项目 {} mode={} 写入 {} 条（提交 {} 条）", projectId, mode, added, items.size());
        Map<String, Object> result = new HashMap<>();
        result.put("added", added);
        result.put("total", items.size());
        return result;
    }

    /**
     * 解析 AI 输出的关系 JSON（纯静态，可独立单测）。
     * <p>兼容：顶层数组 / 顶层对象含 relations 数组 / Markdown 代码块包裹 / 首尾说明文字；
     * 清洗规则：from/to 非空且不等、关系类型缺省为「未知关系」、按 from|to|type 去重、字段长度截断。</p>
     *
     * @param content AI 输出文本
     * @return 清洗后的关系草稿列表
     */
    static List<RelationDraftVO> parseDrafts(String content) {
        if (content == null || content.isBlank()) return List.of();
        JsonNode node = parseQuiet(JsonUtil.extractJson(content));
        // JsonUtil 对「说明文字 + JSON 数组」只截出第一个对象：若顶层对象不含 relations 数组，
        // 再尝试宽松的数组/对象提取（剥代码块 + 取首个 [..]）
        if (node == null || (node.isObject() && !node.path("relations").isArray())) {
            JsonNode wider = parseQuiet(extractArrayOrObject(content));
            if (wider != null) node = wider;
        }
        if (node == null) return List.of();
        JsonNode arr = node.isArray() ? node : node.path("relations");
        if (!arr.isArray() || arr.isEmpty()) return List.of();
        List<RelationDraftVO> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : arr) {
            if (item == null || !item.isObject()) continue;
            String from = text(item, "from").trim();
            String to = text(item, "to").trim();
            if (from.isEmpty() || to.isEmpty() || from.equals(to)) continue;
            String type = text(item, "relationType");
            if (type.isEmpty()) type = text(item, "relation_type");
            if (type.isEmpty()) type = "未知关系";
            String desc = text(item, "description");
            String key = from + "|" + to + "|" + type;
            if (!seen.add(key)) continue;
            out.add(new RelationDraftVO(from, to,
                    type.length() > 50 ? type.substring(0, 50) : type,
                    desc.length() > 255 ? desc.substring(0, 255) : (desc.isEmpty() ? null : desc)));
        }
        return out;
    }

    /** 宽松提取 JSON 数组/对象：剥代码块 + 取首个 [..] 或 {..}（兼容「说明文字 + 代码块 + JSON」输出） */
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

    /** 节点字段安全取文本 */
    private static String text(JsonNode item, String field) {
        JsonNode v = item.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    /**
     * 组装 AI 生成 Prompt（改用 Prompt 模板表，code=relation_gen 三级回退渲染）。
     * <p>占位符：world_setting 世界观 / roster 现有名单 / target_section 单角色信息段（可空）/
     * task_requirement 范围任务要求。业务侧仍负责读取世界观与名单，模板文本可在「设置→Prompt」编辑。
     * 单角色范围支持特殊 NPC（target）与普通型 NPC（crowdTarget）两种目标。</p>
     *
     * @param projectId   项目 ID
     * @param scope       character / crowd / project
     * @param target      单角色范围的特殊 NPC 目标（可空）
     * @param crowdTarget 单角色范围的普通型 NPC 目标（可空）
     * @return 完整 Prompt 文本
     */
    private String buildGeneratePrompt(Long projectId, String scope, ActorCharacter target, ActorOrdinaryNpc crowdTarget) {
        Long userId = currentUserProvider.currentUserId();
        Map<String, Object> placeholders = new HashMap<>();
        // 世界观
        String world = worldSettingRepository.findTopByProjectIdOrderByVersionDesc(projectId)
                .map(this::renderWorld)
                .orElse("（项目暂无世界观设定）");
        placeholders.put("world_setting", truncate(world, WORLD_MAX));
        // 现有名单（NPC + 普通人群成员）
        placeholders.put("roster", buildRoster(projectId));
        // 单角色范围：附加目标角色信息段 + 任务要求；全项目范围只给任务要求
        if (target != null) {
            placeholders.put("target_section", buildTargetSection(target));
            placeholders.put("task_requirement", "【任务要求】请识别「" + target.getName()
                    + "」与名单内外其他角色之间的所有关系（双向：该角色既可以是关系发起方 from，也可以是目标方 to），不遗漏任何明确的关系。");
        } else if (crowdTarget != null) {
            placeholders.put("target_section", buildCrowdTargetSection(crowdTarget));
            placeholders.put("task_requirement", "【任务要求】请识别「" + crowdTarget.getName()
                    + "」与名单内外其他角色之间的所有关系（双向：该角色既可以是关系发起方 from，也可以是目标方 to；可关联特殊 NPC、普通型 NPC 或世界观中提到但未创建的角色），不遗漏任何明确的关系。");
        } else {
            placeholders.put("target_section", "");
            placeholders.put("task_requirement", "【任务要求】请识别该世界观下所有角色之间的完整关系网络（双向、尽量全面），包括名单外但世界观中明确提到的角色。");
        }
        return promptTemplateService.render(userId, projectId, PromptTemplateService.CODE_RELATION_GEN, placeholders);
    }

    /**
     * 现有角色名单（NPC + 普通型 NPC，普通人群重构后普通 NPC 以具体人名入名单）。
     *
     * @param projectId 项目 ID
     * @return 名单文本（空则返回「（暂无）」）
     */
    private String buildRoster(Long projectId) {
        StringBuilder roster = new StringBuilder();
        List<ActorCharacter> npcs = characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0);
        for (ActorCharacter c : npcs) {
            roster.append("- ").append(c.getName())
                    .append(c.getTitle() == null || c.getTitle().isBlank() ? "" : "（" + c.getTitle() + "）")
                    .append("common".equals(c.getType()) ? " [普通NPC]" : " [特殊NPC]").append("\n");
        }
        for (ActorOrdinaryNpc n : ordinaryNpcRepository.findByProjectIdOrderByIdAsc(projectId)) {
            roster.append("- ").append(n.getName())
                    .append("（普通型：")
                    .append(n.getOccupation() == null || n.getOccupation().isBlank() ? "职业未知" : n.getOccupation())
                    .append("）\n");
        }
        return roster.length() == 0 ? "（暂无）\n" : truncate(roster.toString(), ROSTER_MAX);
    }

    /**
     * 单特殊 NPC 信息段（姓名/头衔/类型/重要度/详细信息）。
     *
     * @param target 目标角色
     * @return 信息段文本
     */
    private String buildTargetSection(ActorCharacter target) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前角色】\n姓名：").append(target.getName())
                .append("，头衔：").append(target.getTitle() == null || target.getTitle().isBlank() ? "无" : target.getTitle())
                .append("，类型：").append("common".equals(target.getType()) ? "普通NPC" : "特殊NPC")
                .append("，重要度：").append(target.getImportance() == null ? "未标注" : target.getImportance())
                .append("\n详细信息：").append(target.getDetail() == null || target.getDetail().isBlank()
                        ? "（未提供）" : truncate(target.getDetail(), TARGET_DETAIL_MAX))
                .append("\n\n");
        return sb.toString();
    }

    /**
     * 单普通型 NPC 信息段（姓名/种族/次级种族/归属/职业/所在地/详情；2026-08-19 新增）。
     *
     * @param target 目标普通型 NPC
     * @return 信息段文本
     */
    private String buildCrowdTargetSection(ActorOrdinaryNpc target) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前角色】\n姓名：").append(target.getName())
                .append("，种族：").append(target.getRace() == null || target.getRace().isBlank() ? "未知" : target.getRace())
                .append(target.getSubRace() == null || target.getSubRace().isBlank() ? "" : " / " + target.getSubRace())
                .append("，归属：").append(target.getAffiliation() == null || target.getAffiliation().isBlank() ? "未知" : target.getAffiliation())
                .append("，职业：").append(target.getOccupation() == null || target.getOccupation().isBlank() ? "未知" : target.getOccupation())
                .append("，所在地：").append(target.getLocation() == null || target.getLocation().isBlank() ? "未知" : target.getLocation())
                .append("\n详细信息：").append(target.getDetail() == null || target.getDetail().isBlank()
                        ? "（未提供）" : truncate(target.getDetail(), TARGET_DETAIL_MAX))
                .append("\n\n");
        return sb.toString();
    }

    /** 世界观实体 → 紧凑文本 */
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
        return t.length() == 0 ? "（世界观字段为空）" : t.toString();
    }

    /** 世界观字段追加（空跳过） */
    private void appendIf(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(label).append("：").append(value).append("\n");
    }

    /** 字符串截断（null 安全） */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 供应商错误文案收敛（AI 调用失败 → 友好提示） */
    private String friendlyError(Exception e) {
        if (e instanceof AiCallException ae && ae.getMessage() != null && !ae.getMessage().isBlank()) {
            return ae.getMessage();
        }
        return e == null || e.getMessage() == null || e.getMessage().isBlank() ? "未知错误" : e.getMessage();
    }
}
