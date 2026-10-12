package com.holzyn.actor.domain.growth.persona;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PersonaRenderer 单元测试（O6 轨道 G1a：档案分片渲染器）。
 * <p>职责：以基准夹具（沈砚秋 11 维全量视图）为输入，验证三件事——</p>
 * <ol>
 *   <li><b>11 维完整性</b>：全量渲染产出 11 个段、含预期标题；</li>
 *   <li><b>篇幅对齐 O6 实测基线</b>：全量渲染 ≈3120 字符 / ≈1930 token（实测标定 0.62）；
 *       决策场景分片 ≈650 token（相对现状角色卡 3263 token 降约 80%）；</li>
 *   <li><b>确定性</b>：同一输入逐字节一致（保障 Prompt 稳定前缀与缓存命中）。</li>
 * </ol>
 * <p>所属模块：domain/growth/persona（测试）。</p>
 */
class PersonaRendererTest {

    /** 基准夹具类路径 */
    private static final String FIXTURE = "growth/persona_sample_shenyanqiu.json";

    /** 渲染器（无状态，可直接 new） */
    private final PersonaRenderer renderer = new PersonaRenderer();

    /** 读取基准夹具。 */
    private PersonaProfile fixture() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(in, "基准夹具缺失：" + FIXTURE);
            return PersonaJson.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** 基准夹具对应的模块投影上下文（结构列/阶段/谱系/知识/目标由既有模块提供）。 */
    private PersonaRenderContext fixtureContext() {
        PersonaRenderContext c = new PersonaRenderContext();
        c.setName("沈砚秋");
        c.setGender("男");
        c.setRace("人族");
        c.setSubRace("汉");
        c.setTitle("青禾村村长 · 沈氏宗族族长");
        c.setAffiliation("沈氏宗族 / 青禾村公所");
        c.setOccupation("村长（兼宗族族长、里正）");
        c.setLocation("沈氏祠堂");
        c.setCurrentActivity("在祠堂与族老议事");
        c.setAgeYears(62);
        c.setBirthLabel("青禾历前 62 年 3 月 11 日");
        c.setStageLabel("老年期（ELDER）");
        c.setStageEnteredLabel("青禾历 53 年 1 月（60 岁）");
        c.setCapacityRatio(0.61);
        c.setPeakAge(30d);
        c.setDeathRiskAnnual(0.02);
        return c;
    }

    /**
     * 全量渲染：11 个段、首尾标题正确。
     */
    @Test
    void renderFullHasElevenBlocks() throws IOException {
        PersonaProfile p = fixture();
        String text = renderer.renderFull(p, fixtureContext());
        PersonaRenderer.Measure m = PersonaRenderer.measure(text);
        assertEquals(11, m.blocks(), "全量渲染应产出 11 个段（11 维）");
        assertTrue(text.contains("【身份与社会】"), "应含 ① 标题");
        assertTrue(text.contains("【社会面具】"), "应含 ⑪ 标题");
        assertTrue(text.contains("【发育·遗传·谱系】"), "应含 ⑩ 标题");
        assertTrue(text.contains("沈砚秋"), "应含姓名（结构列投影）");
        assertTrue(text.contains("青禾方言"), "应含语言指纹内容");
        assertTrue(text.contains("触发物"), "应含创伤触发物");
    }

    /**
     * 全量渲染篇幅对齐 O6 实测基线（3120 字符 ≈ 1930 token；允许 ±25% 工程冗余）。
     */
    @Test
    void renderFullMatchesOctBaseline() throws IOException {
        String text = renderer.renderFull(fixture(), fixtureContext());
        int chars = text.length();
        int measured = PersonaRenderer.measuredTokens(text);
        assertTrue(chars >= 2600 && chars <= 4000,
                "全量渲染字符数应贴近 O6 基线 3120（±25% 工程冗余），实际 " + chars);
        assertTrue(measured >= 1400 && measured <= 2500,
                "按 0.62 token/字符 对照 O6 基线 ≈1930，实际 " + measured);
    }

