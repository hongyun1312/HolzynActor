package com.holzyn.actor.ai.guard;

import com.holzyn.actor.ai.AiCallException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一护栏策略对象单元测试（O3 评估与工程 P2-4，2026-09-21）。
 * <p>职责：验证——①默认参数解析；②yml 场景覆盖生效；③运行时覆盖优先级最高；
 * ④空输出判定；⑤输出重复检测（窗口/阈值/跨链路隔离）；⑥失败分类；⑦生效参数合并。</p>
 */
class GuardRailPolicyTest {

    private GuardRailPolicy policy(GuardRailProperties props, GuardRailTracker tracker) {
        return new GuardRailPolicy(props, tracker);
    }

    @Test
    void defaultEffectiveFromProperties() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailPolicy p = policy(props, new GuardRailTracker());
        GuardRailPolicy.Effective eff = p.effective("action");
        assertEquals(2, eff.maxRetries());
        assertEquals(1, eff.minOutputChars());
        assertTrue(eff.duplicateEnabled());
        assertEquals(3, eff.duplicateWindow());
        assertEquals(0.9, eff.duplicateDiceThreshold());
        assertEquals(10, eff.maxConsecutiveFailures());
    }

    @Test
    void ymlSceneOverrideApplies() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailProperties.SceneGuard guard = new GuardRailProperties.SceneGuard();
        guard.setMaxRetries(4);
        guard.setDuplicateWindow(5);
        props.getScenes().put("dialog", guard);
        GuardRailPolicy p = policy(props, new GuardRailTracker());
        assertEquals(4, p.maxRetries("dialog"));
        assertEquals(5, p.effective("dialog").duplicateWindow());
        // 未覆盖场景回退默认
        assertEquals(2, p.maxRetries("action"));
    }

    @Test
    void runtimeOverrideWinsOverYml() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailProperties.SceneGuard yml = new GuardRailProperties.SceneGuard();
        yml.setMaxRetries(4);
        props.getScenes().put("action", yml);
        GuardRailTracker tracker = new GuardRailTracker();
        GuardRailProperties.SceneGuard runtime = new GuardRailProperties.SceneGuard();
        runtime.setMaxRetries(6);
        tracker.putOverride("action", runtime);
        GuardRailPolicy p = policy(props, tracker);
        assertEquals(6, p.maxRetries("action"));
        // 移除运行时覆盖回到 yml
        tracker.removeOverride("action");
        assertEquals(4, p.maxRetries("action"));
    }

    @Test
    void blankOutputDetection() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailPolicy p = policy(props, new GuardRailTracker());
        assertTrue(p.isBlankOutput("action", null));
        assertTrue(p.isBlankOutput("action", ""));
        assertTrue(p.isBlankOutput("action", "   "));
        assertFalse(p.isBlankOutput("action", "好的，我去看看。"));
    }

    @Test
    void blankOutputThresholdRespectsOverride() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailProperties.SceneGuard guard = new GuardRailProperties.SceneGuard();
        guard.setMinOutputChars(10);
        props.getScenes().put("dialog", guard);
        GuardRailPolicy p = policy(props, new GuardRailTracker());
        // 8 字 < 10 字阈值 → 判空
        assertTrue(p.isBlankOutput("dialog", "你好呀。"));
        // 默认场景 1 字阈值 → 不判空
        assertFalse(p.isBlankOutput("action", "你好呀。"));
    }

    @Test
    void duplicateDetectedOnlyAfterWindowConsecutiveSimilarOutputs() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailPolicy p = policy(props, new GuardRailTracker());
        String same = "沈砚秋决定去青禾集市看看今日的行情，顺便采买一些药材。";
        // 窗口 3：前 2 次不触发（窗口未满），第 3 次起连续相同触发
        assertFalse(p.isDuplicate("action", "t-1", same));
        assertFalse(p.isDuplicate("action", "t-1", same));
        assertTrue(p.isDuplicate("action", "t-1", same));
        assertTrue(p.isDuplicate("action", "t-1", same));
    }

    @Test
    void duplicateIsolatedAcrossTraceLinks() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailPolicy p = policy(props, new GuardRailTracker());
        String same = "温叙决定在医馆里整理今天的药方。";
        // 同一链路连续 3 次触发
        assertFalse(p.isDuplicate("action", "link-a", same));
        assertFalse(p.isDuplicate("action", "link-a", same));
        assertTrue(p.isDuplicate("action", "link-a", same));
        // 不同链路不互相污染（前 2 次不触发）
        assertFalse(p.isDuplicate("action", "link-b", same));
        assertFalse(p.isDuplicate("action", "link-b", same));
    }

    @Test
    void duplicateThresholdDistinguishesSimilarFromDifferent() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailPolicy p = policy(props, new GuardRailTracker());
        String a = "沈砚秋前往青禾集市查看药材行情。";
        String b = "沈砚秋前往青禾集市查看药材行情。";
        String c = "白灵犀在村口溪边清洗衣裳。";
        // t-2 链路：a/b 完全相同 → 第 3 次触发复读
        assertFalse(p.isDuplicate("action", "t-2", a));
        assertFalse(p.isDuplicate("action", "t-2", b));
        assertTrue(p.isDuplicate("action", "t-2", a));
        // t-3 链路：前 2 次相同不触发，第 3 次连续相同触发
        assertFalse(p.isDuplicate("action", "t-3", c));
        assertFalse(p.isDuplicate("action", "t-3", c));
        assertTrue(p.isDuplicate("action", "t-3", c));
        // 链路隔离：t-2 不因 t-3 的 c 而变化（窗口独立）
        assertFalse(p.isDuplicate("action", "t-4", "不同链路第一次"));
        assertFalse(p.isDuplicate("action", "t-4", "不同链路第一次"));
    }

    @Test
    void duplicateDisabledBySceneOverride() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailProperties.SceneGuard guard = new GuardRailProperties.SceneGuard();
        guard.setDuplicateEnabled(false);
        props.getScenes().put("world_frame_daily", guard);
        GuardRailPolicy p = policy(props, new GuardRailTracker());
        String same = "今日晴，风平浪静，村里一切如常。";
        // 关闭重复检测 → 永不触发
        assertFalse(p.isDuplicate("world_frame_daily", "t-4", same));
        assertFalse(p.isDuplicate("world_frame_daily", "t-4", same));
        assertFalse(p.isDuplicate("world_frame_daily", "t-4", same));
    }

    @Test
    void failureClassification() {
        GuardRailProperties props = new GuardRailProperties();
        GuardRailPolicy p = policy(props, new GuardRailTracker());
        assertEquals(GuardRailPolicy.FailureKind.TIMEOUT,
                p.classify(new AiCallException("AI 调用超时（130 秒看门狗断开）")));
        assertEquals(GuardRailPolicy.FailureKind.TIMEOUT,
                p.classify(new AiCallException("Read timed out after 120000ms")));
        // connect timeout 含 timeout 关键词 → 归 TIMEOUT（超时是连接失败的首要症状）
        assertEquals(GuardRailPolicy.FailureKind.TIMEOUT,
                p.classify(new AiCallException("AI 调用失败（Spring AI）：connect timeout")));
        // 纯网络类失败（无 timeout 关键词）→ NETWORK
        assertEquals(GuardRailPolicy.FailureKind.NETWORK,
                p.classify(new AiCallException("AI 调用失败（Spring AI）：Connection refused")));
        assertEquals(GuardRailPolicy.FailureKind.EMPTY,
                p.classify(new AiCallException("AI 输出为空（护栏拦截）：模型未返回有效内容")));
        assertEquals(GuardRailPolicy.FailureKind.DUPLICATE,
                p.classify(new AiCallException("AI 输出重复（护栏拦截）：模型输出陷入复读")));
        assertEquals(GuardRailPolicy.FailureKind.OTHER,
                p.classify(new AiCallException("供应商返回 400：模型不支持该参数")));
        assertEquals(GuardRailPolicy.FailureKind.OTHER, p.classify(null));
    }

    @Test
    void nullSceneFallsBackToDefaults() {
        GuardRailPolicy p = policy(new GuardRailProperties(), new GuardRailTracker());
        assertEquals(2, p.maxRetries(null));
        assertFalse(p.isBlankOutput(null, "x"));
        assertTrue(p.effective(null).duplicateEnabled());
        assertEquals(0.9, p.effective(null).duplicateDiceThreshold());
    }
}
