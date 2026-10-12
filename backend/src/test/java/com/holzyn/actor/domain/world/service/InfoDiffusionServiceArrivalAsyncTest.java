package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.agent.event.EventSpec;
import com.holzyn.actor.domain.agent.event.WorldEventBus;
import com.holzyn.actor.domain.npc.profile.repository.ActorCharacterRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxSceneRepository;
import com.holzyn.actor.domain.sandbox.repository.ActorSandboxStateRepository;
import com.holzyn.actor.domain.world.config.WorldInfoProperties;
import com.holzyn.actor.domain.world.entity.ActorInfoDiffusion;
import com.holzyn.actor.domain.world.entity.ActorInfoPacket;
import com.holzyn.actor.domain.world.entity.ActorWorldFrame;
import com.holzyn.actor.domain.world.repository.ActorInfoDiffusionRepository;
import com.holzyn.actor.domain.world.repository.ActorInfoPacketRepository;
import com.holzyn.actor.domain.world.repository.ActorWorldFrameRepository;
import com.holzyn.actor.domain.world.repository.WorldLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 信息扩散「到达事件异步发布」回归测试（2026-09-27 P0 修复，domain/world/service）。
 *
 * <p><b>被修的缺陷（真跑实证）</b>：世界引擎唯一推进线程 {@code scheduling-1} 卡在
 * {@code EngineScheduler.worldScan → WorldEngine.simulate → InfoDiffusionService.diffuseProject
 * （@Transactional）→ publishArrivalEvent → InfoDistortionService.tryAiDistortion →
 * AiSpringChatGateway.chatResponse → sun.nio.ch.WEPoll.wait}（DeepSeek 套接字读等待）。后果：
 * ① 世界时钟冻结（{@code engineGameSecond=172733} 冻结 4 分钟以上，且 {@code paused=0}）；
 * ② H2 行锁跨 AI 存活（{@code actor_info_diffusion} 整轮读为空 ⇒ 事务未提交）；
 * ③ 最坏 active 包 14 × 区域 15 = 210 次串行 AI（单次均值 3.7 s ≈ 13 分钟），失真缓存键含 gameDay
 * ⇒ 每个游戏日边界复发 ⇒ 速率 60（24 现实分钟 = 1 游戏日）不可能成立。</p>
 *
 * <p><b>本测试锁定的四条不变量</b>：
 * ① 到达事件的 AI 改写<b>不在调用线程</b>执行（调用线程=世界 tick 线程）；
 * ② 到达事件的 AI 改写<b>不在事务内</b>执行；
 * ③ 慢 AI（1.5 s）<b>不阻塞</b> {@link InfoDiffusionService#diffuseProject} 返回（修复前会被拖数分钟）；
 * ④ 事务语义：事务内只物化扩散行，事件在 <b>afterCommit</b> 之后才发布；AI 抛异常不影响物化与调用方。</p>
 */
class InfoDiffusionServiceArrivalAsyncTest {

    private ActorInfoPacketRepository packetRepository;
    private ActorInfoDiffusionRepository diffusionRepository;
    private ActorWorldFrameRepository frameRepository;
    private WorldLocationRepository locationRepository;
    private ActorSandboxStateRepository sandboxStateRepository;
    private ActorSandboxSceneRepository sceneRepository;
    private ActorCharacterRepository characterRepository;
    private ActorCharacterRepository npcRepository;
    private WorldEventBus worldEventBus;
    private InfoDistortionService distortionService;
    private WorldInfoProperties props;
    private InfoDiffusionService service;

    /** 已保存扩散行快照（事务内物化的证据） */
    private final List<ActorInfoDiffusion> savedRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        packetRepository = Mockito.mock(ActorInfoPacketRepository.class);
        diffusionRepository = Mockito.mock(ActorInfoDiffusionRepository.class);
        frameRepository = Mockito.mock(ActorWorldFrameRepository.class);
        locationRepository = Mockito.mock(WorldLocationRepository.class);
        sandboxStateRepository = Mockito.mock(ActorSandboxStateRepository.class);
        sceneRepository = Mockito.mock(ActorSandboxSceneRepository.class);
        characterRepository = Mockito.mock(ActorCharacterRepository.class);
        npcRepository = Mockito.mock(ActorCharacterRepository.class);
        worldEventBus = Mockito.mock(WorldEventBus.class);
        distortionService = Mockito.mock(InfoDistortionService.class);
        props = new WorldInfoProperties();
        service = new InfoDiffusionService(packetRepository, diffusionRepository, frameRepository,
                locationRepository, sandboxStateRepository, sceneRepository, characterRepository,
                npcRepository, worldEventBus, distortionService, props, new ObjectMapper());

        // 一个可达信息包（origin 即区域，距离 0 ⇒ 帧生成时刻即到达），无地点/NPC ⇒ 区域全集仅 {集市}
        ActorInfoPacket packet = new ActorInfoPacket();
        packet.setId(1L);
        packet.setProjectId(1L);
        packet.setFrameId(10L);
        packet.setStatus("active");
        packet.setOrigin("集市");
        packet.setContent("集市失火，粮仓烧了大半。");
        packet.setSpreadSpeedDaysPerKm(1.0);

        ActorWorldFrame frame = new ActorWorldFrame();
        frame.setId(10L);
        frame.setProjectId(1L);
        frame.setGeneratedAtGameSecond(86400L);

        when(packetRepository.findByProjectIdAndStatus(any(), any())).thenReturn(List.of(packet));
        when(packetRepository.findByProjectId(any())).thenReturn(List.of(packet));
        when(frameRepository.findAllById(any())).thenReturn(List.of(frame));
        when(frameRepository.findByProjectIdAndStatus(any(), any())).thenReturn(List.of(frame));
        when(diffusionRepository.findByPacketId(any())).thenReturn(List.of());
        when(diffusionRepository.findByProjectId(any())).thenReturn(List.of());
        when(diffusionRepository.save(any())).thenAnswer(inv -> {
            ActorInfoDiffusion d = inv.getArgument(0);
            d.setId(1000L + savedRows.size());
            savedRows.add(d);
            return d;
        });
        when(locationRepository.findByProjectId(any())).thenReturn(List.of());
        when(sandboxStateRepository.findByProjectIdOrderByIdAsc(any())).thenReturn(List.of());
        when(sceneRepository.findByProjectIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(characterRepository.findByProjectIdAndDeletedOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(npcRepository.findByProjectIdOrderByIdAsc(any())).thenReturn(List.of());
        when(distortionService.distortForRegion(any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn("传闻版：集市失火，粮仓烧了大半。");

        savedRows.clear();
    }

    /** 不变量①+②：AI 改写在专有线程执行，且执行时无事务上下文（调用线程不被占用）。 */
    @Test
    void arrivalAiRewrite_runsOnDedicatedThreadAndOutsideTransaction() throws Exception {
        CountDownLatch aiStarted = new CountDownLatch(1);
        AtomicReference<String> aiThread = new AtomicReference<>();
        AtomicReference<Boolean> txActiveAtAi = new AtomicReference<>();
        AtomicReference<String> callerThread = new AtomicReference<>();
        when(distortionService.distortForRegion(any(), any(), any(), any(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    aiThread.set(Thread.currentThread().getName());
                    txActiveAtAi.set(TransactionSynchronizationManager.isActualTransactionActive());
                    aiStarted.countDown();
                    return "传闻版：集市失火。";
                });

        callerThread.set(Thread.currentThread().getName());
        service.diffuseProject(1L, 100000L);

        assertTrue(aiStarted.await(5, TimeUnit.SECONDS), "到达事件 AI 改写应被异步投递并执行");
        assertNotEquals(callerThread.get(), aiThread.get(), "AI 改写不得在调用线程（世界 tick 线程）执行");
        assertTrue(aiThread.get().startsWith("diffusion-arrival"),
                "AI 改写应运行在专有线程池上，实际=" + aiThread.get());
        assertFalse(Boolean.TRUE.equals(txActiveAtAi.get()),
                "到达事件 AI 改写必须在事务外执行（否则 H2 行锁跨 AI 存活）");
        assertEquals(1, savedRows.size(), "扩散行仍在事务内物化（不因异步而丢）");
        verify(worldEventBus, timeout(5000)).publish(eq(1L), eq(1L), any(EventSpec.class));
    }

    /** 不变量③（核心回归）：慢 AI 不阻塞扩散调用——修复前该调用会同步等完 AI（实测 4 分钟以上）。 */
    @Test
    void slowArrivalAi_doesNotBlockDiffuseProject() {
        when(distortionService.distortForRegion(any(), any(), any(), any(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    Thread.sleep(1500L);
                    return "传闻版：集市失火。";
                });

        long t0 = System.currentTimeMillis();
        service.diffuseProject(1L, 100000L);
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(elapsed < 500L,
                "扩散调用不得等待到达事件 AI（修复前实测被拖 4 分钟以上），实际耗时=" + elapsed + "ms");
        assertEquals(1, savedRows.size(), "慢 AI 不影响扩散行物化");
    }

    /** 不变量④-a：事务内只物化扩散行；到达事件必须在 afterCommit 之后才发布。 */
    @Test
    void arrivalEvent_publishesOnlyAfterCommit() {
        // 同线程执行器：把「是否异步」隔离出去，专测「提交前不发、提交后才发」
        service.setArrivalPublishExecutor(Runnable::run);
        AtomicReference<Boolean> aiRan = new AtomicReference<>(false);
        when(distortionService.distortForRegion(any(), any(), any(), any(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    aiRan.set(true);
                    return "传闻版：集市失火。";
                });

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.diffuseProject(1L, 100000L);
            assertEquals(1, savedRows.size(), "扩散行在事务内物化");
            assertFalse(Boolean.TRUE.equals(aiRan.get()),
                    "事务未提交前不得触发到达事件 AI 改写（否则 AI 在事务内）");
            verify(worldEventBus, Mockito.never()).publish(any(), any(), any());

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            assertTrue(Boolean.TRUE.equals(aiRan.get()), "提交后应触发到达事件 AI 改写");
            verify(worldEventBus, timeout(2000)).publish(eq(1L), eq(1L), any(EventSpec.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 不变量④-b：AI 抛异常被隔离——扩散行仍物化、调用方不抛异常（世界推进不被阻断）。 */
    @Test
    void arrivalAiFailure_isIsolated() {
        service.setArrivalPublishExecutor(Runnable::run);
        when(distortionService.distortForRegion(any(), any(), any(), any(), any(), any(), anyLong()))
                .thenThrow(new RuntimeException("DeepSeek read timeout"));

        assertDoesNotThrow(() -> service.diffuseProject(1L, 100000L));
        assertEquals(1, savedRows.size(), "AI 失败不得回滚/丢弃扩散行");
        verify(worldEventBus, Mockito.never()).publish(any(), any(), any());
    }
}
