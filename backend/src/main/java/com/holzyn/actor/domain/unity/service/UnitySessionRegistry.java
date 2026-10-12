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

package com.holzyn.actor.domain.unity.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unity 连接会话注册表（U 轨道 U1 服务端对接层，）——连接级状态唯一持有者。
 * <p>职责：每项目 WS 会话集合（多连接容忍，同一项目可多端连接）、上行 seq 幂等对账
 * （断点续传：重复 seq 丢弃、seq 只进不退）、连接活性时间戳（心跳看门狗数据源）、
 * 状态统计（调试页）。</p>
 * <p>语义约定：本注册表为<b>纯内存态</b>（重启丢失可接受——Unity 重连即重新
 * 握手+scene_init，seq 从新连接重新计数；对齐项目「进程内存态重启丢失可接受」惯例）；
 * 连接/断开的<b>激活副作用</b>（世界激活/静止）由 {@link UnityWsHandler} 生命周期回调承担。</p>
 */
@Slf4j
@Component
public class UnitySessionRegistry {

    /** 每项目 WS 会话集合（项目 ID → 会话集合；并发安全） */
    private final ConcurrentHashMap<Long, Set<WebSocketSession>> sessionsByProject = new ConcurrentHashMap<>();

    /** 每项目已接受的最大上行 seq（项目 ID → seq；连接级幂等对账基准） */
    private final ConcurrentHashMap<Long, AtomicLong> lastSeqByProject = new ConcurrentHashMap<>();

    /** 每会话最后活跃时刻（会话 ID → epoch 毫秒；任意消息/发送都 touch） */
    private final ConcurrentHashMap<String, Long> lastActivityBySession = new ConcurrentHashMap<>();

    // ==================== 连接生命周期 ====================

    /**
     * 注册连接（WS 握手成功后调用）。
     *
     * @param projectId 项目 ID
     *
     * @param session WS 会话
     */
    public void register(Long projectId, WebSocketSession session) {
        if (projectId == null || session == null) {
            return;
        }
        sessionsByProject.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).add(session);
        lastSeqByProject.computeIfAbsent(projectId, k -> new AtomicLong(0L));
        touch(session);
        log.info("[Unity] 连接注册: project={} session={} 当前连接数={}", projectId,
                shortId(session), sessionCount(projectId));
    }

    /**
     * 注销连接（连接关闭/失联踢出后调用）。
     *
     * @param projectId 项目 ID
     *
     * @param session WS 会话
     */
    public void unregister(Long projectId, WebSocketSession session) {
        if (projectId == null || session == null) {
            return;
        }
        Set<WebSocketSession> set = sessionsByProject.get(projectId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessionsByProject.remove(projectId);
            }
        }
        lastActivityBySession.remove(session.getId());
        log.info("[Unity] 连接注销: project={} session={} 剩余连接数={}", projectId,
                shortId(session), sessionCount(projectId));
    }

    // ==================== 查询 ====================

    /**
     * 项目当前是否至少有一个 Unity 连接（= 外部时钟权威/独占激活判定依据）。
     *
     * @param projectId 项目 ID
     *
     * @return true=已连接
     */
    public boolean isConnected(Long projectId) {
        if (projectId == null) {
            return false;
        }
        Set<WebSocketSession> set = sessionsByProject.get(projectId);
        return set != null && !set.isEmpty();
    }

    /**
     * 项目当前全部会话（下行推送用；无连接返回空集合）。
     *
     * @param projectId 项目 ID
     *
     * @return 会话列表（不可变快照）
     */
    public List<WebSocketSession> sessionsOf(Long projectId) {
        Set<WebSocketSession> set = sessionsByProject.get(projectId);
        return set == null ? List.of() : List.copyOf(set);
    }

    /**
     * 全部项目的全部会话（心跳广播用）。
     *
     * @return 全部会话列表（不可变快照）
     */
    public List<WebSocketSession> allSessions() {
        return sessionsByProject.values().stream()
                .flatMap(Set::stream)
                .toList();
    }

    /**
     * 指定项目会话数。
     *
     * @param projectId 项目 ID
     *
     * @return 会话数
     */
    public int sessionCount(Long projectId) {
        Set<WebSocketSession> set = sessionsByProject.get(projectId);
        return set == null ? 0 : set.size();
    }

    /**
     * 当前连接的项目数（调试统计）。
     *
     * @return 已连接项目数
     */
    public int connectedProjectCount() {
        return sessionsByProject.size();
    }

    /**
     * 当前总连接数（调试统计）。
     *
     * @return 会话总数
     */
    public int totalSessionCount() {
        return sessionsByProject.values().stream().mapToInt(Set::size).sum();
    }

    // ==================== seq 幂等对账（断点续传） ====================

    /**
     * 上行 seq 幂等对账：seq 必须严格递增（连接级），重复/回退拒绝。
     * <p>断点续传语义：客户端重连后可从断点 seq 续传（服务端已处理过的 seq 会被丢弃）；
     * seq 缺失（null）放行（部分消息类型不要求序号）。</p>
     *
     * @param projectId 项目 ID
     *
     * @param seq 上行序号（可空=放行）
     *
     * @return true=新序号可处理；false=重复/回退（应丢弃）
     */
    public boolean acceptSeq(Long projectId, Long seq) {
        if (projectId == null || seq == null) {
            return true;
        }
        AtomicLong last = lastSeqByProject.computeIfAbsent(projectId, k -> new AtomicLong(0L));
        long prev = last.get();
        if (seq <= prev) {
            log.debug("[Unity] seq 重复/回退丢弃: project={} seq={} 已处理到={}", projectId, seq, prev);
            return false;
        }
        return last.compareAndSet(prev, seq);
    }

    /**
     * 项目当前已处理的最大 seq（调试/对账展示）。
     *
     * @param projectId 项目 ID
     *
     * @return 最大 seq（无上报返回 0）
     */
    public long lastSeqOf(Long projectId) {
        AtomicLong last = lastSeqByProject.get(projectId);
        return last == null ? 0L : last.get();
    }

    // ==================== 活性看门狗 ====================

    /**
     * 刷新会话活性（收到任意上行消息/下行发送成功后调用）。
     *
     * @param session WS 会话
     */
    public void touch(WebSocketSession session) {
        if (session != null) {
            lastActivityBySession.put(session.getId(), System.currentTimeMillis());
        }
    }

    /**
     * 会话最后活跃时刻（epoch 毫秒；未知返回 0）。
     *
     * @param sessionId 会话 ID
     *
     * @return 活跃时刻
     */
    public long lastActivityOf(String sessionId) {
        return lastActivityBySession.getOrDefault(sessionId, 0L);
    }

    /**
     * 失联扫描：返回超过 timeoutMillis 未活跃的会话（心跳看门狗踢出用）。
     *
     * @param timeoutMillis 失联阈值（毫秒）
     *
     * @return 失联会话列表
     */
    public List<WebSocketSession> staleSessions(long timeoutMillis) {
        long now = System.currentTimeMillis();
        return sessionsByProject.values().stream()
                .flatMap(Set::stream)
                .filter(s -> s != null && s.isOpen()
                        && now - lastActivityOf(s.getId()) > timeoutMillis)
                .toList();
    }

    /** 会话短 ID（日志用）。 */
    private String shortId(WebSocketSession session) {
        String id = session.getId();
        return id == null ? "?" : (id.length() > 8 ? id.substring(0, 8) : id);
    }
}
