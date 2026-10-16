package com.holzyn.actor.config;

import com.holzyn.actor.domain.growth.config.GrowthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「世界屏障 + N6 认知注意力」配置校验（2026-09-28，N6 轮新增；2026-09-29 配置文件拆分后适配）。
 * <p>为什么必须有这个测试：本仓库没有任何 {@code @SpringBootTest}，yml 键名拼错/占位符写坏
 * 不会被既有套件发现（沿 {@code RoutingYamlMaxTokensTest} 先例，直载 yml 不启 Spring 上下文）。</p>
 * <p>2026-09-29 配置文件拆分：系统底层在 application.yml（主文件，spring.config.import 引入
 * application-actor.yml 功能业务配置）。本测试校验的 world/growth 属功能业务段，故合并加载
 * 两个文件（模拟真实启动的配置合并环境，两文件语法/键位同时被校验）。</p>
 * <p>锁定内容：</p>
 * <ol>
 *   <li><b>P0-1 时间比参数（2026-09-27 实测报告 §6）</b>：{@code dispatch-wait-seconds} 默认
 *       <b>1100</b>（原 180——固定 180s 不随 rate 缩放是「每小时 22/27 角色决策被推迟」的唯一根因），
 *       且可被 {@code HOLOZYN_ACTOR_WORLD_DISPATCH_WAIT_SECONDS} 环境变量覆盖；</li>
 *   <li><b>N6 认知注意力配置（2026-09-27）</b>：参与度窗口/步长、记忆加权配额表
 *       （focus/ambient/dormant）、相机聚焦阈值全部可解析且能被 {@code GrowthProperties}
 *       绑定（生产实际读取方式）；</li>
 *   <li>yml 整体可被 Spring YAML 加载器解析（flow 风格 Map 写法有效）。</li>
 * </ol>
 * <p>所属模块：test/config（配置校验）</p>
 */
class WorldConfigYamlTest {

    /** 合并加载 application.yml + application-actor.yml 到 StandardEnvironment（占位符可解析）。 */
    private static StandardEnvironment env() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        assertTrue(!sources.isEmpty(), "application.yml 加载结果为空");
        List<PropertySource<?>> actorSources = new YamlPropertySourceLoader()
                .load("application-actor", new ClassPathResource("application-actor.yml"));
        assertTrue(!actorSources.isEmpty(), "application-actor.yml 加载结果为空");
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        actorSources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    private static String strAt(StandardEnvironment env, String key) {
        String raw = env.getProperty(key);
        assertNotNull(raw, "缺失或键名拼错：" + key);
        return raw.trim();
    }

    @Test
    @DisplayName("P0-01 根治：tick 队列容量默认 604800（7 游戏天）且可被环境变量覆盖")
    void tickQueueCapacityResolvesAndOverridable() throws IOException {
        // 键路径以生产读取方式为准：WorldEngine @Value 读 holzyn.actor.world.tick-queue-capacity-game-seconds
        assertEquals(604800, Integer.parseInt(strAt(env(), "holzyn.actor.world.tick-queue-capacity-game-seconds")),
                "默认 604800=7 游戏天（rate=24 覆盖约 7 真实小时离线）");
        System.setProperty("HOLOZYN_ACTOR_WORLD_TICK_QUEUE_CAPACITY_GAME_SECONDS", "86400");
        try {
            assertEquals(86400, Integer.parseInt(strAt(env(), "holzyn.actor.world.tick-queue-capacity-game-seconds")),
                    "环境变量未覆盖 tick-queue-capacity-game-seconds");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_WORLD_TICK_QUEUE_CAPACITY_GAME_SECONDS");
        }
        assertEquals(604800, Integer.parseInt(strAt(env(), "holzyn.actor.world.tick-queue-capacity-game-seconds")),
                "清理后应回到默认 604800");
    }

    @Test
    @DisplayName("P0-1：dispatch-wait-seconds 默认 1100（180→1100 收口），且可被环境变量覆盖")
    void dispatchWaitSecondsResolvedAndOverridable() throws IOException {
        // 键路径以生产读取方式为准：WorldEngine @Value 读 holzyn.actor.world.dispatch-wait-seconds
        // （yml 中为 world 下 barrier 的兄弟键，二者同属 6 空格层级）
        assertEquals(1100, Integer.parseInt(strAt(env(), "holzyn.actor.world.dispatch-wait-seconds")),
                "P0-1 未落地：应为 1100（rate=4 全认知档要求日边界全量 ≈1012s）");
        System.setProperty("HOLOZYN_ACTOR_WORLD_DISPATCH_WAIT_SECONDS", "180");
        try {
            assertEquals(180, Integer.parseInt(strAt(env(), "holzyn.actor.world.dispatch-wait-seconds")),
                    "环境变量未覆盖 dispatch-wait-seconds");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_WORLD_DISPATCH_WAIT_SECONDS");
        }
        assertEquals(1100, Integer.parseInt(strAt(env(), "holzyn.actor.world.dispatch-wait-seconds")),
                "清理后应回到默认 1100");
    }

