package com.holzyn.actor.ai.route;

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
 * 场景路由表「可加载性 + 占位符可覆盖性 + 分档正确性」校验。
 * <p>背景（2026-09-27 第 3 轮长跑后复核）：把 {@code max-tokens} 由一律 8192/16384 收敛为
 * 按档位/场景分档，并把 7 个档位值改成可环境变量覆盖的占位符
 * （{@code ${HOLOZYN_ACTOR_AI_ROUTING_*_MAX_TOKENS:默认值}}）。</p>
 * <p>为什么必须有这个测试：本仓库 <b>没有任何 {@code @SpringBootTest}</b>（252 个测试类都不加载
 * application.yml），yml 语法错误、键名拼错、占位符写坏都不会被既有套件发现，只会在真实启动时炸。
 * 本测试用 {@link YamlPropertySourceLoader} + {@link StandardEnvironment} 直接加载 application.yml
 * （2026-09-29 拆分后：routing 属系统底层段，在 application.yml 主文件，另合并加载
 * application-actor.yml 模拟真实启动合并环境），不启 Spring 上下文、不连 H2、不调 AI，
 * 因此可安全纳入常规 {@code mvn test}。</p>
 * <p>锁定的不变量：</p>
 * <ol>
 *   <li>yml 可被 Spring 的 YAML 加载器解析（flow 风格 {@code "${...}"} 引号写法有效）；</li>
 *   <li>7 个档位 max-tokens 解析为约定值，且能被同名 System Property（=环境变量语义）覆盖；</li>
 *   <li>全部 max-tokens 落在 (0, 8192]，即不再有 12288/16384，也没有 0/负值；</li>
 *   <li><b>温度一律未被改动</b>（本轮授权范围只有输出上限 + 缓存，温度属于行为语义）。</li>
 * </ol>
 * <p>所属模块：test/ai/route（AI 接入层-场景路由配置校验）</p>
 */
class RoutingYamlMaxTokensTest {

    private static final String P = "holzyn.actor.ai.routing.";

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

    private static int intAt(StandardEnvironment env, String key) {
        String raw = env.getProperty(key);
        assertNotNull(raw, "缺失或键名拼错：" + key);
        return Integer.parseInt(raw.trim());
    }

    private static double dblAt(StandardEnvironment env, String key) {
        String raw = env.getProperty(key);
        assertNotNull(raw, "缺失或键名拼错：" + key);
        return Double.parseDouble(raw.trim());
    }

    @Test
    @DisplayName("yml 可加载：档位 max-tokens 解析为分档值（含 flow 风格占位符引号写法）")
    void tierMaxTokensResolveFromYaml() throws IOException {
        StandardEnvironment env = env();
        assertEquals(4096, intAt(env, P + "default-tier.max-tokens"));
        assertEquals(4096, intAt(env, P + "tiers.decision.max-tokens"));
        assertEquals(2048, intAt(env, P + "tiers.dialog.max-tokens"));
        assertEquals(3072, intAt(env, P + "tiers.memory.max-tokens"));
        assertEquals(4096, intAt(env, P + "tiers.world.max-tokens"));
        assertEquals(2048, intAt(env, P + "tiers.crowd.max-tokens"));
        assertEquals(8192, intAt(env, P + "tiers.tool.max-tokens"));
    }

    @Test
    @DisplayName("yml 可加载：逐场景 max-tokens 分档值（含 growth_space_generate 重点护栏）")
    void sceneMaxTokensResolveFromYaml() throws IOException {
        StandardEnvironment env = env();
        assertEquals(8192, intAt(env, P + "scenes.persona_bootstrap_fill_a.max-tokens"));
        assertEquals(8192, intAt(env, P + "scenes.persona_bootstrap_fill_b.max-tokens"));
        assertEquals(4096, intAt(env, P + "scenes.growth_space_generate.max-tokens"));
        assertEquals(2048, intAt(env, P + "scenes.growth_persona_shift_eval.max-tokens"));
        assertEquals(2048, intAt(env, P + "scenes.growth_wedding_narrative.max-tokens"));
        assertEquals(2048, intAt(env, P + "scenes.space_trigger.max-tokens"));
        assertEquals(2048, intAt(env, P + "scenes.agent_decision.max-tokens"));
    }

