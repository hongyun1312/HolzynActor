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

package com.holzyn.actor.domain.agent.runtime;

import com.holzyn.actor.domain.agent.runtime.entity.ActorForesightRecord;
import com.holzyn.actor.domain.agent.runtime.repository.ActorForesightRecordRepository;
import com.holzyn.actor.domain.world.service.MacroStateService;
import com.holzyn.actor.domain.world.service.WorldClockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决策与协调 轻量前瞻推演服务（，domain/agent/runtime）。
 * <p>职责：<ul>
 * <li><b>触发判定（确定性）</b>：{@link #shouldTrigger}——刺激事件重要度 ≥ 阈值 ∪ 角色
 * 紧急度 ≥ 阈值 ∪ 刺激文本命中场景关键词（资源获取/人际关系变动/位置变更，
 * <li><b>前瞻段组装</b>：{@link #buildSection}——【前瞻推演】段（先输出 2~3 个候选行动及
 * 短期后果 candidates[]，再在 action 选最终行动）+ 宏观快照摘要注入（：注入
 * 粮价/库存/民怨/议题/势力，宏观 ~ 已交付）；</li>
 * <li><b>会话记录</b>：{@link #open}/{@link #recordIfOpen}/{@link #close}——ThreadLocal
 * 前瞻会话（同一次 STIMULUS 即时决策线程内开→记→关），决策产出后由 DecisionService
 * 调 {@link #recordIfOpen} 落 {@code actor_foresight_record}（AI 未输出候选 → success=false）；</li>
 * <li><b>调试预览</b>：{@link #preview}——不开启会话的触发+段落预览（调试前端/冒烟用）。</li>
 * </ul></p>
 * <p>设计约束：同调用插段 ——前瞻段直接拼入决策情境，零新增 AI 调用；
 * AI 失败/未输出候选 → 原决策照常执行（失败回退无前瞻原文），仅记录 success=false 可观测。</p>
 */
@Slf4j
@Service
public class ForesightService {

    /** 前瞻会话 ThreadLocal（同一次即时决策线程内 open→recordIfOpen→close） */
    private static final ThreadLocal<ForesightSession> SESSION = new ThreadLocal<>();

    /** 刺激文本摘录上限（记录用） */
    private static final int EXCERPT_MAX = 500;

    /** 前瞻段提示文本前缀（DecisionService 据此识别「本轮是否开启前瞻」——当前由会话承载，保留用于日志） */
    public static final String SECTION_HEADER = "—— 前瞻推演（本轮要求） ——";

    private final ActorForesightRecordRepository repository;
    private final ObjectMapper objectMapper;

    /** 宏观状态服务（前瞻段宏观快照摘要注入；null=未装配跳过宏观段，前瞻主体不受影响） */
    @Autowired(required = false)
    private MacroStateService macroStateService;

    /** 世界时钟（当前游戏秒回退；null=纯数据单测/未装配） */
    @Autowired(required = false)
    private WorldClockService worldClockService;

    /**
     * 潜在世界预测注入（P1 ③-2）：前瞻宏观段升级——在【当下时局】后追加
     * 【未来一天世界走向】（WorldOutlookService.outlookSegment 程序化趋势+AI 预测段；
     * null=未装配跳过世界走向段，前瞻主体不受影响）
     */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldOutlookService worldOutlookService;

    /** 实时决策配置（读取 foresight.* 段；构造器注入，测试可传 mock/直接构造） */
    private final RealtimeDecisionProperties realtimeProperties;

    /** 兼容构造（测试/简单装配：不传配置时 props() 回退默认值）。 */
    public ForesightService(ActorForesightRecordRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, null);
    }

    /** 全量构造（Spring 用 @Autowired 注入仓库/序列化器/配置；测试可传 mock/直接构造）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ForesightService(ActorForesightRecordRepository repository, ObjectMapper objectMapper,
                            RealtimeDecisionProperties realtimeProperties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.realtimeProperties = realtimeProperties;
    }

    // ==================== 触发判定（确定性规则，可单测） ====================

    /**
     * 前瞻触发判定（确定性规则）：事件重要度 ≥ 阈值 ∪ urgency ≥ 阈值 ∪ 刺激文本命中
     * 场景关键词（资源/关系/位置）。纯静态逻辑可单测。
     *
     * @param type 刺激类型（STIMULUS 即时决策专属；可空=按文本关键词判定）
     *
     * @param importance 事件重要度（非事件刺激=null）
     *
     * @param urgent 角色是否紧急（urgency ≥ 阈值，由调用方按状态读取）
     *
     * @param stimulusText 刺激文本（关键词命中基准；可空）
     *
     * @param props 前瞻配置（不可空）
     *
     * @return true=触发前瞻推演
     */
    public static boolean shouldTrigger(StimulusType type, Integer importance, boolean urgent,
                                        String stimulusText, RealtimeDecisionProperties.Foresight props) {
        if (props == null || !props.isEnabled()) {
            return false;
        }
        // ① 事件重要度 ≥ 阈值
        if (importance != null && importance >= props.getImportanceThreshold()) {
            return true;
        }
        // ② 紧急度 ≥ 阈值（状态告急时推演后果）
        if (urgent) {
            return true;
        }
        // ③ 刺激文本命中场景关键词（资源获取/人际关系变动/位置变更）
        if (stimulusText != null && !stimulusText.isBlank()) {
            if (hits(stimulusText, props.getResourceKeywords())
                    || hits(stimulusText, props.getRelationKeywords())
                    || hits(stimulusText, props.getLocationKeywords())) {
                return true;
            }
        }
        return false;
    }

    /** 文本是否命中任一关键词（大小写不敏感包含匹配）。 */
    private static boolean hits(String text, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String k : keywords) {
            if (k != null && !k.isBlank() && text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** 触发原因文本（判定命中时的可读描述；未命中返回空串）。 */
    public static String triggerReason(StimulusType type, Integer importance, boolean urgent,
                                       String stimulusText, RealtimeDecisionProperties.Foresight props) {
        if (props == null || !props.isEnabled()) {
            return "";
        }
        if (importance != null && importance >= props.getImportanceThreshold()) {
            return "事件重要度=" + importance + "（≥" + props.getImportanceThreshold() + "）";
        }
        if (urgent) {
            return "urgency≥" + props.getUrgencyThreshold() + "（状态紧急）";
        }
        if (stimulusText != null && !stimulusText.isBlank()) {
            for (String k : props.getResourceKeywords()) {
                if (k != null && !k.isBlank() && stimulusText.contains(k)) {
                    return "场景关键词命中（资源）：「" + k + "」";
                }
            }
            for (String k : props.getRelationKeywords()) {
                if (k != null && !k.isBlank() && stimulusText.contains(k)) {
                    return "场景关键词命中（关系）：「" + k + "」";
                }
            }
            for (String k : props.getLocationKeywords()) {
                if (k != null && !k.isBlank() && stimulusText.contains(k)) {
                    return "场景关键词命中（位置）：「" + k + "」";
                }
            }
        }
        return "";
    }

    // ==================== 前瞻段组装（含宏观输入注入） ====================

    /**
     * 组装【前瞻推演】段（拼入决策情境文本，同调用插段——零新增 AI 调用）：
     * 要求 AI 先设想 ≤max-candidates 个候选行动及短期后果（candidates[]），再在 action
     * 字段输出最终选择；宏观快照摘要（粮价/库存/民怨/议题/势力）注入供后果推演参照；
     * 潜在世界预测注入（P1 ③-2）追加【未来一天世界走向】（程序化趋势+AI 预测段）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 触发角色引用（special:{id}；宏观段/日志用）
     *
     * @param gameSecond 当前游戏秒（可空）
     *
     * @return 前瞻段文本（恒非空；宏观注入失败自动省略宏观段）
     */
    public String buildSection(Long projectId, String npcRef, Long gameSecond) {
        RealtimeDecisionProperties.Foresight props = props();
        StringBuilder sb = new StringBuilder();
        sb.append(SECTION_HEADER).append("\n");
        sb.append("请先设想 ").append(Math.max(1, props.getMaxCandidates()))
                .append(" 个候选行动及其短期后果（输出到 candidates 数组，每项 {action, consequence}），")
                .append("再从中选择最符合你性格与处境的行动输出到 action 字段。");
        // 宏观输入注入
        String macro = macroSummary(projectId);
        if (props.isMacroInjectEnabled() && macro != null && !macro.isBlank()) {
            sb.append("\n【当下时局】").append(macro);
        }
        // 潜在世界预测注入（P1 ③-2）：【未来一天世界走向】段（趋势+预测；未装配/无数据跳过）
        String outlook = worldOutlook(projectId);
        if (outlook != null && !outlook.isBlank()) {
            sb.append("\n").append(outlook);
        }
        String text = sb.toString();
        int max = Math.max(200, props.getSectionMaxLength());
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    /** 宏观快照摘要（失败/未初始化返回 null——省略宏观段零阻断）。 */
    private String macroSummary(Long projectId) {
        if (macroStateService == null || projectId == null) {
            return null;
        }
        try {
            var state = macroStateService.stateOf(projectId);
            if (state == null) {
                return null;
            }
            String summary = macroStateService.buildSummary(state, "daily");
            return summary == null || summary.isBlank() ? null : abbreviate(summary, 260);
        } catch (Exception e) {
            log.debug("[前瞻推演] 宏观快照摘要注入失败（省略宏观段）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    /** 潜在世界预测【未来一天世界走向】段（P1 ③-2；未装配/失败返回 null——省略零阻断）。 */
    private String worldOutlook(Long projectId) {
        if (worldOutlookService == null || projectId == null) {
            return null;
        }
        try {
            return worldOutlookService.outlookSegment(projectId);
        } catch (Exception e) {
            log.debug("[前瞻推演] 世界走向段注入失败（省略）: project={} : {}", projectId, e.getMessage());
            return null;
        }
    }

    // ==================== 前瞻会话（open → recordIfOpen → close） ====================

    /**
     * 打开前瞻会话（STIMULUS 即时决策触发判定命中后、触发决策前调用；同一虚拟线程内有效）。
     * 决策产出后由 DecisionService 调 {@link #recordIfOpen} 落库；决策循环结束由调用方
     * {@link #close} 清理（防线程复用泄漏）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 触发角色引用
     *
     * @param npcName 触发角色名（可空）
     *
     * @param gameSecond 当前游戏秒（可空）
     *
     * @param triggerReason 触发原因（可空）
     *
     * @param stimulusText 刺激文本（记录摘录用；可空）
     *
     * @param section 注入的前瞻段全文（记录用；可空）
     */
    public void open(Long projectId, String npcRef, String npcName, Long gameSecond,
                     String triggerReason, String stimulusText, String section) {
        ForesightSession s = new ForesightSession(projectId, npcRef, npcName, gameSecond,
                triggerReason, stimulusText, section);
        SESSION.set(s);
    }

    /**
     * 记录决策产出并关闭会话（DecisionService 决策解析成功后调用；无会话=非前瞻决策，零成本）。
     * AI 输出了 candidates → success=true；未输出 → success=false（失败可观测）。
     *
     * @param projectId 项目 ID（与会话匹配才记录）
     *
     * @param npcRef 角色引用（与会话匹配才记录）
     *
     * @param decision 决策 Map（validateDecision 产出；含 foresightCandidates 时取之）
     */
    public void recordIfOpen(Long projectId, String npcRef, Map<String, Object> decision) {
        ForesightSession s = SESSION.get();
        if (s == null || !s.matches(projectId, npcRef)) {
            return; // 非前瞻决策路径（引擎 tick/手动/无会话）零成本跳过
        }
        try {
            List<Map<String, String>> candidates = extractCandidates(decision);
            String finalAction = decision == null ? "" : String.valueOf(decision.getOrDefault("action", ""));
            ActorForesightRecord rec = new ActorForesightRecord();
            rec.setProjectId(projectId);
            rec.setNpcRef(npcRef);
            rec.setNpcName(s.npcName());
            rec.setGameSecond(s.gameSecond());
            rec.setTriggerReason(abbreviate(s.triggerReason(), 200));
            rec.setSituationExcerpt(abbreviate(s.stimulusText(), EXCERPT_MAX));
            rec.setMacroSummary(abbreviate(macroOf(s.section()), 500));
            rec.setCandidatesJson(toJson(candidates));
            rec.setFinalAction(abbreviate(finalAction, 400));
            rec.setSuccess(!candidates.isEmpty());
            repository.save(rec);
            log.info("[前瞻推演] 记录前瞻决策: project={} ref={} 候选={} 成功={} 最终={}",
                    projectId, npcRef, candidates.size(), rec.getSuccess(), abbreviate(finalAction, 40));
        } catch (Exception e) {
            log.warn("[前瞻推演] 前瞻记录失败（不影响决策主流程）: ref={} : {}", npcRef, e.getMessage());
        } finally {
            SESSION.remove();
        }
    }

    /** 关闭前瞻会话（决策循环 finally 兜底清理；recordIfOpen 已消费时幂等）。 */
    public void close() {
        SESSION.remove();
    }

    /** 从决策 Map 提取候选（validateDecision 已把 candidates 数组落 foresightCandidates 键）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> extractCandidates(Map<String, Object> decision) {
        List<Map<String, String>> out = new ArrayList<>();
        if (decision == null) {
            return out;
        }
        Object raw = decision.get("foresightCandidates");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object action = m.get("action");
                    Object consequence = m.get("consequence");
                    Map<String, String> one = new LinkedHashMap<>();
                    one.put("action", action == null ? "" : String.valueOf(action));
                    one.put("consequence", consequence == null ? "" : String.valueOf(consequence));
                    out.add(one);
                }
            }
        }
        return out;
    }

    /** 从前瞻段文本提取宏观摘要（【当下时局】之后的段落；未注入返回空）。 */
    private static String macroOf(String section) {
        if (section == null || section.isBlank()) {
            return "";
        }
        int idx = section.indexOf("【当下时局】");
        return idx < 0 ? "" : section.substring(idx + "【当下时局】".length());
    }

    // ==================== 调试预览（不开会话） ====================

    /**
     * 前瞻触发+段落预览（调试前端/冒烟用；不开启会话不落库）。
     *
     * @param projectId 项目 ID
     *
     * @param npcRef 角色引用（可空）
     *
     * @param stimulusText 刺激文本（可空）
     *
     * @param importance 事件重要度（可空）
     *
     * @param urgent 是否紧急（可空=false）
     *
     * @param type 刺激类型 code（可空）
     *
     * @return 预览结果（triggered/reason/section）
     */
    public Map<String, Object> preview(Long projectId, String npcRef, String stimulusText,
                                       Integer importance, Boolean urgent, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean triggered = shouldTrigger(parseType(type), importance,
                Boolean.TRUE.equals(urgent), stimulusText, props());
        out.put("triggered", triggered);
        out.put("reason", triggered
                ? triggerReason(parseType(type), importance, Boolean.TRUE.equals(urgent), stimulusText, props())
                : "");
        out.put("section", triggered ? buildSection(projectId, npcRef, null) : "");
        out.put("importanceThreshold", props().getImportanceThreshold());
        out.put("urgencyThreshold", props().getUrgencyThreshold());
        out.put("maxCandidates", props().getMaxCandidates());
        return out;
    }

    /** 刺激类型 code → 枚举（未知/空白回退 null）。 */
    private static StimulusType parseType(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (StimulusType t : StimulusType.values()) {
            if (t.code().equals(code.trim())) {
                return t;
            }
        }
        return null;
    }

    // ==================== 查询（调试/冒烟） ====================

    /** 按项目+角色查前瞻记录（最新在前；角色空=全项目）。 */
    public List<ActorForesightRecord> list(Long projectId, String npcRef, Integer limit) {
        List<ActorForesightRecord> all = npcRef == null || npcRef.isBlank()
                ? repository.findByProjectIdOrderByIdDesc(projectId)
                : repository.findByProjectIdAndNpcRefOrderByIdDesc(projectId, npcRef);
        if (limit != null && limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    // ==================== 私有辅助 ====================

    /** 当前配置（读取注入的 realtime-decision.foresight.*；null=测试回退默认值）。 */
    private RealtimeDecisionProperties.Foresight props() {
        return realtimeProperties == null ? new RealtimeDecisionProperties.Foresight()
                : realtimeProperties.getForesight();
    }

    /** 列表 → JSON（失败回退 "[]"）。 */
    private String toJson(List<Map<String, String>> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 文本摘要（超长截断；null 回退空串）。 */
    static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }

    /** 前瞻会话（ThreadLocal 载体）。 */
    record ForesightSession(Long projectId, String npcRef, String npcName, Long gameSecond,
                            String triggerReason, String stimulusText, String section) {
        boolean matches(Long pid, String ref) {
            return projectId != null && projectId.equals(pid) && npcRef != null && npcRef.equals(ref);
        }
    }
}
