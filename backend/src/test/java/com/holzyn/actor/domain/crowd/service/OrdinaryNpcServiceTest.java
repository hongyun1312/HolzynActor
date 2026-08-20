package com.holzyn.actor.domain.crowd.service;

import com.holzyn.actor.common.BizException;
import com.holzyn.actor.domain.crowd.entity.ActorOrdinaryNpc;
import com.holzyn.actor.domain.crowd.vo.FieldDictPreviewVO;
import com.holzyn.actor.domain.crowd.vo.FieldDictVO;
import com.holzyn.actor.domain.crowd.vo.OrdinaryNpcDraftVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OrdinaryNpcService 纯逻辑单元测试（2026-08-19 分类体系重构后）。
 * <p>职责：验证多字段筛选匹配（match）/排序（sorter）、标准字段数据拟定解析
 * （parseFieldDict 字段树 + 主次分类字段 + 去重）与清洗（cleanFieldDict）、
 * 字段取值与分组概况（fieldValue/fieldGroupOverview）、AI 居民草稿解析（parseDrafts，含次级种族）、
 * 项目级调度解析（parseProjectSchedule 主分类分组 + 归属指令）。</p>
 */
class OrdinaryNpcServiceTest {

    // ==================== 居民草稿解析（parseDrafts，含次级种族） ====================

    /**
     * 居民草稿解析：顶层数组完整字段透传（含次级种族 subRace 与年龄整数）。
     */
    @Test
    void parseDraftsReadsTopLevelArrayWithSubRace() {
        List<OrdinaryNpcDraftVO> drafts = OrdinaryNpcService.parseDrafts(
                "[{\"name\":\"阿福\",\"gender\":\"男\",\"race\":\"人族\",\"subRace\":\"汉族\",\"age\":28,"
                        + "\"affiliation\":\"临江渔村\",\"location\":\"临江码头\",\"occupation\":\"渔夫\","
                        + "\"detail\":\"世代打渔的渔村青年\"}]");
        assertEquals(1, drafts.size());
        OrdinaryNpcDraftVO d = drafts.get(0);
        assertEquals("阿福", d.name());
        assertEquals("男", d.gender());
        assertEquals("人族", d.race());
        assertEquals("汉族", d.subRace());
        assertEquals(28, d.age());
        assertEquals("临江渔村", d.affiliation());
        assertEquals("临江码头", d.location());
        assertEquals("渔夫", d.occupation());
        assertTrue(d.detail().contains("世代打渔"));
    }

    /**
     * 居民草稿解析：兼容顶层对象含 residents 数组 + snake_case 次级种族字段（sub_race）。
     */
    @Test
    void parseDraftsReadsResidentsFieldAndSnakeCaseSubRace() {
        List<OrdinaryNpcDraftVO> drafts = OrdinaryNpcService.parseDrafts(
                "{\"residents\":[{\"name\":\"翠娘\",\"race\":\"妖精\",\"sub_race\":\"猫妖\"}]}");
        assertEquals(1, drafts.size());
        assertEquals("妖精", drafts.get(0).race());
        assertEquals("猫妖", drafts.get(0).subRace());
    }

    /**
     * 居民草稿解析：Markdown 代码块包裹 + 首尾说明文字。
     */
    @Test
    void parseDraftsStripsCodeFenceAndSurroundingText() {
        List<OrdinaryNpcDraftVO> drafts = OrdinaryNpcService.parseDrafts(
                "以下是生成的居民：\n```json\n[{\"name\":\"铁柱\",\"occupation\":\"铁匠\"}]\n```\n完毕");
        assertEquals(1, drafts.size());
        assertEquals("铁柱", drafts.get(0).name());
    }

    /**
     * 居民草稿解析：字段截断兜底（超长名称截断到 50）。
     */
    @Test
    void parseDraftsTruncatesOverlongFields() {
        String longName = "名".repeat(80);
        List<OrdinaryNpcDraftVO> drafts = OrdinaryNpcService.parseDrafts(
                "[{\"name\":\"" + longName + "\",\"occupation\":\"农夫\"}]");
        assertEquals(1, drafts.size());
        assertEquals(50, drafts.get(0).name().length());
    }

