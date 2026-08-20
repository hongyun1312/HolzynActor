package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.world.dto.WorldLocationDTO;
import com.holzyn.actor.domain.world.service.WorldLocationService;
import com.holzyn.actor.domain.world.vo.WorldLocationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界观地点表控制器。
 * <p>职责：提供项目级地点 CRUD 与 AI 提取接口——
 * GET 列表 / POST 新增 / PUT 修改 / DELETE 删除 / PUT batch 编辑模式全量保存 / POST extract AI 提取合并。
 * 归属一律以项目维度（本地单用户）。</p>
 * <p>所属模块：domain/world（世界域-地点子域）</p>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/world-locations")
@Slf4j
@RequiredArgsConstructor
public class WorldLocationController {

    private final WorldLocationService worldLocationService;
    private final CurrentUserProvider currentUserProvider;

    /** 查询项目全部地点 */
    @GetMapping
    public R<List<WorldLocationVO>> list(@PathVariable("projectId") Long projectId) {
        return R.ok(worldLocationService.list(projectId));
    }

    /** 新增地点 */
    @PostMapping
    public R<WorldLocationVO> create(@PathVariable("projectId") Long projectId, @RequestBody WorldLocationDTO dto) {
        return R.ok(worldLocationService.create(projectId, dto));
    }

    /** 修改地点 */
    @PutMapping("/{id}")
    public R<WorldLocationVO> update(@PathVariable("projectId") Long projectId, @PathVariable("id") Long id,
                                     @RequestBody WorldLocationDTO dto) {
        return R.ok(worldLocationService.update(projectId, id, dto));
    }

    /** 删除地点 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("projectId") Long projectId, @PathVariable("id") Long id) {
        worldLocationService.delete(projectId, id);
        return R.ok(null);
    }

    /** 编辑模式全量保存（增删改查一体，按列表顺序整体替换） */
    @PutMapping("/batch")
    public R<List<WorldLocationVO>> batchReplace(@PathVariable("projectId") Long projectId,
                                                 @RequestBody List<WorldLocationDTO> items) {
        return R.ok(worldLocationService.batchReplace(projectId, items));
    }

    /** AI 重新提取地点（基于已存地理设定文本，与现有地点合并追加） */
    @PostMapping("/extract")
    public R<List<WorldLocationVO>> extract(@PathVariable("projectId") Long projectId) {
        return R.ok(worldLocationService.extractAndMerge(currentUserProvider.currentUserId(), projectId));
    }

    /**
     * AI 流式提取地点（SSE）：逐条推送 location 事件（前端每提取完一个就显示一个），
     * 完成后推送 done（{added, total}），失败推送 error。
     * <p>与 /extract 的区别：流式调用 AI，每生成一个完整地点对象立即推送 + 后端逐条日志，
     * 前端无需等全部生成完即可逐条看到结果。</p>
     *
     * @param projectId 项目 ID
     * @return SSE 事件流（text/event-stream）
     */
    @PostMapping(value = "/extract/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter extractStream(@PathVariable("projectId") Long projectId) {
        SseEmitter emitter = new SseEmitter(0L);
        Long userId = currentUserProvider.currentUserId();
        WorldLocationService.LocationProgress progress = new WorldLocationService.LocationProgress() {
            @Override
            public void onStart() {
                send("start", Map.of("message", "AI 提取开始"));
            }

            @Override
            public void onLocation(WorldLocationDTO dto) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("name", nvl(dto.getName()));
                data.put("type", nvl(dto.getType()));
                data.put("intro", nvl(dto.getIntro()));
                data.put("importance", dto.getImportance() == null ? 3 : dto.getImportance());
                send("location", data);
            }

            @Override
            public void onDone(int added, int extracted) {
                send("done", Map.of("added", added, "total", extracted));
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
            worldLocationService.extractAndMergeStream(userId, projectId, progress);
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", e.getMessage() == null ? "地点提取失败" : e.getMessage())));
            } catch (Exception ignored) {
                // 连接已断开
            }
            emitter.complete();
        }
        return emitter;
    }

    /** null 安全取字符串（SSE 事件字段归一） */
    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
