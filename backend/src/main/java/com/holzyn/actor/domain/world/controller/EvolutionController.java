package com.holzyn.actor.domain.world.controller;

import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.common.R;
import com.holzyn.actor.domain.world.service.WorldEvolutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 世界演化控制器（V2.1 世界演化增强）。
 * <p>职责：提供世界演化会话的创建（手动/AI 自动选择场景背景角色）、逐轮推进、
 * 连续演化 SSE 流式播放（vP5-7.9，群聊式）、手动加入/退场角色、结束归档
 * （事件入时间线 + 角色级记忆隔离）与列表/详情查询。</p>
 * <p>所属模块：controller/world（世界演化子域）</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EvolutionController {

    /** 世界演化服务 */
    private final WorldEvolutionService evolutionService;

    /** 当前用户解析器 */
    private final CurrentUserProvider currentUserProvider;

    /** 流式任务执行器：虚拟线程，不阻塞请求线程 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 演化流式超时（30 分钟：连续演化持续播放需长超时，连接活跃时由事件保活） */
    private static final long STREAM_TIMEOUT_MS = 1_800_000L;

    /**
     * 项目演化会话列表。
     *
     * @param projectId 项目 ID
     * @return 演化会话摘要列表
     */
    @GetMapping("/projects/{projectId}/evolutions")
    public R<List<Map<String, Object>>> list(@PathVariable("projectId") Long projectId) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(evolutionService.list(userId, projectId));
    }

    /**
     * 开始一次世界演化。
     *
     * @param projectId 项目 ID
     * @param body      入参：{mode? manual/ai, sceneId?, background?, characterIds?}
     * @return 演化会话详情（含参与者）
     */
    @PostMapping("/projects/{projectId}/evolutions")
    public R<Map<String, Object>> start(@PathVariable("projectId") Long projectId,
                                        @RequestBody(required = false) Map<String, Object> body) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(evolutionService.start(userId, projectId, body));
    }

    /**
     * 演化会话详情（含参与者与轮次消息）。
     *
     * @param id 演化会话 ID
     * @return 详情视图
     */
    @GetMapping("/evolutions/{id}")
    public R<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return R.ok(evolutionService.detail(id));
    }

    /**
     * 连续演化 SSE 流式播放（vP5-7.9 群聊式）：
     * 后端循环「调度选角 → 该角色流式发言/行动 → 可选场景变化/加入退场」，持续到用户停止或场景只剩 1 人。
     * 事件：schedule（调度决策）/ message-start（角色开始）/ token（增量）/ done（本拍完成）/
     * system（场景变化/加入退场）/ finished（自动收尾归档）/ error。
     *
     * @param id 演化会话 ID
     * @return SSE 发射器
     */
    @GetMapping("/evolutions/{id}/stream")
    public SseEmitter stream(@PathVariable("id") Long id) {
        Long userId = currentUserProvider.currentUserId();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean alive = new AtomicBoolean(true);
        emitter.onCompletion(() -> alive.set(false));
        emitter.onTimeout(() -> alive.set(false));
        emitter.onError(e -> alive.set(false));
        executor.execute(() -> evolutionService.stream(emitter, id, userId, alive));
        return emitter;
    }

    /**
     * 推进一轮世界演化（AI 编排角色言行 + 加入/退场 + 收尾判定；保留的单轮接口，前端连续播放走 stream）。
     *
     * @param id 演化会话 ID
     * @return 本轮结果
     */
    @PostMapping("/evolutions/{id}/turn")
    public R<Map<String, Object>> turn(@PathVariable("id") Long id) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(evolutionService.turn(userId, id));
    }

    /**
     * 手动加入一位角色。
     *
     * @param id   演化会话 ID
     * @param body 入参：{characterId}
     * @return 成功响应
     */
    @PostMapping("/evolutions/{id}/join")
    public R<Void> join(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        Long userId = currentUserProvider.currentUserId();
        Long characterId = body == null ? null : asLong(body.get("characterId"));
        if (characterId == null) {
            throw new IllegalArgumentException("请指定要加入的角色");
        }
        evolutionService.join(userId, id, characterId);
        return R.ok(null);
    }

    /**
     * 手动退场一位角色。
     *
     * @param id   演化会话 ID
     * @param body 入参：{characterId}
     * @return 成功响应
     */
    @PostMapping("/evolutions/{id}/leave")
    public R<Void> leave(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        Long userId = currentUserProvider.currentUserId();
        Long characterId = body == null ? null : asLong(body.get("characterId"));
        if (characterId == null) {
            throw new IllegalArgumentException("请指定要退场的角色");
        }
        evolutionService.leave(userId, id, characterId);
        return R.ok(null);
    }

    /**
     * 结束演化并归档（事件入时间线 + 参与者角色级记忆隔离）。
     *
     * @param id 演化会话 ID
     * @return {event, memoryCount}
     */
    @PostMapping("/evolutions/{id}/finish")
    public R<Map<String, Object>> finish(@PathVariable("id") Long id) {
        Long userId = currentUserProvider.currentUserId();
        return R.ok(evolutionService.finish(userId, id));
    }

    /**
     * 删除演化会话（级联清理参与者与轮次消息；归档事件保留在时间线）。
     *
     * @param id 演化会话 ID
     * @return 成功响应
     */
    @DeleteMapping("/evolutions/{id}")
    public R<Void> delete(@PathVariable("id") Long id) {
        Long userId = currentUserProvider.currentUserId();
        evolutionService.delete(userId, id);
        return R.ok(null);
    }

    /**
     * Long 取值辅助。
     */
    private Long asLong(Object v) {
        if (v == null) return null;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }
}
