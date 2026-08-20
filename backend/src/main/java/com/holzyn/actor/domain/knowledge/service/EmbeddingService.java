package com.holzyn.actor.domain.knowledge.service;

import com.holzyn.actor.ai.AiCallException;
import com.holzyn.actor.ai.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Embedding 调用服务（A-C7 P1，P3 知识库 RAG 核心）。
 * <p>职责：按 OpenAI 兼容协议调用供应商的 {@code POST {base_url}/embeddings} 端点
 * （火山方舟 doubao-embedding / 硅基 bge / OpenAI text-embedding-3 等），
 * 将文本转为向量（float[]），供知识分块入库与余弦检索使用。</p>
 * <p>批量上限：不同供应商对单次请求的 input 条数有限制（如 doubao-embedding 单次最多 10 条），
 * 超出时自动拆分为多次请求并按序合并（{@code holzyn.actor.embedding.batch-size}，默认 10，可配置）。</p>
 * <p>降级策略：调用方（KnowledgeRetrievalService）先通过 ModelApiService.resolveEmbeddingProvider
 * 探测是否配置 embedding 供应商；未配置或调用失败时降级文本检索 + 提示，不阻断主流程。</p>
 * <p>所属模块：service/knowledge（知识库子域）</p>
 */
@Slf4j
@Component
public class EmbeddingService {

    /** 请求/响应超时（embedding 单次调用，长文本分块后每块独立调用，15 秒足够） */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** 默认单次请求最大 input 条数（供应商限制；doubao-embedding 单次最多 10 条） */
    private static final int DEFAULT_BATCH_SIZE = 10;

    /** 单次请求最大 input 条数（可配置：HOLOZYN_ACTOR_EMBEDDING_BATCH_SIZE，默认 10） */
    @Value("${holzyn.actor.embedding.batch-size:10}")
    private int batchSize;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数：初始化共享 HttpClient 与 Jackson 解析器。
     *
     * @param objectMapper Spring 注入的 Jackson ObjectMapper
     */
    public EmbeddingService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 将单段文本转为向量（一次 /embeddings 调用，batch 大小为 1）。
     *
     * @param config 已解密的 embedding 供应商运行时配置（baseUrl/apiKey/model）
     * @param text   待向量化文本
     * @return 向量（float[]）
     */
    public float[] embed(ProviderConfig config, String text) {
        return embed(config, text, null);
    }

    /**
     * 将单段文本转为向量（一次 /embeddings 调用，batch 大小为 1），并累加用量。
     *
     * @param config   已解密的 embedding 供应商运行时配置（baseUrl/apiKey/model）
     * @param text     待向量化文本
     * @param usageAcc 用量累加器（长度≥2 的 int[]：0=promptTokens，1=totalTokens；可空=不统计）
     * @return 向量（float[]）
     */
    public float[] embed(ProviderConfig config, String text, int[] usageAcc) {
        return embedBatch(config, new String[]{text}, usageAcc)[0];
    }

    /**
     * 批量将文本转为向量（OpenAI 兼容 /embeddings 支持一次多输入）。
     * <p>按供应商单次请求上限（batch-size，默认 10）自动拆分多次调用，结果按序合并，
     * 避免「Embeddings API input limit exceeded」类 400 错误。</p>
     *
     * @param config 已解密的 embedding 供应商运行时配置
     * @param texts  待向量化文本数组
     * @return 向量数组（顺序与入参一致）
     */
    public float[][] embedBatch(ProviderConfig config, String[] texts) {
        return embedBatch(config, texts, null);
    }

