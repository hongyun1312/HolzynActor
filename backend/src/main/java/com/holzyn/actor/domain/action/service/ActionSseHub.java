package com.holzyn.actor.domain.action.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行动事件 SSE 广播中心。
 * <p>职责：维护「行动时间线」页的 SSE 订阅者集合，行动引擎模拟执行完成后向所有订阅者
 * 广播 action 事件，前端时间线实时刷新（无需轮询）。</p>
 * <p>所属模块：service/action（行动子域）</p>
 */
@Slf4j
@Component
public class ActionSseHub {

    /** 订阅者集合（并发安全） */
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    /**
     * 订阅行动事件流（GET /api/actions/stream 使用）。
     *
     * @return SSE 发射器（无超时，连接保持至页面关闭）
     */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        // 订阅即推送心跳注释，避免代理缓冲导致连接不建立
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("message", "行动时间线已订阅")));
        } catch (Exception ignored) {
            // 忽略连接建立失败
        }
        return emitter;
    }

    /**
     * 向全部订阅者广播行动事件。
     *
     * @param data 事件数据（type=action / planId / characterId / action / status / time）
     */
    public void broadcast(Map<String, Object> data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("action").data(data));
            } catch (Exception e) {
                // 连接断开：移除订阅者
                emitters.remove(emitter);
            }
        }
    }
}