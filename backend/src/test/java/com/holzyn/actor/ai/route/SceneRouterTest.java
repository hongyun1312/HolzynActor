package com.holzyn.actor.ai.route;

import com.holzyn.actor.ai.AiChatRequest;
import com.holzyn.actor.ai.AiChatRequest.ChatMessage;
import com.holzyn.actor.domain.settings.service.ModelApiService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景-模型路由器单元测试（O3 评估与工程 P2-5，2026-09-21）。
 * <p>职责：验证——①类别档位命中（decision/dialog/memory/world/crowd/tool）；
 * ②全局默认回退（未列出场景）；③yml 场景覆盖优先于类别；④运行时覆盖优先级最高；
 * ⑤applyToRequest 补档位（调用方显式值保留、空值补档位）；⑥供应商规则
 * （调用方显式 providerId 优先 / 路由 provider-id / 默认 null）；⑦生效表导出。</p>
 */
class SceneRouterTest {

    private SceneRouter router(SceneRoutingProperties props, ModelApiService modelApiService) {
        return new SceneRouter(props, modelApiService);
    }

    private SceneRoutingProperties props() {
        SceneRoutingProperties props = new SceneRoutingProperties();
        props.setDefaultTier(new SceneRoutingProperties.Tier(0.5, 2048));
        props.getTiers().put("decision", new SceneRoutingProperties.Tier(0.3, 2048));
        props.getTiers().put("dialog", new SceneRoutingProperties.Tier(0.8, 1024));
        props.getTiers().put("memory", new SceneRoutingProperties.Tier(0.2, 1024));
        props.getTiers().put("world", new SceneRoutingProperties.Tier(0.7, 2048));
        props.getTiers().put("crowd", new SceneRoutingProperties.Tier(0.6, 512));
        props.getCategoryMap().put("action", "decision");
        props.getCategoryMap().put("interrupt_resume", "decision");
        props.getCategoryMap().put("dialog", "dialog");
        props.getCategoryMap().put("autonomous_chat", "dialog");
        props.getCategoryMap().put("memory", "memory");
        props.getCategoryMap().put("chronicle_distill", "memory");
        props.getCategoryMap().put("world_frame_daily", "world");
        props.getCategoryMap().put("environment_desc", "world");
        props.getCategoryMap().put("crowd_ai", "crowd");
        return props;
    }

    private ModelApiService modelApiService() {
        return Mockito.mock(ModelApiService.class);
    }

    @Test
    void categoryTierResolved() {
        SceneRouter router = router(props(), modelApiService());
        assertEquals(0.3, router.resolve("action").temperature());
        assertEquals(2048, router.resolve("action").maxTokens());
        assertEquals(0.8, router.resolve("dialog").temperature());
        assertEquals(1024, router.resolve("dialog").maxTokens());
        assertEquals(0.2, router.resolve("memory").temperature());
        assertEquals(0.7, router.resolve("world_frame_daily").temperature());
        assertEquals(0.6, router.resolve("crowd_ai").temperature());
        assertEquals(SceneRouter.SOURCE_TIER, router.resolve("action").source());
        assertEquals("decision", router.resolve("action").category());
    }

    @Test
    void unknownSceneFallsBackToGlobalDefault() {
        SceneRouter router = router(props(), modelApiService());
        SceneRouter.Route r = router.resolve("some_unknown_scene");
        assertEquals(0.5, r.temperature());
        assertEquals(2048, r.maxTokens());
        assertEquals(SceneRouter.SOURCE_DEFAULT, r.source());
        assertNull(r.category());
        // null 场景同样全局默认
        assertEquals(0.5, router.resolve(null).temperature());
    }

    @Test
    void sceneOverrideWinsOverCategory() {
        SceneRoutingProperties props = props();
        props.getScenes().put("action", new SceneRoutingProperties.SceneRouteOverride(0.45, null, null));
        SceneRouter router = router(props, modelApiService());
        SceneRouter.Route r = router.resolve("action");
        assertEquals(0.45, r.temperature());
        // maxTokens 未覆盖 → 沿用类别档位
        assertEquals(2048, r.maxTokens());
        assertEquals(SceneRouter.SOURCE_SCENE, r.source());
        // 逐场景覆盖不能丢掉类别：决策模型的选取依赖 category=decision
        assertEquals("decision", r.category());
    }

