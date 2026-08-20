package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.knowledge.dto.KnowledgeDocDTO;
import com.holzyn.actor.domain.character.entity.ActorCharacter;
import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.project.entity.ActorProject;
import com.holzyn.actor.domain.knowledge.vo.KnowledgeDocVO;
import com.holzyn.actor.domain.character.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.domain.project.repository.ActorProjectRepository;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识库服务（A-C7 P1，P3 知识库 RAG）。
 * <p>职责：承载知识文档的增删改查、txt/md 上传、重新索引与检索预览——
 * 保存时自动分块 + 向量化（embedding 可用时），未配置 embedding 降级文本检索；
 * 提供项目归属校验（文档 → 项目 → 用户两级）。</p>
 * <p>所属模块：service/knowledge（知识库子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final ActorKnowledgeDocRepository docRepository;
    private final ActorProjectRepository projectRepository;
    private final ActorCharacterRepository characterRepository;
    private final CurrentUserProvider currentUserProvider;
    private final KnowledgeRetrievalService retrievalService;
    private final ModelApiService modelApiService;
    private final ObjectMapper objectMapper;

    /**
     * 查询某项目的知识文档列表（含向量化状态）。
     *
     * @param projectId 项目 ID
     * @return 文档 VO 列表
     */
    @Transactional(readOnly = true)
    public List<KnowledgeDocVO> list(Long projectId) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        String embeddingModel = embeddingModelName(userId, projectId);
        List<ActorKnowledgeDoc> docs = docRepository.findByProjectIdOrderByIdAsc(projectId);
        return docs.stream().map(d -> toVO(d, embeddingModel)).toList();
    }

    /**
     * 新建知识文档（保存时自动分块 + 向量化）。
     *
     * @param projectId 项目 ID
     * @param dto       入参（title/content/characterId）
     * @return 保存后的文档 VO
     */
    @Transactional
    public KnowledgeDocVO create(Long projectId, KnowledgeDocDTO dto) {
        requireProject(projectId);
        if (dto.content() == null || dto.content().isBlank()) {
            throw new BizException(400, "文档内容不能为空");
        }
        Long userId = currentUserProvider.currentUserId();
        ActorKnowledgeDoc doc = new ActorKnowledgeDoc();
        doc.setProjectId(projectId);
        doc.setCharacterId(dto.characterId());
        doc.setTitle(dto.title());
        doc.setContent(dto.content());
        // 分块 + 向量化（未配置 embedding 时返回空数组，走文本检索降级）
        doc.setEmbedding(retrievalService.buildEmbeddedChunks(userId, projectId, dto.content()));
        doc = docRepository.save(doc);
        log.info("[知识库] 新建文档：项目={} 文档={} 标题={} 字数={}", projectId, doc.getId(), dto.title(),
                dto.content().length());
        return toVO(doc, embeddingModelName(userId, projectId));
    }

    /**
     * 文档详情（归属校验）。
     *
     * @param id 文档主键
     * @return 文档 VO
     */
    @Transactional(readOnly = true)
    public KnowledgeDocVO detail(Long id) {
        ActorKnowledgeDoc doc = requireDoc(id);
        return toVO(doc, embeddingModelName(currentUserProvider.currentUserId(), doc.getProjectId()));
    }

    /**
     * 编辑文档：标题/正文/角色级归属；正文变化时重新分块 + 向量化。
     *
     * @param id  文档主键
     * @param dto 入参
     * @return 更新后的文档 VO
     */
    @Transactional
    public KnowledgeDocVO update(Long id, KnowledgeDocDTO dto) {
        ActorKnowledgeDoc doc = requireDoc(id);
        Long userId = currentUserProvider.currentUserId();
        doc.setTitle(dto.title());
        if (dto.content() != null && !dto.content().isBlank()) {
            doc.setContent(dto.content());
            // 正文变化：重新分块 + 向量化
            doc.setEmbedding(retrievalService.buildEmbeddedChunks(userId, doc.getProjectId(), dto.content()));
        }
        if (dto.characterId() != null) {
            doc.setCharacterId(dto.characterId());
        }
        doc = docRepository.save(doc);
        return toVO(doc, embeddingModelName(userId, doc.getProjectId()));
    }

    /**
     * 删除文档（归属校验）。
     *
     * @param id 文档主键
     */
    @Transactional
    public void delete(Long id) {
        ActorKnowledgeDoc doc = requireDoc(id);
        docRepository.delete(doc);
        log.info("[知识库] 删除文档：文档={} 项目={}", id, doc.getProjectId());
    }

    /**
     * 重新索引：对现有正文重新分块 + 向量化（embedding 配置变更后使用）。
     *
     * @param id 文档主键
     * @return 更新后的文档 VO
     */
    @Transactional
    public KnowledgeDocVO reindex(Long id) {
        ActorKnowledgeDoc doc = requireDoc(id);
        Long userId = currentUserProvider.currentUserId();
        if (doc.getContent() == null || doc.getContent().isBlank()) {
            throw new BizException(400, "文档内容为空，无需重新索引");
        }
        doc.setEmbedding(retrievalService.buildEmbeddedChunks(userId, doc.getProjectId(), doc.getContent()));
        doc = docRepository.save(doc);
        log.info("[知识库] 重新索引：文档={} 项目={}", id, doc.getProjectId());
        return toVO(doc, embeddingModelName(userId, doc.getProjectId()));
    }

    /**
     * 上传 txt/md 文件新建知识文档（文件名去扩展名作标题）。
     *
     * @param projectId 项目 ID
     * @param file      上传文件（txt/md/markdown，≤5MB）
     * @param characterId 角色级归属（可空=项目级）
     * @return 保存后的文档 VO
     */
    @Transactional
    public KnowledgeDocVO upload(Long projectId, MultipartFile file, Long characterId) {        requireProject(projectId);
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要上传的文件");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!isTextFile(filename)) {
            throw new BizException(400, "仅支持 txt / md / markdown 文本文件：" + filename);
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException(400, "文件读取失败：" + e.getMessage());
        }
        if (content.isBlank()) {
            throw new BizException(400, "文件内容为空：" + filename);
        }
        String title = stripExtension(filename);
        Long userId = currentUserProvider.currentUserId();
        ActorKnowledgeDoc doc = new ActorKnowledgeDoc();
        doc.setProjectId(projectId);
        doc.setCharacterId(characterId);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setEmbedding(retrievalService.buildEmbeddedChunks(userId, projectId, content));
        doc = docRepository.save(doc);
        log.info("[知识库] 上传文档：项目={} 文档={} 文件={} 字数={}", projectId, doc.getId(), filename, content.length());
        return toVO(doc, embeddingModelName(userId, projectId));
    }

    /**
     * 原始创建知识文档（<b>不向量化</b>；2026-08-19 新建项目解析重构）。
     * <p>职责：解析工作流把「原始世界观文件全文」先落知识库、暂不向量化（embedding 留空），
     * 待世界初始化第 6 步再统一向量化；避免解析阶段阻塞在 embedding 调用。</p>
     *
     * @param projectId 项目 ID
     * @param title     文档标题（一般取文件名）
     * @param content   文档全文
     * @return 保存后的文档 VO（vectorized=false）
     */
    @Transactional
    public KnowledgeDocVO createRaw(Long projectId, String title, String content) {
        requireProject(projectId);
        if (content == null || content.isBlank()) {
            throw new BizException(400, "文档内容不能为空");
        }
        Long userId = currentUserProvider.currentUserId();
        ActorKnowledgeDoc doc = new ActorKnowledgeDoc();
        doc.setProjectId(projectId);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setEmbedding(null); // 暂不向量化：embedding 为空，检索走文本降级
        doc = docRepository.save(doc);
        log.info("[知识库] 原始创建文档（未向量化）：项目={} 文档={} 标题={} 字数={}",
                projectId, doc.getId(), title, content.length());
        return toVO(doc, embeddingModelName(userId, projectId));
    }

    /**
     * 项目全部文档向量化（2026-08-19 世界初始化第 6 步）。
     * <p>逐条对「未向量化」的文档重新分块 + 向量化（embedding 未配置时降级空数组，
     * 由 buildEmbeddedChunks 内部处理，不抛错）；单条失败不影响其余。</p>
     *
     * @param projectId 项目 ID
     * @return 本次完成向量化的文档数
     */
    @Transactional
    public int vectorizeAll(Long projectId) {
        requireProject(projectId);
        Long userId = currentUserProvider.currentUserId();
        List<ActorKnowledgeDoc> docs = docRepository.findByProjectIdOrderByIdAsc(projectId);
        int done = 0;
        for (ActorKnowledgeDoc doc : docs) {
            if (doc.getContent() == null || doc.getContent().isBlank()) {
                continue;
            }
            try {
                doc.setEmbedding(retrievalService.buildEmbeddedChunks(userId, projectId, doc.getContent()));
                docRepository.save(doc);
                done++;
                log.info("[知识库] 向量化完成：项目={} 文档={} 标题={} 字数={}",
                        projectId, doc.getId(), doc.getTitle(), doc.getContent().length());
            } catch (Exception e) {
                log.warn("[知识库] 向量化失败（跳过继续）：项目={} 文档={}：{}",
                        projectId, doc.getId(), e.getMessage());
            }
        }
        log.info("[知识库] 批量向量化结束：项目={} 完成 {} 条（共 {} 条）", projectId, done, docs.size());
        return done;
    }

    /**
     * 检索预览：embedding 向量检索优先，文本关键词降级。
     *
     * @param projectId   项目 ID
     * @param characterId 角色级过滤（可空）
     * @param query       查询文本
     * @param topK        返回条数（默认 3）
     * @return 命中片段列表
     */
    @Transactional(readOnly = true)
    public List<KnowledgeRetrievalService.KnowledgeHit> search(Long projectId, Long characterId, String query, int topK) {
        requireProject(projectId);
        if (query == null || query.isBlank()) {
            throw new BizException(400, "请输入检索内容");
        }
        Long userId = currentUserProvider.currentUserId();
        List<KnowledgeRetrievalService.KnowledgeHit> hits =
                retrievalService.search(userId, projectId, characterId, query, topK);
        log.info("[知识库] 检索：项目={} 角色={} 命中={} 查询={}", projectId, characterId, hits.size(), query);
        return hits;
    }

    /**
     * 按 id + 项目归属查询文档（不存在或越权抛 404）。
     *
     * @param id 文档主键
     * @return 文档实体
     */
    private ActorKnowledgeDoc requireDoc(Long id) {
        ActorKnowledgeDoc doc = docRepository.findById(id)
                .orElseThrow(() -> new BizException(404, "知识文档不存在或无权访问"));
        requireProject(doc.getProjectId());
        return doc;
    }

    /**
     * 校验项目归属当前用户（越权抛 404）。
     *
     * @param projectId 项目 ID
     */
    private void requireProject(Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        ActorProject p = projectRepository.findByIdAndUserIdAndDeleted(projectId, userId, 0)
                .orElseThrow(() -> new BizException(404, "项目不存在或无权访问"));
    }

    /**
     * 实体转 VO：解析分块数/向量化状态 + 角色名。
     *
     * @param doc            文档实体
     * @param embeddingModel 当前用户 embedding 模型名（可空）
     * @return VO 对象
     */
    private KnowledgeDocVO toVO(ActorKnowledgeDoc doc, String embeddingModel) {
        int chunkCount = 0;
        boolean vectorized = false;
        String emb = doc.getEmbedding();
        if (emb != null && !emb.isBlank() && !"[]".equals(emb.trim())) {
            try {
                JsonNode arr = objectMapper.readTree(emb);
                if (arr.isArray()) {
                    chunkCount = arr.size();
                    vectorized = arr.size() > 0 && arr.get(0).path("embedding").isArray();
                }
            } catch (Exception e) {
                log.warn("文档 {} 向量解析失败: {}", doc.getId(), e.getMessage());
            }
        }
        String characterName = null;
        if (doc.getCharacterId() != null) {
            characterName = characterRepository.findById(doc.getCharacterId())
                    .map(ActorCharacter::getName).orElse(null);
        }
        return KnowledgeDocVO.of(doc, characterName, chunkCount, vectorized,
                vectorized ? embeddingModel : null);
    }

    /**
     * 当前用户 embedding 模型名（展示用，项目级优先/用户级回退，无则 null）。
     *
     * @param userId    用户 ID
     * @param projectId 项目 ID（NULL=仅用户级）
     * @return 模型名或 null
     */
    private String embeddingModelName(Long userId, Long projectId) {
        ProviderConfig cfg = modelApiService.resolveEmbeddingProvider(userId, projectId);
        return cfg == null ? null : cfg.model();
    }

    /**
     * 判断是否为支持的文本文件（txt/md/markdown）。
     *
     * @param filename 文件名
     * @return true 表示支持
     */
    private boolean isTextFile(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    /**
     * 去除文件扩展名（作文档标题）。
     *
     * @param filename 文件名
     * @return 标题
     */
    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot > 0 && dot < filename.length() - 1) {
            return filename.substring(0, dot);
        }
        return filename;
    }
}
