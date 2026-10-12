package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.domain.npc.relation.entity.ActorCharacterRelation;
import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.worldmap.config.WorldMapProperties;
import com.holzyn.actor.domain.worldmap.model.MapCharacter;
import com.holzyn.actor.domain.worldmap.model.WorldMapHome;
import com.holzyn.actor.domain.worldmap.model.WorldMapLayout;
import com.holzyn.actor.domain.worldmap.model.WorldMapRegion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldMapMechanismGenerator 单元测试（位置移动与地图生成系统 M1，V1.3 第六层：纯静态构造断言）。
 * <p>职责：机制兜底满足「现实聚落分布规律」（核心居中/民居环带/生产外围/村口地标）、
 * 同居共宅分组（亲密关系同住）、确定性（同 seed 同布局）、空间合法（界内/不重叠/住所不落路）。</p>
 */
class WorldMapMechanismGeneratorTest {

    private final WorldMapMechanismGenerator generator = new WorldMapMechanismGenerator();
    private final WorldMapProperties props = new WorldMapProperties();
    private final int sceneSize = 500;

    private ActorWorldLocation loc(long id, String name, String type) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setName(name);
        l.setType(type);
        l.setIntro(name + "的简介：此处风物清嘉，人来人往。");
        return l;
    }

    private MapCharacter chara(String ref, String name, String type, String occupation) {
        MapCharacter c = new MapCharacter();
        c.setRef(ref);
        c.setName(name);
        c.setType(type);
        c.setOccupation(occupation);
        c.setImportance(type.equals("special") ? 4 : 1);
        c.setOwnerCandidate(type.equals("special"));
        return c;
    }

    private List<ActorWorldLocation> villageLocations() {
        List<ActorWorldLocation> list = new ArrayList<>();
        list.add(loc(1, "村口石牌坊", "地标"));
        list.add(loc(2, "青禾祠堂", "宗祠"));
        list.add(loc(3, "村中集市", "集市"));
        list.add(loc(4, "青禾医馆", "医馆"));
        list.add(loc(5, "沈记粮铺", "商铺"));
        list.add(loc(6, "沈家大宅", "住宅"));
        list.add(loc(7, "李记面馆后屋", "住宅"));
        list.add(loc(8, "竹林茅舍", "住宅"));
        list.add(loc(9, "村西梯田", "农田"));
        list.add(loc(10, "山神庙", "庙宇"));
        list.add(loc(11, "渡口", "码头"));
        return list;
    }

    private List<MapCharacter> villageCharacters() {
        List<MapCharacter> list = new ArrayList<>();
        list.add(chara("npc:1", "沈砚秋", "special", "粮商"));
        list.add(chara("npc:2", "温婉", "special", "医者"));
        list.add(chara("npc:3", "李掌柜", "special", "面馆掌柜"));
        list.add(chara("npc:4", "王阿婆", "common", "农妇"));
        list.add(chara("npc:5", "孙二娘", "common", "摊贩"));
        return list;
    }

    private ActorCharacterRelation rel(String a, String b, String type) {
        ActorCharacterRelation r = new ActorCharacterRelation();
        r.setFromRef(a);
        r.setToRef(b);
        r.setRelationType(type);
        return r;
    }

    @Test
    void generatesRealisticVillageLayout() {
        WorldMapLayout layout = generator.generate(villageLocations(), villageCharacters(),
                List.of(), sceneSize, 42L, "village", props, List.of());
        assertEquals("mechanism", layout.getSource());
        assertFalse(layout.getRegions().isEmpty(), "应有区域");
        // ① 坐标界内 + 半径合法
        for (WorldMapRegion r : layout.getRegions()) {
            assertTrue(r.getCenterX() >= props.getMargin() && r.getCenterX() <= sceneSize - props.getMargin(),
                    "区域 X 越界：" + r.getName());
            assertTrue(r.getCenterZ() >= props.getMargin() && r.getCenterZ() <= sceneSize - props.getMargin(),
                    "区域 Z 越界：" + r.getName());
            assertTrue(r.getRadius() >= props.getMinRegionRadius() && r.getRadius() <= props.getMaxRegionRadius(),
                    "区域半径非法：" + r.getName());
        }
        // ② 区域两两不重叠
        List<WorldMapRegion> regions = layout.getRegions();
        for (int i = 0; i < regions.size(); i++) {
            for (int j = i + 1; j < regions.size(); j++) {
                assertFalse(WorldMapValidator.regionsOverlap(regions.get(i), regions.get(j), props.getRegionGap()),
                        "区域重叠：" + regions.get(i).getName() + " ↔ " + regions.get(j).getName());
            }
        }
        // ③ 现实分布：祠堂（核心）比梯田（生产）更近村心
        double cx = sceneSize / 2.0;
        double cz = sceneSize / 2.0;
        double coreDist = distToCenter(regions, cx, cz, "青禾祠堂");
        double fieldDist = distToCenter(regions, cx, cz, "村西梯田");
        assertTrue(coreDist < fieldDist, "祠堂应比梯田更近村心（core=" + coreDist + " field=" + fieldDist + "）");
        // ④ 村口地标在边缘
        double gateDist = distToCenter(regions, cx, cz, "村口石牌坊");
        assertTrue(gateDist > coreDist, "牌坊应在村边（比祠堂远）");
        // ⑤ 道路存在且坐标合法
        assertFalse(layout.getRoads().isEmpty(), "应有道路");
        assertFalse(layout.getPlacements().isEmpty(), "应有建筑摆放");
    }

    @Test
    void cohabitationGroupingSharesOneHome() {
        List<ActorCharacterRelation> relations = List.of(rel("npc:1", "npc:2", "夫妻"));
        WorldMapLayout layout = generator.generate(villageLocations(), villageCharacters(),
                relations, sceneSize, 42L, "village", props, List.of());
        // 5 人 = 1 户夫妻 + 3 独居 = 4 间住所
        assertEquals(4, layout.getHomes().size(), "夫妻同住应只生成一间（5 人 → 4 间）");
        // 每角色恰在一间住所
        Set<String> covered = new HashSet<>();
        for (WorldMapHome h : layout.getHomes()) {
            for (var m : h.getMembers()) {
                assertTrue(covered.add(m.getCharacterRef()), "角色重复入住：" + m.getCharacterRef());
            }
        }
        assertEquals(5, covered.size(), "全部角色应有住所");
        // 夫妻同住一间且恰一个户主
        WorldMapHome coupleHome = layout.getHomes().stream()
                .filter(h -> h.getMembers().size() == 2).findFirst().orElse(null);
        assertNotNull(coupleHome, "应存在夫妻同住的住所");
        long owners = coupleHome.getMembers().stream().filter(m -> m.isOwner()).count();
        assertEquals(1, owners, "一间住所恰一个户主");
        Set<String> names = new HashSet<>();
        coupleHome.getMembers().forEach(m -> names.add(m.getCharacterRef()));
        assertTrue(names.contains("npc:1") && names.contains("npc:2"), "夫妻应同住一间");
        assertTrue(coupleHome.getName().contains("沈"), "家名应以户主姓氏开头：" + coupleHome.getName());
    }

    @Test
    void homesAvoidRoadsAndAreSeparated() {
        WorldMapLayout layout = generator.generate(villageLocations(), villageCharacters(),
                List.of(), sceneSize, 7L, "village", props, List.of());
        for (WorldMapHome h : layout.getHomes()) {
            // 不落任何道路
            for (var road : layout.getRoads()) {
                assertFalse(WorldMapValidator.pointOnPolyline(h.getPosX(), h.getPosZ(),
                                road.getPath(), road.getWidth() / 2 + 3),
                        "住所落在路上：" + h.getName() + " 于 " + road.getName());
            }
        }
        // 住所两两间距 ≥ homeMinDistance
        List<WorldMapHome> homes = layout.getHomes();
        for (int i = 0; i < homes.size(); i++) {
            for (int j = i + 1; j < homes.size(); j++) {
                double d = Math.hypot(homes.get(i).getPosX() - homes.get(j).getPosX(),
                        homes.get(i).getPosZ() - homes.get(j).getPosZ());
                assertTrue(d >= props.getHomeMinDistance(),
                        "住所过近：" + homes.get(i).getName() + " ↔ " + homes.get(j).getName() + " d=" + d);
            }
        }
    }

    @Test
    void deterministicSameSeed() {
        WorldMapLayout a = generator.generate(villageLocations(), villageCharacters(),
                List.of(), sceneSize, 99L, "village", props, List.of());
        WorldMapLayout b = generator.generate(villageLocations(), villageCharacters(),
                List.of(), sceneSize, 99L, "village", props, List.of());
        assertEquals(a.getRegions().size(), b.getRegions().size());
        for (int i = 0; i < a.getRegions().size(); i++) {
            assertEquals(a.getRegions().get(i).getCenterX(), b.getRegions().get(i).getCenterX(), 1e-6);
            assertEquals(a.getRegions().get(i).getCenterZ(), b.getRegions().get(i).getCenterZ(), 1e-6);
        }
        assertEquals(a.getHomes().size(), b.getHomes().size());
    }

    private double distToCenter(List<WorldMapRegion> regions, double cx, double cz, String name) {
        for (WorldMapRegion r : regions) {
            if (name.equals(r.getName())) {
                return Math.hypot(r.getCenterX() - cx, r.getCenterZ() - cz);
            }
        }
        throw new AssertionError("找不到区域：" + name);
    }
}