    /**
     * 渲染确定性：同输入两次渲染逐字节一致（Prompt 稳定前缀与缓存命中的前提）。
     */
    @Test
    void renderIsDeterministic() throws IOException {
        PersonaProfile p = fixture();
        PersonaRenderContext c = fixtureContext();
        assertEquals(renderer.renderFull(p, c), renderer.renderFull(p, c), "同一输入必须逐字节一致");
        assertEquals(renderer.renderShardsForScene(p, c, "action_gen"),
                renderer.renderShardsForScene(p, c, "action_gen"), "分片渲染同样必须确定");
    }

    /**
     * 决策场景分片：全量注入（2026-10-10 决策：不设分片字符预算硬边界，信息完整进 AI 请求）。
     */
    @Test
    void renderDecisionShardsFullInjection() throws IOException {
        String text = renderer.renderShardsForScene(fixture(), fixtureContext(), "action_gen");
        // 分片组合：S1 S2 S4 S6 S8——恰好 5 段
        assertEquals(5, PersonaRenderer.measure(text).blocks(), "决策场景应注入 5 个分片");
        assertTrue(text.contains("【身份锚定】") && text.contains("【人格与价值观】")
                        && text.contains("【身体与生理】") && text.contains("【习惯与日常】")
                        && text.contains("【社会面具】"),
                "决策场景分片组合应为 S1 S2 S4 S6 S8");
        assertFalse(text.contains("【语言指纹】"), "决策场景不应注入 S3（语言指纹）");
        // 不设预算：S4 体质核心行完整（力量/耐力/敏捷/平衡），不再被分片预算截断成「力量 6、…」
        assertTrue(text.contains("力量") && text.contains("耐力") && text.contains("敏捷") && text.contains("平衡"),
                "S4 体质核心行应完整（此前被分片字符预算截断）");
    }

    /**
     * SALL 全量开关展开为 S1~S11；未知分片码被忽略且不报错。
     */
    @Test
    void renderShardsSallExpands() throws IOException {
        PersonaProfile p = fixture();
        PersonaRenderContext c = fixtureContext();
        String sall = renderer.renderShards(p, c, List.of(PersonaSchema.Shards.SALL));
        assertEquals(11, PersonaRenderer.measure(sall).blocks(), "SALL 应展开为 11 个分片");
        String unknown = renderer.renderShards(p, c, List.of("S99"));
        assertEquals("", unknown, "未知分片码应被忽略（不抛异常、不产出内容）");
    }

    /**
     * 按维渲染子集（主前端只读区块 / 调试页逐维对照）。
     */
    @Test
    void renderDimsSubset() throws IOException {
        String text = renderer.renderDims(fixture(), fixtureContext(),
                List.of(PersonaSchema.Dims.MASK, PersonaSchema.Dims.VOICE));
        assertEquals(2, PersonaRenderer.measure(text).blocks(), "按维渲染应只产出请求的两维");
        assertTrue(text.contains("【社会面具】") && text.contains("【语言与表达】"));
        assertFalse(text.contains("【身份与社会】"), "未请求的维不应出现");
    }

    /**
     * 面具分片只注入"此刻相关"的情境（上下文给定时不再罗列全部 8 个情境）。
     */
    @Test
    void maskShardUsesCurrentContexts() throws IOException {
        PersonaProfile p = fixture();
        PersonaRenderContext c = fixtureContext();
        c.setMaskContexts(List.of("before_rival", "public_place"));
        String s8 = renderer.renderShards(p, c, List.of(PersonaSchema.Shards.S8));
        assertTrue(s8.contains("对仇怨对象"), "应注入当前情境（对仇怨对象）");
        assertTrue(s8.contains("公共场合"), "应注入当前情境（公共场合）");
        assertFalse(s8.contains("对伴侣"), "未命中的情境不应注入");
        assertTrue(s8.contains("面具破绽"), "破绽（失态触发物）必须始终保留");
    }

