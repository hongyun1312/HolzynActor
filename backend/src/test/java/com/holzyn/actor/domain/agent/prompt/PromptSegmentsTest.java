package com.holzyn.actor.domain.agent.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 段装配去重单元测试（与实现同包）。
 *
 * <p>职责：锁定两条边界——① 同一段头 + 同一内容（含截断长度不同的前缀关系）只留一份，
 * 且保留更完整的那份；② <b>同一段头 + 不同内容必须都留下</b>——实跑里
 * {@code 【当前状态】有点闷}（体感一句）与 {@code 【当前状态】}+需求/心情清单（状态模块详细段）
 * 同名不同义，按段头硬删会删掉角色的真实状态。</p>
 */
class PromptSegmentsTest {

    /** 段头识别：行首【…】/版本行；句中/普通文本不算。 */
    @Test
    void headerKeyRecognizesOnlyLineStartHeaders() {
        assertEquals("【当前环境】", PromptSegments.headerKey("【当前环境】冬日的傍晚，天已经全黑了"));
        assertEquals("世界状态版本", PromptSegments.headerKey("[世界状态版本 gs62287774161/v7]"));
        assertEquals("【所处之地】", PromptSegments.headerKey("  【所处之地】河神庙"));
        // 句中出现的段名不是段头（【决策要求】正文里常顺带提【时间感】：那一段的行首是普通文字）
        assertNull(PromptSegments.headerKey("由你自己判断。【时间感】留意现在的时刻与处境，时机未到可以先等"));
        // 普通文本
        assertNull(PromptSegments.headerKey("去集市买米"));
        assertNull(PromptSegments.headerKey(""));
        assertNull(PromptSegments.headerKey(null));
    }

    /** 【当前环境】双份（人设段 + 感知段各注一次，内容一致）→ 只留第一份。 */
    @Test
    void dedupeRemovesSecondEnvironmentSegment() {
        String prompt = "【人设】老王\n\n"
                + "【当前环境】冬日傍晚，天已经全黑了（体感 −3℃）\n\n"
                + "【环境偏好】怕冷，夜里不愿出门\n\n"
                + "【当前环境】冬日傍晚，天已经全黑了（体感 −3℃）\n\n"
                + "【要求】按人设行动";
        String out = PromptSegments.dedupe(prompt);
        assertEquals(1, count(out, "【当前环境】"), "环境段只允许一份");
        assertEquals(1, count(out, "【环境偏好】"), "其它段不受影响");
        assertTrue(out.contains("【人设】老王"));
        assertTrue(out.contains("【要求】按人设行动"));
    }

    /**
     * 截断长度不同的同一段（人设段 140 字 vs 感知段 120 字）：互为前缀 → 只留更完整的那份。
     */
    @Test
    void dedupeKeepsLongerVariantWhenPrefixRelated() {
        String longer = "【当前环境】冬日傍晚，天已经全黑了，北风裹着雪粒（体感 −9℃，日落 17:30）";
        String shorter = "【当前环境】冬日傍晚，天已经全黑了";
        String prompt = longer + "\n\n【要求】按人设行动\n\n" + shorter;
        String out = PromptSegments.dedupe(prompt);
        assertEquals(1, count(out, "【当前环境】"), "前缀关系视为同一段：" + out);
        assertTrue(out.contains("北风裹着雪粒"), "应保留更完整的那份：" + out);
    }

    /** 版本行重复（同一版本两次）→ 只留一份。 */
    @Test
    void dedupeRemovesRepeatedVersionLine() {
        String prompt = "【我所知道的事】\n- 井水变浑（来源：事件#12）\n[世界状态版本 gs1/v3]\n\n"
                + "【当前所见】\n[世界状态版本 gs1/v3]";
        String out = PromptSegments.dedupe(prompt);
        assertEquals(1, count(out, "[世界状态版本"), "版本行只允许一份");
        assertEquals(1, count(out, "【我所知道的事】"));
        assertTrue(out.contains("【当前所见】"), "后续段保留");
    }

    /** 重复段带续行：整块丢弃（不是只删标题行留下孤儿内容）。 */
    @Test
    void dedupeDropsWholeDuplicateBlock() {
        String prompt = "【世界动态】\n- 邻镇收茧\n- 铁料断供\n\n"
                + "【世界动态】\n- 邻镇收茧\n- 铁料断供\n\n"
                + "【要求】自己掂量";
        String out = PromptSegments.dedupe(prompt);
        assertEquals(1, count(out, "【世界动态】"));
        assertEquals(1, count(out, "铁料断供"), "重复块的续行必须一起丢掉");
        assertTrue(out.contains("【要求】自己掂量"));
    }

    /**
     * <b>安全边界</b>：同一段头 + 不同内容 = 两个不同的东西，必须都留下。
     * （实跑 {@code proactive_retrieval} 里 【当前状态】 同时承载「一句体感」与「状态清单」）
     */
    @Test
    void dedupeKeepsSameHeaderWithDifferentContent() {
        String prompt = "【当前状态】有点闷\n\n"
                + "【当前世界动态】\n- 河神庙夜聚\n\n"
                + "【当前状态】\n- 需求：饥饿 43/100（欠佳）\n- 心情：47/100；情绪：calm（强度 81）";
        String out = PromptSegments.dedupe(prompt);
        assertEquals(2, count(out, "【当前状态】"), "同名不同义必须都留下：" + out);
        assertTrue(out.contains("有点闷"), "体感那句保留");
        assertTrue(out.contains("饥饿 43/100"), "状态清单保留");
    }

    /** 无重复时原样返回（不重排、不删空行）。 */
    @Test
    void dedupeKeepsPromptUnchangedWhenNoDuplicate() {
        String prompt = "【任务】\n做点事\n\n【输入】\n情境\n\n【输出】\nJSON";
        assertEquals(prompt, PromptSegments.dedupe(prompt));
    }

    /** 单段（含同行正文）原样返回。 */
    @Test
    void dedupeKeepsSingleOccurrenceContent() {
        String prompt = "【当前环境】冬日傍晚，天已经全黑了\n【所处之地】河神庙\n- 这里眼下：老樟树下有人夜谈";
        assertEquals(prompt, PromptSegments.dedupe(prompt));
    }

    /** null/空白原样返回（零阻断）。 */
    @Test
    void dedupePassesThroughNullAndBlank() {
        assertNull(PromptSegments.dedupe(null));
        assertEquals("", PromptSegments.dedupe(""));
        assertEquals("   ", PromptSegments.dedupe("   "));
    }

    private static int count(String text, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }
}
