package com.holzyn.actor.common;

import com.holzyn.actor.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通知占位控制器（P4+ 实现）。
 * <p>职责：提供 /api/notifications 占位接口（返回空数据），消除前端铃铛组件在 actor
 * 模块未实现通知时的 404 噪音；P4 阶段对接 web 通知中心与 Redis 实时推送。</p>
 * <p>所属模块：controller/common（通用控制器）</p>
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    /**
     * 通知列表（占位：返回空列表）。
     *
     * @param page 页码（默认 1）
     * @param size 每页条数（默认 8）
     * @return 空通知列表与总数 0
     */
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(name = "page", defaultValue = "1") int page,
                                       @RequestParam(name = "size", defaultValue = "8") int size) {
        return R.ok(Map.of("list", List.of(), "total", 0, "page", page, "size", size));
    }

    /**
     * 未读通知数（占位：恒为 0）。
     *
     * @return 未读数
     */
    @GetMapping("/unread-count")
    public R<Integer> unreadCount() {
        return R.ok(0);
    }

    /**
     * 标记单条通知已读（占位）。
     *
     * @param id 通知主键
     * @return 标记结果
     */
    @PutMapping("/{id}/read")
    public R<Map<String, Object>> markRead(@PathVariable("id") Long id) {
        return R.ok(Map.of("id", id, "read", true));
    }

    /**
     * 全部标记已读（占位）。
     *
     * @return 标记结果
     */
    @PutMapping("/read-all")
    public R<Map<String, Object>> markAllRead() {
        return R.ok(Map.of("readAll", true));
    }
}
