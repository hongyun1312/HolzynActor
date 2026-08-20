package com.holzyn.actor.domain.knowledge.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.knowledge.dto.KnowledgeDocDTO;
import com.holzyn.actor.domain.knowledge.vo.KnowledgeDocVO;
import com.holzyn.actor.domain.knowledge.service.KnowledgeRetrievalService;
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
 * 知识库控制器（A-C7 P1，P3 知识库 RAG 实现）。
 * <p>职责：提供知识文档列表/新建/详情/编辑/删除/重新索引/上传/检索预览接口。
 * 归属以当前登录用户为准（服务层校验）；统一返回 R&lt;T&gt;。</p>
 * <p>所属模块：controller/knowledge（知识库子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    /**
     * 知识文档列表（含向量化状态）。
     *
     * @param projectId 项目 ID
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
     * @param dto       入参（title/content/characterId）
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
     * @return 文档 VO
     */
    @GetMapping("/knowledge-docs/{id}")
    public R<KnowledgeDocVO> detail(@PathVariable("id") Long id) {
        return R.ok(knowledgeService.detail(id));
    }

    /**
     * 编辑知识文档（正文变化时重新向量化）。
     *
     * @param id  文档主键
     * @param dto 入参
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
     * @return 更新后的文档
     */
    @PostMapping("/knowledge-docs/{id}/reindex")
    public R<KnowledgeDocVO> reindex(@PathVariable("id") Long id) {
        return R.ok(knowledgeService.reindex(id));
    }

    /**
     * 上传 txt/md 文件新建知识文档。
     *
     * @param projectId   项目 ID
     * @param file        上传文件（multipart，txt/md）
     * @param characterId 角色级归属（可空=项目级）
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
     * @param projectId   项目 ID
     * @param body        检索参数（query 必填；characterId/topK 可选）
     * @return 命中片段列表（docId/title/text/score）
     */
    @PostMapping("/projects/{projectId}/knowledge-docs/search")
    public R<List<KnowledgeRetrievalService.KnowledgeHit>> search(@PathVariable("projectId") Long projectId,
                                                                  @RequestBody Map<String, Object> body) {
        if (body == null || body.get("query") == null) {
            throw new BizException(400, "请输入检索内容 query");
        }
        String query = String.valueOf(body.get("query"));
        Long characterId = body.get("characterId") == null ? null : Long.valueOf(String.valueOf(body.get("characterId")));
        int topK = body.get("topK") == null ? 3 : Integer.parseInt(String.valueOf(body.get("topK")));
        return R.ok(knowledgeService.search(projectId, characterId, query, topK));
    }
}