    /**
     * 居民草稿解析：空输入/非法 JSON/缺名称 → 空列表。
     */
    @Test
    void parseDraftsHandlesEmptyAndInvalidInput() {
        assertTrue(OrdinaryNpcService.parseDrafts(null).isEmpty());
        assertTrue(OrdinaryNpcService.parseDrafts("").isEmpty());
        assertTrue(OrdinaryNpcService.parseDrafts("不是 JSON").isEmpty());
        assertTrue(OrdinaryNpcService.parseDrafts("[]").isEmpty());
        assertTrue(OrdinaryNpcService.parseDrafts("[{\"occupation\":\"铁匠\"}]").isEmpty());
    }

    // ==================== 字段字典拟定解析（parseFieldDict） ====================

    /**
     * 字段字典解析：主/次分类字段 + 种族两级 / 归属 / 职业字段树。
     */
    @Test
    void parseFieldDictReadsClassificationAndFieldTree() {
        String content = "{\"classification\":{\"primary\":\"race\",\"secondary\":\"affiliation\"},"
                + "\"fields\":{"
                + "\"race\":[{\"level1\":\"人族\",\"level2\":\"汉族\",\"source\":\"世界观【种族】\"},"
                + "{\"level1\":\"妖精\",\"level2\":\"猫妖\",\"source\":\"世界观【种族】猫妖\"}],"
                + "\"affiliation\":[{\"level1\":\"临江渔村\",\"source\":\"世界观【势力】\"}],"
                + "\"occupation\":[{\"level1\":\"渔夫\",\"source\":\"世界观【职业】\"}]}}";
        FieldDictPreviewVO p = OrdinaryNpcService.parseFieldDict(content);
        assertEquals("race", p.primaryField());
        assertEquals("affiliation", p.secondaryField());
        assertEquals(2, p.fields().get(OrdinaryNpcService.FIELD_RACE).size());
        assertEquals("汉族", p.fields().get(OrdinaryNpcService.FIELD_RACE).get(0).level2());
        assertEquals("猫妖", p.fields().get(OrdinaryNpcService.FIELD_RACE).get(1).level2());
        assertEquals("临江渔村", p.fields().get(OrdinaryNpcService.FIELD_AFFILIATION).get(0).level1());
        assertEquals("渔夫", p.fields().get(OrdinaryNpcService.FIELD_OCCUPATION).get(0).level1());
        // 三个字段列表始终存在（空输入也返回空列表）
        assertEquals(3, p.fields().size());
    }

    /**
     * 字段字典解析：种族同 level1 不同 level2 不去重；同 level1+level2 去重。
     */
    @Test
    void parseFieldDictDeduplicatesByLevel1AndLevel2() {
        String content = "{\"fields\":{\"race\":["
                + "{\"level1\":\"人族\",\"level2\":\"汉族\",\"source\":\"a\"},"
                + "{\"level1\":\"人族\",\"level2\":\"汉族\",\"source\":\"b\"},"
                + "{\"level1\":\"人族\",\"level2\":\"回族\",\"source\":\"c\"}]}}";
        FieldDictPreviewVO p = OrdinaryNpcService.parseFieldDict(content);
        List<FieldDictVO> races = p.fields().get(OrdinaryNpcService.FIELD_RACE);
        assertEquals(2, races.size());
        assertEquals("汉族", races.get(0).level2());
        assertEquals("回族", races.get(1).level2());
    }

    /**
     * 字段字典解析：主/次字段归一（大小写/空白容错）、非法字段忽略；缺出处补默认文案。
     */
    @Test
    void parseFieldDictNormalizesFieldsAndDefaultsSource() {
        String content = "{\"classification\":{\"primary\":\" Race \",\"secondary\":\"occupation\"},"
                + "\"fields\":{\"race\":[{\"level1\":\"人族\",\"level2\":\"汉族\"}]}}";
        FieldDictPreviewVO p = OrdinaryNpcService.parseFieldDict(content);
        assertEquals("race", p.primaryField());
        assertEquals("occupation", p.secondaryField());
        assertEquals("取自世界观设定", p.fields().get(OrdinaryNpcService.FIELD_RACE).get(0).source());
    }

