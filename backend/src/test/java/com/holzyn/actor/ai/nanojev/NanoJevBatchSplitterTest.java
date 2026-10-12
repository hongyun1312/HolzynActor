package com.holzyn.actor.ai.nanojev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * N1 接入基建单测：批量拆分器（官方契约三轴上限：states≤32 / questions≤96 / 路径≤256 / body≤2MB）。
 *
 * <p>测试纪律：纯 JUnit5；分布级断言（批数/批内个数）而非点断言。</p>
 *
 * <p>所属模块：ai/nanojev（N 轨道 N1）</p>
 */
class NanoJevBatchSplitterTest {

    private static NanoJevProperties props() {
        NanoJevProperties p = new NanoJevProperties();
        p.setMaxStatesPerRequest(32);
        p.setMaxQuestionsPerRequest(96);
        p.setMaxPathsPerRequest(256);
        p.setMaxBodyBytes(2 * 1024 * 1024);
        return p;
    }

    /** 构造一个含 qCount 个 boolean 题的 state（每题 1 路径）。 */
    private static NanoJevState boolState(String id, int qCount) {
        Map<String, NanoJevQuestion> qs = NanoJevState.newQuestions();
        for (int i = 0; i < qCount; i++) {
            qs.put("q" + i, NanoJevQuestion.bool("命题" + i));
        }
        return NanoJevState.text(id, "上下文", qs);
    }

    @Test
    @DisplayName("单 state 单批；states 超 32 自动分 2 批")
    void splitsByStates() {
        List<NanoJevState> states = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            states.add(boolState("s" + i, 1));
        }
        List<List<NanoJevState>> batches = NanoJevBatchSplitter.split(states, props());
        assertEquals(2, batches.size(), "33 states → 32+1 两批");
        assertEquals(32, batches.get(0).size());
        assertEquals(1, batches.get(1).size());
    }

    @Test
    @DisplayName("questions 超 96 自动分 2 批（青禾村 20 NPC × 5 题 = 100 题场景）")
    void splitsByQuestions() {
        List<NanoJevState> states = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            states.add(boolState("s" + i, 5));
        }
        List<List<NanoJevState>> batches = NanoJevBatchSplitter.split(states, props());
        // 100 题 > 96 → 2 批（19 批不了：19×5=95 ≤96 → 19+1=20 个 state，剩余 1 state 5 题）
        assertEquals(2, batches.size(), "100 题 → 2 批");
        assertEquals(19, batches.get(0).size(), "首批 19 state × 5 题 = 95 题");
        assertEquals(1, batches.get(1).size(), "次批 1 state × 5 题");
    }

    @Test
    @DisplayName("候选路径超 256 自动分 2 批（choice 大候选集场景）")
    void splitsByPaths() {
        Map<String, NanoJevQuestion> qs = NanoJevState.newQuestions();
        Map<String, String> candidates = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            candidates.put("c" + i, "候选" + i);
        }
        qs.put("pick", NanoJevQuestion.choice("选一个", candidates));
        List<NanoJevState> states = List.of(
                NanoJevState.text("s1", "ctx", qs),
                NanoJevState.text("s2", "ctx", qs));
        List<List<NanoJevState>> batches = NanoJevBatchSplitter.split(states, props());
        // 400 路径 > 256 → 2 批，每批 1 state
        assertEquals(2, batches.size());
        assertEquals(1, batches.get(0).size());
        assertEquals(1, batches.get(1).size());
    }

    @Test
    @DisplayName("单 state 自身超限（>96 题）独立成批，不拆题")
    void oversizedSingleStateOwnBatch() {
        List<NanoJevState> states = List.of(boolState("huge", 100));
        List<List<NanoJevState>> batches = NanoJevBatchSplitter.split(states, props());
        assertEquals(1, batches.size(), "单 state 超限独立成批（服务端 500 由 fail-closed 兜底）");
        assertEquals(1, batches.get(0).size());
    }

    @Test
    @DisplayName("请求体字节超限按 state 粒度再拆（小 body 上限注入验证）")
    void splitsByBodyBytes() {
        NanoJevProperties p = props();
        p.setMaxBodyBytes(500);
        List<NanoJevState> states = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, NanoJevQuestion> qs = NanoJevState.newQuestions();
            qs.put("q", NanoJevQuestion.bool("命题"));
            states.add(NanoJevState.text("s" + i, "很长的上下文内容，用于撑大请求体。" .repeat(50), qs));
        }
        List<List<NanoJevState>> batches = NanoJevBatchSplitter.split(states, p);
        assertEquals(3, batches.size(), "每批 1 state（各 state 自身超 500 字节）");
    }

    @Test
    @DisplayName("空输入返回空批次；统计口径")
    void emptyAndStats() {
        assertEquals(0, NanoJevBatchSplitter.split(List.of(), props()).size());
        List<NanoJevState> states = List.of(boolState("s1", 2), boolState("s2", 3));
        assertEquals(5, NanoJevBatchSplitter.totalQuestions(states));
        assertEquals(5, NanoJevBatchSplitter.totalPaths(states), "boolean 每题 1 路径");
    }
}
