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

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.common.JsonUtil;
import com.holzyn.actor.domain.account.CurrentUserProvider;
import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.entity.ActorCharacter;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxScene;
import com.holzyn.actor.domain.sandbox.entity.ActorSandboxState;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.world.config.WorldInfoProperties;
import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.world.repository.ActorInfoDiffusionRepository;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 信息包扩散引擎。
 * <p>职责：<b>每游戏小时边界</b>（WorldEngine 小时级钩子
 * 调用，含长离线回补逐小时物化——）按信息包扩散参数（spread_speed_days_per_km 天/公里 ×
 * 区域间距离）计算当前到达范围，<b>物化</b>到 actor_info_diffusion（每包每区域一条，packet_id+region
 * 语义唯一）；valid_until 过期即停（包置 expired，不再新增扩散行）；生成中间传播节点（relayed_by：
 * 优先取包的 spread_channels，缺省模板池轮换，确定性可复现）；区域距离 = <b>沙盘坐标欧氏距离</b>
 * （1 单位=1 米，÷1000=公里；地点代表坐标=该地点 NPC 沙盘位置均值，无坐标确定性派生，无坐标对回退
 * 名义距离——）。</p>
 * <p>扩散目标区域 = 项目世界地点名 ∪ 信息包 origin ∪ 当前生效帧宏观事件 affected_area（双轨匹配
 * 的区域全集，供感知空间层按「地点名精确 / 原文包含」双轨匹配）。</p>
 * <p><b>到达事件</b>：每包每区域<b>首达</b>发布 1 条
 * kind=world_frame 低重要度（1）事件，标题「消息传到了{区域}」，内容=<b>区域传闻版</b>（同区域同包
 * 同日共享失真缓存 {@link InfoDistortionService#distortForRegion}，AI 失败回退规则模板），
 * 幂等键 diffusion:{projectId}:{gameDay}:{packetId}:{region}——由「扩散行存在性检查」天然去重不刷屏。
 * <b>修复</b>：到达事件的区域传闻 AI 改写 + 事件发布<b>不再在扩散事务内同步执行</b>，
 * 改为「扩散行物化（事务内、零 AI）+ 事务提交后异步发布（虚拟/固定并行度线程池、事务外）」，详见
 * <p>纯规则零成本：扩散物化/距离/选节点全部确定性计算，零 AI（AI 失真仅到达事件内容与感知注入时按
 * 缓存收敛触发）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InfoDiffusionService {

    /** 事件类别：世界帧 */
    private static final String KIND_WORLD_FRAME = "world_frame";

    /** 缺省传播速度（天/公里；扩散参数缺省补全由 WorldFrameValidator.normalize 保证，此处防御） */
    private static final double DEFAULT_SPEED = 1.0;

    /** 缺省传播节点模板池（包的 spread_channels 为空时按 hash 轮换；确定性可复现） */
    private static final String[] RELAY_TEMPLATES = {"商队", "信使", "行脚商贩", "流民", "驿卒", "走方郎中"};

    /** 世界时钟（当前游戏秒回退；null=未装配） */
    @Autowired(required = false)
    private com.holzyn.actor.domain.world.service.WorldClockService worldClockService;

    /** 当前用户解析（本地单用户 id=1；null=回退 1L） */
    @Autowired(required = false)
    private CurrentUserProvider currentUserProvider;

    private final ActorInfoPacketRepository packetRepository;
    private final ActorInfoDiffusionRepository diffusionRepository;
    private final ActorWorldFrameRepository frameRepository;
    private final WorldLocationRepository locationRepository;
    private final ActorSandboxStateRepository sandboxStateRepository;
    private final ActorSandboxSceneRepository sceneRepository;
    private final ActorCharacterRepository characterRepository;
    private final ActorCharacterRepository npcRepository;
    private final WorldEventBus worldEventBus;
    private final InfoDistortionService infoDistortionService;
    private final WorldInfoProperties props;
    private final ObjectMapper objectMapper;

    /** 项目地点代表坐标缓存：projectId → {区域名 → [x, z]}（每扩散 tick 刷新，防频繁 DB 查询） */
    private final ConcurrentHashMap<Long, Map<String, double[]>> coordinateCache = new ConcurrentHashMap<>();

    /** 到达事件发布并行度兜底值（config 未设时用；AI 改写=秒级网络等待，4 路并发够用且不触发限流） */
    private static final int DEFAULT_ARRIVAL_PUBLISH_PARALLELISM = 4;

    /** 到达事件发布执行器（惰性单例；单测可注入同线程执行器保证确定性，见 setArrivalPublishExecutor） */
    private volatile Executor arrivalPublishExecutor;

    /**
     * 待发布到达事件快照（事务提交后异步发布用）。
     * <p>为什么要快照而不是直接传实体：① 提交后实体已游离，跨线程读托管实体不安全；
     * ② 到达事件的全部输入都是不可变值（包 ID/origin/正文/失真规则/区域/传播节点/距离/游戏日/
     * 到达秒），快照后异步发布与事务生命周期完全解耦。</p>
     */
    private record PendingArrival(Long userId, Long projectId, Long packetId, String origin, String content,
                                  String distortionRulesJson, String region, String relayed,
                                  double distKm, long day, long arrival) {
    }

    // ==================== 入口：每游戏小时扩散（WorldEngine 小时级钩子） ====================

    /**
     * 每游戏小时扩散主流程（WorldEngine ②-e 钩子调用，含长离线回补逐小时调用）：
     * 取项目 active 信息包 → 过期置 expired → 逐包逐区域计算到达 → 物化 actor_info_diffusion +
     * 首达发布到达事件。项目无 active 包/开关关直接返回（零成本）。
     * <p><b>事务边界铁律</b>：本方法带 {@code @Transactional}，但事务内
     * <b>只做规则计算与 actor_info_diffusion 物化，绝不调用 AI、绝不发布事件</b>——到达事件被登记为
     * 快照并在提交后异步发布（{@link #scheduleArrivalPublishes}）。历史缺陷：区域传闻 AI 改写曾在本
     * 事务内同步串行执行，H2 行锁跨 AI 存活 + 世界引擎单线程被占满，实测引擎游戏秒冻结 4 分钟以上
     * （14 包 × 15 区域 = 210 次串行 AI ≈ 13 分钟），速率 60（24 现实分钟=1 游戏日）无法成立。</p>
     *
     * @param projectId 项目 ID
     *
     * @param gameSecond 当前游戏秒（可空=世界时钟当前值）
     */
    @Transactional
    public void diffuseProject(Long projectId, Long gameSecond) {
        if (!props.isDiffusionEnabled() || projectId == null) {
            return;
        }
        Long now = gameSecond != null ? gameSecond : currentEngineGameSecond(projectId);
        if (now == null) {
            return;
        }
        List<ActorInfoPacket> packets = packetRepository.findByProjectIdAndStatus(projectId, "active");
        if (packets == null || packets.isEmpty()) {
            return;
        }
        long day = Math.floorDiv(now, 86400L);
        // 区域全集（世界地点 ∪ 包 origin ∪ 当前帧宏观事件 affected_area）+ 坐标
        List<String> regions = regionsOfProject(projectId);
        Map<String, double[]> coords = coordinatesOf(projectId);
        // 帧生成时刻（包所属帧 generatedAtGameSecond，用于到达时间基准）
        Map<Long, Long> frameAtByPacket = frameGeneratedAtBy(packets);

        Long userId = currentUserProvider == null ? 1L : currentUserProvider.currentUserId();
        // 待发布到达事件快照（事务内只登记不发布：AI 不得在事务内、tick 不得等待 AI）
        List<PendingArrival> pending = new ArrayList<>();
        for (ActorInfoPacket packet : packets) {
            Long frameAt = frameAtByPacket.get(packet.getId());
            if (frameAt == null) {
                log.debug("[信息扩散] 信息包无所属帧（孤儿）跳过: project={} packet={}", projectId, packet.getId());
                continue;
            }
            // 过期即停：包置 expired，不再新增扩散行（已到达区域历史保留）
            Long validUntil = packet.getValidUntilDay();
            if (validUntil != null && day > validUntil) {
                if (!"expired".equals(packet.getStatus())) {
                    packet.setStatus("expired");
                    packetRepository.save(packet);
                    log.debug("[信息扩散] 信息包过期置 expired: project={} packet={} validUntil={}",
                            projectId, packet.getId(), validUntil);
                }
                continue;
            }
            double speed = packet.getSpreadSpeedDaysPerKm() == null
                    ? DEFAULT_SPEED : packet.getSpreadSpeedDaysPerKm();
            if (speed <= 0) {
                speed = DEFAULT_SPEED;
            }
            // 已到达区域集合（packet_id+region 语义唯一 → 存在即到达，事件不重复）
            Set<String> reached = reachedRegions(packet.getId());
            for (String region : regions) {
                if (reached.contains(region)) {
                    continue;
                }
                // origin 自身距离恒为 0（帧生成时刻即到，即使 origin 无坐标也不走兜底距离）
                double distKm = region.equals(packet.getOrigin())
                        ? 0d : distanceKm(coords.get(packet.getOrigin()), coords.get(region));
                long arrival = arrivalSecond(frameAt, speed, distKm);
                if (now < arrival) {
                    continue; // 尚未到达
                }
                String relayed = relayedBy(packet.getId(), parseStringList(packet.getSpreadChannelsJson()), region);
                // 物化扩散行（首达）
                ActorInfoDiffusion d = new ActorInfoDiffusion();
                d.setPacketId(packet.getId());
                d.setProjectId(projectId);
                d.setRegion(region);
                d.setReachedGameSecond(arrival);
                d.setGameDay(Math.floorDiv(arrival, 86400L));
                d.setRelayedBy(relayed);
                d.setDistortionAppliedJson(toJson(Map.of(
                        "distanceKm", distKm,
                        "rules", parseStringList(packet.getDistortionRulesJson()),
                        "speedDaysPerKm", speed)));
                diffusionRepository.save(d);
                log.debug("[信息扩散] 信息包到达区域: project={} packet={} region={} 距{} 里 经{}",
                        projectId, packet.getId(), region, formatLi(distKm), relayed);
                // 首达登记「区域传闻版」到达事件（不在此处发布：事务提交后异步，见 scheduleArrivalPublishes）
                if (props.isArrivalEventEnabled()) {
                    pending.add(new PendingArrival(userId, projectId, packet.getId(), packet.getOrigin(),
                            packet.getContent(), packet.getDistortionRulesJson(), region, relayed,
                            distKm, day, arrival));
                }
            }
        }
        // 事务提交后异步发布（AI 改写 + 事件发布都在事务外、不在 tick 线程）
        scheduleArrivalPublishes(projectId, pending);
    }

    // ==================== 到达事件 ====================

    /**
     * 到达事件发布调度：事务提交后在独立线程池异步发布，<b>绝不在扩散事务内、绝不阻塞 tick 线程</b>。
     * <p>现场证据（jstack 线程转储 + H2 会话， 17:18~17:22）：世界引擎唯一推进线程
     * InfoDiffusionService.diffuseProject（@Transactional，CGLIB TransactionInterceptor 在栈上）→
     * publishArrivalEvent → InfoDistortionService.tryAiDistortion → AiSpringChatGateway.chatResponse
     * → sun.nio.ch.WEPoll.wait}（DeepSeek 套接字读等待）；期间 {@code engineGameSecond=172733} 冻结
     * 4 分钟以上（{@code paused=0}/{@code worldRunning=1}），{@code actor_info_diffusion} 读为空
     * （整轮未提交 ⇒ 事务确实跨 AI 存活），且 8 个 {@code world-dispatch-*} 线程全空闲（非池饥饿）。
     * 规模上限 = active 包 14 × 区域 15 = 210 次串行 AI（单次实测均值 3.7 s ≈ 13 分钟），失真缓存键
     * 含 gameDay ⇒ 每个游戏日边界复发。</p>
     * <p>修法：本方法把快照登记到
     * {@link TransactionSynchronization#afterCommit()}；已提交后（或本就不在事务中，例如单测直呼、
     * 非事务调用方）直接投递。任何情况下调用方（tick）都不等待 AI。</p>
     *
     * @param projectId 项目 ID（仅用于日志）
     *
     * @param pending 待发布到达事件快照（可空/空=直接返回）
     */
    private void scheduleArrivalPublishes(Long projectId, List<PendingArrival> pending) {
        if (pending == null || pending.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitArrivalPublishes(projectId, pending);
                }
            });
            log.debug("[信息扩散] 到达事件已登记提交后异步发布: project={} 条数={}", projectId, pending.size());
        } else {
            // 无事务上下文（单测直呼/调用方已去事务化）：直接投递，行为仍为「异步、非调用线程」
            submitArrivalPublishes(projectId, pending);
        }
    }

    /** 逐条投递到达事件发布任务（单条投递失败只记日志，不影响其它条与调用方）。 */
    private void submitArrivalPublishes(Long projectId, List<PendingArrival> pending) {
        Executor executor = arrivalPublishExecutor();
        int submitted = 0;
        for (PendingArrival item : pending) {
            try {
                executor.execute(() -> publishArrivalEvent(item));
                submitted++;
            } catch (Exception e) {
                log.warn("[信息扩散] 到达事件投递失败（隔离继续）: project={} packet={} region={} : {}",
                        projectId, item.packetId(), item.region(), e.getMessage());
            }
        }
        log.debug("[信息扩散] 到达事件异步发布已投递: project={} 投递={}/{}（tick 不等待 AI）",
                projectId, submitted, pending.size());
    }

    /**
     * 到达事件发布执行器（惰性单例）：固定并行度守护线程池。
     * <p>并行度取 {@code holzyn.actor.world-info.arrival-publish-parallelism}（默认 4）：AI 改写是
     * 秒级网络等待，4 路并发足以在 1 游戏小时（rate 60 下现实 1 分钟）内消化新到区域，同时避免瞬时
     * 数百并发触发供应商限流（429/超时）。工作队列无界 ⇒ 不拒绝任务、不阻塞 tick，积压由消费速率
     * 自然收敛（AI 失败仍回退规则模板，不会因积压丢失扩散行）。</p>
     */
    private Executor arrivalPublishExecutor() {
        Executor e = arrivalPublishExecutor;
        if (e == null) {
            synchronized (this) {
                e = arrivalPublishExecutor;
                if (e == null) {
                    int parallelism = props.getArrivalPublishParallelism() > 0
                            ? props.getArrivalPublishParallelism() : DEFAULT_ARRIVAL_PUBLISH_PARALLELISM;
                    e = Executors.newFixedThreadPool(parallelism, r -> {
                        Thread t = new Thread(r, "diffusion-arrival");
                        t.setDaemon(true);
                        return t;
                    });
                    arrivalPublishExecutor = e;
                    log.info("[信息扩散] 到达事件发布执行器就绪: 并行度={}（事务外异步，tick 不等待 AI）",
                            parallelism);
                }
            }
        }
        return e;
    }

    /**
     * 单测覆盖点：注入同线程执行器（{@code Runnable::run}）使发布确定同步，或注入受控执行器
     * （延迟执行）验证「调用方不等待、AI 不在事务内」。
     */
    void setArrivalPublishExecutor(Executor executor) {
        this.arrivalPublishExecutor = executor;
    }

    /**
     * 发布单条扩散到达事件（每包每区域首达 1 条，天然去重）：kind=world_frame/source=engine/global/
     * 重要度 1（信息级），标题「消息传到了{区域}」，内容=区域传闻版（InfoDistortionService 区域缓存，
     * AI 失败回退规则模板），幂等键 diffusion:{projectId}:{gameDay}:{packetId}:{region}。
     * <p><b>执行上下文</b>：由 {@link #submitArrivalPublishes} 投递到专有线程池执行——不在扩散事务内、
     * 不在 tick 线程上。因此本方法内的 AI 改写（秒级）既不会长期持有 H2 行锁，也不会冻结世界推进。</p>
     */
    private void publishArrivalEvent(PendingArrival item) {
        try {
            String origin = item.origin() == null || item.origin().isBlank() ? "他处" : item.origin();
            String spreadContext = "消息经" + item.relayed() + "从" + origin + "传来，距事发地"
                    + formatLi(item.distKm());
            String rumor = infoDistortionService.distortForRegion(item.projectId(), item.region(),
                    item.packetId(), item.content(), parseStringList(item.distortionRulesJson()),
                    spreadContext, item.day());
            String title = "消息传到了" + item.region();
            String content = rumor == null || rumor.isBlank()
                    ? (item.content() == null ? title : item.content()) : rumor;
            worldEventBus.publish(item.userId(), item.projectId(), EventSpec.builder()
                    .kind(KIND_WORLD_FRAME)
                    .source("engine")
                    .visibility("global")
                    .locationName(item.region())
                    .title(abbreviate(title, 200))
                    .content(abbreviate(content, 300))
                    .importance(1)
                    .gameHour(Math.floorDiv(item.arrival(), 3600L))
                    .sourceKey("diffusion:" + item.projectId() + ":" + item.day() + ":"
                            + item.packetId() + ":" + item.region())
                    .build());
        } catch (Exception e) {
            // 到达事件发布失败不影响扩散物化（隔离继续）；扩散行已在事务内提交，不丢
            log.warn("[信息扩散] 到达事件发布失败（隔离继续）: project={} packet={} region={} : {}",
                    item.projectId(), item.packetId(), item.region(), e.getMessage());
        }
    }

    // ==================== 区域与坐标（供感知层复用） ====================

    /**
     * 项目扩散区域全集：世界地点名 ∪ active 信息包 origin ∪
     * 当前生效帧宏观事件 affected_area；去重保序（地点表序优先）。
     *
     * @param projectId 项目 ID
     *
     * @return 区域名列表（非空去重）
     */
    public List<String> regionsOfProject(Long projectId) {
        LinkedHashSet<String> regions = new LinkedHashSet<>();
        // ① 世界地点名
        List<ActorWorldLocation> locations = locationRepository.findByProjectId(projectId);
        for (ActorWorldLocation l : locations) {
            if (l.getName() != null && !l.getName().isBlank()) {
                regions.add(l.getName().trim());
            }
        }
        // ② active 信息包 origin
        List<ActorInfoPacket> packets = packetRepository.findByProjectIdAndStatus(projectId, "active");
        for (ActorInfoPacket p : packets) {
            if (p.getOrigin() != null && !p.getOrigin().isBlank()) {
                regions.add(p.getOrigin().trim());
            }
        }
        // ③ 当前生效帧宏观事件 affected_area
        frameRepository.findByProjectIdAndStatus(projectId, "active").stream().findFirst()
                .ifPresent(frame -> {
                    if (frame.getMacroEventsJson() != null && !frame.getMacroEventsJson().isBlank()) {
                        WorldFrameParse.FrameJson parsed = WorldFrameParse.parse(
                                "{\"macro_events\":" + frame.getMacroEventsJson() + "}", 0L);
                        for (WorldFrameParse.MacroEvent e : parsed.macroEvents()) {
                            if (e.affectedArea() != null) {
                                for (String area : e.affectedArea()) {
                                    if (area != null && !area.isBlank()) {
                                        regions.add(area.trim());
                                    }
                                }
                            }
                        }
                    }
                });
        return new ArrayList<>(regions);
    }

    /**
     * 项目区域代表坐标：{区域名 → [x, z]}——该地点有 NPC 在时取沙盘位置均值
     * （1 单位=1 米），无 NPC 的地点按顺序确定性派生（绕场景中心环形均匀分布，半径=场景尺寸×0.33）；
     * 每 tick 刷新缓存（数据量小，一致性优先）。坐标体系为 S3 中心化后 [-size/2, size/2]。
     *
     * @param projectId 项目 ID
     *
     * @return 区域名 → [x, z]（仅世界地点名参与坐标化；包 origin/affected_area 无坐标时距离走兜底）
     */
    public Map<String, double[]> coordinatesOf(Long projectId) {
        Map<String, double[]> out = new LinkedHashMap<>();
        List<ActorWorldLocation> locations = locationRepository.findByProjectId(projectId);
        if (locations.isEmpty()) {
            return out;
        }
        // 批量加载 NPC 所在地映射（避免逐状态 findById；special/crowd 双轨）
        Map<String, String> refToLocation = new LinkedHashMap<>();
        for (ActorCharacter ch : characterRepository.findByProjectIdAndDeletedOrderByIdAsc(projectId, 0)) {
            if (ch.getLocation() != null && !ch.getLocation().isBlank()) {
                refToLocation.put(com.holzyn.actor.common.NpcRefs.of(ch.getId()), ch.getLocation());
            }
        }
        for (ActorCharacter n : npcRepository.findByProjectIdOrderByIdAsc(projectId)) {
            if (n.getLocation() != null && !n.getLocation().isBlank()) {
                refToLocation.put(com.holzyn.actor.common.NpcRefs.of(n.getId()), n.getLocation());
            }
        }
        // 沙盘状态 → NPC 所在地名 → 位置累加（取均值作为地点代表坐标）
        Map<String, double[]> sums = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<ActorSandboxState> states = sandboxStateRepository.findByProjectIdOrderByIdAsc(projectId);
        for (ActorSandboxState st : states) {
            if (st.getPosX() == null || st.getPosZ() == null || st.getNpcRef() == null) {
                continue;
            }
            String locationName = refToLocation.get(st.getNpcRef());
            if (locationName == null || locationName.isBlank()) {
                continue;
            }
            double[] acc = sums.computeIfAbsent(locationName, k -> new double[]{0d, 0d});
            acc[0] += st.getPosX();
            acc[1] += st.getPosZ();
            counts.merge(locationName, 1, Integer::sum);
        }
        for (Map.Entry<String, double[]> e : sums.entrySet()) {
            int n = counts.getOrDefault(e.getKey(), 1);
            out.put(e.getKey(), new double[]{e.getValue()[0] / n, e.getValue()[1] / n});
        }
        // 无 NPC 的地点：确定性派生坐标（环形均匀分布，半径=场景尺寸×0.33，中心原点）
        int size = sceneSizeOf(projectId);
        double radius = Math.max(40d, size * 0.33d);
        List<String> noCoord = new ArrayList<>();
        for (ActorWorldLocation l : locations) {
            String name = l.getName() == null ? "" : l.getName().trim();
            if (name.isBlank() || out.containsKey(name)) {
                continue;
            }
            noCoord.add(name);
        }
        int n = noCoord.size();
        for (int i = 0; i < n; i++) {
            double angle = 2d * Math.PI * i / Math.max(1, n);
            out.put(noCoord.get(i), new double[]{radius * Math.cos(angle), radius * Math.sin(angle)});
        }
        coordinateCache.put(projectId, out);
        return out;
    }

    /** 场景尺寸（首个沙盘场景 sceneSize，缺省 500）。 */
    private int sceneSizeOf(Long projectId) {
        try {
            List<ActorSandboxScene> scenes = sceneRepository.findByProjectIdOrderByCreatedAtAsc(projectId);
            if (scenes != null && !scenes.isEmpty() && scenes.get(0).getSceneSize() != null) {
                return scenes.get(0).getSceneSize();
            }
        } catch (Exception ignored) {
            // 场景查询失败回退默认尺寸
        }
        return 500;
    }

    // ==================== 静态纯逻辑（可单测） ====================

    /**
     * 到达游戏秒：frameGeneratedAt + ceil(spreadSpeedDaysPerKm × distanceKm × 86400)。
     * origin 自身 distanceKm=0 → 到达时刻=帧生成时刻（消息从源头即刻可知）。
     *
     * @param frameGeneratedAt 帧生成游戏秒（扩散时间基准）
     *
     * @param speedDaysPerKm 传播速度（天/公里，越小越快；<=0 视为缺省 1）
     *
     * @param distanceKm 区域间距离（公里，>=0）
     *
     * @return 到达游戏秒
     */
    static long arrivalSecond(long frameGeneratedAt, double speedDaysPerKm, double distanceKm) {
        double speed = speedDaysPerKm > 0 ? speedDaysPerKm : DEFAULT_SPEED;
        double days = speed * Math.max(0d, distanceKm);
        return frameGeneratedAt + (long) Math.ceil(days * 86400d);
    }

    /**
     * 欧氏距离（公里，1 单位=1 米）：两坐标均可用 → hypot(dx,dz)/1000；任一缺失 → 兜底名义距离。
     *
     * @param a 起点坐标 [x, z]（可空）
     *
     * @param b 终点坐标 [x, z]（可空）
     *
     * @param fallbackKm 兜底名义距离（公里，>0）
     *
     * @return 距离（公里）
     */
    static double distanceKm(double[] a, double[] b, double fallbackKm) {
        if (a == null || b == null) {
            return fallbackKm > 0 ? fallbackKm : 1.0d;
        }
        double dx = a[0] - b[0];
        double dz = a[1] - b[1];
        double meters = Math.sqrt(dx * dx + dz * dz);
        return meters / 1000d;
    }

    /** 实例版欧氏距离（兜底取配置 distance-fallback-km）。 */
    private double distanceKm(double[] a, double[] b) {
        return distanceKm(a, b, props.getDistanceFallbackKm());
    }

    /**
     * 传播节点（relayed_by）确定性选择：包渠道列表非空 → 按 (packetId ^ region hash) 轮换取一个；
     * 渠道为空 → 模板池同法轮换。同包同区域恒同节点（可复现）。
     *
     * @param packetId 信息包 ID
     *
     * @param channels 传播渠道列表（可空/空）
     *
     * @param region 目标区域名
     *
     * @return 传播节点名
     */
    static String relayedBy(Long packetId, List<String> channels, String region) {
        int seed = (int) (packetId == null ? 0L : packetId) ^ (region == null ? 0 : region.hashCode());
        List<String> pool = (channels == null || channels.isEmpty()) ? List.of(RELAY_TEMPLATES) : channels;
        return pool.get(Math.floorMod(seed, pool.size()));
    }

    /** 公里 → 「X 里」展示（1 公里≈2 里；<0.05 里就近表述）。 */
    static String formatLi(double km) {
        double li = km * 2d;
        if (li < 0.05d) {
            return "事发地附近";
        }
        if (li < 10d) {
            return String.format("%.1f 里", li);
        }
        return Math.round(li) + " 里";
    }

    // ==================== 读接口查询（world-info/packets + world-info/diffusion） ====================

    /**
     * 信息包查询（GET /world-info/packets）：按区域（origin 精确 或 扩散已到达该区域）与
     * 状态（expired=true → 仅 expired；false → 仅 active；null → 全部）过滤，id 升序。
     *
     * @param projectId 项目 ID
     *
     * @param region 区域名（可空=不过滤）
     *
     * @param expired true=仅过期包 / false=仅 active / null=全部
     *
     * @return 信息包列表
     */
    public List<ActorInfoPacket> queryPackets(Long projectId, String region, Boolean expired) {
        if (projectId == null) {
            return List.of();
        }
        List<ActorInfoPacket> all = expired == null
                ? packetRepository.findByProjectId(projectId)
                : packetRepository.findByProjectIdAndStatus(projectId, expired ? "expired" : "active");
        if (region == null || region.isBlank()) {
            return all;
        }
        String target = region.trim();
        List<ActorInfoPacket> out = new ArrayList<>();
        for (ActorInfoPacket p : all) {
            if (target.equals(p.getOrigin())) {
                out.add(p);
                continue;
            }
            for (ActorInfoDiffusion d : diffusionRepository.findByPacketId(p.getId())) {
                if (target.equals(d.getRegion())) {
                    out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 扩散状态查询（GET /world-info/diffusion）：按包（packetId 可空=按项目全部），到达时刻升序。
     *
     * @param projectId 项目 ID
     *
     * @param packetId 信息包 ID（可空）
     *
     * @return 扩散状态列表
     */
    public List<ActorInfoDiffusion> queryDiffusion(Long projectId, Long packetId) {
        if (projectId == null) {
            return List.of();
        }
        List<ActorInfoDiffusion> rows = new ArrayList<>(packetId != null
                ? diffusionRepository.findByPacketId(packetId)
                : diffusionRepository.findByProjectId(projectId));
        rows.sort(java.util.Comparator.comparing(ActorInfoDiffusion::getReachedGameSecond,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        return rows;
    }

    // ==================== 私有辅助 ====================

    /** 包所属帧生成时刻（packetId → generatedAtGameSecond；帧缺失回退 gameDay×86400）。 */
    private Map<Long, Long> frameGeneratedAtBy(List<ActorInfoPacket> packets) {
        Map<Long, Long> out = new LinkedHashMap<>();
        Set<Long> frameIds = new LinkedHashSet<>();
        for (ActorInfoPacket p : packets) {
            if (p.getFrameId() != null) {
                frameIds.add(p.getFrameId());
            }
        }
        if (frameIds.isEmpty()) {
            return out;
        }
        for (ActorWorldFrame f : frameRepository.findAllById(frameIds)) {
            for (ActorInfoPacket p : packets) {
                if (p.getFrameId() != null && p.getFrameId().equals(f.getId())) {
                    out.put(p.getId(), f.getGeneratedAtGameSecond() == null
                            ? (f.getGameDay() == null ? 0L : f.getGameDay() * 86400L)
                            : f.getGeneratedAtGameSecond());
                }
            }
        }
        return out;
    }

    /** 某包已到达区域集合（扩散行 packet_id+region 语义唯一）。 */
    Set<String> reachedRegions(Long packetId) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<ActorInfoDiffusion> rows = diffusionRepository.findByPacketId(packetId);
        for (ActorInfoDiffusion d : rows) {
            if (d.getRegion() != null && !d.getRegion().isBlank()) {
                out.add(d.getRegion());
            }
        }
        return out;
    }

    /** 当前游戏秒（世界时钟引擎推进位置；失败/未装配返回 null）。 */
    private Long currentEngineGameSecond(Long projectId) {
        try {
            return worldClockService == null ? null : worldClockService.currentEngineGameSecond(projectId);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 数组文本 → 字符串列表（容错空）。 */
    List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> out = new ArrayList<>();
            tools.jackson.databind.JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isArray()) {
                node.forEach(n -> {
                    String t = n == null ? "" : n.asText("").trim();
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                });
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 对象 → JSON（失败回退 null 不抛异常）。 */
    private String toJson(Object o) {
        try {
            return o == null ? null : objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 文本截断（null 回退 null）。 */
    private String abbreviate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "…";
    }
}