    /**
     * 字段字典解析：非 race 字段的 level2 忽略；空/非法输入返回三空列表。
     */
    @Test
    void parseFieldDictIgnoresLevel2ForNonRaceAndHandlesEmpty() {
        String content = "{\"fields\":{\"affiliation\":[{\"level1\":\"会馆\",\"level2\":\"无关二级\",\"source\":\"s\"}]}}";
        FieldDictPreviewVO p = OrdinaryNpcService.parseFieldDict(content);
        assertNull(p.fields().get(OrdinaryNpcService.FIELD_AFFILIATION).get(0).level2());
        FieldDictPreviewVO empty = OrdinaryNpcService.parseFieldDict("");
        assertTrue(empty.fields().get(OrdinaryNpcService.FIELD_RACE).isEmpty());
        FieldDictPreviewVO junk = OrdinaryNpcService.parseFieldDict("不是 JSON");
        assertTrue(junk.fields().get(OrdinaryNpcService.FIELD_OCCUPATION).isEmpty());
    }

    // ==================== 字段字典清洗（cleanFieldDict） ====================

    /**
     * 字段字典清洗：去空白、跳过空一级值、去重；非 race 字段 level2 清空。
     * 注意：cleanFieldDict 按固定字段名（race/affiliation/occupation）读分组，map key 不归一。
     */
    @Test
    void cleanFieldDictTrimsNormalizesAndDeduplicates() {
        Map<String, List<FieldDictVO>> raw = new LinkedHashMap<>();
        raw.put("race", List.of(
                new FieldDictVO("race", " 人族 ", " 汉族 ", "a"),
                new FieldDictVO("race", "人族", "汉族", "b"),
                new FieldDictVO("race", "  ", "x", "c")));
        raw.put("affiliation", List.of(new FieldDictVO("affiliation", "会馆", "无关二级", "d")));
        Map<String, List<FieldDictVO>> cleaned = OrdinaryNpcService.cleanFieldDict(raw);
        assertEquals(1, cleaned.get(OrdinaryNpcService.FIELD_RACE).size());
        assertEquals("人族", cleaned.get(OrdinaryNpcService.FIELD_RACE).get(0).level1());
        assertEquals("汉族", cleaned.get(OrdinaryNpcService.FIELD_RACE).get(0).level2());
        assertEquals(1, cleaned.get(OrdinaryNpcService.FIELD_AFFILIATION).size());
        assertEquals("会馆", cleaned.get(OrdinaryNpcService.FIELD_AFFILIATION).get(0).level1());
        // 非 race 字段 level2 被清空
        assertNull(cleaned.get(OrdinaryNpcService.FIELD_AFFILIATION).get(0).level2());
    }

    /**
     * 字段字典清洗：null 输入返回三空列表；非法字段名忽略。
     */
    @Test
    void cleanFieldDictHandlesNullAndUnknownFields() {
        Map<String, List<FieldDictVO>> cleaned = OrdinaryNpcService.cleanFieldDict(null);
        assertEquals(3, cleaned.size());
        assertTrue(cleaned.get(OrdinaryNpcService.FIELD_RACE).isEmpty());
        Map<String, List<FieldDictVO>> raw = Map.of("bogus", List.of(new FieldDictVO("bogus", "x", null, "s")));
        assertTrue(OrdinaryNpcService.cleanFieldDict(raw).get(OrdinaryNpcService.FIELD_OCCUPATION).isEmpty());
    }

    // ==================== 多字段筛选匹配（match） ====================

    /** 构造带基本档案的 NPC（包私有静态方法级辅助） */
    private static ActorOrdinaryNpc npc(String name, String gender, String race, String subRace, Integer age,
                                        String affiliation, String occupation, String location, String detail) {
        ActorOrdinaryNpc n = new ActorOrdinaryNpc();
        n.setName(name);
        n.setGender(gender);
        n.setRace(race);
        n.setSubRace(subRace);
        n.setAge(age);
        n.setAffiliation(affiliation);
        n.setOccupation(occupation);
        n.setLocation(location);
        n.setDetail(detail);
        return n;
    }

