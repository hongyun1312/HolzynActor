package com.holzyn.actor.domain.crowd.controller;

import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.crowd.dto.OrdinaryNpcDTO;
import com.holzyn.actor.domain.crowd.dto.OrdinaryNpcGenerateDTO;
import com.holzyn.actor.domain.crowd.service.OrdinaryNpcService;
import com.holzyn.actor.domain.crowd.vo.FieldDictPreviewVO;
import com.holzyn.actor.domain.crowd.vo.FieldDictVO;
import com.holzyn.actor.domain.crowd.vo.OrdinaryNpcDraftVO;
import com.holzyn.actor.domain.crowd.vo.OrdinaryNpcVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 普通型 NPC 控制器（2026-08-19 分类体系重构后的接口全集）。
 * <p>职责：提供普通型 NPC 的单表 CRUD / 多字段筛选排序分页 / 统计、
 * 标准字段数据（字段字典：AI 依据世界观拟定种族[含次级种族]/归属/职业 + 主次分类字段，预览确认 +
 * 手动增删）、AI 分批生成（SSE 逐条，从字段字典/地点选取）、预览确认批量入库、
 * 两级 AI 调度（项目级按主/次分类字段分组 + 归属下发指令 → 归属级合并执行）/ 程序化调度 /
 * 环境摘要 / 定时调度开关。归属以当前登录用户为准（服务层校验）。</p>
 * <p>所属模块：controller/crowd（普通型人群子域）</p>
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CrowdController {

    private final OrdinaryNpcService ordinaryNpcService;
    private final CurrentUserProvider currentUserProvider;

    // ==================== 普通型 NPC CRUD / 筛选 ====================

    /**
     * 分页查询普通型 NPC（多字段筛选 + 关键词 + 年龄区间 + 排序）。
     *
     * @param projectId   项目 ID
     * @param gender      性别（可空）
     * @param race        种族（可空）
     * @param subRace     次级种族（可空）
     * @param affiliation 归属（可空）
     * @param occupation  职业（可空）
     * @param location    当前所在地（可空）
     * @param keyword     关键词（可空）
     * @param ageMin      年龄下限（可空）
     * @param ageMax      年龄上限（可空）
     * @param sortBy      排序字段（id/name/age/race/subRace/affiliation/occupation/location）
     * @param sortDir     asc/desc
     * @param page        页码（默认 1）
     * @param size        每页条数（默认 20）
     * @return 分页结果
     */
    @GetMapping("/projects/{projectId}/ordinary-npcs")
    public R<PageResult<OrdinaryNpcVO>> page(@PathVariable("projectId") Long projectId,
                                             @RequestParam(name = "gender", required = false) String gender,
                                             @RequestParam(name = "race", required = false) String race,
                                             @RequestParam(name = "subRace", required = false) String subRace,
                                             @RequestParam(name = "affiliation", required = false) String affiliation,
                                             @RequestParam(name = "occupation", required = false) String occupation,
                                             @RequestParam(name = "location", required = false) String location,
                                             @RequestParam(name = "keyword", required = false) String keyword,
                                             @RequestParam(name = "ageMin", required = false) Integer ageMin,
                                             @RequestParam(name = "ageMax", required = false) Integer ageMax,
                                             @RequestParam(name = "sortBy", required = false) String sortBy,
                                             @RequestParam(name = "sortDir", required = false) String sortDir,
                                             @RequestParam(name = "page", defaultValue = "1") int page,
                                             @RequestParam(name = "size", defaultValue = "20") int size) {
        OrdinaryNpcService.NpcQuery q = new OrdinaryNpcService.NpcQuery(
                gender, race, subRace, affiliation, occupation, location, keyword, ageMin, ageMax, sortBy, sortDir);
        return R.ok(ordinaryNpcService.page(projectId, q, page, size));
    }

    /**
     * 手动新增单个普通型 NPC。
     *
     * @param projectId 项目 ID
     * @param dto       档案入参（名称必填）
     * @return 新增后的 VO
     */
    @PostMapping("/projects/{projectId}/ordinary-npcs")
    public R<OrdinaryNpcVO> create(@PathVariable("projectId") Long projectId,
                                   @Valid @RequestBody OrdinaryNpcDTO dto) {
        return R.ok(ordinaryNpcService.create(projectId, dto));
    }

    /**
     * 修改普通型 NPC。
     *
     * @param id  主键
     * @param dto 档案入参
     * @return 更新后的 VO
     */
    @PutMapping("/ordinary-npcs/{id}")
    public R<OrdinaryNpcVO> update(@PathVariable("id") Long id, @Valid @RequestBody OrdinaryNpcDTO dto) {
        return R.ok(ordinaryNpcService.update(id, dto));
    }

    /**
     * 删除单个普通型 NPC。
     *
     * @param id 主键
     * @return 删除确认
     */
    @DeleteMapping("/ordinary-npcs/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") Long id) {
        ordinaryNpcService.delete(id);
        return R.ok(Map.of("id", id, "deleted", true));
    }

    /**
     * 批量删除普通型 NPC。
     *
     * @param projectId 项目 ID
     * @param body      主键列表 {ids: [...]}
     * @return {deleted 实际删除数}
     */
    @PostMapping("/projects/{projectId}/ordinary-npcs/batch-delete")
    public R<Map<String, Object>> batchDelete(@PathVariable("projectId") Long projectId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        List<?> raw = body == null ? List.of() : (List<?>) body.getOrDefault("ids", List.of());
        List<Long> ids = raw.stream().map(String::valueOf).map(Long::valueOf).toList();
        return R.ok(Map.of("deleted", ordinaryNpcService.batchDelete(projectId, ids)));
    }

    /**
     * 统计（总数/主次分类字段分布/归属分布）。
     *
     * @param projectId 项目 ID
     * @return 统计 Map
     */
    @GetMapping("/projects/{projectId}/ordinary-npcs/stats")
    public R<Map<String, Object>> stats(@PathVariable("projectId") Long projectId) {
        return R.ok(ordinaryNpcService.stats(projectId));
    }

    // ==================== 标准字段数据（字段字典） ====================

    /**
     * 查询项目字段字典（按字段分组：race/affiliation/occupation，含出处）。
     *
     * @param projectId 项目 ID
     * @return { race: [...], affiliation: [...], occupation: [...] }
     */
    @GetMapping("/projects/{projectId}/ordinary-npcs/field-dict")
    public R<Map<String, List<FieldDictVO>>> fieldDict(@PathVariable("projectId") Long projectId) {
        return R.ok(ordinaryNpcService.fieldDict(projectId));
    }

    /**
     * AI 依据世界观一次性拟定全部字段字典 + 选出主/次分类字段（不落库，供预览确认）。
     *
     * @param projectId 项目 ID
     * @return 拟定预览（fields + classification）
     */
    @PostMapping("/projects/{projectId}/ordinary-npcs/field-dict/generate")
    public R<FieldDictPreviewVO> generateFieldDict(@PathVariable("projectId") Long projectId) {
        return R.ok(ordinaryNpcService.generateFieldDict(projectId));
    }

    /**
     * 保存字段字典（预览确认后整体替换）+ 保存主/次分类字段。
     *
     * @param projectId 项目 ID
     * @param preview   确认后的预览
     * @return 保存后的字段字典分组
     */
    @PostMapping("/projects/{projectId}/ordinary-npcs/field-dict")
    public R<Map<String, List<FieldDictVO>>> saveFieldDict(@PathVariable("projectId") Long projectId,
                                                           @RequestBody(required = false) FieldDictPreviewVO preview) {
        return R.ok(ordinaryNpcService.saveFieldDict(projectId, preview));
    }

    /**
     * 手动新增一条字段字典。
     *
     * @param projectId 项目 ID
     * @param entry     条目（field/level1/level2/source）
     * @return 新增后的条目
     */
    @PostMapping("/projects/{projectId}/ordinary-npcs/field-dict/entry")
    public R<FieldDictVO> addFieldDictEntry(@PathVariable("projectId") Long projectId,
                                            @RequestBody(required = false) FieldDictVO entry) {
        return R.ok(ordinaryNpcService.addFieldDictEntry(projectId, entry));
    }

    /**
     * 手动删除一条字段字典（race 二级删除需传 level2；其余字段 level2 传空或 undefined）。
     *
     * @param projectId 项目 ID
     * @param field     字段名（race/affiliation/occupation，URL 编码）
     * @param l1        一级值（URL 编码）
     * @param l2        二级值（可空，URL 编码）
     * @return 删除确认
     */
    @DeleteMapping("/projects/{projectId}/ordinary-npcs/field-dict/{field}/{l1}/{l2}")
    public R<Map<String, Object>> deleteFieldDictEntry(@PathVariable("projectId") Long projectId,
                                                       @PathVariable("field") String field,
                                                       @PathVariable("l1") String l1,
                                                       @PathVariable(value = "l2", required = false) String l2) {
        ordinaryNpcService.deleteFieldDictEntry(projectId, field, l1,
                "undefined".equals(l2) || l2 == null ? "" : l2);
        return R.ok(Map.of("deleted", true));
    }

    // ==================== AI 生成 / 入库 ====================

    /**
     * AI 分批生成普通型 NPC（SSE）：逐条推送 npc 事件（前端逐条显示进度），
     * 完成后推送 done（{total, generated, failedBatches}），失败推送 error。
     * 生成结果仅作预览，确认后走 batch-save 入库。
     *
     * @param projectId 项目 ID
     * @param dto       生成入参（count 1~500）
     * @return SSE 事件流（text/event-stream）
     */
    @PostMapping(value = "/projects/{projectId}/ordinary-npcs/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@PathVariable("projectId") Long projectId,
                                     @RequestBody(required = false) OrdinaryNpcGenerateDTO dto) {
        SseEmitter emitter = new SseEmitter(0L);
        int count = dto == null || dto.count() == null ? 0 : dto.count();
        OrdinaryNpcService.GenerateProgress progress = new OrdinaryNpcService.GenerateProgress() {
            @Override
            public void onStart(int total, int batchSize) {
                send("start", Map.of("count", total, "batchSize", batchSize));
            }

            @Override
            public void onNpc(OrdinaryNpcDraftVO draft, int index) {
                send("npc", draftData(draft));
            }

            @Override
            public void onDone(int total, int generated, int failedBatches) {
                send("done", Map.of("total", total, "generated", generated, "failedBatches", failedBatches));
                emitter.complete();
            }

            /** 发送 SSE 事件（连接已断开时静默） */
            private void send(String name, Object data) {
                try {
                    emitter.send(SseEmitter.event().name(name).data(data));
                } catch (Exception ignored) {
                    // 前端已断开时忽略推送失败
                }
            }
        };
        try {
            ordinaryNpcService.generateStream(projectId, count, progress);
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", e.getMessage() == null ? "居民生成失败" : e.getMessage())));
            } catch (Exception ignored) {
                // 连接已断开
            }
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 批量入库（AI 生成预览确认后保存选中的草稿）。
     *
     * @param projectId 项目 ID
     * @param items     确认后的居民档案列表
     * @return {saved 实际入库数, total 提交条数}
     */
    @PostMapping("/projects/{projectId}/ordinary-npcs/batch-save")
    public R<Map<String, Object>> batchSave(@PathVariable("projectId") Long projectId,
                                            @RequestBody(required = false) List<OrdinaryNpcDTO> items) {
        List<OrdinaryNpcDTO> list = items == null ? List.of() : items;
        return R.ok(Map.of("saved", ordinaryNpcService.batchSave(projectId, list), "total", list.size()));
    }

    // ==================== 调度 / 环境 ====================

    /**
     * 程序化状态机调度（手动触发，零 AI 成本；定时任务同路径）。
     *
     * @param projectId 项目 ID
     * @return 调度结果 Map（mode/hour/states/summary）
     */
    @PostMapping("/projects/{projectId}/ordinary-npcs/schedule")
    public R<Map<String, Object>> schedule(@PathVariable("projectId") Long projectId) {
        return R.ok(ordinaryNpcService.scheduleProgrammatic(projectId));
    }

    /**
     * 两级 AI 集体调度（手动触发）：项目级 AI 按主/次分类字段分组 + 归属下发指令 →
     * 归属级合并归属指令 + 相关主分类分组指令逐人输出状态/行动；单归属失败降级程序化。
     *
     * @param projectId 项目 ID
     * @return 调度结果 Map（mode/summary/hour/updated/affiliations/primaryGroups）
     */
    @PostMapping("/projects/{projectId}/ordinary-npcs/schedule-ai")
    public R<Map<String, Object>> scheduleAi(@PathVariable("projectId") Long projectId) {
        return R.ok(ordinaryNpcService.scheduleWithAi(projectId));
    }

    /**
     * 环境摘要（对话/行动注入背景板）。
     *
     * @param projectId 项目 ID
     * @return {projectId, summary, total, hasSnapshot}
     */
    @GetMapping("/projects/{projectId}/ordinary-npcs/env-summary")
    public R<Map<String, Object>> envSummary(@PathVariable("projectId") Long projectId) {
        return R.ok(ordinaryNpcService.envSummary(projectId));
    }

    /**
     * 项目级调度运行时信息（定时开关/主次分类字段/上次调度/环境快照）。
     *
     * @param projectId 项目 ID
     * @return {enabled, primaryField, secondaryField, lastScheduleAt, latestSummary}
     */
    @GetMapping("/projects/{projectId}/ordinary-npcs/runtime")
    public R<Map<String, Object>> runtime(@PathVariable("projectId") Long projectId) {
        return R.ok(ordinaryNpcService.runtimeInfo(projectId));
    }

    /**
     * 项目级定时调度开关（定时任务每 5 分钟程序化推进启用项目）。
     *
     * @param projectId 项目 ID
     * @param body      开关 {enabled: true/false}
     * @return 更新后的运行时信息
     */
    @PutMapping("/projects/{projectId}/ordinary-npcs/schedule-enabled")
    public R<Map<String, Object>> setEnabled(@PathVariable("projectId") Long projectId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        ordinaryNpcService.setEnabled(projectId, enabled);
        return R.ok(ordinaryNpcService.runtimeInfo(projectId));
    }

    /** 草稿 → SSE 事件数据 Map（null 安全） */
    private static Map<String, Object> draftData(OrdinaryNpcDraftVO d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", nvl(d.name()));
        m.put("gender", nvl(d.gender()));
        m.put("race", nvl(d.race()));
        m.put("subRace", nvl(d.subRace()));
        m.put("age", d.age() == null ? "" : d.age());
        m.put("affiliation", nvl(d.affiliation()));
        m.put("location", nvl(d.location()));
        m.put("occupation", nvl(d.occupation()));
        m.put("detail", nvl(d.detail()));
        return m;
    }

    /** null 安全取字符串（SSE 事件字段归一） */
    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
