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

package com.holzyn.actor.domain.world.fact.service.impl;

import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.world.authority.service.AuthorityWriteGuard;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFact;
import com.holzyn.actor.domain.world.fact.entity.ActorWorldFactLog;
import com.holzyn.actor.domain.world.fact.model.FactAuthority;
import com.holzyn.actor.domain.world.fact.model.FactChange;
import com.holzyn.actor.domain.world.fact.model.FactCommand;
import com.holzyn.actor.domain.world.fact.model.FactTypes;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactLogRepository;
import com.holzyn.actor.domain.world.fact.repository.ActorWorldFactRepository;
import com.holzyn.actor.domain.world.fact.service.WorldFactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BRAIN 侧事实写入口实现（§4.5/§4.6）。
 *
 * <p>事务边界：本类<b>零 AI</b>，允许 {@code @Transactional}（H2 行锁只在毫秒级）。
 * AI 裁决必须在事务外完成，再由调用方把 {@code EffectOp} 交给本服务。</p>
 *
 * <p>不变量落地：</p>
 * <ol>
 *   <li>每次 upsert/expire/void 都写一条 log，且 log.version 与 fact.version 一致；</li>
 *   <li>幂等键非空时，重复命令直接返回当前事实（op=duplicate），不重复写 log；</li>
 *   <li>写前过 {@link AuthorityWriteGuard}，ENGINE 模式下 backend 不得写 engine 事实。</li>
 * </ol>
 */
@Slf4j
@Service
public class WorldFactServiceImpl implements WorldFactService {

    /** JSON 序列化器（快照/差异；Jackson 3 异常为非受检）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ActorWorldFactRepository factRepository;

    private final ActorWorldFactLogRepository logRepository;

    private final AuthorityWriteGuard writeGuard;

    /**
     * 世界事件总线：事实变更统一从这里发布 {@code fact} 事件，
     * 供 FactPerceptionSubscriber 做在场认知/刺激/传闻。字段注入保持既有构造器签名不变。
     */
    @Autowired(required = false)
    private WorldEventBus worldEventBus;

    public WorldFactServiceImpl(ActorWorldFactRepository factRepository,
                                ActorWorldFactLogRepository logRepository,
                                AuthorityWriteGuard writeGuard) {
        this.factRepository = factRepository;
        this.logRepository = logRepository;
        this.writeGuard = writeGuard;
    }

    @Override
    @Transactional
    public FactChange upsert(FactCommand cmd) {
        requireCommand(cmd);
        String factType = FactTypes.require(cmd.factType());
        String visibility = FactTypes.requireVisibility(cmd.visibility(), factType);
        FactAuthority authority = cmd.authority() == null ? FactAuthority.BRAIN : cmd.authority();
        // 本服务是 BRAIN 写入口：即使事实声明 authority=engine，写者仍是 brain → 守卫会拒绝。
        writeGuard.assertCanWrite(cmd.projectId(), authority, "brain");

        ActorWorldFact existing = factRepository
                .findByProjectIdAndFactKey(cmd.projectId(), cmd.factKey().trim())
                .orElse(null);
        if (isDuplicate(cmd, existing)) {
            return new FactChange(existing, existing.getVersion(), existing.getVersion(), "duplicate");
        }

        boolean created = existing == null;
        ActorWorldFact fact = created ? new ActorWorldFact() : existing;
        String beforeJson = created ? null : snapshot(fact);
        int fromVersion = created ? 0 : fact.getVersion() == null ? 0 : fact.getVersion();

        if (created) {
            fact.setProjectId(cmd.projectId());
            fact.setFactKey(cmd.factKey().trim());
            fact.setCreatedAt(LocalDateTime.now());
            fact.setVersion(1);
        } else {
            fact.setVersion(fromVersion + 1);
        }
        fact.setFactType(factType);
        fact.setName(cmd.name());
        fact.setStateText(cmd.stateText());
        fact.setAttributesJson(cmd.attributesJson());
        fact.setLocationId(cmd.locationId());
        fact.setLocationName(cmd.locationName());
        fact.setVisibility(visibility);
        fact.setAuthority(authority.name().toLowerCase());
        fact.setValidFromGameSecond(cmd.validFromGameSecond());
        fact.setValidToGameSecond(cmd.validToGameSecond());
        fact.setStatus("active");
        fact.setSourceEventId(cmd.sourceEventId());
        fact.setUpdatedAt(LocalDateTime.now());
        fact = factRepository.save(fact);

        writeLog(fact, fromVersion, created ? "create" : "update", beforeJson, cmd,
                cmd.sourceEventId(), null);
        publishFactEvent(fact, cmd, created ? "create" : "update");
        log.debug("[事实层] {} factKey={} v{}→v{}", created ? "新建" : "更新",
                fact.getFactKey(), fromVersion, fact.getVersion());
        return new FactChange(fact, fromVersion, fact.getVersion(), created ? "create" : "update");
    }

