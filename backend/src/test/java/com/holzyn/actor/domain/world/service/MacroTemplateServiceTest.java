package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.config.MacroTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界观模板服务单元测试（宏观社会演化系统 M3，domain/world/service，同包）。
 * <p>职责：验证双维度模板抽象——内置 5 套模板参数（文明形态×力量体系/核心资源/季节联动强度/
 * 科技维度名/事件叙事）、世界观文本关键词推导（仙侠/高武/现代/未来/兜底）、手动覆盖与清除、
 * 事件叙事渲染（xianxia=通用仙侠村落文本）+ 模板字段缺失兜底。</p>
 */
class MacroTemplateServiceTest {

    private final MacroTemplateService service = new MacroTemplateService();

    /** 内置 5 套模板齐全，双维度语义正确。 */
    @Test
    void builtinsCoverFiveTemplates() {
        assertEquals(5, MacroTemplate.builtins().size());
        MacroTemplate xianxia = MacroTemplate.builtins().get(MacroTemplate.ID_XIANXIA);
        assertEquals(MacroTemplate.CIV_AGRARIAN, xianxia.civilization());
        assertEquals(MacroTemplate.POWER_XIANXIA, xianxia.power());
        assertEquals("粮", xianxia.coreResource());
        assertEquals(1.0, xianxia.seasonFactorScale(), 0.001, "仙侠季节全联动");
        MacroTemplate modern = MacroTemplate.builtins().get(MacroTemplate.ID_MODERN);
        assertEquals(MacroTemplate.CIV_INDUSTRIAL, modern.civilization());
        assertEquals("食物", modern.coreResource());
        assertEquals(0.4, modern.seasonFactorScale(), 0.001, "现代季节弱化");
        MacroTemplate future = MacroTemplate.builtins().get(MacroTemplate.ID_FUTURE);
        assertEquals(MacroTemplate.CIV_INFORMATION, future.civilization());
        assertEquals("能源", future.coreResource());
        assertEquals(0.15, future.seasonFactorScale(), 0.001, "未来气候控制近零");
        assertEquals("灵能AI", future.techName("array"), "未来模板法阵维度名=灵能AI（世界观适配）");
    }

    /** 世界观文本关键词推导：仙侠/高武/现代/未来/兜底。 */
    @Test
    void deriveFromSettingText() {
        assertEquals(MacroTemplate.ID_XIANXIA, service.deriveFromSetting(
                "仙侠村落，灵气充沛，神庙镇压妖邪"));
        assertEquals(MacroTemplate.ID_WUXIA, service.deriveFromSetting(
                "江湖风云，门派林立，内力武功争锋"));
        assertEquals(MacroTemplate.ID_MODERN, service.deriveFromSetting(
                "现代都市，公司林立，网络发达"));
        assertEquals(MacroTemplate.ID_FUTURE, service.deriveFromSetting(
                "星舰穿梭宇宙，能源争夺，人工智能崛起"));
        assertEquals(MacroTemplate.DEFAULT_ID, service.deriveFromSetting(""), "空文本回退默认仙侠");
        assertEquals(MacroTemplate.DEFAULT_ID, service.deriveFromSetting(null));
        // 无关键词文本 → 默认模板
        assertEquals(MacroTemplate.DEFAULT_ID, service.deriveFromSetting("一片普通的土地"));
    }

    /** 未知 id 回退默认；手动覆盖生效与清除。 */
    @Test
    void overrideAndClear() {
        assertEquals(MacroTemplate.DEFAULT_ID, service.of("unknown").id(), "未知模板回退默认");
        service.override(37L, MacroTemplate.ID_FUTURE);
        assertEquals(MacroTemplate.ID_FUTURE, service.forProject(37L).id());
        assertTrue(service.isManual(37L));
        service.clearOverride(37L);
        assertEquals(MacroTemplate.DEFAULT_ID, service.forProject(37L).id());
        assertFalse(service.isManual(37L));
    }

    /** xianxia 事件叙事渲染=通用仙侠村落文本（事件码=通用语义码）；占位符替换正确。 */
    @Test
    void xianxiaEventTextRendered() {
        MacroTemplate xianxia = service.of(MacroTemplate.ID_XIANXIA);
        String text = xianxia.renderEventText("price_surge", "第6日", null, "grain_price", "约30%");
        assertTrue(text.contains("第6日"));
        assertTrue(text.contains("暴涨约30%"));
        assertTrue(text.contains("粮行门前排起长队"), "通用仙侠文本保留");
        String plague = xianxia.renderEventText("plague", "第5日", "，铁匠", null, null);
        assertTrue(plague.contains("铁匠病倒了"), "具名 NPC 病倒句式");
    }

    /** modern 模板叙事为通用文本（非仙侠村落语义）。 */
    @Test
    void modernEventTextGeneric() {
        MacroTemplate modern = service.of(MacroTemplate.ID_MODERN);
        String text = modern.renderEventText("caravan", "第6日", null, null, null);
        assertTrue(text.contains("物流车"), "现代模板商队=物流车");
        assertFalse(text.contains("本村"), "现代模板不应含仙侠村落语义");
    }

    /**
     * 模板缺失兜底链：构造时空白标量归一化为通用默认；缺失语义码/维度/议题/组织骨架
     * 逐级回退通用值——任何取值恒非空、不抛 NPE。
     */
    @Test
    void missingFieldsFallBackToGeneric() {
        MacroTemplate bare = new MacroTemplate("bare", null, null, null, null, null, null, null,
                0.0, Map.of(), Map.of(), Map.of(), null, null, null, null, null);
        assertEquals("bare", bare.name(), "名称缺失回退 id");
        assertEquals(MacroTemplate.CIV_AGRARIAN, bare.civilization(), "文明形态缺失回退农耕");
        assertEquals(MacroTemplate.POWER_MUNDANE, bare.power(), "力量体系缺失回退现实");
        assertEquals("物资", bare.coreResource(), "核心资源缺失回退通用物资");
        assertEquals("钱", bare.currencyUnit(), "计价单位缺失回退钱");
        assertEquals("钱/份", bare.priceUnit(), "价格单位缺失回退钱/份");
        assertEquals("本境", bare.communityName(), "社区名缺失回退本境");
        assertEquals("农事", bare.techName("agriculture"), "科技维度缺失回退通用维度名");
        assertEquals("未知维度", bare.techName("未知维度"), "未知维度码回退原码");
        assertEquals("旱灾", bare.extremeEventName("drought"), "极端事件名缺失回退通用名");
        assertEquals("时局", bare.initialIssues().get(0), "议题缺失回退通用议题");
        assertEquals("世系望族", bare.groupTemplates().get(0).name(), "组织骨架缺失回退通用骨架");
        String text = bare.renderEventText("unknown_code", "第1日", null, null, null);
        assertTrue(text.contains("unknown_code"), "未知事件码回退通用文本（含原码）");
    }

    /** 极端事件名映射（模板世界观适配：旱涝暴雪 → 能源枯竭/管网过载/异常寒潮）。 */
    @Test
    void extremeEventNamesPerTemplate() {
        MacroTemplate future = service.of(MacroTemplate.ID_FUTURE);
        assertEquals("能源枯竭", future.extremeEventName("drought"));
        assertEquals("管网过载", future.extremeEventName("flood"));
        assertEquals("异常寒潮", future.extremeEventName("blizzard"));
    }
}