    /**
     * 空档案与空上下文：不抛异常，产出空串（零阻断）。
     */
    @Test
    void renderNullProfileIsBlank() {
        assertEquals("", renderer.renderFull(null, null));
        assertEquals("", renderer.renderShards(null, null, List.of(PersonaSchema.Shards.S1)));
        assertEquals("", renderer.renderFull(PersonaProfile.empty(1L, 1L), null),
                "空档案（无任何维）应产出空串");
    }

    /**
     * 输出渲染态样本到 {@code target/growth/}，供阶段②-1 人工审阅
     * （正式基线样本由本测试产物复制到 src/test/resources 归档）。
     */
    @Test
    void dumpRenderSample() throws IOException {
        PersonaProfile p = fixture();
        PersonaRenderContext c = fixtureContext();
        String full = renderer.renderFull(p, c);
        String action = renderer.renderShardsForScene(p, c, "action_gen");
        String dialog = renderer.renderShardsForScene(p, c, "dialog_system");
        StringBuilder sb = new StringBuilder();
        sb.append("=== 全量 11 维（renderFull）===\n").append(full).append("\n\n");
        sb.append("=== 决策场景分片 action_gen（S1 S2 S4 S6 S8）===\n").append(action).append("\n\n");
        sb.append("=== 单聊场景分片 dialog_system（S1 S2 S3 S7 S8 S10）===\n").append(dialog).append("\n\n");
        sb.append("=== 量测 ===\n");
        sb.append("全量：").append(PersonaRenderer.measure(full)).append('\n');
        sb.append("决策：").append(PersonaRenderer.measure(action)).append('\n');
        sb.append("单聊：").append(PersonaRenderer.measure(dialog)).append('\n');
        Path out = Path.of("target", "growth", "persona_render_sample_shenyanqiu.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        assertTrue(Files.exists(out), "渲染样本应写入 " + out.toAbsolutePath());
    }

    /**
     * 能力自评卡（P1 ①-2）：S5 能力与知识分片——有自评输出「自评」行（领域=分数 + 整体一句）；
     * 无自评不输出该行（旧数据兼容）。
     */
    @Test
    void abilityShardRendersSelfEfficacy() throws IOException {
        PersonaProfile p = fixture();
        PersonaDims.Ability ab = p.getDims().getAbility() == null ? new PersonaDims.Ability() : p.getDims().getAbility();
        ab.getSelfEfficacy().put("农事耕作", 4);
        ab.getSelfEfficacy().put("记账算筹", 2);
        ab.setSelfComment("农活是把好手，就是算账总犯迷糊");
        p.getDims().setAbility(ab);

        String s5 = renderer.renderShards(p, fixtureContext(), List.of(PersonaSchema.Shards.S5));
        assertTrue(s5.contains("自评"), "S5 应含自评行");
        assertTrue(s5.contains("农事耕作=4/5"), "自评领域+分数");
        assertTrue(s5.contains("记账算筹=2/5"), "自评领域+分数（低分领域）");
        assertTrue(s5.contains("整体：农活是把好手，就是算账总犯迷糊"), "整体自评一句");
    }

    /**
     * 能力自评卡兼容：无自评（旧档案）→ 不输出「自评」行（零噪音）。
     */
    @Test
    void abilityShardSkipsEmptySelfEfficacy() throws IOException {
        PersonaProfile p = fixture();
        if (p.getDims().getAbility() != null) {
            p.getDims().getAbility().getSelfEfficacy().clear();
            p.getDims().getAbility().setSelfComment(null);
        }
        String s5 = renderer.renderShards(p, fixtureContext(), List.of(PersonaSchema.Shards.S5));
        assertFalse(s5.contains("自评"), "无自评不应输出自评行：" + s5);
    }
}