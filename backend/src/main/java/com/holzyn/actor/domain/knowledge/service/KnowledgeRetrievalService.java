package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.domain.knowledge.entity.ActorKnowledgeDoc;
import com.holzyn.actor.domain.knowledge.repository.ActorKnowledgeDocRepository;
import com.holzyn.actor.ai.ProviderConfig;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import com.holzyn.actor.domain.usage.service.UsageLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索服务（A-C7 P1，P3 知识库 RAG 核心）。
 * <p>职责：实现知识文档的「分块 → embedding 入库 → 余弦 top-k 检索」全链路——
 * ① 分块：按段落/句子切分（默认约 500 字/块 + 50 字重叠）；
 * ② 入库：调用 EmbeddingService 批量向量化分块，落 doc.embedding JSON 列；
 * ③ 检索：项目级全检 + 当前角色级合并，embedding 可用时余弦 top-k，
 *    未配置/失败时降级文本关键词检索 + 提示，保证功能可用。</p>
 * <p>纯逻辑（分块/余弦/文本打分）抽为静态方法便于单元测试。</p>
 * <p>所属模块：service/knowledge（知识库子域）</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    /** 默认分块大小（字符数） */
    static final int CHUNK_SIZE = 500;

    /** 相邻块重叠字符数（保留上下文语义） */
    static final int CHUNK_OVERLAP = 50;

    /** 静态 ObjectMapper（纯逻辑静态方法复用） */
    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();

    private final ActorKnowledgeDocRepository docRepository;
    private final ModelApiService modelApiService;
    private final EmbeddingService embeddingService;
    private final UsageLogService usageLogService;
    private final ObjectMapper objectMapper;

    /**
     * 是否已配置可用的 embedding 供应商（RAG 向量检索开关探测，项目级优先/用户级回退）。
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（NULL=仅用户级）
     * @return true 表示可走向量检索
     */
    public boolean isRagAvailable(Long userId, Long projectId) {
        return modelApiService.resolveEmbeddingProvider(userId, projectId) != null;
    }

    /**
     * 分块 + 向量化，生成 embedding 列的 JSON 文本（无 embedding 配置时返回空数组）。
     *
     * @param userId    归属用户 ID（embedding 凭据归属）
     * @param projectId 项目 ID（NULL=仅用户级）
     * @param content   文档全文
     * @return JSON 数组文本（[{text, embedding}, ...]；降级时 "[]"）
     */
    public String buildEmbeddedChunks(Long userId, Long projectId, String content) {
        ProviderConfig cfg = modelApiService.resolveEmbeddingProvider(userId, projectId);
        if (cfg == null) {
            return "[]";
        }
        List<String> chunks = chunk(content, CHUNK_SIZE, CHUNK_OVERLAP);
        if (chunks.isEmpty()) {
            return "[]";
        }
        try {
            // 用量累加器：0=promptTokens 1=totalTokens（embedding 消耗输入 token）
            int[] usageAcc = new int[2];
            long startMs = System.currentTimeMillis();
            float[][] vectors = embeddingService.embedBatch(cfg, chunks.toArray(new String[0]), usageAcc);
            recordEmbeddingUsage(userId, projectId, cfg, usageAcc, startMs);
            ArrayNode arr = objectMapper.createArrayNode();
            for (int i = 0; i < chunks.size(); i++) {
                var item = arr.addObject();
                item.put("text", chunks.get(i));
                var emb = item.putArray("embedding");
                for (float v : vectors[i]) {
                    emb.add(v);
                }
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            log.warn("embedding 生成失败，降级文本检索（该文档无向量）: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 知识检索（embedding 优先，文本关键词降级）。
     *
     * @param userId      归属用户 ID
     * @param projectId   项目 ID（项目级全检）
     * @param characterId 当前角色 ID（可空=仅项目级；非空=项目级 + 该角色级合并）
     * @param query       查询文本
     * @param topK        返回条数（默认 3）
     * @return 命中的知识片段列表（按相关度降序）
     */
    public List<KnowledgeHit> search(Long userId, Long projectId, Long characterId, String query, int topK) {
        List<ActorKnowledgeDoc> docs = docRepository.findByProjectIdOrderByIdAsc(projectId).stream()
                // 双粒度：项目级（characterId 空）恒纳入；角色级仅当前角色
                .filter(d -> d.getCharacterId() == null || d.getCharacterId().equals(characterId))
                .toList();
        int k = topK > 0 ? topK : 3;
        // 无知识文档：直接返回空（避免无谓的 embedding 调用）
        if (docs.isEmpty()) {
            return List.of();
        }

        // ① 向量检索路径（embedding 可用时，项目级优先/用户级回退）
        ProviderConfig cfg = modelApiService.resolveEmbeddingProvider(userId, projectId);
        if (cfg != null) {
            try {
                int[] usageAcc = new int[2];
                long startMs = System.currentTimeMillis();
                float[] qv = embeddingService.embed(cfg, query, usageAcc);
                recordEmbeddingUsage(userId, projectId, cfg, usageAcc, startMs);
                List<KnowledgeHit> hits = new ArrayList<>();
                for (ActorKnowledgeDoc d : docs) {
                    hits.addAll(vectorHits(d, qv));
                }
                return top(hits, k);
            } catch (Exception e) {
                log.warn("向量检索失败，降级文本检索: {}", e.getMessage());
            }
        }
        // ② 文本关键词检索降级
        List<KnowledgeHit> textHits = new ArrayList<>();
        for (ActorKnowledgeDoc d : docs) {
            textHits.addAll(textHits(d, query));
        }
        return top(textHits, k);
    }

    /**
     * 记录 embedding 向量化用量（scene=embedding；2026-08-18 补记，此前 embedding 消耗 token 未入库）。
     * <p>embedding 只消耗输入 token：tokenIn=total_tokens（缺省回退 prompt_tokens），tokenOut=0。</p>
     *
     * @param userId    归属用户 ID
     * @param projectId 项目 ID（可空=仅用户级配置）
     * @param config    embedding 供应商运行时配置（取模型名；ProviderConfig 无 providerId 故记为 null）
     * @param usageAcc  用量累加器（0=promptTokens，1=totalTokens）
     * @param startMs   调用起始时间戳（毫秒）
     */
    private void recordEmbeddingUsage(Long userId, Long projectId, ProviderConfig config, int[] usageAcc, long startMs) {
        int prompt = usageAcc == null || usageAcc.length < 2 ? 0 : usageAcc[0];
        int total = usageAcc == null || usageAcc.length < 2 ? 0 : usageAcc[1];
        if (total <= 0 && prompt <= 0) {
            // 供应商未返回 usage：仍记一条（token 为 0），保证调用次数可统计
        }
        usageLogService.record(userId, projectId, null, null, config == null ? null : config.model(),
                "embedding", total > 0 ? total : prompt, 0, 0, 0,
                (int) (System.currentTimeMillis() - startMs));
    }

    /**
     * 对单个文档做向量检索：逐块余弦相似度，产出（可能多条）命中。
     *
     * @param doc  文档实体
     * @param qv   查询向量
     * @return 该文档的命中片段（按得分降序）
     */
    private List<KnowledgeHit> vectorHits(ActorKnowledgeDoc doc, float[] qv) {
        List<KnowledgeHit> hits = new ArrayList<>();
        String embeddingJson = doc.getEmbedding();
        if (embeddingJson == null || embeddingJson.isBlank() || "[]".equals(embeddingJson.trim())) {
            return hits;
        }
        try {
            JsonNode arr = STATIC_MAPPER.readTree(embeddingJson);
            if (!arr.isArray()) return hits;
            for (JsonNode item : arr) {
                String text = item.path("text").asText("");
                JsonNode emb = item.path("embedding");
                if (text.isBlank() || !emb.isArray()) continue;
                float[] chunkVec = toFloatArray(emb);
                double score = cosine(qv, chunkVec);
                if (score > 0) {
                    hits.add(new KnowledgeHit(doc.getId(), doc.getTitle(), text, score, doc.getCharacterId()));
                }
            }
        } catch (Exception e) {
            log.warn("文档 {} 向量解析失败，跳过: {}", doc.getId(), e.getMessage());
        }
        return hits;
    }

    /**
     * 对单个文档做文本关键词检索（降级路径）。
     *
     * @param doc   文档实体
     * @param query 查询文本
     * @return 该文档的命中片段（全文作为一个候选片段）
     */
    private List<KnowledgeHit> textHits(ActorKnowledgeDoc doc, String query) {
        String content = doc.getContent() == null ? "" : doc.getContent();
        double score = textScore(query, content);
        if (score <= 0) {
            return List.of();
        }
        // 取命中位置附近的片段（前后各 120 字），避免全文过长注入
        String snippet = snippetAround(content, query);
        return List.of(new KnowledgeHit(doc.getId(), doc.getTitle(), snippet, score, doc.getCharacterId()));
    }

    /**
     * 取包含首个命中位置的片段（前后各 120 字）。
     *
     * @param content 全文
     * @param query   查询文本
     * @return 片段文本
     */
    private String snippetAround(String content, String query) {
        if (query == null || query.isBlank()) {
            return content.length() > 300 ? content.substring(0, 300) : content;
        }
        int idx = content.indexOf(query.trim());
        if (idx < 0) {
            // 按第一个二元组定位
            for (int i = 0; i + 1 < query.length(); i++) {
                idx = content.indexOf(query.substring(i, i + 2));
                if (idx >= 0) break;
            }
        }
        int from = Math.max(0, (idx < 0 ? 0 : idx) - 120);
        int to = Math.min(content.length(), (idx < 0 ? 120 : idx + query.length() + 120));
        return content.substring(from, to);
    }

    /**
     * 取 top-k（按得分降序）。
     *
     * @param hits 全部命中
     * @param k    返回条数
     * @return 排序后的 top-k
     */
    private List<KnowledgeHit> top(List<KnowledgeHit> hits, int k) {
        return hits.stream()
                .sorted(Comparator.comparingDouble(KnowledgeHit::score).reversed())
                .limit(k)
                .toList();
    }

    /**
     * JsonNode 数组转 float[]。
     *
     * @param node 数组节点
     * @return float 数组
     */
    private float[] toFloatArray(JsonNode node) {
        float[] arr = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            arr[i] = (float) node.get(i).asDouble();
        }
        return arr;
    }

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 文本分块：按段落/句子切分，每块不超过 chunkSize，相邻块带 overlap 字符重叠。
     *
     * @param text      全文
     * @param chunkSize 单块最大字符数
     * @param overlap   相邻块重叠字符数
     * @return 分块列表（空文本返回空列表）
     */
    static List<String> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").trim();
        List<String> sentences = splitIntoSentences(normalized);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String s : sentences) {
            // 超长句（罕见）：硬切分
            for (String piece : splitLong(s, chunkSize)) {
                if (current.length() > 0 && current.length() + piece.length() + 1 > chunkSize) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                    String tail = tailOf(chunks.get(chunks.size() - 1), overlap);
                    current.append(tail);
                }
                if (current.length() > 0) current.append('\n');
                current.append(piece);
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            chunks.add(last);
        }
        return chunks;
    }

    /**
     * 按句子切分（保留标点）：以换行/。！？!?；; 为边界。
     * <p>换行被替换为句号后可能产生孤立标点片段（纯标点），会并入上一句，避免分块失真。</p>
     *
     * @param text 文本
     * @return 句子列表
     */
    static List<String> splitIntoSentences(String text) {
        String normalized = text.replaceAll("\\r?\\n+", "。");
        String[] parts = normalized.split("(?<=[。！？!?；;])");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            // 纯标点片段（如换行替换产生的孤立句号）：并入上一句
            if (isOnlyPunctuation(s)) {
                if (!result.isEmpty()) {
                    result.set(result.size() - 1, result.get(result.size() - 1) + s);
                }
                continue;
            }
            result.add(s);
        }
        return result;
    }

    /**
     * 判断文本是否仅由标点/空白构成（无字母、数字）。
     *
     * @param s 文本
     * @return true 表示纯标点
     */
    private static boolean isOnlyPunctuation(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 超长句硬切分：按 chunkSize 均分。
     *
     * @param s         句子
     * @param chunkSize 块大小
     * @return 切分后的片段列表
     */
    private static List<String> splitLong(String s, int chunkSize) {
        if (s.length() <= chunkSize) {
            return List.of(s);
        }
        List<String> pieces = new ArrayList<>();
        int from = 0;
        while (from < s.length()) {
            int to = Math.min(s.length(), from + chunkSize);
            pieces.add(s.substring(from, to));
            from = to;
        }
        return pieces;
    }

    /**
     * 取文本尾部 overlap 字符（重叠用；不足则整段）。
     *
     * @param text    文本
     * @param overlap 字符数
     * @return 尾部片段
     */
    private static String tailOf(String text, int overlap) {
        if (text == null || text.isEmpty() || overlap <= 0) {
            return "";
        }
        int from = Math.max(0, text.length() - overlap);
        // 尽量从完整词边界开始（回退到最近的标点或空白，避免从字中间切断）
        String tail = text.substring(from);
        int cut = tail.indexOf('。');
        if (cut > 0 && cut < tail.length() / 2) {
            return tail.substring(cut + 1);
        }
        return tail;
    }

    /**
     * 余弦相似度（两个向量）。
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 余弦值（-1~1；维度不同或零向量返回 0）
     */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * 文本关键词打分（降级检索）：整句命中加权 + 二元组共现计数。
     *
     * @param query   查询文本
     * @param content 文档内容
     * @return 相关度得分（0 = 无命中）
     */
    static double textScore(String query, String content) {
        if (query == null || query.isBlank() || content == null || content.isBlank()) {
            return 0;
        }
        String q = query.trim();
        String c = content;
        double score = 0;
        // 整句命中：按查询长度加权
        if (c.contains(q)) {
            score += q.length() * 2.0;
        }
        // 二元组共现计数（中文无空格分词，用二元组近似）
        for (int i = 0; i + 1 < q.length(); i++) {
            String bigram = q.substring(i, i + 2);
            int idx = 0, cnt = 0;
            while ((idx = c.indexOf(bigram, idx)) >= 0) {
                cnt++;
                idx += bigram.length();
            }
            score += cnt;
        }
        return score;
    }

    /**
     * 知识命中片段记录。
     *
     * @param docId       来源文档 ID
     * @param title       文档标题
     * @param text        命中的知识片段文本
     * @param score       相关度得分
     * @param characterId 文档角色级归属（空=项目级）
     */
    public record KnowledgeHit(Long docId, String title, String text, double score, Long characterId) {
    }
}
