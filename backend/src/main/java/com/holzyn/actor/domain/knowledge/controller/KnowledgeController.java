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

package com.holzyn.actor.domain.knowledge.controller;

import com.holzyn.actor.ai.KnowledgeHit;
import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.knowledge.dto.KnowledgeDocDTO;
import com.holzyn.actor.domain.knowledge.entity.KnowledgeCandidate;
import com.holzyn.actor.domain.knowledge.service.KnowledgeBridgeService;
import com.holzyn.actor.domain.knowledge.service.KnowledgeLifecycleService;
import com.holzyn.actor.domain.knowledge.vo.KnowledgeDocVO;
import com.holzyn.actor.domain.knowledge.service.KnowledgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库控制器。
 * <p>职责：提供知识文档列表/新建/详情/编辑/删除/重新索引/上传/检索预览接口。
 * 归属以当前登录用户为准（服务层校验）；统一返回 R&lt;T&gt;。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /** 知识生命周期服务（P1 ②-2：衰减扫描/手动归档/还原）。 */
    private final KnowledgeLifecycleService lifecycleService;

    /** 记忆-知识桥接服务（P1 ②-3：AI 判定抽取 + 全自动确认 + 落「经历沉淀」文档）。 */
    private final KnowledgeBridgeService knowledgeBridgeService;

    /** 候选仓库（P1 ②-3：候选留档列表观测）。 */
    private final com.holzyn.actor.domain.knowledge.repository.KnowledgeCandidateRepository candidateRepository;

    /**
     * 知识文档列表（含向量化状态）。
     *
     * @param projectId 项目 ID
     *
     * @return 文档 VO 列表
     */
    @GetMapping("/projects/{projectId}/knowledge-docs")
    public R<List<KnowledgeDocVO>> list(@PathVariable("projectId") Long projectId) {
        return R.ok(knowledgeService.list(projectId));
    }

    /**
     * 新建知识文档（保存时自动分块 + 向量化）。
     *
     * @param projectId 项目 ID
     *
     * @param dto 入参（title/content/characterId）
     *
     * @return 保存后的文档
     */
    @PostMapping("/projects/{projectId}/knowledge-docs")
    public R<KnowledgeDocVO> create(@PathVariable("projectId") Long projectId, @Valid @RequestBody KnowledgeDocDTO dto) {
        return R.ok(knowledgeService.create(projectId, dto));
    }

    /**
     * 知识文档详情。
     *
     * @param id 文档主键
     *
     * @return 文档 VO
     */
    @GetMapping("/knowledge-docs/{id}")
    public R<KnowledgeDocVO> detail(@PathVariable("id") Long id) {
        return R.ok(knowledgeService.detail(id));
    }

    /**
     * 编辑知识文档（正文变化时重新向量化）。
     *
     * @param id 文档主键
     *
     * @param dto 入参
     *
     * @return 更新后的文档
     */
    @PutMapping("/knowledge-docs/{id}")
    public R<KnowledgeDocVO> update(@PathVariable("id") Long id, @Valid @RequestBody KnowledgeDocDTO dto) {
        return R.ok(knowledgeService.update(id, dto));
    }

    /**
     * 删除知识文档。
     *
     * @param id 文档主键
     *
     * @return 删除确认
     */
    @DeleteMapping("/knowledge-docs/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") Long id) {
        knowledgeService.delete(id);
        return R.ok(Map.of("id", id, "deleted", true));
    }

    /**
     * 重新索引：对现有正文重新分块 + 向量化（embedding 配置变更后使用）。
     *
     * @param id 文档主键
     *
     * @return 更新后的文档
     */
    @PostMapping("/knowledge-docs/{id}/reindex")
    public R<KnowledgeDocVO> reindex(@PathVariable("id") Long id) {
        return R.ok(knowledgeService.reindex(id));
    }

    /**
     * 上传 txt/md 文件新建知识文档。
     *
     * @param projectId 项目 ID
     *
     * @param file 上传文件（multipart，txt/md）
     *
     * @param characterId 角色级归属（可空=项目级）
     *
     * @return 保存后的文档
     */
    @PostMapping("/projects/{projectId}/knowledge-docs/upload")
    public R<KnowledgeDocVO> upload(@PathVariable("projectId") Long projectId,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "characterId", required = false) Long characterId) {
        return R.ok(knowledgeService.upload(projectId, file, characterId));
    }

    /**
     * 检索预览：embedding 向量检索优先，文本关键词降级。
     *
     * @param projectId 项目 ID
     *
     * @param body 检索参数（query 必填；characterId/topK 可选）
     *
     * @return 命中片段列表（docId/title/text/score）
     */
    @PostMapping("/projects/{projectId}/knowledge-docs/search")
    public R<List<KnowledgeHit>> search(@PathVariable("projectId") Long projectId,
                                        @RequestBody Map<String, Object> body) {
        if (body == null || body.get("query") == null) {
            throw new BizException(400, "请输入检索内容 query");
        }
        String query = String.valueOf(body.get("query"));
        Long characterId = body.get("characterId") == null ? null : Long.valueOf(String.valueOf(body.get("characterId")));
        int topK = body.get("topK") == null ? 3 : Integer.parseInt(String.valueOf(body.get("topK")));
        return R.ok(knowledgeService.search(projectId, characterId, query, topK));
    }

    /**
     * 世界设定 → 项目级知识文档同步（知识库 RAG 适配：幂等 upsert + 向量化）。
     * <p>把项目最新版本世界设定（地理/势力/规则/文化/历史/总览）与地点志同步为项目级知识文档；
     * 供既有项目/导入项目/修复场景手动补建（新项目在世界初始化第 6 步自动执行；
     * 世界设定编辑/项目导入后自动执行，见 ProjectService/HolzynImportService）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return {synced=本次新建/更新文档数}
     */
    @PostMapping("/projects/{projectId}/knowledge-docs/sync-world")
    public R<Map<String, Object>> syncWorld(@PathVariable("projectId") Long projectId) {
        int synced = knowledgeService.syncWorldSetting(projectId);
        return R.ok(Map.of("synced", synced));
    }

    /**
     * 全角色人物知识卡同步（知识检索质量深化：结构化知识卡幂等 upsert + 向量化）。
     * <p>把每个角色的结构化人物知识卡（身份/背景/性格/常去地点/技能/目标/知识边界）同步为
     * 角色级知识文档；供既有项目/导入项目/修复场景手动补建（新项目在世界初始化第 6 步自动执行；
     * 档案保存后自动执行，见 PersonaService.savePersona）。</p>
     *
     * @param projectId 项目 ID
     *
     * @return {synced=本次新建/更新知识卡数}
     */
    @PostMapping("/projects/{projectId}/knowledge-docs/sync-character-cards")
    public R<Map<String, Object>> syncCharacterCards(@PathVariable("projectId") Long projectId) {
        int synced = knowledgeService.syncCharacterCards(projectId);
        return R.ok(Map.of("synced", synced));
    }

    /**
     * 知识生命周期衰减扫描（P1 ②-2，手动触发）：按引用率（access_count + last_access_at）
     * 评估项目全部知识文档的状态迁移（hot/warm/stale/archived）。
     *
     * @param projectId 项目 ID
     *
     * @return {evaluated=评估数, migrated=迁移数, skipped=跳过数, byState=各目标态迁移计数}
     */
    @PostMapping("/projects/{projectId}/knowledge-docs/scan-lifecycle")
    public R<Map<String, Object>> scanLifecycle(@PathVariable("projectId") Long projectId) {
        KnowledgeLifecycleService.ScanResult r = lifecycleService.scanProject(projectId);
        return R.ok(Map.of("evaluated", r.evaluated(), "migrated", r.migrated(),
                "skipped", r.skipped(), "byState", r.byState()));
    }

    /**
     * 手动归档知识文档（P1 ②-2）：置 archived（不再参与检索，可还原）。
     *
     * @param id 文档主键
     *
     * @return {id, archived=true/false}
     */
    @PostMapping("/knowledge-docs/{id}/archive")
    public R<Map<String, Object>> archiveDoc(@PathVariable("id") Long id) {
        int n = lifecycleService.archiveDoc(id);
        return R.ok(Map.of("id", id, "archived", n == 1));
    }

    /**
     * 手动还原知识文档（P1 ②-2）：archived → warm（还原后按引用时间自然演化）。
     *
     * @param id 文档主键
     *
     * @return {id, restored=true/false}
     */
    @PostMapping("/knowledge-docs/{id}/restore")
    public R<Map<String, Object>> restoreDoc(@PathVariable("id") Long id) {
        int n = lifecycleService.restoreDoc(id);
        return R.ok(Map.of("id", id, "restored", n == 1));
    }

    /**
     * 知识桥接候选列表（P1 ②-3，观测/审计）：promoted/duplicate/rejected 全状态留档。
     *
     * @param projectId 项目 ID
     *
     * @return 候选列表（最新在前）
     */
    @GetMapping("/projects/{projectId}/knowledge-candidates")
    public R<List<KnowledgeCandidate>> candidates(@PathVariable("projectId") Long projectId) {
        return R.ok(candidateRepository.findByProjectIdOrderByIdDesc(projectId));
    }

    /**
     * 手动触发记忆-知识桥接（P1 ②-3，冒烟/运维直驱）：给定角色 + 洞察列表，
     * 独立 AI 判定抽取「可复用事实型知识候选」→ 全自动确认 → 写入角色级「经历沉淀」文档。
     *
     * @param projectId 项目 ID
     *
     * @param body {characterRef: "npc:{id}", insights: ["洞察1", ...]}
     *
     * @return {promoted=写入沉淀文档数, duplicate=重复跳过数, rejected=规则拒绝数}
     */
    @PostMapping("/projects/{projectId}/knowledge-bridge/run")
    public R<Map<String, Object>> runBridge(@PathVariable("projectId") Long projectId,
                                            @RequestBody Map<String, Object> body) {
        if (body == null || body.get("characterRef") == null) {
            throw new BizException(400, "请输入角色引用 characterRef（npc:{id}）");
        }
        String characterRef = String.valueOf(body.get("characterRef"));
        @SuppressWarnings("unchecked")
        List<String> insights = body.get("insights") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        if (insights.isEmpty()) {
            throw new BizException(400, "请输入至少一条洞察 insights");
        }
        KnowledgeBridgeService.BridgeResult r =
                knowledgeBridgeService.extractAndPromote(projectId, characterRef, null, null, insights);
        return R.ok(Map.of("promoted", r.promoted(), "duplicate", r.duplicate(), "rejected", r.rejected()));
    }
}
