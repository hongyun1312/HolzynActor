/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.world.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宏观社会演化 · 世界观模板。
 * <p>职责：把宏观层的「语义层」从引擎内核中解耦出来——引擎只认<b>通用语义码</b>，世界内容
 * 全部由模板承载：</p>
 * <ul>
 * <li><b>事件码</b>：宏观事件类型在引擎/DB/代码中一律使用通用英文语义码（harvest_good/
 * price_surge/bank_run/drought…），任何世界不出现中文旧名（丰年/钱庄挤兑/大旱…）——
 * 展示文本由模板 eventTexts 按世界观渲染，标题/日志等代码侧呈现用 {@link #displayName}；</li>
 * <li><b>文明形态轴</b>（civilization）：agrarian 农耕 / industrial 工商工业 / information 信息能源
 * ——决定经济基础与核心资源语义（粮 / 食物 / 能源）；</li>
 * <li><b>力量体系轴</b>（power）：mundane 现实 / wuxia 武侠高武 / xianxia 仙侠修仙 / futuristic 科幻异能
 * ——决定势力形态命名、科技维度命名、极端事件集；</li>
 * <li><b>内容字段</b>：核心资源名/计价单位/社区名/季节联动强度/科技维度显示名/事件叙事文本/
 * 极端事件命名/初始议题/<b>初始信仰·禁忌·流行</b>/
 * <b>中观组织骨架 groupTemplates</b>。</li>
 * </ul>
 * <p>内置 5 套：ancient 古代农耕 / xianxia 仙侠修仙（默认）/
 * wuxia 武侠高武 / modern 现代都市 / future 科幻未来。引擎内核全部参数化，
 * 不硬编码资源语义——模板即语义（详见 {@link MacroTemplateService}）。</p>
 * <p><b>缺失兜底</b>：模板是可选的语义增强层，不是必需层——
 * 任何字段缺失/为空都走「模板值 → 代码级通用默认值 → 安全空值」链式兜底：
 * 标量字段构造器归一化（空白回退通用默认），语义码取值（事件/科技维度/极端事件名）
 * 逐级回退通用名与原码，集合字段（议题/组织骨架）空值回退通用集合，
 * 信仰/禁忌/流行空值安全空集。任何组合下取值恒非空、不抛 NPE、不产出破损文本。</p>
 */
public final class MacroTemplate {

    // ==================== 文明形态 ====================

    /** 文明形态：农耕（季节/收成强联动） */
    public static final String CIV_AGRARIAN = "agrarian";
    /** 文明形态：工商工业（供应链/市场） */
    public static final String CIV_INDUSTRIAL = "industrial";
    /** 文明形态：信息能源（能源/信用点） */
    public static final String CIV_INFORMATION = "information";

    // ==================== 力量体系 ====================

    /** 力量体系：现实（无超凡） */
    public static final String POWER_MUNDANE = "mundane";
    /** 力量体系：武侠高武（内力/武功/江湖） */
    public static final String POWER_WUXIA = "wuxia";
    /** 力量体系：仙侠修仙（灵气/修行/法术） */
    public static final String POWER_XIANXIA = "xianxia";
    /** 力量体系：科幻异能（星舰/AI/灵能） */
    public static final String POWER_FUTURISTIC = "futuristic";

    // ==================== 模板 id ====================

    /** 模板 id：古代农耕 */
    public static final String ID_ANCIENT = "ancient";
    /** 模板 id：仙侠修仙（默认） */
    public static final String ID_XIANXIA = "xianxia";
    /** 模板 id：武侠高武 */
    public static final String ID_WUXIA = "wuxia";
    /** 模板 id：现代都市 */
    public static final String ID_MODERN = "modern";
    /** 模板 id：科幻未来 */
    public static final String ID_FUTURE = "future";

    /** 默认模板（未知 id/无法推导/字段空白时回退） */
    public static final String DEFAULT_ID = ID_XIANXIA;

    /** 事件文本占位符：{day} 当日文本 · {npc} 具名 NPC · {indicator} 指标 · {magnitude} 幅度 */
    private static final String P_DAY = "{day}";
    private static final String P_NPC = "{npc}";
    private static final String P_INDICATOR = "{indicator}";
    private static final String P_MAGNITUDE = "{magnitude}";

    // ==================== 通用事件语义码 → 通用中文显示名 ====================

    /**
     * 事件码 → 通用中文显示名（无歧义、跨世界通用； 通用化：引擎/DB 只用语义码，
     * 标题/日志/调试等代码侧呈现用本映射；叙事正文由模板 eventTexts 按世界观渲染）。
     */
    public static final Map<String, String> GENERIC_DISPLAY = Map.ofEntries(
            Map.entry("harvest_good", "丰收"),
            Map.entry("harvest_bad", "歉收"),
            Map.entry("price_surge", "物价暴涨"),
            Map.entry("caravan", "外来商旅"),
            Map.entry("bank_run", "挤兑风潮"),
            Map.entry("plague", "疫病流行"),
            Map.entry("famine", "饥荒"),
            Map.entry("refugee_influx", "流民涌入"),
            Map.entry("migration_wave", "迁徙潮"),
            Map.entry("faction_split", "势力决裂"),
            Map.entry("faction_alliance", "势力结盟"),
            Map.entry("power_shift", "势力易主"),
            Map.entry("assembly", "联合大会"),
            Map.entry("official_edict", "官府新政"),
            Map.entry("opinion_shift", "舆论转向"),
            Map.entry("relief", "赈济"),
            Map.entry("revolt_risk", "民变风险"),
            Map.entry("tech_breakthrough", "技艺突破"),
            Map.entry("tech_lost", "技艺失传"),
            Map.entry("faith_crisis", "信仰危机"),
            Map.entry("new_custom", "新习俗兴起"),
            Map.entry("rumor", "谣言四起"),
            Map.entry("drought", "旱灾"),
            Map.entry("flood", "洪涝"),
            Map.entry("blizzard", "雪灾"));

    /** 事件码 → 通用中文显示名（缺失回退原码——恒非空、确定性）。 */
    public static String displayName(String code) {
        return GENERIC_DISPLAY.getOrDefault(code, code);
    }

    // ==================== 代码级通用默认值（模板缺失兜底，无世界色彩） ====================

    /** 科技维度通用显示名（techNames 缺该维度时兜底；再缺失回退原维度码） */
    private static final Map<String, String> GENERIC_TECH_NAMES = Map.of(
            "agriculture", "农事", "craft", "工艺", "medicine", "医术", "array", "秘术");

    /** 初始议题通用兜底（initialIssues 缺失/为空时使用；跨世界无歧义的通用议题） */
    private static final List<String> GENERIC_ISSUES = List.of("时局", "生计");

    /** 中观组织骨架通用兜底（groupTemplates 缺失/为空时使用；无世界色彩的四类基础组织） */
    private static final List<GroupTemplate> GENERIC_GROUP_SKELETONS = List.of(
            new GroupTemplate("世系望族", "family", List.of("宗族", "名望"), 50),
            new GroupTemplate("祭仪教团", "cult", List.of("信仰", "仪式"), 45),
            new GroupTemplate("同业行会", "guild", List.of("行当", "互助"), 35),
            new GroupTemplate("乡邻结社", "community", List.of("邻里", "互助"), 30));

    // ==================== 模板字段 ====================

    private final String id;
    private final String name;
    private final String civilization;
    private final String power;
    /** 核心资源名（粮/食物/能源）——粮价=「核心资源价」，收成=「产出」 */
    private final String coreResource;
    /** 计价单位（文/元/信用点） */
    private final String currencyUnit;
    /** 核心资源计价单位（文/石、元/份、信用点/单位） */
    private final String priceUnit;
    /** 社区名（本村/城区/星港） */
    private final String communityName;
    /** 季节联动强度（1.0 农耕全联动 / 0.7 高武 / 0.4 现代 / 0.15 未来近零——未来有气候控制） */
    private final double seasonFactorScale;
    /** 科技维度显示名（agriculture/craft/medicine/array → 世界观适配名） */
    private final Map<String, String> techNames;
    /** 事件码 → 叙事模板文本（含占位符；缺失回退通用文本；键=通用语义码） */
    private final Map<String, String> eventTexts;
    /** 极端事件名（drought 旱灾 / flood 洪涝 / blizzard 雪灾 → 世界观适配名） */
    private final Map<String, String> extremeEventNames;
    /** 初始舆论议题（模板默认） */
    private final List<String> initialIssues;
    /** 初始信仰分布 */
    private final Map<String, Double> initialBeliefs;
    /** 初始禁忌 */
    private final List<String> initialTaboos;
    /** 初始流行/习俗 */
    private final List<String> initialFashions;
    /**
     * 中观组织形态骨架（名称/类型/标签/初始影响力——模板语义通用命名，
     * 不硬编码任何具体世界；世界观文本解析成功时以解析结果为准，本骨架为兜底）
     */
    private final List<GroupTemplate> groupTemplates;

    /**
     * 构造：标量字段空白一律归一化为代码级通用默认值（任何构造方式下
     * 取值恒非空）；集合字段空值回退通用集合（议题/组织骨架）或安全空集（信仰/禁忌/流行）。
     */
    public MacroTemplate(String id, String name, String civilization, String power,
                         String coreResource, String currencyUnit, String priceUnit,
                         String communityName, double seasonFactorScale,
                         Map<String, String> techNames, Map<String, String> eventTexts,
                         Map<String, String> extremeEventNames, List<String> initialIssues,
                         Map<String, Double> initialBeliefs, List<String> initialTaboos,
                         List<String> initialFashions, List<GroupTemplate> groupTemplates) {
        this.id = id;
        this.name = (name == null || name.isBlank()) ? id : name;
        this.civilization = (civilization == null || civilization.isBlank()) ? CIV_AGRARIAN : civilization;
        this.power = (power == null || power.isBlank()) ? POWER_MUNDANE : power;
        this.coreResource = (coreResource == null || coreResource.isBlank()) ? "物资" : coreResource;
        this.currencyUnit = (currencyUnit == null || currencyUnit.isBlank()) ? "钱" : currencyUnit;
        this.priceUnit = (priceUnit == null || priceUnit.isBlank()) ? "钱/份" : priceUnit;
        this.communityName = (communityName == null || communityName.isBlank()) ? "本境" : communityName;
        this.seasonFactorScale = seasonFactorScale;
        this.techNames = techNames == null ? Map.of() : techNames;
        this.eventTexts = eventTexts == null ? Map.of() : eventTexts;
        this.extremeEventNames = extremeEventNames == null ? Map.of() : extremeEventNames;
        this.initialIssues = (initialIssues == null || initialIssues.isEmpty()) ? GENERIC_ISSUES : initialIssues;
        this.initialBeliefs = initialBeliefs == null ? Map.of() : initialBeliefs;
        this.initialTaboos = initialTaboos == null ? List.of() : initialTaboos;
        this.initialFashions = initialFashions == null ? List.of() : initialFashions;
        this.groupTemplates = (groupTemplates == null || groupTemplates.isEmpty())
                ? GENERIC_GROUP_SKELETONS : groupTemplates;
    }

    /** 中观组织形态骨架定义（模板语义通用命名） */
    public record GroupTemplate(String name, String type, List<String> tags, double influence) {
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String civilization() {
        return civilization;
    }

    public String power() {
        return power;
    }

    public String coreResource() {
        return coreResource;
    }

    public String currencyUnit() {
        return currencyUnit;
    }

    public String priceUnit() {
        return priceUnit;
    }

    public String communityName() {
        return communityName;
    }

    public double seasonFactorScale() {
        return seasonFactorScale;
    }

    public Map<String, String> techNames() {
        return techNames;
    }

    public Map<String, String> extremeEventNames() {
        return extremeEventNames;
    }

    public List<String> initialIssues() {
        return initialIssues;
    }

    public Map<String, Double> initialBeliefs() {
        return initialBeliefs;
    }

    public List<String> initialTaboos() {
        return initialTaboos;
    }

    public List<String> initialFashions() {
        return initialFashions;
    }

    public List<GroupTemplate> groupTemplates() {
        return groupTemplates;
    }

    /** 科技维度显示名（缺失回退通用维度名，再缺失回退原维度码——恒非空）。 */
    public String techName(String dim) {
        String v = techNames.get(dim);
        if (v != null && !v.isBlank()) {
            return v;
        }
        return GENERIC_TECH_NAMES.getOrDefault(dim, dim);
    }

    /** 极端事件显示名（drought/flood/blizzard；缺失回退通用名）。 */
    public String extremeEventName(String kind) {
        return extremeEventNames.getOrDefault(kind, displayName(kind));
    }

    /** 核心资源计量单位（priceUnit「文/石」→「石」；缺失回退空串）。 */
    public String resourceUnit() {
        int idx = priceUnit == null ? -1 : priceUnit.indexOf('/');
        if (idx >= 0 && idx + 1 < priceUnit.length()) {
            return priceUnit.substring(idx + 1);
        }
        return "";
    }

    /**
     * 事件叙事模板渲染：模板文本 + 占位符替换（缺失回退通用文本）。
     *
     * @param eventCode 事件语义码（harvest_good/price_surge/drought…）
     *
     * @param dayText 当日文本（如「第 26 日」）
     *
     * @param namedNpc 具名普通 NPC 名（可空）
     *
     * @param indicator 指标名（可空）
     *
     * @param magnitude 变化幅度文本（可空）
     *
     * @return 渲染后的叙事文本（恒非空）
     */
    public String renderEventText(String eventCode, String dayText, String namedNpc,
                                  String indicator, String magnitude) {
        String tpl = eventTexts.get(eventCode);
        if (tpl == null || tpl.isBlank()) {
            return genericText(eventCode, dayText, namedNpc, indicator, magnitude);
        }
        return tpl.replace(P_DAY, dayText == null ? "" : dayText)
                .replace(P_NPC, namedNpc == null ? "" : namedNpc)
                .replace(P_INDICATOR, indicator == null ? "" : indicator)
                .replace(P_MAGNITUDE, magnitude == null ? "" : magnitude);
    }

    /** 通用兜底文本（模板未定制的事件码）。 */
    private static String genericText(String eventCode, String dayText, String namedNpc,
                                      String indicator, String magnitude) {
        StringBuilder sb = new StringBuilder();
        sb.append(dayText == null || dayText.isBlank() ? "" : dayText).append("，")
                .append(displayName(eventCode));
        if (namedNpc != null && !namedNpc.isBlank()) {
            sb.append("（涉及").append(namedNpc).append("）");
        }
        if (indicator != null && !indicator.isBlank()) {
            sb.append("——指标 ").append(indicator);
            if (magnitude != null && !magnitude.isBlank()) {
                sb.append("（").append(magnitude).append("）");
            }
        }
        return sb.toString();
    }

    // ==================== 内置 5 套模板 ====================

    /**
     * 内置模板全集（权威基线；5 套均为<b>无特定世界内容</b>的参数化通用文本——
     * 世界专属内容（村名/宗族/势力/异象等）由世界观设定文本与 AI 叙事承载，模板零测试世界内容）。
     * ⚠️ 通用化：事件映射键全部为通用语义码（引擎/DB/代码零中文旧名）。
     */
    public static Map<String, MacroTemplate> builtins() {
        Map<String, MacroTemplate> m = new LinkedHashMap<>();
        m.put(ID_XIANXIA, xianxia());
        m.put(ID_ANCIENT, ancient());
        m.put(ID_WUXIA, wuxia());
        m.put(ID_MODERN, modern());
        m.put(ID_FUTURE, future());
        return m;
    }

    /** 仙侠修仙（默认模板：通用仙侠村落叙事，无特定世界名）。 */
    private static MacroTemplate xianxia() {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("harvest_good", "{day}，本村风调雨顺，田里稻浪翻金，今年收成颇丰，粮仓渐盈，村民脸上多了笑意。");
        ev.put("harvest_bad", "{day}，本村田里收成欠佳，粮仓见底之忧渐起，农户们眉头紧锁。");
        ev.put("price_surge", "{day}，粮价较前日暴涨{magnitude}，市集粮行门前排起长队，人人都在议论囤粮之事。");
        ev.put("caravan", "{day}，邻近镇子的商队进了村，货郎担子、驮马铃铛一路响到集市，带来了外界的货物与消息。");
        ev.put("bank_run", "{day}，村中钱庄门前挤满了人——粮价波动之下，家家户户急着兑回铜钱，掌柜擦着汗好言安抚。");
        ev.put("plague", "{day}{npc}病倒了，村中忽然起了时疫，医馆门前排起长队，药香与愁云一同弥漫。");
        ev.put("famine", "{day}{npc}饿得面黄肌瘦，粮仓告急，饥荒的阴影笼罩全村，有人开始收拾行囊。");
        ev.put("refugee_influx", "{day}，村外来了几家流民，携儿带女，在村口怯生生地张望——村里人议论着收留还是送走。");
        ev.put("migration_wave", "{day}，粮价高涨生计艰难，有村民打点行装离村谋生，村口送别的人红了眼眶。");
        ev.put("faction_split", "{day}，{indicator}之间嫌隙渐深，祠堂与庙前的争吵传遍了全村。");
        ev.put("faction_alliance", "{day}，{indicator}走得亲近，说媒的、递帖的走动起来，村里传说要结亲家了。");
        ev.put("power_shift", "{day}，{indicator}声势大盛，村中的话语权悄然易位，连茶馆里的闲谈都换了风向。");
        ev.put("assembly", "{day}，村中召开集会，各股势力齐聚一堂，共议村务大事。");
        ev.put("official_edict", "{day}，邻近镇子传来官府新令，村口告示栏前人声鼎沸，众人各怀心思。");
        ev.put("opinion_shift", "{day}，村中的议论风向悄然转变——{indicator}成了茶馆里的头等大事。");
        ev.put("relief", "{day}，村中大户开仓放粮，粥棚支起，饿着肚子的乡邻们终于吃上一口热粥，怨气也散了几分。");
        ev.put("revolt_risk", "{day}，粮价高企、怨声四起，村头巷尾有人提着扁担聚在一处，气氛一触即发。");
        ev.put("drought", "{day}，连月无雨，田土干裂，禾苗打蔫，村边溪流的水位一降再降。");
        ev.put("flood", "{day}，连日暴雨，溪水漫过田埂，稻禾泡在浑水里，农户们望着天直叹气。");
        ev.put("blizzard", "{day}，大雪封村，屋檐挂起冰棱，出村的路被积雪埋了大半。");
        ev.put("tech_breakthrough", "{day}，{indicator}一朝开窍，技艺更上层楼——村中相熟的匠人、医者奔走相告，都说这是难得的造化。");
        ev.put("tech_lost", "{day}，{indicator}久无人精研，会的人越来越少，眼看着这门手艺就要断在村里。");
        ev.put("faith_crisis", "{day}，庙里的香火稀了，有人开始嘀咕——{indicator}是不是不灵了？");
        ev.put("new_custom", "{day}，村里新添了规矩与热闹，{indicator}渐渐成了人人都认的惯例。");
        ev.put("rumor", "{day}，村里谣言越传越邪乎，{indicator}被添油加醋说了无数个版本。");
        Map<String, String> extreme = new LinkedHashMap<>();
        extreme.put("drought", "大旱");
        extreme.put("flood", "洪涝");
        extreme.put("blizzard", "暴雪");
        return new MacroTemplate(ID_XIANXIA, "仙侠修仙", CIV_AGRARIAN, POWER_XIANXIA,
                "粮", "文", "文/石", "本村", 1.0,
                Map.of("agriculture", "灵植农艺", "craft", "炼器工艺", "medicine", "医术丹术", "array", "法阵"),
                ev, extreme, List.of("天地异象", "官府寻人", "节庆筹备"),
                Map.of("神祇信仰", 0.85, "灵气信仰", 0.7),
                List.of("夜不出村", "山不夜行", "外客不留宿神庙", "望日不夜作"),
                List.of("逢望日不夜作", "春祈节祭神", "秋收庙会"),
                // 中观组织骨架（通用命名兜底；世界观文本解析成功时以解析结果为准）
                List.of(
                        new GroupTemplate("村中宗族", "family", List.of("村务", "宗祠"), 60),
                        new GroupTemplate("庙宇祭司一脉", "cult", List.of("香火", "镇守"), 55),
                        new GroupTemplate("行医世家", "guild", List.of("医馆", "诊治"), 40),
                        new GroupTemplate("往来商帮", "guild", List.of("商贸"), 30),
                        new GroupTemplate("游侠散人", "community", List.of("武艺", "护村"), 35)
                ));
    }

    /** 古代农耕（无超凡，官府/宗族）。 */
    private static MacroTemplate ancient() {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("harvest_good", "{day}，风调雨顺，田里五谷丰登，粮仓渐满，乡邻们脸上都是笑。");
        ev.put("harvest_bad", "{day}，田里收成欠佳，粮仓见底之忧渐起，农户们眉头紧锁。");
        ev.put("price_surge", "{day}，粮价较前日暴涨{magnitude}，米行门前排起长队，人人都在议论囤粮之事。");
        ev.put("caravan", "{day}，外来的商队进了镇，驮马铃铛一路响到集市，带来了远方的货物与消息。");
        ev.put("bank_run", "{day}，镇中钱庄门前挤满了人——粮价波动之下，家家户户急着兑回铜钱，掌柜好言安抚。");
        ev.put("plague", "{day}{npc}病倒了，镇上忽然起了时疫，药铺门前排起长队。");
        ev.put("famine", "{day}{npc}饿得面黄肌瘦，粮仓告急，饥荒的阴影笼罩全镇。");
        ev.put("refugee_influx", "{day}，城外来了几家流民，携儿带女，在城门下怯生生地张望。");
        ev.put("migration_wave", "{day}，粮价高涨生计艰难，有人打点行装离乡谋生。");
        ev.put("faction_split", "{day}，{indicator}之间嫌隙渐深，宗祠与街头的争吵传遍全镇。");
        ev.put("faction_alliance", "{day}，{indicator}走得亲近，说媒的走动起来。");
        ev.put("power_shift", "{day}，{indicator}声势大盛，镇上的话语权悄然易位。");
        ev.put("assembly", "{day}，镇上召开集会，各股势力齐聚一堂，共议大事。");
        ev.put("official_edict", "{day}，衙门传来新令，告示栏前人声鼎沸。");
        ev.put("opinion_shift", "{day}，街巷的议论风向悄然转变——{indicator}成了茶楼里的头等大事。");
        ev.put("relief", "{day}，镇中大户开仓放粮，粥棚支起，怨气散了几分。");
        ev.put("revolt_risk", "{day}，粮价高企、怨声四起，街头有人聚在一处，气氛一触即发。");
        ev.put("drought", "{day}，连月无雨，田土干裂，禾苗打蔫，河渠水位一降再降。");
        ev.put("flood", "{day}，连日暴雨，河水漫过田埂，庄稼泡在浑水里。");
        ev.put("blizzard", "{day}，大雪封门，屋檐挂起冰棱，出镇的路被积雪埋了大半。");
        ev.put("tech_breakthrough", "{day}，{indicator}一朝开窍，技艺更上层楼，众人奔走相告。");
        ev.put("tech_lost", "{day}，{indicator}久无人精研，会的人越来越少。");
        ev.put("faith_crisis", "{day}，庙里的香火稀了，有人开始嘀咕——{indicator}是不是不灵了？");
        ev.put("new_custom", "{day}，镇上添了新规矩与热闹，{indicator}渐渐成了人人都认的惯例。");
        ev.put("rumor", "{day}，镇上谣言越传越邪乎，{indicator}被添油加醋说了无数个版本。");
        Map<String, String> extreme = new LinkedHashMap<>();
        extreme.put("drought", "大旱");
        extreme.put("flood", "洪涝");
        extreme.put("blizzard", "暴雪");
        return new MacroTemplate(ID_ANCIENT, "古代农耕", CIV_AGRARIAN, POWER_MUNDANE,
                "粮", "文", "文/石", "本镇", 1.0,
                Map.of("agriculture", "农艺", "craft", "工艺", "medicine", "医术", "array", "营造术"),
                ev, extreme, List.of("秋收筹备", "官府新政"),
                Map.of("乡土认同", 0.6),
                List.of("公共场合守序"),
                List.of(),
                List.of(
                        new GroupTemplate("本地宗族", "family", List.of("村务", "田产"), 55),
                        new GroupTemplate("衙门吏员", "community", List.of("官府", "法度"), 45),
                        new GroupTemplate("行医世家", "guild", List.of("医馆", "诊治"), 35),
                        new GroupTemplate("往来商帮", "guild", List.of("商贸"), 30)
                ));
    }

    /** 武侠高武（内力/武功/门派，季节弱化——武人抗天时）。 */
    private static MacroTemplate wuxia() {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("harvest_good", "{day}，风调雨顺，田间丰收，粮仓渐满。");
        ev.put("harvest_bad", "{day}，田里收成欠佳，粮仓见底之忧渐起。");
        ev.put("price_surge", "{day}，粮价较前日暴涨{magnitude}，米行门前排起长队。");
        ev.put("caravan", "{day}，商队进了镇，带来了远方的货物与江湖消息。");
        ev.put("bank_run", "{day}，钱庄门前挤满了人，掌柜好言安抚。");
        ev.put("plague", "{day}{npc}病倒了，镇上忽然起了时疫，药铺门前排起长队。");
        ev.put("famine", "{day}{npc}饿得面黄肌瘦，粮仓告急，饥荒的阴影笼罩全镇。");
        ev.put("refugee_influx", "{day}，城外来了几家流民，携儿带女。");
        ev.put("migration_wave", "{day}，生计艰难，有人打点行装离乡谋生。");
        ev.put("faction_split", "{day}，{indicator}之间嫌隙渐深，武林同道议论纷纷。");
        ev.put("faction_alliance", "{day}，{indicator}走得亲近，结亲的消息传遍江湖。");
        ev.put("power_shift", "{day}，{indicator}声势大盛，武林话语权悄然易位。");
        ev.put("assembly", "{day}，各门派齐聚一堂，召开武林大会共议大事。");
        ev.put("official_edict", "{day}，官府传来新令，告示栏前人声鼎沸。");
        ev.put("opinion_shift", "{day}，江湖议论的风向悄然转变——{indicator}成了茶馆里的头等大事。");
        ev.put("relief", "{day}，镇上大户开仓放粮，粥棚支起。");
        ev.put("revolt_risk", "{day}，怨声四起，街头有人聚在一处，气氛一触即发。");
        ev.put("drought", "{day}，连月无雨，田土干裂，河渠水位一降再降。");
        ev.put("flood", "{day}，连日暴雨，河水漫过田埂。");
        ev.put("blizzard", "{day}，大雪封山，出镇的路被积雪埋了大半。");
        ev.put("tech_breakthrough", "{day}，{indicator}一朝顿悟，武艺/技艺更上层楼，名动一时。");
        ev.put("tech_lost", "{day}，{indicator}久无人精研，会的人越来越少。");
        ev.put("faith_crisis", "{day}，庙里的香火稀了，{indicator}的传闻生了疑。");
        ev.put("new_custom", "{day}，江湖上新添了规矩，{indicator}渐渐成了惯例。");
        ev.put("rumor", "{day}，江湖谣言越传越邪乎，{indicator}被添油加醋说了无数个版本。");
        Map<String, String> extreme = new LinkedHashMap<>();
        extreme.put("drought", "大旱");
        extreme.put("flood", "洪涝");
        extreme.put("blizzard", "暴雪");
        return new MacroTemplate(ID_WUXIA, "武侠高武", CIV_AGRARIAN, POWER_WUXIA,
                "粮", "两", "两/石", "本镇", 0.7,
                Map.of("agriculture", "农事", "craft", "锻造", "medicine", "医术", "array", "内功心法"),
                ev, extreme, List.of("江湖传闻", "武林大会"),
                Map.of("侠义认同", 0.7),
                List.of("祸不及无辜"),
                List.of("武林大会"),
                List.of(
                        new GroupTemplate("武林宗门", "sect", List.of("武学", "名望"), 60),
                        new GroupTemplate("护镖行会", "guild", List.of("护送", "商贸"), 40),
                        new GroupTemplate("江湖散人", "community", List.of("游侠", "义气"), 35),
                        new GroupTemplate("官府势力", "community", List.of("官府", "法度"), 45)
                ));
    }

    /** 现代都市（工商文明，供应链/物价，季节弱化）。 */
    private static MacroTemplate modern() {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("harvest_good", "{day}，本季产出充足，市场供应平稳，货架满满，市民心情不错。");
        ev.put("harvest_bad", "{day}，本季供应趋紧，超市货架出现缺货，人们开始囤货。");
        ev.put("price_surge", "{day}，物价较前日大涨{magnitude}，超市门前排起长队，网上也吵翻了天。");
        ev.put("caravan", "{day}，一批物流车进了城，卸下一箱箱货物，带来了远方的商品与消息。");
        ev.put("bank_run", "{day}，银行门前排起了长队——物价波动之下，储户急着取出现金。");
        ev.put("plague", "{day}{npc}，本市忽然出现疫情，医院发热门诊排起长队。");
        ev.put("famine", "{day}{npc}，物资告急，抢购潮愈演愈烈。");
        ev.put("refugee_influx", "{day}，城外来了几户人家，拖着行李在城郊怯生生地张望。");
        ev.put("migration_wave", "{day}，物价高涨生计艰难，有人收拾行李搬去了别处。");
        ev.put("faction_split", "{day}，{indicator}之间嫌隙渐深，公司年会与社区论坛吵成一片。");
        ev.put("faction_alliance", "{day}，{indicator}走得亲近，联姻的消息传开。");
        ev.put("power_shift", "{day}，{indicator}声势大盛，商圈与社区的话语权悄然易位。");
        ev.put("assembly", "{day}，各路人马齐聚一堂，召开大会共议大事。");
        ev.put("official_edict", "{day}，市政府出台新政，公告栏与手机推送同时刷屏。");
        ev.put("opinion_shift", "{day}，网络舆论风向悄然转变——{indicator}成了热搜上的头等大事。");
        ev.put("relief", "{day}，市政府投放平价物资，发放救济粮，怨气散了几分。");
        ev.put("revolt_risk", "{day}，物价高企、怨声四起，广场上有人聚集请愿，气氛紧张。");
        ev.put("drought", "{day}，连月无雨，水库见底，供水开始限量。");
        ev.put("flood", "{day}，连日暴雨，城区内涝，街道被淹。");
        ev.put("blizzard", "{day}，暴雪封城，道路被积雪埋了大半，出行困难。");
        ev.put("tech_breakthrough", "{day}，{indicator}取得关键技术突破，行业震动，上了新闻头条。");
        ev.put("tech_lost", "{day}，{indicator}长期无人投入，人才流失，眼看就要断层。");
        ev.put("faith_crisis", "{day}，人们开始怀疑{indicator}的价值，讨论声越来越多。");
        ev.put("new_custom", "{day}，城里流行起{indicator}，渐渐成了人人都认的惯例。");
        ev.put("rumor", "{day}，网上谣言越传越邪乎，{indicator}被添油加醋说了无数个版本。");
        Map<String, String> extreme = new LinkedHashMap<>();
        extreme.put("drought", "干旱");
        extreme.put("flood", "洪涝");
        extreme.put("blizzard", "暴雪");
        return new MacroTemplate(ID_MODERN, "现代都市", CIV_INDUSTRIAL, POWER_MUNDANE,
                "食物", "元", "元/份", "本市", 0.4,
                Map.of("agriculture", "农业科技", "craft", "制造业", "medicine", "医疗", "array", "信息技术"),
                ev, extreme, List.of("城市议题", "节庆筹备"),
                Map.of("社区认同", 0.6),
                List.of("公共场合守序"),
                List.of(),
                List.of(
                        new GroupTemplate("本地家族", "family", List.of("地产", "资本"), 50),
                        new GroupTemplate("行业协会", "guild", List.of("商贸", "行业"), 40),
                        new GroupTemplate("社区组织", "community", List.of("民生", "邻里"), 35),
                        new GroupTemplate("市政机构", "community", List.of("官府", "治理"), 50)
                ));
    }

    /** 科幻未来（信息能源文明，能源/信用点，气候控制→季节近零联动，极端事件=能源/异象）。 */
    private static MacroTemplate future() {
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("harvest_good", "{day}，能源井满负荷运转，供应充足，港口灯火通明，市民情绪稳定。");
        ev.put("harvest_bad", "{day}，本周期能源产出不足，分配额度收紧。");
        ev.put("price_surge", "{day}，能源价格较前周期大涨{magnitude}，充能站前排起长队。");
        ev.put("caravan", "{day}，一支星际商船队靠港，卸下成批货柜，带来了外域的物资与消息。");
        ev.put("bank_run", "{day}，信用银行门前排起了长队——能源价格波动之下，储户急着兑换硬通货。");
        ev.put("plague", "{day}{npc}，本站区出现异常疫病，医疗舱前排起长队。");
        ev.put("famine", "{day}{npc}，物资告急，配给额度降到红线以下。");
        ev.put("refugee_influx", "{day}，港区来了几艘小艇，载着避难的人家，在闸口外怯生生地张望。");
        ev.put("migration_wave", "{day}，能源价格高企，有人变卖资产，搭船迁往外域。");
        ev.put("faction_split", "{day}，{indicator}之间嫌隙渐深，董事席与公共频道吵成一片。");
        ev.put("faction_alliance", "{day}，{indicator}走得亲近，联姻/合并的消息传开。");
        ev.put("power_shift", "{day}，{indicator}声势大盛，站区的权力格局悄然易位。");
        ev.put("assembly", "{day}，各集团齐聚一堂，召开联席大会共议大事。");
        ev.put("official_edict", "{day}，市政议会出台新策，公告屏与全息推送同时刷屏。");
        ev.put("opinion_shift", "{day}，公共频道的舆论风向悄然转变——{indicator}成了热议头条。");
        ev.put("relief", "{day}，市政投放平价能源与物资，开放配给站，怨气散了几分。");
        ev.put("revolt_risk", "{day}，能源价格高企、怨声四起，广场上有人聚集抗议，气氛紧张。");
        ev.put("drought", "{day}，能源井枯竭警报响起，配给全面收紧——水与电都开始限量。");
        ev.put("flood", "{day}，反重力管网过载，街区被淹，悬浮车停摆。");
        ev.put("blizzard", "{day}，异常寒潮来袭，站区气温骤降，管线冻裂。");
        ev.put("tech_breakthrough", "{day}，{indicator}取得关键技术突破，全域震动，上了头条。");
        ev.put("tech_lost", "{day}，{indicator}长期无人投入，人才流失，眼看就要断层。");
        ev.put("faith_crisis", "{day}，人们开始质疑{indicator}的价值，讨论声越来越多。");
        ev.put("new_custom", "{day}，全域流行起{indicator}，渐渐成了人人都认的惯例。");
        ev.put("rumor", "{day}，全域网谣言越传越邪乎，{indicator}被添油加醋说了无数个版本。");
        Map<String, String> extreme = new LinkedHashMap<>();
        extreme.put("drought", "能源枯竭");
        extreme.put("flood", "管网过载");
        extreme.put("blizzard", "异常寒潮");
        return new MacroTemplate(ID_FUTURE, "科幻未来", CIV_INFORMATION, POWER_FUTURISTIC,
                "能源", "信用点", "信用点/单位", "本站区", 0.15,
                Map.of("agriculture", "生态农业", "craft", "纳米制造", "medicine", "基因医疗", "array", "灵能AI"),
                ev, extreme, List.of("外域传闻", "能源配额"),
                Map.of("秩序认同", 0.6),
                List.of("站区公共守则"),
                List.of("能源配额周"),
                List.of(
                        new GroupTemplate("家族财团", "family", List.of("资本", "股权"), 55),
                        new GroupTemplate("行业公会", "guild", List.of("贸易", "配额"), 40),
                        new GroupTemplate("市政议会", "community", List.of("治理", "民生"), 50),
                        new GroupTemplate("星港机构", "community", List.of("机构", "枢纽"), 35)
                ));
    }
}
