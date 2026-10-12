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

package com.holzyn.actor.domain.debug.service;

import com.holzyn.actor.domain.debug.entity.ActorAiCallLog;
import com.holzyn.actor.domain.debug.repository.ActorAiCallLogRepository;
import com.holzyn.actor.domain.usage.service.TestUsageLogService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 调用 trace 埋点服务（调试前端埋点，异步写库）。
 * <p>职责：承载 {@link AiCallTrace} 的全生命周期——Router 埋点经
 * {@link #begin(Long)} 取到当前上下文（或创建兜底 trace）、{@link #end(AiCallTrace)}
 * 提交兜底 trace；{@link AiCallContext#run} 块结束经注册的 sink 统一提交；
 * 提交为<b>虚拟线程异步写库</b>，写库失败仅告警不阻断主业务流程（对话/生成照常返回）。</p>
 * <p><b>AI 调用详情 JSON</b>：落库时把
 * {@code AiCallTrace.toDetailJson()} 组装的全量详情 JSON 一并写入 {@code actor_ai_call_log.detail_json}
 * 列（LONGTEXT）；同时以同名 JSON 文件输出到 {@code <data-dir>/ai-call-logs/}（命名
 * {@code ai-call-{id}-{scene}.json}，pretty 打印便于人工查看）——落库 + 文件双份存储。
 * 文件输出开关 {@code holzyn.actor.debug.ai-log.file.enabled}（默认 true）；</p>
 * <p>开关/截断：<ul>
 * <li>{@code holzyn.actor.debug.ai-log.enabled}（默认 true）：false 时全部跳过不落库；</li>
 * 截断上限，防超长内容撑爆库。</li>
 * </ul></p>
 * <p><b>长跑测试用量明细（，表 actor_test_usage_log）</b>：本方法是全仓库 AI 调用的
 * 唯一汇聚点，因此长跑计量在此挂第二个 sink —— {@link TestUsageLogService}（独立开关
 * {@code holzyn.actor.test-usage.enabled}、独立虚拟线程、system/user/response <b>全文不截断</b>、
 * 失败仅告警）。该表是<b>测试设施而非领域概念</b>，详见 {@code TestUsageLogService} 与
 * {@code TestUsageLog} 的类注释。</p>
 */
@Slf4j
@Service
public class AiCallTraceService {

    /** trace 仓库 */
    private final ActorAiCallLogRepository repository;

    /** 埋点总开关（@Value 字段注入：false 时全部跳过不落库） */
    @Value("${holzyn.actor.debug.ai-log.enabled:true}")
    private boolean enabled = true;

    /** 单段 Prompt/Response 截断上限（字符，@Value 字段注入） */
    @Value("${holzyn.actor.debug.ai-log.max-prompt-chars:20000}")
    private int maxPromptChars = 20_000;

    /** 详情 JSON 文件输出开关（@Value 字段注入，默认 true；字段初值 false 仅用于直接构造的测试场景不落文件） */
    @Value("${holzyn.actor.debug.ai-log.file.enabled:true}")
    private boolean fileEnabled = false;

    /** 本地数据目录（@Value 字段注入，取 holzyn.actor.data-dir；文件输出根 = <data-dir>/ai-call-logs） */
    @Value("${holzyn.actor.data-dir:./data}")
    private String dataDir = null;

    /** Jackson 序列化器（详情 JSON 文件 pretty 打印用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 异步写库执行器：虚拟线程（每次提交新建虚拟线程，IO 密集型轻量） */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 在途写库任务计数（供测试 awaitIdle / 状态展示） */
    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * 长跑「测试专用」用量明细写入器（可选注入）。
     * <p>为什么是字段注入 + {@code required = false}：本类的构造签名（含测试专用构造）被大量
     * 单测直接使用，架构纪律要求<b>不得修改既有构造签名</b>；且单测直接 {@code new} 本类时
     * 该字段保持 null = 不记录明细，与本类「测试构造不落文件」的既有做法一致。</p>
     * <p>开关在 {@link TestUsageLogService} 内部（{@code holzyn.actor.test-usage.enabled}，
     * 默认 false），与本类 {@code debug.ai-log.enabled} 相互独立：关掉调试埋点也照样能留测量数据。</p>
     */
    @Autowired(required = false)
    private TestUsageLogService testUsageRecorder;

    /**
     * 构造（Spring 使用：仓库注入，开关/截断/数据目录由 @Value 字段注入）。
     *
     * @param repository trace 仓库
     */
    @Autowired
    public AiCallTraceService(ActorAiCallLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 测试专用构造：显式指定开关与截断上限（不触发 Spring 配置注入；文件输出默认关闭）。
     *
     * @param repository trace 仓库（测试可传 mock/内存实现）
     *
     * @param enabled 埋点总开关
     *
     * @param maxPromptChars 单段截断上限
     */
    AiCallTraceService(ActorAiCallLogRepository repository, boolean enabled, int maxPromptChars) {
        this(repository, enabled, maxPromptChars, false, null);
    }

    /**
     * 测试专用构造（文件输出测试用）：显式指定全部参数。
     *
     * @param repository trace 仓库
     *
     * @param enabled 埋点总开关
     *
     * @param maxPromptChars 单段截断上限
     *
     * @param fileEnabled 详情 JSON 文件输出开关
     *
     * @param dataDir 文件输出根目录（null=不输出）
     */
    AiCallTraceService(ActorAiCallLogRepository repository, boolean enabled, int maxPromptChars,
                       boolean fileEnabled, String dataDir) {
        this.repository = repository;
        this.enabled = enabled;
        this.maxPromptChars = Math.max(0, maxPromptChars);
        this.fileEnabled = fileEnabled;
        this.dataDir = dataDir;
    }

    /**
     * 启动后注册落库 sink：AiCallContext.run 块结束的 trace 经此异步落库。
     */
    @PostConstruct
    void registerSink() {
        AiCallContext.setSink(this::submitAsync);
    }

    /** 埋点开关（供概览页展示） */
    public boolean isEnabled() {
        return enabled;
    }

    /** 单段截断上限（供概览页展示） */
    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    /**
     * 开始一次 trace（Router 埋点调用）。
     * <ul>
     * <li>当前线程有 {@link AiCallContext}：复用其 trace 并回填 projectId（块尾由 run 统一提交）；</li>
     * <li>无上下文：创建兜底 trace（scene=通用值），由 {@link #end(AiCallTrace)} 提交；
     * 兜底 trace 继承当前业务动作级 trace_id（B5，withTrace 包裹时）。</li>
     * </ul>
     *
     * @param projectId 项目 ID（无项目调用=null）
     *
     * @return 本次调用的 trace
     */
    public AiCallTrace begin(Long projectId) {
        AiCallTrace current = AiCallContext.current();
        if (current != null) {
            current.setProjectId(projectId);
            current.markInvoked();
            return current;
        }
        AiCallTrace trace = new AiCallTrace("chat", null);
        trace.setContextOwned(false);
        trace.setProjectId(projectId);
        trace.setTraceId(AiCallContext.currentTraceId());
        trace.markInvoked();
        return trace;
    }

    /**
     * 开始一次兜底 trace（Router finally 调用）：仅提交「非上下文」trace，
     * 上下文 trace 由 AiCallContext.run 的 finally 提交，避免双写。
     *
     * @param trace 本次调用的 trace
     */
    public void end(AiCallTrace trace) {
        if (trace != null && !trace.isContextOwned()) {
            submitAsync(trace);
        }
    }

    /**
     * 创建指定场景的全新 trace（不关联当前 AiCallContext，contextOwned=false，由 {@link #end} 提交）。
     * <p>A5：供非对话类 AI 调用（如 embedding 向量化）使用——保证每次调用一条独立 trace、
     * 场景固定（如 embedding），即使在循环/多批调用中也不会共享同一上下文 trace 相互覆盖。
     * B5：继承当前业务动作级 trace_id（withTrace 包裹时，embedding 调用也挂到决策链路）。</p>
     *
     * @param projectId 项目 ID（无项目调用=null）
     *
     * @param scene 调用场景（embedding 等；null 回退 chat）
     *
     * @return 本次调用的 trace
     */
    public AiCallTrace beginScene(Long projectId, String scene) {
        AiCallTrace trace = new AiCallTrace(scene == null ? "chat" : scene, null);
        trace.setContextOwned(false);
        trace.setProjectId(projectId);
        trace.setTraceId(AiCallContext.currentTraceId());
        trace.markInvoked();
        return trace;
    }

    /**
     * 异步落库（虚拟线程）：未调用过 Provider 的空 trace、开关关闭、已提交过的 trace 均跳过。
     * <p>落库同时把详情 JSON（detail_json 列内容）以同名 JSON 文件输出到
     * {@code <data-dir>/ai-call-logs/}（落库 + 文件双份存储，均失败仅告警不阻断主流程）。</p>
     *
     * @param trace 本次调用的 trace
     */
    public void submitAsync(AiCallTrace trace) {
        if (trace == null || !trace.isInvoked() || trace.isSubmitted()) {
            return;
        }
        // 长跑「测试专用」用量明细（独立开关 + 独立虚拟线程 + 失败仅告警）。
        // 位置在 enabled 门控之前：关掉调试埋点（debug.ai-log.enabled=false）也照样留测量数据；
        // usageRecorded 标志保证「一次调用最多一条测量记录」（埋点关闭时 submitted 不会置位，故单列一标志）。
        if (testUsageRecorder != null && !trace.isUsageRecorded()) {
            trace.setUsageRecorded(true);
            testUsageRecorder.recordAsync(trace);
        }
        if (!enabled) {
            // 与既有语义一致：埋点关闭时不置 submitted 标志（保持本方法在关闭态完全无副作用）
            return;
        }
        trace.setSubmitted(true);
        inFlight.incrementAndGet();
        executor.submit(() -> {
            try {
                ActorAiCallLog saved = repository.save(trace.toEntity(maxPromptChars));
                // mock 仓库可能返回 null（测试/异常路径），回退到入参实体
                if (saved == null) {
                    saved = trace.toEntity(maxPromptChars);
                }
                writeDetailFile(saved);
            } catch (Exception e) {
                // 埋点写库失败不影响主流程，仅告警
                log.warn("[AI-Trace] 写入 actor_ai_call_log 失败（不影响主流程）: {}", e.getMessage());
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    /**
     * 把 AI 调用详情 JSON 以文件形式输出（，与 detail_json 列同内容，pretty 打印便于人工查看）。
     * <p>位置/命名：{@code <data-dir>/ai-call-logs/ai-call-{id}-{scene}.json}
     * （id 缺失时回退时间戳；scene 做文件名安全化，非法字符替换为下划线）。</p>
     *
     * @param saved 已落库（或待落库）的实体（含 detailJson）
     */
    private void writeDetailFile(ActorAiCallLog saved) {
        if (!fileEnabled || dataDir == null || dataDir.isBlank() || saved == null
                || saved.getDetailJson() == null || saved.getDetailJson().isBlank()) {
            return;
        }
        try {
            Path dir = Path.of(dataDir).resolve("ai-call-logs");
            Files.createDirectories(dir);
            String idPart = saved.getId() != null ? String.valueOf(saved.getId()) : "t" + System.currentTimeMillis();
            String scene = sanitizeFileName(saved.getScene());
            Path file = dir.resolve("ai-call-" + idPart + "-" + scene + ".json");
            String pretty;
            try {
                pretty = MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(MAPPER.readTree(saved.getDetailJson()));
            } catch (Exception e) {
                // 详情 JSON 非合法 JSON（理论不发生）：原样写入
                pretty = saved.getDetailJson();
            }
            Files.writeString(file, pretty, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 文件输出失败不影响主流程，仅告警
            log.warn("[AI-Trace] 写入 detail_json 文件失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 文件名安全化：保留字母/数字/下划线/连字符，其余替换为下划线；空值回退 unknown */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /**
     * 等待在途写库任务排空（测试断言用；超时后返回，不阻塞业务）。
     *
     * @param timeoutMs 最大等待毫秒
     */
    public void awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
