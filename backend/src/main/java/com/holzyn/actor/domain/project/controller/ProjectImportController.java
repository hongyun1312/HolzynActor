package com.holzyn.actor.domain.project.controller;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.project.dto.ProjectImportDTO;
import com.holzyn.actor.domain.project.vo.ProjectImportPreviewVO;
import com.holzyn.actor.domain.project.vo.ProjectVO;
import com.holzyn.actor.domain.project.service.ProjectImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件导入建项目控制器。
 * <p>职责：提供上传解析（multipart 多文件 txt/md）、AI 自动生成角色、确认创建三类接口；
 * 文件仅读取文本内存解析，不落盘存储；归属一律以当前会话用户为准。</p>
 * <p>所属模块：controller/importer（导入子域）</p>
 */
@RestController
@RequestMapping("/api/projects/import")
@Slf4j
@RequiredArgsConstructor
public class ProjectImportController {

    /** 导入服务 */
    private final ProjectImportService importService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /** SSE 解析执行器（异步执行解析并逐阶段推送进度，避免阻塞请求线程） */
    private final ExecutorService parseExecutor = Executors.newFixedThreadPool(2);

    /** JSON 序列化器（SSE 事件数据） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 上传并解析文件：AI 结构化提取项目/世界观/角色，返回预览。
     *
     * @param files 多文件（txt/md，单文件 ≤5MB）
     * @return 预览 VO
     */
    @PostMapping("/parse")
    public R<ProjectImportPreviewVO> parse(@RequestParam("files") MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BizException(400, "请至少上传一个文件");
        }
        List<String> texts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                continue;
            }
            String name = f.getOriginalFilename();
            if (!isTextFile(name)) {
                throw new BizException(400, "仅支持 txt / md / markdown 文本文件：" + name);
            }
            try {
                byte[] bytes = f.getBytes();
                texts.add(new String(bytes, StandardCharsets.UTF_8));
                // 调试日志：文件名/类型/大小
                String type = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "unknown";
                log.info("[文件导入] 收到上传文件：name={}, type={}, size={}B, chars={}",
                        name, type, bytes.length, texts.get(texts.size() - 1).length());
            } catch (Exception e) {
                throw new BizException(400, "文件读取失败：" + name);
            }
            names.add(name);
        }
        if (texts.isEmpty()) {
            throw new BizException(400, "未读取到有效文件内容");
        }
        return R.ok(importService.parse(currentUserProvider.currentUserId(), texts, names));
    }

    /**
     * 上传并解析文件（SSE 流式）：逐阶段推送 progress 事件，完成后推送 result 事件，失败推送 error 事件。
     * <p>前端用 fetch + ReadableStream 消费；进度：1 世界观初稿 / 2-6 五大字段 / 7 自由文本 / 8 角色提取。</p>
     *
     * @param files 多文件（txt/md，单文件 ≤5MB）
     * @return SSE 事件流（text/event-stream）
     */
    @PostMapping(value = "/parse/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter parseStream(@RequestParam("files") MultipartFile[] files) {
        // 不限时：程序持续输出即可一直等待（贴合「只要还在正常输出就重置超时」）
        SseEmitter emitter = new SseEmitter(0L);
        // 关键：必须在请求线程获取 userId（SecurityContext 是 ThreadLocal，不会自动传入异步线程）
        Long userId = currentUserProvider.currentUserId();
        parseExecutor.execute(() -> {
            try {
                if (files == null || files.length == 0) {
                    throw new BizException(400, "请至少上传一个文件");
                }
                List<String> texts = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (MultipartFile f : files) {
                    if (f == null || f.isEmpty()) {
                        continue;
                    }
                    String name = f.getOriginalFilename();
                    if (!isTextFile(name)) {
                        throw new BizException(400, "仅支持 txt / md / markdown 文本文件：" + name);
                    }
                    try {
                        byte[] bytes = f.getBytes();
                        texts.add(new String(bytes, StandardCharsets.UTF_8));
                        String type = name.contains(".") ? name.substring(name.lastIndexOf(".") + 1) : "unknown";
                        log.info("[文件导入] 收到上传文件：name={}, type={}, size={}B, chars={}",
                                name, type, bytes.length, texts.get(texts.size() - 1).length());
                    } catch (Exception e) {
                        throw new BizException(400, "文件读取失败：" + name);
                    }
                    names.add(name);
                }
                if (texts.isEmpty()) {
                    throw new BizException(400, "未读取到有效文件内容");
                }
                // 进度回调：每完成一个阶段推送 progress 事件
                ProjectImportService.ImportProgress progress = (done, total, label, chars) -> {
                    try {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("done", done);
                        data.put("total", total);
                        data.put("label", label);
                        data.put("chars", chars);
                        emitter.send(SseEmitter.event().name("progress")
                                .data(objectMapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        log.warn("[文件导入-SSE] 进度推送失败: {}", e.getMessage());
                    }
                };
                ProjectImportPreviewVO preview = importService.parse(userId, texts, names, progress);
                emitter.send(SseEmitter.event().name("result")
                        .data(objectMapper.writeValueAsString(preview), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                try {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("message", e.getMessage() == null ? "解析失败" : e.getMessage());
                    emitter.send(SseEmitter.event().name("error")
                            .data(objectMapper.writeValueAsString(err), MediaType.APPLICATION_JSON));
                } catch (Exception ex) {
                    log.warn("[文件导入-SSE] 错误推送失败: {}", ex.getMessage());
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    /**
     * AI 自动生成符合世界观的角色档案（预览「AI 自动生成」补充）。
     *
     * @param body 请求体：{projectName?, worldSettingFreeText?, count?}
     * @return 角色档案列表
     */
    @PostMapping("/characters/generate")
    public R<List<ProjectImportDTO.CharacterPart>> generateCharacters(@RequestBody(required = false) ProjectImportDTO.GenerateCharactersRequest body) {
        String projectName = body == null ? null : body.projectName();
        String worldFreeText = body == null ? null : body.worldSettingFreeText();
        Integer count = body == null ? null : body.count();
        return R.ok(importService.generateCharacters(currentUserProvider.currentUserId(), projectName, worldFreeText, count));
    }

    /**
     * 确认创建：一次事务创建项目 + 世界观 + 角色档案。
     *
     * @param dto 确认后的完整结构
     * @return 创建后的项目 VO
     */
    @PostMapping("/confirm")
    public R<ProjectVO> confirm(@RequestBody ProjectImportDTO.Confirm dto) {
        return R.ok(importService.confirmCreate(currentUserProvider.currentUserId(), dto));
    }

    /**
     * 判断是否为文本文件（txt/md/markdown）。
     *
     * @param name 文件名
     * @return true 表示支持
     */
    private boolean isTextFile(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown");
    }
}