    @Test
    void runtimeOverrideWinsOverEverything() {
        SceneRoutingProperties props = props();
        props.getScenes().put("action", new SceneRoutingProperties.SceneRouteOverride(0.45, null, null));
        SceneRouter router = router(props, modelApiService());
        router.putOverride("action", new SceneRoutingProperties.SceneRouteOverride(0.9, 64, 67L));
        SceneRouter.Route r = router.resolve("action");
        assertEquals(0.9, r.temperature());
        assertEquals(64, r.maxTokens());
        assertEquals(67L, r.providerId());
        assertEquals(SceneRouter.SOURCE_RUNTIME, r.source());
        // 移除运行时覆盖回到 yml 场景覆盖
        router.removeOverride("action");
        assertEquals(0.45, router.resolve("action").temperature());
    }

    @Test
    void applyToRequestRoutingWinsOverExplicitParams() {
        SceneRouter router = router(props(), modelApiService());
        SceneRouter.Route route = router.resolve("dialog"); // 0.8 / 1024（测试本地档位表）
        // 温度未设、长度未设 → 都补档位
        AiChatRequest req = new AiChatRequest(null, List.of(new ChatMessage("user", "你好")), null, null, false);
        AiChatRequest applied = router.applyToRequest(req, route);
        assertEquals(0.8, applied.temperature());
        assertEquals(1024, applied.maxTokens());
        // 用户决策（2026-09-21）路由优先：调用方显式温度/长度也被档位覆盖
        AiChatRequest explicit = new AiChatRequest(null, List.of(new ChatMessage("user", "你好")), 0.1, 32, false);
        AiChatRequest appliedExplicit = router.applyToRequest(explicit, route);
        assertEquals(0.8, appliedExplicit.temperature());
        assertEquals(1024, appliedExplicit.maxTokens());
        // 路由字段为空（如默认档位无该字段）→ 保留调用方原值
        AiChatRequest req2 = new AiChatRequest(null, List.of(new ChatMessage("user", "你好")), 0.55, null, false);
        AiChatRequest applied2 = router.applyToRequest(req2, new SceneRouter.Route(null, null, null, "default", null));
        assertEquals(0.55, applied2.temperature());
        // 路由为 null → 原样返回
        assertTrue(router.applyToRequest(req, null) == req);
    }

    @Test
    void effectiveProviderIdPrecedence() {
        SceneRouter router = router(props(), modelApiService());
        SceneRouter.Route routeWithProvider = new SceneRouter.Route(0.5, null, 67L, "scene", null);
        // 调用方显式 providerId 优先
        assertEquals(88L, router.effectiveProviderId(88L, routeWithProvider));
        // 路由 provider-id 次之
        assertEquals(67L, router.effectiveProviderId(null, routeWithProvider));
        // 都无 → null=默认 chat provider
        assertNull(router.effectiveProviderId(null, null));
    }

    @Test
    void effectiveTableContainsAllScenesWithSources() {
        SceneRouter router = router(props(), modelApiService());
        List<SceneRouter.EffectiveRow> rows = router.effectiveTable();
        assertTrue(rows.stream().anyMatch(r -> r.scene().equals("action") && r.source().equals(SceneRouter.SOURCE_TIER)));
        assertTrue(rows.stream().anyMatch(r -> r.scene().equals("dialog")));
        assertTrue(rows.stream().anyMatch(r -> r.scene().equals("(default)")));
        // 运行时覆盖后来源标记变化
        router.putOverride("action", new SceneRoutingProperties.SceneRouteOverride(0.9, null, null));
        assertTrue(router.effectiveTable().stream()
                .anyMatch(r -> r.scene().equals("action") && r.source().equals(SceneRouter.SOURCE_RUNTIME)));
    }

    @Test
    void disabledRouterStillResolvesButGatewaySkips() {
        SceneRoutingProperties props = props();
        props.setEnabled(false);
        SceneRouter router = router(props, modelApiService());
        assertFalse(router.enabled());
        // 关闭时 resolve 仍可用（供调试页预览），网关侧不应用
        assertEquals(0.3, router.resolve("action").temperature());
    }
}