    /**
     * 筛选匹配：各字段精确匹配（性别/种族/次级种族/归属/职业/所在地）。
     */
    @Test
    void matchFiltersByAllFields() {
        ActorOrdinaryNpc n = npc("阿福", "男", "人族", "汉族", 28, "临江渔村", "渔夫", "临江码头", "世代打渔");
        assertTrue(OrdinaryNpcService.match(n, q("gender", "男")));
        assertTrue(OrdinaryNpcService.match(n, q("race", "人族")));
        assertTrue(OrdinaryNpcService.match(n, q("subRace", "汉族")));
        assertTrue(OrdinaryNpcService.match(n, q("affiliation", "临江渔村")));
        assertTrue(OrdinaryNpcService.match(n, q("occupation", "渔夫")));
        assertTrue(OrdinaryNpcService.match(n, q("location", "临江码头")));
        assertFalse(OrdinaryNpcService.match(n, q("race", "妖精")));
        assertFalse(OrdinaryNpcService.match(n, q("subRace", "猫妖")));
    }

    /**
     * 筛选匹配：年龄区间（含边界）+ 关键词（命中名称/种族/次级种族/归属/职业/地点/详情）。
     */
    @Test
    void matchFiltersByAgeRangeAndKeyword() {
        ActorOrdinaryNpc n = npc("阿福", "男", "人族", "汉族", 28, "临江渔村", "渔夫", "临江码头", "世代打渔");
        assertTrue(OrdinaryNpcService.match(n, q("ageMin", 28, "ageMax", 30)));
        assertTrue(OrdinaryNpcService.match(n, q("ageMin", 28, "ageMax", 28)));
        assertFalse(OrdinaryNpcService.match(n, q("ageMin", 29, "ageMax", 30)));
        assertFalse(OrdinaryNpcService.match(n, q("ageMin", 10, "ageMax", 27)));
        assertTrue(OrdinaryNpcService.match(n, q("keyword", "阿福")));
        assertFalse(OrdinaryNpcService.match(n, q("keyword", "猫"))); // 未命中
        assertTrue(OrdinaryNpcService.match(n, q("keyword", "打渔")));
        assertTrue(OrdinaryNpcService.match(n, q("keyword", "码头")));
    }

    /**
     * 筛选匹配：null 查询全放行；空过滤值不筛；null 实体字段按空串处理。
     */
    @Test
    void matchHandlesNullQueryAndBlankFilters() {
        ActorOrdinaryNpc n = npc("阿福", null, "人族", null, null, null, null, null, null);
        assertTrue(OrdinaryNpcService.match(n, null));
        assertTrue(OrdinaryNpcService.match(n, new OrdinaryNpcService.NpcQuery(null, null, null, null, null,
                null, "", null, null, null, null)));
        assertFalse(OrdinaryNpcService.match(n, new OrdinaryNpcService.NpcQuery("女", null, null, null, null,
                null, null, null, null, null, null)));
        // 空实体字段被过滤值精确匹配时不通过（null → 空串 ≠ 过滤值）
        assertFalse(OrdinaryNpcService.match(n, q("affiliation", "临江渔村")));
    }

    // ==================== 排序（sorter） ====================