    @Test
    @DisplayName("N6：growth.attention 参与度/相机配置可解析（默认值与 yml 一致）")
    void attentionN6KeysResolve() throws IOException {
        StandardEnvironment env = env();
        assertEquals("true", strAt(env, "holzyn.actor.growth.attention.participation-enabled"));
        assertEquals("6", strAt(env, "holzyn.actor.growth.attention.participation-window-game-hours"));
        assertEquals("2", strAt(env, "holzyn.actor.growth.attention.participation-level-step"));
        assertEquals("true", strAt(env, "holzyn.actor.growth.attention.memory-weight-enabled"));
        assertEquals("true", strAt(env, "holzyn.actor.growth.attention.camera-focus-enabled"));
        assertEquals("50", strAt(env, "holzyn.actor.growth.attention.camera-mid-meters"));
        assertEquals("100", strAt(env, "holzyn.actor.growth.attention.camera-far-meters"));
        // flow 风格 Map：逐键可读（focus/ambient/dormant）
        assertEquals("5", strAt(env, "holzyn.actor.growth.attention.memory-l1-cap-by-tier.focus"));
        assertEquals("2", strAt(env, "holzyn.actor.growth.attention.memory-l1-cap-by-tier.dormant"));
        assertEquals("4", strAt(env, "holzyn.actor.growth.attention.memory-l2-top-k-by-tier.focus"));
        assertEquals("2", strAt(env, "holzyn.actor.growth.attention.memory-l2-top-k-by-tier.dormant"));
        assertEquals("3", strAt(env, "holzyn.actor.growth.attention.space-memory-top-k-by-tier.focus"));
        assertEquals("1", strAt(env, "holzyn.actor.growth.attention.space-memory-top-k-by-tier.dormant"));
    }

    @Test
    @DisplayName("绑定路径：GrowthProperties 能从 yml 绑定 N6 注意力配置（生产实际读取方式）")
    void binderProducesAttentionConfig() throws IOException {
        GrowthProperties props = Binder.get(env())
                .bind("holzyn.actor.growth", Bindable.of(GrowthProperties.class))
                .orElseThrow(() -> new AssertionError("GrowthProperties 绑定失败"));
        assertTrue(props.getAttention().isMemoryWeightEnabled());
        assertTrue(props.getAttention().isParticipationEnabled());
        assertEquals(6, props.getAttention().getParticipationWindowGameHours());
        assertEquals(2, props.getAttention().getParticipationLevelStep());
        assertEquals(Integer.valueOf(5), props.getAttention().getMemoryL1CapByTier().get("focus"));
        assertEquals(Integer.valueOf(2), props.getAttention().getMemoryL1CapByTier().get("dormant"));
        assertEquals(Integer.valueOf(4), props.getAttention().getMemoryL2TopKByTier().get("focus"));
        assertEquals(Integer.valueOf(2), props.getAttention().getMemoryL2TopKByTier().get("dormant"));
        assertEquals(Integer.valueOf(3), props.getAttention().getSpaceMemoryTopKByTier().get("focus"));
        assertEquals(Integer.valueOf(1), props.getAttention().getSpaceMemoryTopKByTier().get("dormant"));
        assertEquals(50.0, props.getAttention().getCameraMidMeters(), 1e-9);
        assertEquals(100.0, props.getAttention().getCameraFarMeters(), 1e-9);
        // N5 既有键仍绑定（未被我方改动）
        assertEquals(2, props.getAttention().getPeriodTicks());
    }

    @Test
    @DisplayName("R1：world.lookahead 默认值可解析且可被环境变量覆盖（引擎 @Value 键路径）")
    void lookaheadKeysResolveAndOverridable() throws IOException {
        // 键路径以生产读取方式为准：WorldEngine @Value 读 holzyn.actor.world.lookahead.{enabled,game-minutes}
        assertEquals("true", strAt(env(), "holzyn.actor.world.lookahead.enabled"));
        assertEquals("15", strAt(env(), "holzyn.actor.world.lookahead.game-minutes"));
        // 环境变量覆盖（松弛绑定：连字符直接删除 → HOLOZYN_ACTOR_WORLD_LOOKAHEAD_GAMEMINUTES）
        System.setProperty("HOLOZYN_ACTOR_WORLD_LOOKAHEAD_GAMEMINUTES", "20");
        try {
            assertEquals("20", strAt(env(), "holzyn.actor.world.lookahead.game-minutes"),
                    "环境变量未覆盖 lookahead.game-minutes");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_WORLD_LOOKAHEAD_GAMEMINUTES");
        }
        System.setProperty("HOLOZYN_ACTOR_WORLD_LOOKAHEAD_ENABLED", "false");
        try {
            assertEquals("false", strAt(env(), "holzyn.actor.world.lookahead.enabled"),
                    "环境变量未覆盖 lookahead.enabled");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_WORLD_LOOKAHEAD_ENABLED");
        }
        assertEquals("15", strAt(env(), "holzyn.actor.world.lookahead.game-minutes"),
                "清理后应回到默认 15");
        assertEquals("true", strAt(env(), "holzyn.actor.world.lookahead.enabled"),
                "清理后应回到默认 true");
    }

