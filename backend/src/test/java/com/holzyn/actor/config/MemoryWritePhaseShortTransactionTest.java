package com.holzyn.actor.config;

import com.holzyn.actor.domain.agent.brain.service.BrainProfileService;
import com.holzyn.actor.domain.agent.memory.AgentMemoryService;
import com.holzyn.actor.domain.memory.service.MemoryArchiveService;
import com.holzyn.actor.domain.memory.service.MemoryWorkingService;
import com.holzyn.actor.domain.npc.profile.entity.ActorAgentProfile;
import com.holzyn.actor.domain.world.service.WorldMemoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * P0-2 记忆写并发治理（2026-09-28）的<b>正向</b>守护：写阶段短事务方法必须保留
 * {@code @Transactional}。
 *
 * <p>与 {@link AiCallOutsideTransactionTest}（含 AI 方法一律无事务）互为镜像——
 * 「AI 在事务外、写阶段短事务在事务内」是治理后的标准分层：有人日后把写阶段方法的事务
 * 摘掉，会立刻红（毫秒级短事务被拆成多次隐式提交，半截失败重跑产生重复行的风险回归）。</p>
 *
 * <p>所属模块：config（跨域架构约束的回归测试，与被约束的域解耦）</p>
 */
class MemoryWritePhaseShortTransactionTest {

    /**
     * 反思写阶段：洞察落库 + staleness 复核 + 证据降级归档——反思链 AI 全部移出事务后，
     * 这三个下游方法是写阶段唯一的事务边界，必须保持短事务。
     */
    @Test
    @DisplayName("反思写阶段下游短事务方法必须带 @Transactional")
    void reflectionWritePhaseMethodsAreTransactional() throws Exception {
        assertHasTransaction(AgentMemoryService.class, "recordReflection",
                Long.class, String.class, String.class, Integer.class, List.class);
        assertHasTransaction(AgentMemoryService.class, "recheckMemories", Long.class, List.class);
        assertHasTransaction(MemoryArchiveService.class, "downgradeEvidence",
                Long.class, String.class, List.class);
        // 反思节奏计数落库（BrainProfileService.save 自带短事务）
        assertHasTransaction(BrainProfileService.class, "save", ActorAgentProfile.class);
    }

    /**
     * 蒸馏/模糊/编年史链路：无 AI 的写入方法保持短事务（调用方已拆事务，
     * 这些方法是被事务外编排调用的唯一事务边界）。
     */
    @Test
    @DisplayName("蒸馏/模糊/编年史写阶段短事务方法必须带 @Transactional")
    void distillFuzzChronicleWritePhaseMethodsAreTransactional() throws Exception {
        // L1 工作记忆写入/巩固（无 AI，保持短事务）
        assertHasTransaction(MemoryWorkingService.class, "writeDraft",
                Long.class, String.class, String.class, String.class, Integer.class);
        assertHasTransaction(MemoryWorkingService.class, "consolidateSession", String.class);
        // 编年史：实体状态失效更新（Zep 式版本链）与对话抽取双通道路由（无 AI，保持短事务）
        assertHasTransaction(WorldMemoryService.class, "upsertEntityState",
                Long.class, String.class, String.class, String.class, String.class, String.class,
                Long.class, String.class, Long.class);
        assertHasTransaction(WorldMemoryService.class, "routeFromExtraction",
                Long.class, Long.class, Long.class, List.class);
    }

    /** 断言指定公开方法存在且标注了 @Transactional（方法不存在则测试失败，防止签名漂移后静默放过）。 */
    private void assertHasTransaction(Class<?> type, String name, Class<?>... params) throws Exception {
        Method m = type.getMethod(name, params);
        assertNotNull(m.getAnnotation(Transactional.class),
                type.getSimpleName() + "#" + name + " 必须带 @Transactional："
                        + "P0-2 治理后该方法是写阶段短事务边界（AI 在事务外），摘掉事务会退化为"
                        + "多次隐式提交，半截失败重跑产生重复行/半截状态（详见类注释）");
    }
}