    /**
     * 排序：白名单字段 asc/desc；null 值恒排最后（asc/desc 均如此，稳定排序）。
     * 每个场景用新列表（避免稳定排序受上一场景残留顺序影响）。
     */
    @Test
    void sorterSortsByWhitelistedFieldAndDirection() {
        ActorOrdinaryNpc a = npc("阿福", "男", "人族", "汉族", 28, "临江渔村", "渔夫", "临江码头", "");
        ActorOrdinaryNpc b = npc("翠娘", "女", "妖精", "猫妖", 35, "会馆", "商贩", "集市", "");
        ActorOrdinaryNpc c = npc("铁柱", "男", "人族", "汉族", null, "临江渔村", "铁匠", "铁匠铺", "");

        // 按年龄 asc：28、35、null（null 排最后）
        List<ActorOrdinaryNpc> l1 = new ArrayList<>(List.of(b, a, c));
        l1.sort(OrdinaryNpcService.sorter(q("sortBy", "age", "sortDir", "asc")));
        assertEquals("阿福", l1.get(0).getName());
        assertEquals("翠娘", l1.get(1).getName());
        assertEquals("铁柱", l1.get(2).getName());

        // 按年龄 desc：35、28、null（null 仍排最后）
        List<ActorOrdinaryNpc> l2 = new ArrayList<>(List.of(a, b, c));
        l2.sort(OrdinaryNpcService.sorter(q("sortBy", "age", "sortDir", "desc")));
        assertEquals("翠娘", l2.get(0).getName());
        assertEquals("阿福", l2.get(1).getName());
        assertEquals("铁柱", l2.get(2).getName());

        // 按名称 asc（Java String.compareTo 按 UTF-16 码点：翠 U+7FE0 < 铁 U+94C1 < 阿 U+963F）
        List<ActorOrdinaryNpc> l3 = new ArrayList<>(List.of(a, b, c));
        l3.sort(OrdinaryNpcService.sorter(q("sortBy", "name", "sortDir", "asc")));
        assertEquals("翠娘", l3.get(0).getName());
        assertEquals("铁柱", l3.get(1).getName());
        assertEquals("阿福", l3.get(2).getName());

        // 按归属 asc（临江渔村 U+4E34 < 会馆 U+4F1A；同值稳定：阿福、铁柱均在翠娘前）
        List<ActorOrdinaryNpc> l4 = new ArrayList<>(List.of(b, a, c));
        l4.sort(OrdinaryNpcService.sorter(q("sortBy", "affiliation")));
        assertEquals("阿福", l4.get(0).getName());
        assertEquals("铁柱", l4.get(1).getName());
        assertEquals("翠娘", l4.get(2).getName());

        // 按种族 desc（妖 U+5996 > 人 U+4EBA → 妖精在前；同值稳定：阿福、铁柱人族）
        List<ActorOrdinaryNpc> l5 = new ArrayList<>(List.of(a, b, c));
        l5.sort(OrdinaryNpcService.sorter(q("sortBy", "race", "sortDir", "desc")));
        assertEquals("翠娘", l5.get(0).getName());
        assertEquals("阿福", l5.get(1).getName());
        assertEquals("铁柱", l5.get(2).getName());

        // 默认按 id asc（null 查询 / 非法字段）
        ActorOrdinaryNpc x = new ActorOrdinaryNpc();
        x.setId(5L);
        x.setName("x");
        ActorOrdinaryNpc y = new ActorOrdinaryNpc();
        y.setId(2L);
        y.setName("y");
        List<ActorOrdinaryNpc> ids = List.of(x, y);
        List<ActorOrdinaryNpc> sorted = ids.stream().sorted(OrdinaryNpcService.sorter(null)).toList();
        assertEquals(2L, sorted.get(0).getId());
        assertEquals(5L, sorted.get(1).getId());
    }

    // ==================== 字段取值 / 分组概况（fieldValue / fieldGroupOverview） ====================

    /**
     * 字段取值：race/affiliation/occupation 归一；空值按「未分类/未知职业」；非法字段返回未分类。
     */
    @Test
    void fieldValueNormalizesByField() {
        ActorOrdinaryNpc n = npc("阿福", null, " 人族 ", null, null, " 临江渔村 ", null, null, null);
        assertEquals("人族", OrdinaryNpcService.fieldValue(n, OrdinaryNpcService.FIELD_RACE));
        assertEquals("临江渔村", OrdinaryNpcService.fieldValue(n, OrdinaryNpcService.FIELD_AFFILIATION));
        assertEquals("未知职业", OrdinaryNpcService.fieldValue(n, OrdinaryNpcService.FIELD_OCCUPATION));
        assertEquals("未分类", OrdinaryNpcService.fieldValue(n, "bogus"));
    }

