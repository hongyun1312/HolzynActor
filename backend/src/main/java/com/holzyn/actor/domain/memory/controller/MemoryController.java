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

package com.holzyn.actor.domain.memory.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.memory.service.CoreMemoryService;
import com.holzyn.actor.domain.memory.service.MemoryArchiveService;
import com.holzyn.actor.domain.memory.service.MemoryConsolidationService;
import com.holzyn.actor.domain.memory.service.MemoryHealthService;
import com.holzyn.actor.domain.memory.vo.ArchiveVO;
import com.holzyn.actor.domain.memory.vo.CoreBlockVO;
import com.holzyn.actor.domain.memory.vo.CoreMemoryVO;
import com.holzyn.actor.domain.memory.vo.MemoryVO;
import com.holzyn.actor.common.PageResult;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.memory.vo.MemoryWorkingVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆控制器。
 * <p>职责：提供记忆列表（项目级+角色级）、删除（软删）与手动触发抽取接口；
 * 归属以当前登录用户为准（服务层校验）；统一返回 R&lt;T&gt;。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MemoryController {

    /** 记忆组件（接线：旧 MemoryService 已删，直连 AgentMemoryService） */
    private final AgentMemoryService memoryService;

    /** L3 核心记忆组件 */
    private final CoreMemoryService coreMemoryService;

    /** 归档 */
    private final MemoryArchiveService archiveService;

    /** 记忆巩固编排 */
    private final MemoryConsolidationService consolidationService;

    /** 记忆治理健康度 */
    private final MemoryHealthService healthService;

    /** L1 模糊引擎（模糊状态列表/详情/手动触发；字段注入保持构造器不变） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.L1FuzzificationService fuzzService;

    /** 跨层蒸馏服务（记忆谱系反向链） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.MemoryDistillService distillService;

    /** 预算化注入路由（注入路由预览 + trace 回放） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.holzyn.actor.domain.memory.service.MemoryInjectionRouter injectionRouter;

    /**
     * 记忆列表（归属校验）。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（可空：空=项目全部记忆；非空=该特殊角色记忆）
     *
     * @param characterRef 统一角色引用（B5：crowd:{id} 普通 NPC 记忆；与 characterId 二选一，优先）
     *
     * @param page 页码（从 1 起，默认 1）
     *
     * @param size 每页条数（默认 20）
     *
     * @return 分页记忆 VO
     */
    @GetMapping("/projects/{projectId}/memories")
    public R<PageResult<MemoryVO>> list(@PathVariable("projectId") Long projectId,
                                        @RequestParam(value = "characterId", required = false) Long characterId,
                                        @RequestParam(value = "characterRef", required = false) String characterRef,
                                        @RequestParam(value = "page", defaultValue = "1") int page,
                                        @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(memoryService.list(projectId, characterId, characterRef, page, size));
    }

    /**
     * 工作记忆（L1）列表。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（可空：空=项目级工作项；非空=该特殊角色工作项）
     *
     * @param characterRef 统一角色引用（crowd:{id} 普通 NPC；与 characterId 二选一，优先）
     *
     * @param status 状态过滤（active/consolidated/expired，可空=全部）
     *
     * @param page 页码（从 1 起，默认 1）
     *
     * @param size 每页条数（默认 20）
     *
     * @return 分页工作记忆 VO
     */
    @GetMapping("/projects/{projectId}/working-memories")
    public R<PageResult<MemoryWorkingVO>> listWorking(@PathVariable("projectId") Long projectId,
                                                      @RequestParam(value = "characterId", required = false) Long characterId,
                                                      @RequestParam(value = "characterRef", required = false) String characterRef,
                                                      @RequestParam(value = "status", required = false) String status,
                                                      @RequestParam(value = "page", defaultValue = "1") int page,
                                                      @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(memoryService.listWorking(projectId, characterId, characterRef, status, page, size));
    }

    /**
     * 核心记忆（L3）详情：静态基线只读渲染 + 动态核心块列表（重要度 desc）。
     *
     * @param projectId 项目 ID
     *
     * @param characterId 角色 ID（可空：空=项目级核心块；非空=该特殊角色核心块）
     *
     * @param characterRef 统一角色引用（crowd:{id} 普通 NPC；与 characterId 二选一，优先）
     *
     * @return 核心记忆详情 VO（baselineText + blocks）
     */
    @GetMapping("/projects/{projectId}/core-memories")
    public R<CoreMemoryVO> coreDetail(@PathVariable("projectId") Long projectId,
                                      @RequestParam(value = "characterId", required = false) Long characterId,
                                      @RequestParam(value = "characterRef", required = false) String characterRef) {
        String ref = resolveRef(projectId, characterId, characterRef);
        return R.ok(coreMemoryService.detail(projectId, ref));
    }

    /**
     * 新建核心块：校验块键合法/内容有界/每角色 ≤8 块。
     *
     * @param projectId 项目 ID
     *
     * @return 新建的块 VO
     */
    @PostMapping("/projects/{projectId}/core-memories")
    public R<CoreBlockVO> createCoreBlock(@PathVariable("projectId") Long projectId,
                                          @RequestBody Map<String, Object> body) {
        String ref = requireString(body, "characterRef");
        String blockKey = requireString(body, "blockKey");
        String blockName = asString(body.get("blockName"));
        String content = requireString(body, "content");
        Integer importance = asInt(body.get("importance"));
        Integer editableByAgent = asInt(body.get("editableByAgent"));
        return R.ok(coreMemoryService.createBlock(projectId, ref, blockKey, blockName, content,
                importance, editableByAgent));
    }

    /**
     * 核心块。
     *
     * @param projectId 项目 ID
     *
     * @param blockId 核心块主键
     *
     * @return 新版本的块 VO
     */
    @PutMapping("/projects/{projectId}/core-memories/{blockId}")
    public R<CoreBlockVO> updateCoreBlock(@PathVariable("projectId") Long projectId,
                                          @PathVariable("blockId") Long blockId,
                                          @RequestBody Map<String, Object> body) {
        String blockName = asString(body.get("blockName"));
        String content = requireString(body, "content");
        Integer importance = asInt(body.get("importance"));
        Integer editableByAgent = asInt(body.get("editableByAgent"));
        return R.ok(coreMemoryService.updateBlock(projectId, blockId, blockName, content,
                importance, editableByAgent));
    }

    /**
     * 删除核心块。
     *
     * @param projectId 项目 ID
     *
     * @param blockId 核心块主键
     *
     * @return 删除确认
     */
    @DeleteMapping("/projects/{projectId}/core-memories/{blockId}")
    public R<Map<String, Object>> deleteCoreBlock(@PathVariable("projectId") Long projectId,
                                                  @PathVariable("blockId") Long blockId) {
        coreMemoryService.deleteBlock(projectId, blockId);
        return R.ok(Map.of("id", blockId, "deleted", true));
    }

    /**
     * 解析归属角色引用（对齐 listWorking 口径）：characterRef 优先；characterId → special:{id}；否则 null=项目级。
     */
    private String resolveRef(Long projectId, Long characterId, String characterRef) {
        if (characterRef != null && !characterRef.isBlank()) {
            return characterRef.trim();
        }
        if (characterId != null) {
            return com.holzyn.actor.common.NpcRefs.of(characterId);
        }
        return null;
    }

    // ==================== 归档（设计 §8：触发/版本链/还原/检索/导出/超龄扫描） ====================

    /**
     * 归档列表（按项目 + 可选 角色/类型/原因/关键词 过滤分页）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色（可空=全部；special:{id}/crowd:{id}）
     *
     * @param type 归档类型（semantic/episodic/reflection/plan/core/working，可空）
     *
     * @param reason 归档原因（age/superseded/downgraded/evicted/manual，可空）
     *
     * @param keyword 内容关键词（可空）
     *
     * @param page 页码（1 起）
     *
     * @param size 每页条数
     *
     * @return 分页归档 VO
     */
    @GetMapping("/projects/{projectId}/archives")
    public R<PageResult<ArchiveVO>> listArchives(@PathVariable("projectId") Long projectId,
                                                 @RequestParam(value = "characterRef", required = false) String characterRef,
                                                 @RequestParam(value = "type", required = false) String type,
                                                 @RequestParam(value = "reason", required = false) String reason,
                                                 @RequestParam(value = "keyword", required = false) String keyword,
                                                 @RequestParam(value = "page", defaultValue = "1") int page,
                                                 @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(archiveService.list(projectId, characterRef, type, reason, keyword, page, size));
    }

    /**
     * 批量手动归档：按来源表分发——memory=长期记忆 / core=核心块。逐条写归档 + 软删。
     *
     * @param projectId 项目 ID
     *
     * @param body 入参：{sourceTable: "actor_memory"|"actor_memory_core", ids: [...]}（九模块规范化重构 core 表名 actor_core_memory → actor_memory_core）
     *
     * @return 实际归档条数
     */
    @PostMapping("/projects/{projectId}/archives/manual")
    public R<Map<String, Object>> manualArchive(@PathVariable("projectId") Long projectId,
                                                @RequestBody Map<String, Object> body) {
        String sourceTable = requireString(body, "sourceTable");
        List<Long> ids = parseIdList(body.get("ids"));
        int archived = archiveService.batchArchive(projectId, sourceTable, ids);
        return R.ok(Map.of("sourceTable", sourceTable, "archived", archived));
    }

    /**
     * 还原归档条目：按 source_table 新建回来源表 + 标记 restored + 去重跳过。
     *
     * @param archiveId 归档主键
     */
    @PostMapping("/archives/{archiveId}/restore")
    public R<Map<String, Object>> restoreArchive(@PathVariable("archiveId") Long archiveId) {
        return R.ok(archiveService.restore(archiveId));
    }

    /**
     * 手动触发超龄归档扫描。
     *
     * @param projectId 项目 ID
     *
     * @return 归档条数
     */
    @PostMapping("/projects/{projectId}/archives/scan-age")
    public R<Map<String, Object>> scanAge(@PathVariable("projectId") Long projectId) {
        int archived = archiveService.scanAgeArchiving(projectId);
        return R.ok(Map.of("projectId", projectId, "archived", archived));
    }

    /**
     * 归档导出下载。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色（可空=全项目）
     *
     * @param format json / markdown（默认 json）
     *
     * @return 文本文件流（Content-Disposition 下载）
     */
    @GetMapping("/projects/{projectId}/archives/export")
    public ResponseEntity<byte[]> exportArchives(@PathVariable("projectId") Long projectId,
                                                 @RequestParam(value = "characterRef", required = false) String characterRef,
                                                 @RequestParam(value = "format", defaultValue = "json") String format) {
        String content = archiveService.export(projectId, characterRef, format);
        boolean md = "markdown".equalsIgnoreCase(format);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String filename = "memory-archive-" + projectId + (md ? ".md" : ".json");
        try {
            filename = URLEncoder.encode(filename, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 编码失败使用默认名
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(md ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON)
                .body(bytes);
    }

    /**
     * 归档条目软删。
     *
     * @param archiveId 归档主键
     *
     * @return 删除确认
     */
    @DeleteMapping("/archives/{archiveId}")
    public R<Map<String, Object>> deleteArchive(@PathVariable("archiveId") Long archiveId) {
        archiveService.softDeleteArchive(archiveId);
        return R.ok(Map.of("id", archiveId, "deleted", true));
    }

    /**
     * L2 记忆更新。
     * <p>用于修正/演进同一条事实，读取命中旧版本后代时以最新版本为准（缓解「自信地错」）。</p>
     *
     * @param memoryId 记忆主键
     *
     * @param body 入参：{content 必填, importance?}
     *
     * @return 新版本记忆 VO
     */
    @PutMapping("/memories/{memoryId}")
    public R<MemoryVO> updateMemory(@PathVariable("memoryId") Long memoryId,
                                    @RequestBody Map<String, Object> body) {
        Long projectId = asLong(body.get("projectId"));
        String content = requireString(body, "content");
        Integer importance = asInt(body.get("importance"));
        if (projectId == null) {
            throw new com.holzyn.actor.common.BizException(400, "projectId 不能为空");
        }
        var next = archiveService.updateL2Memory(projectId, memoryId, content, importance);
        return R.ok(MemoryVO.of(next));
    }

    // ==================== 检索注入与巩固收尾（设计 §9/§10/§11.3） ====================

    /**
     * 分场景注入预览：按场景（dialog/decision/perception/reflection）组装注入组合，
     * 返回结构化结果（各段文本 + 命中记忆得分 + token 预算），供调试前端注入预览页 + 主前端预览弹窗。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 统一角色引用（special:{id}/crowd:{id}）
     *
     * @param scenario 场景（dialog/decision/perception/reflection，默认 dialog）
     *
     * @param query 检索 query（可空）
     *
     * @return 注入结果 Map
     */
    @GetMapping("/projects/{projectId}/memory-injection/preview")
    public R<Map<String, Object>> injectionPreview(@PathVariable("projectId") Long projectId,
                                                   @RequestParam(value = "characterRef", required = false) String characterRef,
                                                   @RequestParam(value = "scenario", defaultValue = "dialog") String scenario,
                                                   @RequestParam(value = "query", required = false) String query) {
        String ref = resolveRef(projectId, null, characterRef);
        if (ref == null) {
            throw new com.holzyn.actor.common.BizException(400, "characterRef 不能为空（需指定角色）");
        }
        var result = memoryService.buildInjection(projectId, ref, scenario, query, List.of());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("characterRef", ref);
        out.put("scenario", result.scenario());
        out.put("totalTokens", result.totalTokens());
        List<Map<String, Object>> segments = new ArrayList<>();
        for (var seg : result.segments().values()) {
            Map<String, Object> sm = new java.util.LinkedHashMap<>();
            sm.put("key", seg.key());
            sm.put("title", seg.title());
            sm.put("text", seg.text());
            sm.put("tokens", seg.tokens());
            sm.put("hits", seg.hits());
            segments.add(sm);
        }
        out.put("segments", segments);
        return R.ok(out);
    }

    /**
     * 记忆治理健康度：归档量（总数/按原因/按来源/已还原）+ 版本链数（L2/L3）+ 分层分布（L1/L2/L3）。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 归属角色（可空=全项目）
     *
     * @return 健康度 Map
     */
    @GetMapping("/projects/{projectId}/memory-health")
    public R<Map<String, Object>> memoryHealth(@PathVariable("projectId") Long projectId,
                                               @RequestParam(value = "characterRef", required = false) String characterRef) {
        return R.ok(healthService.health(projectId, characterRef));
    }

    /**
     * 手动触发统一巩固编排（调试/API 兜底）：会话巩固 + 统一维护（L1 维护 + 归档维护），
     * scene=memory_manage trace 贯穿。
     *
     * @param body 入参：{sessionKey? 会话 key 巩固；maintenance: true? 统一维护}
     */
    @PostMapping("/memory/consolidate")
    public R<Map<String, Object>> consolidate(@RequestBody(required = false) Map<String, Object> body) {
        String sessionKey = body == null ? null : asString(body.get("sessionKey"));
        boolean maintenance = body != null && Boolean.TRUE.equals(body.get("maintenance"));
        int consolidated = 0;
        int steps = 0;
        if (sessionKey != null && !sessionKey.isBlank()) {
            consolidated = consolidationService.consolidateConversation(sessionKey);
        }
        if (maintenance) {
            steps = consolidationService.maintenance();
        }
        return R.ok(Map.of("consolidated", consolidated, "maintenanceSteps", steps));
    }

    private static List<Long> parseIdList(Object v) {
        if (!(v instanceof List<?> raw)) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (Object o : raw) {
            if (o == null) {
                continue;
            }
            try {
                out.add(Long.parseLong(o.toString().trim()));
            } catch (Exception ignored) {
                // 非法 id 跳过
            }
        }
        return out;
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }    /** 必填字符串（缺失/空白抛 400）。 */
    private static String requireString(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new com.holzyn.actor.common.BizException(400, key + " 不能为空");
        }
        return v.toString().trim();
    }

    /** 可选字符串（null → null）。 */
    private static String asString(Object v) {
        return v == null ? null : v.toString().trim();
    }

    /** 可选整数（null/非法 → null）。 */
    private static Integer asInt(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * 删除单条记忆（软删，归属校验）。
     *
     * @param id 记忆主键
     *
     * @return 删除确认
     */
    @DeleteMapping("/memories/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") Long id) {
        memoryService.delete(id);
        return R.ok(Map.of("id", id, "deleted", true));
    }

    /**
     * 手动触发抽取（调试/补抽）：对指定角色参与的最近会话抽取一次新事实。
     *
     * @param id 角色主键
     *
     * @return 本次新增事实条数
     */
    @PostMapping("/characters/{id}/memories/extract")
    public R<Map<String, Object>> extract(@PathVariable("id") Long id) {
        int added = memoryService.extractForCharacter(id);
        return R.ok(Map.of("characterId", id, "added", added));
    }

    // ==================== 记忆内核扩展 接口 ====================

    /**
     * L1 模糊状态列表。
     *
     * @param projectId 项目 ID
     *
     * @param characterRef 角色引用（可空=项目全部）
     *
     * @param status 状态（active/distilled/archived，可空=全部）
     */
    @GetMapping("/projects/{projectId}/memories/fuzz")
    public R<List<Map<String, Object>>> listFuzz(@PathVariable("projectId") Long projectId,
                                                 @RequestParam(value = "characterRef", required = false) String characterRef,
                                                 @RequestParam(value = "status", required = false) String status) {
        if (fuzzService == null) {
            return R.ok(List.of());
        }
        return R.ok(fuzzService.listFuzz(projectId, characterRef, status).stream()
                .map(this::fuzzVo).toList());
    }

    /**
     * 单条模糊详情。
     */
    @GetMapping("/projects/{projectId}/memories/fuzz/{workingId}")
    public R<Map<String, Object>> fuzzDetail(@PathVariable("projectId") Long projectId,
                                             @PathVariable("workingId") Long workingId) {
        if (fuzzService == null) {
            return R.ok(Map.of());
        }
        var vo = fuzzService.fuzzDetail(projectId, workingId);
        return vo == null ? R.ok(Map.of()) : R.ok(fuzzVo(vo));
    }

    /**
     * 手动触发单条模糊。
     */
    @PostMapping("/projects/{projectId}/memories/fuzz/trigger")
    public R<Map<String, Object>> triggerFuzz(@PathVariable("projectId") Long projectId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        if (fuzzService == null) {
            return R.ok(Map.of("triggered", false, "reason", "模糊引擎未装配"));
        }
        Long workingId = asLong(body == null ? null : body.get("memoryWorkingId"));
        if (workingId == null) {
            throw new com.holzyn.actor.common.BizException(400, "memoryWorkingId 不能为空");
        }
        boolean ok = fuzzService.triggerFuzz(projectId, workingId, null);
        return R.ok(Map.of("memoryWorkingId", workingId, "triggered", ok));
    }

    /**
     * 记忆谱系。
     */
    @GetMapping("/projects/{projectId}/memories/lineage")
    public R<List<Map<String, Object>>> lineage(@PathVariable("projectId") Long projectId,
                                                @RequestParam("targetMemoryId") Long targetMemoryId) {
        if (distillService == null) {
            return R.ok(List.of());
        }
        return R.ok(distillService.lineageOf(projectId, targetMemoryId).stream()
                .map(hop -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("sourceMemoryId", hop.sourceMemoryId());
                    m.put("sourceTier", hop.sourceTier());
                    m.put("targetMemoryId", hop.targetMemoryId());
                    m.put("targetTier", hop.targetTier());
                    m.put("consolidateType", hop.consolidateType());
                    m.put("gameSecond", hop.gameSecond());
                    m.put("reductionRatio", hop.reductionRatio());
                    m.put("sourceTokens", hop.sourceTokens());
                    m.put("targetTokens", hop.targetTokens());
                    m.put("sourceContent", hop.sourceContent());
                    return m;
                }).toList());
    }

    /**
     * 注入路由预览。
     */
    @GetMapping("/projects/{projectId}/memories/inject-route")
    public R<Map<String, Object>> injectRoutePreview(@PathVariable("projectId") Long projectId,
                                                     @RequestParam(value = "characterRef", required = false) String characterRef,
                                                     @RequestParam(value = "scenario", defaultValue = "dialog") String scenario,
                                                     @RequestParam(value = "query", required = false) String query,
                                                     @RequestParam(value = "urgency", required = false) Integer urgency) {
        if (injectionRouter == null) {
            return R.ok(Map.of("enabled", false));
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", true);
        m.put("preview", injectionRouter.preview(projectId, characterRef, scenario, query, urgency));
        return R.ok(m);
    }

    /**
     * 注入路由 trace 回放。
     */
    @GetMapping("/projects/{projectId}/memories/inject-route/trace")
    public R<List<Map<String, Object>>> injectRouteTrace(@PathVariable("projectId") Long projectId,
                                                         @RequestParam(value = "characterRef", required = false) String characterRef) {
        if (injectionRouter == null) {
            return R.ok(List.of());
        }
        return R.ok(injectionRouter.recentTraces(projectId, characterRef).stream()
                .map(t -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("seq", t.seq());
                    m.put("projectId", t.projectId());
                    m.put("characterRef", t.characterRef());
                    m.put("scenario", t.scenario());
                    m.put("gameDay", t.gameDay());
                    m.put("tier", t.tier());
                    m.put("reason", t.reason());
                    m.put("urgency", t.urgency());
                    m.put("layers", t.layers().values().stream().map(l -> {
                        Map<String, Object> lm = new java.util.LinkedHashMap<>();
                        lm.put("layer", l.layer());
                        lm.put("count", l.count());
                        lm.put("tokens", l.tokens());
                        return lm;
                    }).toList());
                    return m;
                }).toList());
    }

    /** 模糊状态 VO → Map（JSON 序列化友好）。 */
    private Map<String, Object> fuzzVo(com.holzyn.actor.domain.memory.service.L1FuzzificationService.FuzzStateVO vo) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", vo.id());
        m.put("memoryWorkingId", vo.memoryWorkingId());
        m.put("characterRef", vo.characterRef());
        m.put("originalContent", vo.originalContent());
        m.put("currentContent", vo.currentContent());
        m.put("fuzzLevel", vo.fuzzLevel());
        m.put("lastFuzzGameSecond", vo.lastFuzzGameSecond());
        m.put("nextFuzzGameSecond", vo.nextFuzzGameSecond());
        m.put("fuzzCount", vo.fuzzCount());
        m.put("fuzzHistory", vo.fuzzHistory());
        m.put("retrievalCount", vo.retrievalCount());
        m.put("importanceScore", vo.importanceScore());
        m.put("embeddingNoise", vo.embeddingNoise());
        m.put("status", vo.status());
        m.put("distilledMemoryId", vo.distilledMemoryId());
        return m;
    }
}
