package com.holzyn.actor.ai.nanojev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N1 接入基建单测：NanoJev 响应解析 + 静默回退防护（官方《NanoJev调用文档_V1.0.md》§4.3 实测样例）。
 *
 * <p>测试纪律：纯 JUnit5；分布级断言（argmax 项/概率区间）而非点断言。</p>
 *
 * <p>所属模块：ai/nanojev（N 轨道 N1）</p>
 */
class NanoJevEvaluateResponseTest {

    /** 官方实测样例响应（《NanoJev调用文档_V1.0.md》§4.3，本机 2026-09-20 实测可复现）。 */
    private static final String OFFICIAL_SAMPLE = """
            {
              "schema_version": "openjev-toy-inference-v1",
              "checkpoint": {"directory": "C:\\\\models\\\\NanoJev-unified", "base_model": "Qwen/Qwen3-0.6B", "set_head": "attention"},
              "temperature": {"value": 1.0, "fitted_by_this_command": false},
              "execution": {
                "device": "cuda:0", "precision": "bf16",
                "states": 1, "questions": 3, "candidate_paths": 6,
                "forward_passes": 1, "autoregressive_decode_steps": 0,
                "network_model_calls": 0, "persistent_model_load_count": 1,
                "inference_call_index": 3, "server_evaluation_seconds": 0.0417
              },
              "states": [
                {"id": "s1", "answers": {
                  "device": {"type": "choice",
                    "probabilities": {"unit_0": 0.031026, "unit_1": 0.968974},
                    "choice": "unit_1", "value": "unit_1"},
                  "execute": {"type": "boolean",
                    "probabilities": {"false": 0.117572, "true": 0.882428},
                    "p_true": 0.882428, "value": true},
                  "risk": {"type": "score",
                    "probabilities": {"0": 0.700252, "1": 0.100684, "2": 0.199064},
                    "score": 0.498812, "level": 0, "value": 0.498812}
                }}
              ]
            }
            """;

    @Test
    @DisplayName("官方样例响应解析：execution 元数据 + 三题型答案完整")
    void parsesOfficialSample() {
        NanoJevEvaluateResponse resp = NanoJevEvaluateResponse.parse(OFFICIAL_SAMPLE);
        assertNotNull(resp, "官方样例必须可解析");
        // execution 元数据
        assertEquals(1, resp.execution().states());
        assertEquals(3, resp.execution().questions());
        assertEquals(6, resp.execution().candidatePaths());
        assertEquals(1, resp.execution().forwardPasses(), "forward_passes=1 防静默回退");
        assertEquals(0, resp.execution().decodeSteps());
        assertEquals("cuda:0", resp.execution().device());
        assertTrue(resp.isForwardProof(), "一次前向零解码证明");
        assertFalse(resp.isChatFallback(), "官方响应不是 chat 回退");
        // choice：完整分布 + argmax 项
        NanoJevAnswer device = resp.answerOf("s1", "device");
        assertEquals(NanoJevQuestion.TYPE_CHOICE, device.type());
        assertEquals(2, device.probabilities().size());
        assertEquals("unit_1", device.choice(), "argmax=unit_1");
        assertTrue(device.probabilityOf("unit_1") > 0.9, "unit_1 概率 0.969");
        assertTrue(device.normalized(), "分布归一");
        // boolean：p_true 与阈值化值
        NanoJevAnswer execute = resp.answerOf("s1", "execute");
        assertEquals(Boolean.TRUE, execute.value());
        assertTrue(Math.abs(execute.pTrue() - 0.882428) < 1e-6);
        // score：期望值 + argmax 档位
        NanoJevAnswer risk = resp.answerOf("s1", "risk");
        assertEquals(0, risk.level(), "argmax 档位=0");
        assertTrue(Math.abs(risk.score() - 0.498812) < 1e-6);
        assertTrue(risk.normalized());
    }

    @Test
    @DisplayName("静默回退防护：chat 形状响应（choices+无 execution）必须被识别")
    void detectsChatFallback() {
        String chatFallback = """
                {"choices":[{"message":{"role":"assistant","content":"Hello!"}}],
                 "model":"qwen3.6-35b-q4-un","usage":{"completion_tokens":8,"prompt_tokens":13}}
                """;
        NanoJevEvaluateResponse resp = NanoJevEvaluateResponse.parse(chatFallback);
        // chat 形状无 execution → 解析失败（null），客户端按失败处置
        assertNull(resp, "chat 回退响应必须解析失败（客户端 fail-closed）");
    }

    @Test
    @DisplayName("静默回退防护：execution 非一次前向（forward_passes!=1）必须判定不通过")
    void rejectsNonForwardProof() {
        String bad = OFFICIAL_SAMPLE.replace("\"forward_passes\": 1", "\"forward_passes\": 2");
        NanoJevEvaluateResponse resp = NanoJevEvaluateResponse.parse(bad);
        assertNotNull(resp, "形状合法但数值非法仍可解析（由 isForwardProof 把关）");
        assertFalse(resp.isForwardProof(), "forward_passes=2 必须判定不通过");
    }

    @Test
    @DisplayName("确定性缓存命中（cache_hit=true + forward_passes=0）必须判定通过（2026-09-27 第 2 轮抓修）")
    void acceptsDeterministicCacheHit() {
        // 上游 HolzynLLMServer 缓存命中路径实测形状：forward_passes=0 + cache_hit=true + ~7e-5s
        String cached = OFFICIAL_SAMPLE
                .replace("\"forward_passes\": 1", "\"forward_passes\": 0")
                .replace("\"server_evaluation_seconds\": 0.0417",
                        "\"server_evaluation_seconds\": 7.18e-05, \"cache_hit\": true, \"cache_age_seconds\": 0.095");
        NanoJevEvaluateResponse resp = NanoJevEvaluateResponse.parse(cached);
        assertNotNull(resp, "缓存命中响应形状合法");
        assertTrue(resp.execution().cacheHit(), "cache_hit=true 必须被解析");
        assertEquals(0, resp.execution().forwardPasses(), "缓存命中不跑前向：forward_passes=0");
        assertTrue(resp.isForwardProof(), "确定性缓存命中=语义等价的合法结果，不得判失败");
        assertFalse(resp.isChatFallback(), "缓存命中不是 chat 回退");
    }

    @Test
    @DisplayName("无 cache_hit 字段（老版本服务）仍按 forward_passes 严格判定（向后兼容）")
    void keepsStrictGuardWithoutCacheField() {
        String zero = OFFICIAL_SAMPLE.replace("\"forward_passes\": 1", "\"forward_passes\": 0");
        NanoJevEvaluateResponse resp = NanoJevEvaluateResponse.parse(zero);
        assertNotNull(resp);
        assertFalse(resp.execution().cacheHit(), "缺字段默认 false");
        assertFalse(resp.isForwardProof(), "无 cache_hit 且 forward_passes=0 = 真静默回退，必须不通过");
    }

    @Test
    @DisplayName("非法/空响应解析失败返回 null（宽松回退）")
    void parseFailureReturnsNull() {
        assertNull(NanoJevEvaluateResponse.parse(null));
        assertNull(NanoJevEvaluateResponse.parse(""));
        assertNull(NanoJevEvaluateResponse.parse("not-json"));
        assertNull(NanoJevEvaluateResponse.parse("{\"no_execution\":true}"));
        assertNull(NanoJevEvaluateResponse.parse("{\"execution\":{}}"));
    }
}
