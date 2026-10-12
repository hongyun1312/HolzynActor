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

package com.holzyn.actor.domain.worldgen.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.debug.service.AiCallContext;
import com.holzyn.actor.domain.worldgen.config.WorldGenProperties;
import com.holzyn.actor.domain.worldgen.model.WorldGenStepType;
import com.holzyn.actor.domain.worldgen.vo.WorldGenPreviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实 Script 节点执行器。
 * <p>职责：对 script 节点执行「参数解析（填充后 script_args）→ 转换器分发 → 产物/日志」：</p>
 * <ul>
 * <li><b>local_archive</b>→ {@link LocalArchiveService#checkpoint}：校验 6 个 world/*.json 检查点
 * （存在且非空，断点不完整则节点失败，防下游渲染残缺产物）；</li>
 * <li><b>render_worldview</b>→ {@link WorldGenRenderer#render}：6 维 JSON 合并渲染
 * <li><b>persist_to_world_setting</b>→ {@link WorldGenPersistService#preview}：生成 6 维→分节/地点
 * 映射<b>预览</b>作为节点输出（不落库；确认由用户经 POST /confirm 触发，防未确认覆盖世界观）。</li>
 * </ul>
 * <p>全部脚本节点经 {@link AiCallContext#recordStep} 记录非 AI 环节到决策链路
 * （scene=worldgen，trace_id=taskId 贯穿节点/尝试）。开关关闭时回退桩占位输出。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorldGenScriptNodeExecutor implements WorldGenNodeExecutor {

    /** 脚本名：检查点存档（local_archive.py checkpoint 语义） */
    static final String SCRIPT_CHECKPOINT = "local_archive";
    /** 脚本名：合并渲染世界观文档（render_worldview.py 语义） */
    static final String SCRIPT_RENDER = "render_worldview";
    /** 脚本名：映射落库世界观分节 */
    static final String SCRIPT_PERSIST = "persist_to_world_setting";
    /** 脚本名：角色阵容检查点 */
    static final String SCRIPT_PERSIST_ROSTER = "persist_character_roster";
    /** 脚本名：角色落库 actor_profile */
    static final String SCRIPT_PERSIST_CHARACTERS = "persist_character_profiles";
    /** 脚本名：故事引擎落库 actor_story_plan */
    static final String SCRIPT_PERSIST_STORY_ENGINE = "persist_story_engine";
    /** 脚本名：风格档案落库 + 世界氛围提取 */
    static final String SCRIPT_PERSIST_STYLE_PROFILE = "persist_style_profile";

    /** 静态 JSON 解析器（Jackson 3 / tools.jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LocalArchiveService archiveService;
    private final WorldGenRenderer renderer;
    private final WorldGenPersistService persistService;
    private final WorldGenProperties props;

    /**
     * character 工作流专用服务（字段注入——保持构造器签名不变，既有测试零破坏；
     * 直接 new 时为空 → character 脚本分支报「未注册脚本」而非 NPE）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldGenCharacterService characterService;

    /**
     * story-plan 工作流专用服务（story-plan 工作流 _06 字段注入——保持构造器
     * 签名不变；直接 new 时为空 → story-plan 脚本分支报「未注册脚本」而非 NPE）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WorldGenStoryPlanService storyPlanService;

    /**
     * 本执行器精确接管 script 类型节点。
     */
    @Override
    public String supportedNodeType() {
        return WorldGenStepType.SCRIPT;
    }

    /**
     * 执行 script 节点（按 scriptName 分发）。
     *
     * @param ctx 节点执行上下文
     *
     * @return 执行结果（成功=脚本产物 JSON；失败=错误信息）
     */
    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        long start = System.currentTimeMillis();
        try {
            // 模拟失败钩子
            if (ctx.simulateFailNodeIds() != null && ctx.simulateFailNodeIds().contains(ctx.node().getId())) {
                String msg = "模拟失败（simulateFailNodeIds 命中）：node=" + ctx.node().getId();
                log.info("[worldgen] {}", msg);
                return NodeExecutionResult.failure(msg);
            }
            // 总开关关闭 → 桩占位输出（空跑冒烟）
            if (!props.isEnabled()) {
                return stubPlaceholder(ctx);
            }
            String scriptName = ctx.node().getScriptName();
            Map<String, List<String>> args = parseArgs(ctx.filledInput());
            Path workspace = archiveService.taskWorkspace(ctx.projectId(), ctx.taskId());

            Map<String, Object> out = new LinkedHashMap<>();
            String detail;
            switch (scriptName == null ? "" : scriptName) {
                case SCRIPT_CHECKPOINT -> {
                    List<String> files = args.getOrDefault("--files", List.of());
                    int count = archiveService.checkpoint(workspace, files);
                    out.put("checkpoint", true);
                    out.put("filesChecked", count);
                    out.put("files", files);
                    detail = "检查点校验通过：" + count + " 个维度文件";
                }
                case SCRIPT_RENDER -> {
                    String title = first(args, "--title", "世界观基础");
                    WorldGenRenderer.RenderResult result = renderer.render(workspace, title);
                    out.put("rendered", true);
                    out.put("sections", result.sections());
                    out.put("outputPath", result.outputPath());
                    out.put("outputLength", result.markdown().length());
                    detail = "合并渲染完成：" + result.sections() + " 个维度 → " + result.outputPath()
                            + "（" + result.markdown().length() + " 字符）";
                }
                case SCRIPT_PERSIST -> {
                    WorldGenPreviewVO preview = persistService.preview(ctx.taskId());
                    out.put("preview", true);
                    out.put("taskId", ctx.taskId());
                    out.put("projectId", ctx.projectId());
                    out.put("dimensions", preview.getDimensions().size());
                    out.put("sections", preview.getSections());
                    out.put("locations", preview.getLocations());
                    out.put("confirmEndpoint", "/api/worldgen/tasks/" + ctx.taskId() + "/confirm");
                    detail = "6 维映射预览完成（待确认落库）：分节 " + preview.getSections().size()
                            + " 字段，地点候选 " + preview.getLocations().size() + " 个";
                }
                case SCRIPT_PERSIST_ROSTER -> {
                    Map<String, Object> roster = characterService.persistRoster(ctx.taskId());
                    out.put("rosterChecked", roster.get("rosterChecked"));
                    detail = "角色阵容检查点通过：" + roster.get("rosterChecked") + " 位角色";
                }
                case SCRIPT_PERSIST_CHARACTERS -> {
                    Map<String, Object> persisted = characterService.persistCharacters(ctx.taskId());
                    out.put("created", persisted.get("created"));
                    out.put("skipped", persisted.get("skipped"));
                    out.put("characters", persisted.get("characters"));
                    detail = "角色落库完成：新建 " + persisted.get("created")
                            + " 位（跳过已存在 " + persisted.get("skipped") + " 位）";
                }
                case SCRIPT_PERSIST_STORY_ENGINE -> {
                    Map<String, Object> engine = storyPlanService.persistStoryEngine(ctx.taskId());
                    out.put("version", engine.get("version"));
                    out.put("created", engine.get("created"));
                    detail = "故事引擎落库完成：版本 v" + engine.get("version")
                            + "（" + (Boolean.TRUE.equals(engine.get("created")) ? "新建" : "复用更新") + "）";
                }
                case SCRIPT_PERSIST_STYLE_PROFILE -> {
                    Map<String, Object> style = storyPlanService.persistStyleProfile(ctx.taskId());
                    out.put("version", style.get("version"));
                    out.put("atmosphereLength", style.get("atmosphereLength"));
                    detail = "风格档案落库完成：版本 v" + style.get("version")
                            + "，世界氛围片段 " + style.get("atmosphereLength") + " 字";
                }
                default -> throw new BizException(400, "未注册脚本：" + scriptName);
            }

            // trace 贯穿：脚本环节作为非 AI 步骤记录（scene=worldgen，trace_id=taskId）
            AiCallContext.withTrace(String.valueOf(ctx.taskId()), () -> {
                AiCallContext.recordStep(ctx.projectId(), "worldgen", "script", detail,
                        System.currentTimeMillis() - start);
                return null;
            });

            long ms = System.currentTimeMillis() - start;
            log.info("[worldgen] 脚本节点成功：task={}, node={}, script={}, {}ms",
                    ctx.taskId(), ctx.node().getId(), scriptName, ms);
            return NodeExecutionResult.success(MAPPER.writeValueAsString(out), ms);
        } catch (Exception e) {
            log.warn("[worldgen] 脚本节点执行异常：task={}, node={}, error={}",
                    ctx.taskId(), ctx.node().getId(), e.getMessage());
            return NodeExecutionResult.failure("脚本节点执行失败："
                    + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    // ==================== 参数解析 ====================

    /**
     * 解析填充后的 script_args（简单 CLI 风格：--key value1 value2 ...，遇下一个 -- 结束）。
     *
     * @param argsText 填充占位符后的参数文本
     *
     * @return --key → 值列表（保持顺序）
     */
    static Map<String, List<String>> parseArgs(String argsText) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (argsText == null || argsText.isBlank()) {
            return out;
        }
        String[] tokens = argsText.trim().split("\\s+");
        String current = null;
        for (String t : tokens) {
            if (t.startsWith("--")) {
                current = t;
                out.computeIfAbsent(current, k -> new ArrayList<>());
            } else if (current != null) {
                out.get(current).add(t);
            }
        }
        return out;
    }

    /** 取参数首个值（缺省回退默认）。 */
    private String first(Map<String, List<String>> args, String key, String def) {
        List<String> v = args.get(key);
        return v == null || v.isEmpty() ? def : v.get(0);
    }

    /**
     * 开关关闭时的桩占位输出。
     */
    private NodeExecutionResult stubPlaceholder(NodeExecutionContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stub", true);
        out.put("script", ctx.node().getScriptName());
        out.put("scriptArgs", ctx.filledInput());
        out.put("ran", true);
        try {
            return NodeExecutionResult.success(MAPPER.writeValueAsString(out), 0L);
        } catch (Exception e) {
            return NodeExecutionResult.success("{\"stub\":true}", 0L);
        }
    }
}