    @Test
    @DisplayName("不变量：所有 max-tokens ∈ (0, 8192]（12288/16384 已收敛，且无 0/负值）且温度未被改动")
    void allMaxTokensBoundedAndTemperaturesUnchanged() throws IOException {
        StandardEnvironment env = env();
        for (String key : new String[]{"default-tier", "tiers.decision", "tiers.dialog", "tiers.memory",
                "tiers.world", "tiers.crowd", "tiers.tool"}) {
            int v = intAt(env, P + key + ".max-tokens");
            assertTrue(v > 0 && v <= 8192, key + " max-tokens 越界：" + v);
        }
        // 温度：本轮未被授权调整，逐项锁定（含 11 个第 3 轮补登场景的 0.5）
        assertEquals(0.5, dblAt(env, P + "default-tier.temperature"), 1e-9);
        assertEquals(0.3, dblAt(env, P + "tiers.decision.temperature"), 1e-9);
        assertEquals(0.8, dblAt(env, P + "tiers.dialog.temperature"), 1e-9);
        assertEquals(0.2, dblAt(env, P + "tiers.memory.temperature"), 1e-9);
        assertEquals(0.7, dblAt(env, P + "tiers.world.temperature"), 1e-9);
        assertEquals(0.6, dblAt(env, P + "tiers.crowd.temperature"), 1e-9);
        assertEquals(0.5, dblAt(env, P + "tiers.tool.temperature"), 1e-9);
        assertEquals(0.4, dblAt(env, P + "scenes.persona_bootstrap_fill_a.temperature"), 1e-9);
        assertEquals(0.5, dblAt(env, P + "scenes.persona_bootstrap_fill_b.temperature"), 1e-9);
        for (String scene : new String[]{"agent_decision", "agent_learning", "attention", "growth_space_generate",
                "mask_breach", "micro_decision", "nanojev_sample", "relation_delta", "social_willingness",
                "space_decision", "space_trigger"}) {
            assertEquals(0.5, dblAt(env, P + "scenes." + scene + ".temperature"), 1e-9,
                    scene + " 温度被改动（本轮只允许改 max-tokens）");
        }
    }

    @Test
    @DisplayName("环境变量覆盖：*_MAX_TOKENS 可整体抬高上限（云端 reasoning 回合的无代码逃生门）")
    void maxTokensOverridableByEnvName() throws IOException {
        System.setProperty("HOLOZYN_ACTOR_AI_ROUTING_DIALOG_MAX_TOKENS", "8192");
        System.setProperty("HOLOZYN_ACTOR_AI_ROUTING_DECISION_MAX_TOKENS", "8192");
        try {
            StandardEnvironment env = env();
            assertEquals(8192, intAt(env, P + "tiers.dialog.max-tokens"), "环境变量未覆盖 dialog 档位");
            assertEquals(8192, intAt(env, P + "tiers.decision.max-tokens"), "环境变量未覆盖 decision 档位");
            // 未设置环境变量的档位仍用 yml 默认值
            assertEquals(3072, intAt(env, P + "tiers.memory.max-tokens"));
        } finally {
            System.clearProperty("HOLOZYN_ACTOR_AI_ROUTING_DIALOG_MAX_TOKENS");
            System.clearProperty("HOLOZYN_ACTOR_AI_ROUTING_DECISION_MAX_TOKENS");
        }
        // 清理后回到默认值（避免污染同 JVM 的其它测试）
        assertEquals(2048, intAt(env(), P + "tiers.dialog.max-tokens"));
    }

    @Test
    @DisplayName("绑定路径：SceneRoutingProperties 能绑定出 Integer 分档值（生产实际读取方式）")
    void binderProducesIntegerTiers() throws IOException {
        SceneRoutingProperties props = Binder.get(env())
                .bind(P.substring(0, P.length() - 1), Bindable.of(SceneRoutingProperties.class))
                .orElseThrow(() -> new AssertionError("SceneRoutingProperties 绑定失败"));
        assertEquals(4096, props.getDefaultTier().getMaxTokens());
        assertEquals(4096, props.getTiers().get("decision").getMaxTokens());
        assertEquals(2048, props.getTiers().get("dialog").getMaxTokens());
        assertEquals(3072, props.getTiers().get("memory").getMaxTokens());
        assertEquals(8192, props.getTiers().get("tool").getMaxTokens());
        assertEquals(4096, props.getScenes().get("growth_space_generate").getMaxTokens());
        assertEquals(8192, props.getScenes().get("persona_bootstrap_fill_a").getMaxTokens());
        // 分类映射未被本轮改动（顺带校验绑定确实读到了 category-map）
        assertEquals("world", props.getCategoryMap().get("world_frame_daily"));
        assertEquals("decision", props.getCategoryMap().get("plan_day"));
        // M4 适配（2026-09-29_05）：worldgen 场景纳入 tool 类别（8192 档位）——执行器默认值已对齐
        assertEquals("tool", props.getCategoryMap().get("worldgen"));
        assertEquals("tool", props.getCategoryMap().get("world_time_infer"));
    }
}