    @Test
    @DisplayName("P0-2 遗留根治②：learning.reflection.budget-per-cycle 默认 0（自动浮动）且可被环境变量覆盖")
    void reflectionBudgetKeyResolvesAndOverridable() throws IOException {
        // 键路径以生产读取方式为准：ReflectionService @Value 读
        // holzyn.actor.learning.reflection.budget-per-cycle（默认 0=自动浮动：特殊 NPC 数×每日上限）
        assertEquals("0", strAt(env(), "holzyn.actor.learning.reflection.budget-per-cycle"),
                "默认 0=自动浮动预算（世界人数多时自动放大，无需改配置）");
        // 环境变量覆盖（松弛绑定：连字符直接删除 → HOLOZYN_ACTOR_LEARNING_REFLECTION_BUDGET_PER_CYCLE）
        System.setProperty("HOLOZYN_ACTOR_LEARNING_REFLECTION_BUDGET_PER_CYCLE", "7");
        try {
            assertEquals("7", strAt(env(), "holzyn.actor.learning.reflection.budget-per-cycle"),
                    "环境变量未覆盖 budget-per-cycle");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_LEARNING_REFLECTION_BUDGET_PER_CYCLE");
        }
        assertEquals("0", strAt(env(), "holzyn.actor.learning.reflection.budget-per-cycle"),
                "清理后应回到默认 0");
    }

    @Test
    @DisplayName("worldgen 配置契约：max-tokens 8192 / parallelism 3 / roster-limit 20 / story-character-limit 12 且可被环境变量覆盖")
    void worldgenKeysResolveAndOverridable() throws IOException {
        // 键路径以生产读取方式为准：WorldGenProperties @ConfigurationProperties(prefix = holzyn.actor.worldgen)
        // ⚠️ 2026-09-29_05 适配：max-tokens 4096→8192（对齐 routing tool 档位）；parallelism/roster-limit 新增
        // ⚠️ 2026-09-29_06 适配：story-character-limit 新增（story-plan 角色摘要限流）
        assertEquals("8192", strAt(env(), "holzyn.actor.worldgen.max-tokens"),
                "max-tokens 默认应 8192（tool 档位对齐，防复杂维度截断）");
        assertEquals("3", strAt(env(), "holzyn.actor.worldgen.parallelism"),
                "parallelism 默认应 3（前 3 维并行）");
        assertEquals("20", strAt(env(), "holzyn.actor.worldgen.roster-limit"),
                "roster-limit 默认应 20（character 阵容上限）");
        assertEquals("12", strAt(env(), "holzyn.actor.worldgen.story-character-limit"),
                "story-character-limit 默认应 12（story-plan 角色摘要注入上限）");
        // 环境变量覆盖：yml 键为占位符 ${HOLOZYN_ACTOR_WORLDGEN_ROSTER_LIMIT:20}，占位符名=精确匹配 env 名
        // （冒烟 2026-09-29_05 曾因 yml 缺键 + 只设连字符删除名 HOLOZYN_ACTOR_WORLDGEN_ROSTERLIMIT
        //  而 @ConfigurationProperties 绑定需连字符删除 → 两径皆失效跑默认 20；补 yml 键后占位符径生效）
        System.setProperty("HOLOZYN_ACTOR_WORLDGEN_ROSTER_LIMIT", "5");
        try {
            assertEquals("5", strAt(env(), "holzyn.actor.worldgen.roster-limit"),
                    "环境变量未覆盖 roster-limit");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_WORLDGEN_ROSTER_LIMIT");
        }
        System.setProperty("HOLOZYN_ACTOR_WORLDGEN_MAX_TOKENS", "16384");
        try {
            assertEquals("16384", strAt(env(), "holzyn.actor.worldgen.max-tokens"),
                    "环境变量未覆盖 max-tokens");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_WORLDGEN_MAX_TOKENS");
        }
        System.setProperty("HOLOZYN_ACTOR_WORLDGEN_STORY_CHARACTER_LIMIT", "4");
        try {
            assertEquals("4", strAt(env(), "holzyn.actor.worldgen.story-character-limit"),
                    "环境变量未覆盖 story-character-limit");
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_WORLDGEN_STORY_CHARACTER_LIMIT");
        }
        assertEquals("20", strAt(env(), "holzyn.actor.worldgen.roster-limit"),
                "清理后应回到默认 20");
        assertEquals("12", strAt(env(), "holzyn.actor.worldgen.story-character-limit"),
                "清理后应回到默认 12");
    }
}