    @Override
    @Transactional
    public FactChange expire(FactCommand cmd, String reason) {
        return terminal(cmd, reason, "expired", "expire");
    }

    @Override
    @Transactional
    public FactChange voidFact(FactCommand cmd, String reason) {
        return terminal(cmd, reason, "void", "void");
    }

    @Override
    @Transactional
    public void sweepExpired(Long projectId, long gameSecond) {
        if (projectId == null) {
            return;
        }
        var expired = factRepository
                .findByProjectIdAndStatusAndValidToGameSecondLessThanEqualOrderByIdAsc(
                        projectId, "active", gameSecond);
        for (ActorWorldFact fact : expired) {
            FactCommand cmd = new FactCommand(projectId, fact.getFactType(), fact.getFactKey(),
                    fact.getName(), fact.getStateText(), fact.getAttributesJson(), fact.getLocationId(),
                    fact.getLocationName(), fact.getVisibility(), FactAuthority.BRAIN,
                    fact.getValidFromGameSecond(), fact.getValidToGameSecond(), gameSecond,
                    null, fact.getSourceEventId(), null, null);
            expire(cmd, "到期自动失效");
        }
        if (!expired.isEmpty()) {
            log.info("[事实层] 到期扫描：project={} 过期 {} 条 @gs={}", projectId, expired.size(), gameSecond);
        }
    }

    /**
     * 终止类变更（expire/void）公共实现。
     *
     * @param cmd        命令
     *
     * @param reason     原因（写入日志 delta）
     *
     * @param status     目标状态（expired/void）
     *
     * @param op         日志操作码（expire/void）
     *
     * @return 变更结果
     */
    private FactChange terminal(FactCommand cmd, String reason, String status, String op) {
        requireCommand(cmd);
        FactAuthority authority = cmd.authority() == null ? FactAuthority.BRAIN : cmd.authority();
        writeGuard.assertCanWrite(cmd.projectId(), authority, "brain");
        ActorWorldFact fact = factRepository
                .findByProjectIdAndFactKey(cmd.projectId(), cmd.factKey().trim())
                .orElseThrow(() -> new IllegalArgumentException("事实不存在：" + cmd.factKey()));
        int fromVersion = fact.getVersion() == null ? 0 : fact.getVersion();
        String beforeJson = snapshot(fact);
        fact.setStatus(status);
        if (cmd.gameSecond() != null) {
            fact.setValidToGameSecond(cmd.gameSecond());
        }
        fact.setVersion(fromVersion + 1);
        fact.setUpdatedAt(LocalDateTime.now());
        if (cmd.sourceEventId() != null) {
            fact.setSourceEventId(cmd.sourceEventId());
        }
        fact = factRepository.save(fact);
        writeLog(fact, fromVersion, op, beforeJson, cmd,
                cmd.sourceEventId() == null ? fact.getSourceEventId() : cmd.sourceEventId(), reason);
        publishFactEvent(fact, cmd, op);
        return new FactChange(fact, fromVersion, fact.getVersion(), op);
    }

    /**
     * 发布事实变更事件（感知回流起点）。
     *
     * <p>事件 kind=fact/source=fact；{@code sourceKey=fact:{id}:v{version}} 保证每个版本
     * 只有一条；{@code causeEventId} 指向真正引发本次变更的 dialog/action 事件，形成因果链。
     * 事实本身仍是 BRAIN 写入，事件只负责通知，不改写者/权威。</p>
     *
     * @param fact 已保存事实
     * @param cmd  原命令
     * @param op   create/update/expire/void
     */
    private void publishFactEvent(ActorWorldFact fact, FactCommand cmd, String op) {
        if (worldEventBus == null || fact == null || fact.getId() == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("factId", fact.getId());
            payload.put("factKey", fact.getFactKey());
            payload.put("factType", fact.getFactType());
            payload.put("op", op);
            payload.put("visibility", fact.getVisibility());
            payload.put("authority", fact.getAuthority());
            payload.put("actorRef", cmd.actorRef());
            payload.put("locationId", fact.getLocationId());
            payload.put("locationName", fact.getLocationName());
            payload.put("version", fact.getVersion());
            String visibility = mapVisibility(fact.getVisibility());
            String title = "事实 · " + (fact.getName() == null || fact.getName().isBlank()
                    ? fact.getFactKey() : fact.getName());
            worldEventBus.publish(null, fact.getProjectId(), EventSpec.builder()
                    .kind("fact")
                    .source("fact")
                    .visibility(visibility)
                    .title(title)
                    .content(fact.getStateText())
                    .characterRefs(cmd.actorRef() == null ? null : java.util.List.of(cmd.actorRef()))
                    .locationId(fact.getLocationId())
                    .locationName(fact.getLocationName())
                    .payloadJson(MAPPER.writeValueAsString(payload))
                    .importance(3)
                    .gameHour(cmd.gameSecond() == null ? null : cmd.gameSecond() / 3600L)
                    .causeEventId(cmd.sourceEventId())
                    .sourceKey("fact:" + fact.getId() + ":v" + fact.getVersion())
                    .build());
        } catch (Exception e) {
            // 事实已落库，事件失败不回滚事实；但必须告警，避免感知层静默丢变更
            log.warn("[事实层] 事实事件发布失败（事实已落库）: factKey={} v={} : {}",
                    fact.getFactKey(), fact.getVersion(), e.getMessage());
        }
    }