    /**
     * 批量将文本转为向量，并累加每次调用的 token 用量（供用量日志）。
     *
     * @param config   已解密的 embedding 供应商运行时配置
     * @param texts    待向量化文本数组
     * @param usageAcc 用量累加器（长度≥2 的 int[]：0=promptTokens，1=totalTokens；可空=不统计）
     * @return 向量数组（顺序与入参一致）
     */
    public float[][] embedBatch(ProviderConfig config, String[] texts, int[] usageAcc) {
        if (config == null || texts == null || texts.length == 0) {
            throw new AiCallException("embedding 供应商未配置或输入为空");
        }
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        List<String[]> batches = splitIntoBatches(texts, effectiveBatchSize);
        if (batches.size() > 1) {
            log.info("[embedding] 共 {} 条输入，按每批 {} 条拆分为 {} 次调用", texts.length, effectiveBatchSize, batches.size());
        }
        float[][] result = new float[texts.length][];
        int offset = 0;
        for (int i = 0; i < batches.size(); i++) {
            String[] batch = batches.get(i);
            float[][] part = callEmbeddings(config, batch, usageAcc);
            if (part.length != batch.length) {
                throw new AiCallException("embedding 响应数量与请求不一致（第 " + (i + 1) + " 批：期望 " + batch.length + "，实际 " + part.length + "）");
            }
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    /**
     * 按单批上限拆分输入数组（静态纯逻辑，可单测）。
     *
     * @param texts     输入数组
     * @param batchSize 单批最大条数（≤0 视为默认 10）
     * @return 拆分后的批次列表（空输入返回空列表）
     */
    static List<String[]> splitIntoBatches(String[] texts, int batchSize) {
        List<String[]> batches = new ArrayList<>();
        if (texts == null || texts.length == 0) {
            return batches;
        }
        int size = batchSize > 0 ? Math.min(batchSize, texts.length) : Math.min(DEFAULT_BATCH_SIZE, texts.length);
        for (int from = 0; from < texts.length; from += size) {
            int to = Math.min(texts.length, from + size);
            batches.add(Arrays.copyOfRange(texts, from, to));
        }
        return batches;
    }

    /**
     * 单次 /embeddings 调用（一批，input 条数不超过 batch-size），并把响应的 usage 累加到 acc。
     *
     * @param config   已解密的 embedding 供应商运行时配置
     * @param texts    本批待向量化文本
     * @param usageAcc 用量累加器（长度≥2 的 int[]：0=promptTokens，1=totalTokens；可空=不统计）
     * @return 向量数组（顺序与本批入参一致）
     */
    private float[][] callEmbeddings(ProviderConfig config, String[] texts, int[] usageAcc) {
        String base = normalizeBaseUrl(config.baseUrl());
        String model = config.model();
        if (model == null || model.isBlank()) {
            throw new AiCallException("未配置 embedding 模型名：请在「设置-模型 API」为该供应商填写 embedding 模型");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode inputs = body.putArray("input");
        for (String t : texts) {
            inputs.add(t == null ? "" : t);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/embeddings"))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), java.nio.charset.StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new AiCallException("embedding 调用失败：" + buildError(resp));
            }
            return parseEmbeddings(resp.body(), texts.length, usageAcc);
        } catch (AiCallException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AiCallException("embedding 请求超时（15 秒）：请检查网络或供应商地址");
        } catch (Exception e) {
            throw new AiCallException("embedding 调用异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * 解析 /embeddings 成功响应：提取 data[i].embedding 为 float[]，并把 usage 累加到 acc。
     *
     * @param body     响应体
     * @param count    期望的向量条数
     * @param usageAcc 用量累加器（长度≥2 的 int[]：0=promptTokens，1=totalTokens；可空=不统计）
     * @return 向量数组
     */
    private float[][] parseEmbeddings(String body, int count, int[] usageAcc) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() < count) {
                throw new AiCallException("embedding 响应缺少 data 数组");
            }
            // 用量：OpenAI 兼容 /embeddings 返回 usage.prompt_tokens / usage.total_tokens（部分供应商可能缺省）
            if (usageAcc != null && usageAcc.length >= 2) {
                JsonNode usage = root.path("usage");
                int promptTokens = usage.path("prompt_tokens").asInt(0);
                int totalTokens = usage.path("total_tokens").asInt(promptTokens);
                usageAcc[0] += promptTokens;
                usageAcc[1] += totalTokens;
            }
            float[][] result = new float[count][];
            for (int i = 0; i < count; i++) {
                JsonNode emb = data.path(i).path("embedding");
                if (!emb.isArray() || emb.isEmpty()) {
                    throw new AiCallException("embedding 响应缺少第 " + (i + 1) + " 条向量");
                }
                result[i] = toFloatArray(emb);
            }
            return result;
        } catch (AiCallException e) {
            throw e;
        } catch (Exception e) {
            throw new AiCallException("embedding 响应解析失败：" + e.getMessage());
        }
    }

    /**
     * JsonNode 数组转 float[]。
     *
     * @param node embedding 数组节点
     * @return float 数组
     */
    private float[] toFloatArray(JsonNode node) {
        float[] arr = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            arr[i] = (float) node.get(i).asDouble();
        }
        return arr;
    }

    /**
     * 从失败响应提取友好错误信息（优先 error.message）。
     *
     * @param resp 非 2xx 响应
     * @return 错误描述
     */
    private String buildError(HttpResponse<String> resp) {
        int status = resp.statusCode();
        if (status == 401 || status == 403) return "认证失败（HTTP " + status + "）：API Key 无效或无权限";
        if (status == 404) return "接口不存在（HTTP 404）：请检查 Base URL 是否支持 /embeddings";
        try {
            JsonNode root = objectMapper.readTree(resp.body());
            String err = root.path("error").path("message").asText(null);
            if (err != null && !err.isBlank()) return "供应商返回（HTTP " + status + "）：" + err;
        } catch (Exception ignored) {
            // 忽略解析失败
        }
        return "请求失败（HTTP " + status + "）";
    }

    /**
     * 规范化 Base URL：去除尾部斜杠。
     *
     * @param baseUrl 原始 Base URL
     * @return 规范化结果
     */
    private String normalizeBaseUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            throw new AiCallException("Base URL 不能为空");
        }
        return base;
    }
}
