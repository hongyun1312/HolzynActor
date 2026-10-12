package com.holzyn.actor.domain.world.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二阶行为类归一单元测试（宏观社会演化系统 M6.6/M6.5a，domain/world/service，纯静态）。
 * <p>职责：验证 actionType/actionText/indicator → 行为类映射（囤积/迁徙/抗议/逃离/交易潮/
 * 社交潮/集体休整）、优先级（高权重语义先判）、无法归一返回 null。</p>
 */
class MacroBehaviorClassTest {

    /** 囤积：文本关键词命中（优先级最高）。 */
    @Test
    void hoardingByKeyword() {
        assertEquals("hoarding", MacroBehaviorClass.classify("custom", "把家里的粮囤起来", null));
        assertEquals("hoarding", MacroBehaviorClass.classify("trade", "抢购存粮", null));
        assertEquals("hoarding", MacroBehaviorClass.classify(null, "去米行买粮", null));
    }

    /** 抗议：聚众/请愿类关键词。 */
    @Test
    void protestByKeyword() {
        assertEquals("protest", MacroBehaviorClass.classify("custom", "和乡亲们聚众请愿", null));
        assertEquals("protest", MacroBehaviorClass.classify("social", "在祠堂闹事", null));
    }

    /** 逃离：逃难/避祸关键词。 */
    @Test
    void fleeingByKeyword() {
        assertEquals("fleeing", MacroBehaviorClass.classify("custom", "连夜逃离村子避祸", null));
        assertEquals("fleeing", MacroBehaviorClass.classify("move", "出逃", null));
    }

    /** 迁徙：move 类型或离乡/搬走关键词。 */
    @Test
    void migrationByTypeAndKeyword() {
        assertEquals("migration", MacroBehaviorClass.classify("move", "去邻村", null));
        assertEquals("migration", MacroBehaviorClass.classify("custom", "收拾行囊离乡", null));
    }

    /** 交易潮：trade 类型或买卖/赶集关键词。 */
    @Test
    void tradingSpreeByTypeAndKeyword() {
        assertEquals("trading_spree", MacroBehaviorClass.classify("trade", "买卖货物", null));
        assertEquals("trading_spree", MacroBehaviorClass.classify("custom", "去集市赶集摆摊", null));
    }

    /** 社交潮：social 类型或串门/茶馆关键词。 */
    @Test
    void socialWaveByTypeAndKeyword() {
        assertEquals("social_wave", MacroBehaviorClass.classify("social", "与人闲谈", null));
        assertEquals("social_wave", MacroBehaviorClass.classify("custom", "去茶馆聚会", null));
    }

    /** 集体休整：rest 类型或歇息/小憩关键词。 */
    @Test
    void restBreakByTypeAndKeyword() {
        assertEquals("rest_break", MacroBehaviorClass.classify("rest", "躺下歇息", null));
        assertEquals("rest_break", MacroBehaviorClass.classify("custom", "午后小憩", null));
    }

    /** 无法归一（进食/扫地等普通行为）→ null；空输入安全。 */
    @Test
    void unclassifiableReturnsNull() {
        assertNull(MacroBehaviorClass.classify("custom", "在厨房做饭", null));
        assertNull(MacroBehaviorClass.classify("work", "打扫院子", null));
        assertNull(MacroBehaviorClass.classify(null, null, null));
        assertNull(MacroBehaviorClass.classify("", "", ""));
    }

    /** 显示名映射（缺失回退原码）。 */
    @Test
    void displayNameMapping() {
        assertEquals("囤积", MacroBehaviorClass.displayOf("hoarding"));
        assertEquals("抗议", MacroBehaviorClass.displayOf("protest"));
        assertEquals("unknown_class", MacroBehaviorClass.displayOf("unknown_class"));
        assertTrue(MacroBehaviorClass.DISPLAY.size() >= 7);
    }
}
