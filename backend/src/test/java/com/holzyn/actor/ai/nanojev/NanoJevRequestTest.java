package com.holzyn.actor.ai.nanojev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N1 接入基建单测：NanoJev 请求契约构造/序列化/校验（官方《NanoJev调用文档_V1.0.md》§4.2）。
 *
 * <p>测试纪律：纯 JUnit5 无 Spring 上下文；分布级断言（argmax 项）而非点断言。</p>
 *
 * <p>所属模块：ai/nanojev（N 轨道 N1）</p>
 */
class NanoJevRequestTest {

    @Test
    @DisplayName("choice/boolean/score 三题型序列化为官方契约形状")
    void serializesThreeQuestionTypes() {
        // choice 候选用 LinkedHashMap 保证序列化顺序可断言（Map.of 迭代序不确定）
        java.util.Map<String, String> candidates = new java.util.LinkedHashMap<>();
        candidates.put("unit_0", "balcony speaker");
        candidates.put("unit_1", "dining room fan");
        Map<String, NanoJevQuestion> qs = NanoJevState.newQuestions();
        qs.put("device", NanoJevQuestion.choice("Select exactly the room named in the request.", candidates));
        qs.put("execute", NanoJevQuestion.bool("Is execution allowed? Authorization required for every command.",
                "not allowed", "allowed"));
        qs.put("risk", NanoJevQuestion.score("Classify the request by these rules.",
                List.of("Authorized and safe", "Not authorized")));
        NanoJevRequest req = new NanoJevRequest(List.of(NanoJevState.text("s1", "Home log: fan on.", qs)));
        String json = req.toJson();
        // 顶层只能 states；三题型字段齐全
        assertTrue(json.contains("\"states\""), "顶层必须为 states：\n" + json);
        assertTrue(json.contains("\"type\":\"choice\"") && json.contains("\"criteria\":{\"unit_0\":\"balcony speaker\""));
        assertTrue(json.contains("\"type\":\"boolean\"") && json.contains("\"criteria\":{\"false\":\"not allowed\",\"true\":\"allowed\"}"));
        assertTrue(json.contains("\"type\":\"score\"") && json.contains("\"criteria\":[\"Authorized and safe\",\"Not authorized\"]"));
        // boolean 无显式判据时不写 criteria 键（官方契约：criteria 可选）
        Map<String, NanoJevQuestion> qs2 = NanoJevState.newQuestions();
        qs2.put("ok", NanoJevQuestion.bool("Is it ok?"));
        String json2 = new NanoJevRequest(List.of(NanoJevState.text("s1", "ctx", qs2))).toJson();
        assertTrue(json2.contains("\"type\":\"boolean\""));
        assertTrue(!json2.contains("criteria"), "boolean 无判据时不得输出 criteria 键");
    }

    @Test
    @DisplayName("契约校验：instructions 空 / choice 候选越界 / score 档位越界 / boolean 判据越界 均抛错")
    void validatesContract() {
        assertThrows(IllegalArgumentException.class,
                () -> NanoJevQuestion.choice("  ", Map.of("a", "1", "b", "2")), "instructions 空白必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> NanoJevQuestion.choice("选一个", Map.of("a", "1")), "choice 少于 2 候选必须拒绝");
        Map<String, String> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 256; i++) {
            tooMany.put("c" + i, "候选" + i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> NanoJevQuestion.choice("选一个", tooMany), "choice 超过 255 候选必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> NanoJevQuestion.score("分档", List.of("一")), "score 少于 2 档必须拒绝");
        List<String> tooManyLevels = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            tooManyLevels.add("档" + i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> NanoJevQuestion.score("分档", tooManyLevels), "score 超过 10 档必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new NanoJevQuestion(NanoJevQuestion.TYPE_BOOLEAN, "是否",
                        Map.of("false", "x", "true", "y", "extra", "z"), null),
                "boolean criteria 只允许 false/true 两键（构造校验）");
        assertThrows(IllegalArgumentException.class,
                () -> new NanoJevQuestion("mystery", "题", Map.of("a", "1"), null), "未知题型必须拒绝");
        // 空 questions / 空 id / 空 state 拒绝
        assertThrows(IllegalArgumentException.class,
                () -> new NanoJevState("s1", "ctx", Map.of()), "空 questions 必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new NanoJevState("", "ctx", Map.of("q", NanoJevQuestion.bool("题"))), "空 id 必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new NanoJevState("s1", null, Map.of("q", NanoJevQuestion.bool("题"))), "空 state 必须拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> new NanoJevRequest(List.of()), "空 states 必须拒绝");
    }

    @Test
    @DisplayName("paramsSnapshot 与请求体字节数口径")
    void paramsSnapshotAndBodyBytes() {
        Map<String, NanoJevQuestion> qs = NanoJevState.newQuestions();
        qs.put("a", NanoJevQuestion.choice("选", Map.of("x", "1", "y", "2", "z", "3")));
        qs.put("b", NanoJevQuestion.bool("是否"));
        qs.put("c", NanoJevQuestion.score("分档", List.of("低", "高")));
        NanoJevRequest req = new NanoJevRequest(List.of(NanoJevState.text("s1", "上下文", qs)));
        // 候选路径 = choice 项数(3) + boolean(1) + score 档位数(2) = 6（与服务端 candidate_paths 口径一致）
        assertEquals("states=1, questions=3, paths=6", req.paramsSnapshot(), "paramsSnapshot 口径");
        assertTrue(req.bodyBytes() > 0, "请求体字节数必须为正");
        // 官方 2MB 上限远大于该请求
        assertTrue(req.bodyBytes() < 2 * 1024 * 1024);
    }

    @Test
    @DisplayName("fromJson 回放（调试页/测试用）：官方请求体可解析回请求对象")
    void fromJsonRoundTrip() {
        String json = "{\"states\":[{\"id\":\"s1\",\"state\":\"Home log: fan on.\","
                + "\"questions\":{\"device\":{\"type\":\"choice\",\"instructions\":\"Select the device.\","
                + "\"criteria\":{\"unit_0\":\"balcony speaker\",\"unit_1\":\"dining room fan\"}},"
                + "\"risk\":{\"type\":\"score\",\"instructions\":\"Classify.\","
                + "\"criteria\":[\"Authorized and safe\",\"Not authorized\"]}}}]}";
        NanoJevRequest req = NanoJevRequest.fromJson(json);
        assertEquals(1, req.states().size());
        assertEquals("s1", req.states().get(0).id());
        assertEquals(2, req.states().get(0).questions().size());
        NanoJevQuestion device = req.states().get(0).questions().get("device");
        assertEquals(NanoJevQuestion.TYPE_CHOICE, device.type());
        assertEquals(2, device.criteria().size());
        NanoJevQuestion risk = req.states().get(0).questions().get("risk");
        assertEquals(NanoJevQuestion.TYPE_SCORE, risk.type());
        assertEquals(2, risk.scoreCriteria().size());
        assertEquals("Home log: fan on.", req.states().get(0).state());
        // 非法 JSON → null（宽松回退）
        assertEquals(null, NanoJevRequest.fromJson("not-json"));
    }
}