    /** 事实可见性 → 事件可见性（public 在事件总线口径为 global）。 */
    private static String mapVisibility(String visibility) {
        if ("private".equalsIgnoreCase(visibility)) {
            return "private";
        }
        if ("public".equalsIgnoreCase(visibility)) {
            return "global";
        }
        return "location";
    }

    /**
     * 幂等命中判断：键非空、已存在日志、事实仍在。
     *
     * @param cmd      命令
     *
     * @param existing 已存在事实（可空）
     *
     * @return true=重复命令
     */
    private boolean isDuplicate(FactCommand cmd, ActorWorldFact existing) {
        String key = cmd.idempotencyKey();
        if (existing == null || key == null || key.isBlank()) {
            return false;
        }
        // 幂等键只在「同一条事实」内生效：别的 fact 用过同一个键，不能把当前命令吞掉。
        return logRepository.findTopByProjectIdAndIdempotencyKey(cmd.projectId(), key)
                .map(row -> cmd.factKey().trim().equals(row.getFactKey()))
                .orElse(false);
    }

    /**
     * 写一条事实变更日志（每次变化必有日志）。
     *
     * @param fact          变更后事实
     *
     * @param fromVersion   变更前版本
     *
     * @param op            操作码
     *
     * @param beforeJson    变更前快照
     *
     * @param cmd           原命令
     *
     * @param eventId       来源事件 ID
     *
     * @param deltaJson     差异/原因 JSON
     */
    private void writeLog(ActorWorldFact fact, int fromVersion, String op, String beforeJson,
                          FactCommand cmd, Long eventId, String deltaJson) {
        ActorWorldFactLog row = new ActorWorldFactLog();
        row.setProjectId(fact.getProjectId());
        row.setFactId(fact.getId());
        row.setFactKey(fact.getFactKey());
        row.setVersion(fact.getVersion());
        row.setOp(op);
        row.setBeforeJson(beforeJson);
        row.setAfterJson(snapshot(fact));
        row.setDeltaJson(deltaJson == null ? null : json(Map.of("reason", deltaJson)));
        row.setGameSecond(cmd.gameSecond());
        row.setActorRef(cmd.actorRef());
        row.setWriter("brain");
        row.setEventId(eventId);
        row.setIdempotencyKey(blankToNull(cmd.idempotencyKey()));
        logRepository.save(row);
    }

    /**
     * 事实快照 JSON（固定字段序，便于 diff 与逐字节比对）。
     *
     * @param fact 事实
     *
     * @return JSON 文本
     */
    private String snapshot(ActorWorldFact fact) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("factType", fact.getFactType());
        m.put("factKey", fact.getFactKey());
        m.put("name", fact.getName());
        m.put("stateText", fact.getStateText());
        m.put("attributes", fact.getAttributesJson());
        m.put("locationId", fact.getLocationId());
        m.put("locationName", fact.getLocationName());
        m.put("visibility", fact.getVisibility());
        m.put("authority", fact.getAuthority());
        m.put("version", fact.getVersion());
        m.put("status", fact.getStatus());
        m.put("validFromGameSecond", fact.getValidFromGameSecond());
        m.put("validToGameSecond", fact.getValidToGameSecond());
        return json(m);
    }

    /**
     * JSON 序列化（失败返回 null，不阻断主流程）。
     *
     * @param value 对象
     *
     * @return JSON 文本或 null
     */
    private String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[事实层] 快照序列化失败（字段降级为 null）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 基础参数校验。
     *
     * @param cmd 命令
     *
     * @throws IllegalArgumentException 缺 projectId/factKey/stateText
     */
    private void requireCommand(FactCommand cmd) {
        if (cmd == null) {
            throw new IllegalArgumentException("事实命令不能为空");
        }
        if (cmd.projectId() == null) {
            throw new IllegalArgumentException("事实命令缺少 projectId");
        }
        if (cmd.factKey() == null || cmd.factKey().isBlank()) {
            throw new IllegalArgumentException("事实命令缺少 factKey");
        }
        if (cmd.stateText() == null || cmd.stateText().isBlank()) {
            throw new IllegalArgumentException("事实命令缺少 stateText：" + cmd.factKey());
        }
    }

    /**
     * 空白转 null（唯一索引允许多个 NULL，但不能多个空串）。
     *
     * @param s 原始值
     *
     * @return null 或 trim 后文本
     */
    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