    /**
     * 分组概况：按分类字段分组，输出含组名与人数。
     */
    @Test
    void fieldGroupOverviewGroupsByField() {
        ActorOrdinaryNpc a = npc("阿福", null, "人族", "汉族", null, "临江渔村", "渔夫", null, null);
        ActorOrdinaryNpc b = npc("阿贵", null, "人族", "回族", null, "临江渔村", "农夫", null, null);
        ActorOrdinaryNpc c = npc("猫娘", null, "妖精", "猫妖", null, "会馆", "侍女", null, null);
        String ov = OrdinaryNpcService.fieldGroupOverview(List.of(a, b, c), OrdinaryNpcService.FIELD_RACE);
        assertTrue(ov.contains("人族"));
        assertTrue(ov.contains("共 2 人"));
        assertTrue(ov.contains("妖精"));
        assertTrue(ov.contains("共 1 人"));
        // 次级种族构成出现在概况中
        assertTrue(ov.contains("汉族"));
        assertTrue(ov.contains("猫妖"));
        // 未配置字段返回提示
        assertTrue(OrdinaryNpcService.fieldGroupOverview(List.of(a), null).contains("未配置"));
    }

    // ==================== 项目级调度解析（parseProjectSchedule） ====================

    /**
     * 项目级调度解析：summary + 主分类分组指令（primaryGroups）+ 归属指令（affiliations）。
     */
    @Test
    void parseProjectScheduleReadsSummaryGroupsAndAffiliations() {
        String content = "{\"summary\":\"人族午后集会游行，妖精集市设摊待客。\","
                + "\"primaryGroups\":[{\"group\":\"人族\",\"directive\":\"午后集会游行\"},"
                + "{\"group\":\"妖精\",\"directive\":\"集市设摊待客\"}],"
                + "\"affiliations\":[{\"affiliation\":\"临江渔村\",\"directive\":\"渔汛出船\"},"
                + "{\"affiliation\":\"会馆\",\"directive\":\"维持秩序\"}]}";
        OrdinaryNpcService.ProjectSchedule plan = OrdinaryNpcService.parseProjectSchedule(content);
        assertEquals("人族午后集会游行，妖精集市设摊待客。", plan.summary());
        assertEquals(2, plan.primaryGroups().size());
        assertEquals("人族", plan.primaryGroups().get(0).group());
        assertEquals("午后集会游行", plan.primaryGroups().get(0).directive());
        assertEquals(2, plan.affiliations().size());
        assertEquals("会馆", plan.affiliations().get(1).affiliation());
        assertEquals("维持秩序", plan.affiliations().get(1).directive());
    }

    /**
     * 项目级调度解析：缺组名/指令的项跳过；非法 JSON 抛中文异常。
     */
    @Test
    void parseProjectScheduleSkipsIncompleteAndThrowsOnInvalid() {
        String content = "{\"summary\":\"s\","
                + "\"primaryGroups\":[{\"group\":\"人族\",\"directive\":\"\"},"
                + "{\"group\":\"\",\"directive\":\"d\"},{\"group\":\"妖精\",\"directive\":\"设摊\"}],"
                + "\"affiliations\":[]}";
        OrdinaryNpcService.ProjectSchedule plan = OrdinaryNpcService.parseProjectSchedule(content);
        assertEquals(1, plan.primaryGroups().size());
        assertEquals("妖精", plan.primaryGroups().get(0).group());
        assertTrue(plan.affiliations().isEmpty());
        assertThrows(BizException.class, () -> OrdinaryNpcService.parseProjectSchedule("不是 JSON"));
    }

    /** 构造 NpcQuery（字段名-值键值对，自动映射记录字段） */
    private static OrdinaryNpcService.NpcQuery q(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return new OrdinaryNpcService.NpcQuery(
                m.get("gender"),
                m.get("race"),
                m.get("subRace"),
                m.get("affiliation"),
                m.get("occupation"),
                m.get("location"),
                m.get("keyword"),
                m.get("ageMin") == null ? null : Integer.parseInt(m.get("ageMin")),
                m.get("ageMax") == null ? null : Integer.parseInt(m.get("ageMax")),
                m.get("sortBy"),
                m.get("sortDir"));
    }
}
