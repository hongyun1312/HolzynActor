package com.holzyn.actor.domain.worldmap.service;

import com.holzyn.actor.domain.world.entity.ActorWorldLocation;
import com.holzyn.actor.domain.worldmap.config.WorldMapProperties;
import com.holzyn.actor.domain.worldmap.model.MapCharacter;
import com.holzyn.actor.domain.worldmap.model.WorldMapHome;
import com.holzyn.actor.domain.worldmap.model.WorldMapHomeMember;
import com.holzyn.actor.domain.worldmap.model.WorldMapLayout;
import com.holzyn.actor.domain.worldmap.model.WorldMapRegion;
import com.holzyn.actor.domain.worldmap.model.WorldMapRoad;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorldMapValidator 单元测试（位置移动与地图生成系统 M1，V1.3 第六层：纯静态构造断言）。
 * <p>职责：空间合法性（坐标界内/区域不重叠/住所不落路/住所间距）与引用完整性
 * （每角色恰一间住所/locationId 与 characterRef 存在）——任一失败整批回退机制兜底。</p>
 */
class WorldMapValidatorTest {

    private final WorldMapValidator validator = new WorldMapValidator();
    private final WorldMapProperties props = new WorldMapProperties();
    private final int sceneSize = 500;

    private ActorWorldLocation loc(long id, String name) {
        ActorWorldLocation l = new ActorWorldLocation();
        l.setId(id);
        l.setName(name);
        return l;
    }

    private MapCharacter chara(String ref) {
        MapCharacter c = new MapCharacter();
        c.setRef(ref);
        c.setName(ref);
        return c;
    }

    private WorldMapHome home(String name, double x, double z, String... refs) {
        WorldMapHome h = new WorldMapHome();
        h.setName(name);
        h.setPosX(x);
        h.setPosZ(z);
        for (int i = 0; i < refs.length; i++) {
            WorldMapHomeMember m = new WorldMapHomeMember();
            m.setCharacterRef(refs[i]);
            m.setRole(i == 0 ? "户主" : "同住");
            m.setOwner(i == 0);
            h.getMembers().add(m);
        }
        return h;
    }

    @Test
    void validLayoutPasses() {
        WorldMapLayout layout = new WorldMapLayout();
        WorldMapRegion r1 = new WorldMapRegion();
        r1.setLocationId(1L);
        r1.setName("村口石牌坊");
        r1.setCenterX(250);
        r1.setCenterZ(420);
        r1.setRadius(14);
        layout.getRegions().add(r1);
        WorldMapRegion r2 = new WorldMapRegion();
        r2.setLocationId(2L);
        r2.setName("青禾医馆");
        r2.setCenterX(250);
        r2.setCenterZ(300);
        r2.setRadius(12);
        layout.getRegions().add(r2);
        WorldMapRoad road = new WorldMapRoad();
        road.setName("主道");
        road.setWidth(5);
        road.getPath().add(new double[]{250, 40});
        road.getPath().add(new double[]{250, 470});
        layout.getRoads().add(road);
        layout.getHomes().add(home("沈砚秋家", 240, 310, "npc:1"));

        Map<Long, ?> locations = Map.of(1L, loc(1L, "村口石牌坊"), 2L, loc(2L, "青禾医馆"));
        Map<String, MapCharacter> characters = Map.of("npc:1", chara("npc:1"));

        WorldMapValidator.Result result = validator.validate(layout, locations, characters,
                List.of(), props, sceneSize);
        assertTrue(result.valid(), "合法布局应通过，issues=" + result.issues());
    }

    @Test
    void regionOverlapFails() {
        WorldMapLayout layout = new WorldMapLayout();
        WorldMapRegion r1 = new WorldMapRegion();
        r1.setLocationId(1L);
        r1.setName("A");
        r1.setCenterX(250);
        r1.setCenterZ(250);
        r1.setRadius(20);
        layout.getRegions().add(r1);
        WorldMapRegion r2 = new WorldMapRegion();
        r2.setLocationId(2L);
        r2.setName("B");
        r2.setCenterX(252);
        r2.setCenterZ(250);
        r2.setRadius(20);
        layout.getRegions().add(r2);
        layout.getHomes().add(home("独居", 200, 200, "npc:1"));

        Map<Long, ?> locations = Map.of(1L, loc(1L, "A"), 2L, loc(2L, "B"));
        Map<String, MapCharacter> characters = Map.of("npc:1", chara("npc:1"));
        WorldMapValidator.Result result = validator.validate(layout, locations, characters,
                List.of(), props, sceneSize);
        assertFalse(result.valid(), "重叠区域应校验失败");
        assertTrue(result.issues().stream().anyMatch(s -> s.contains("重叠")));
    }

    @Test
    void homeOnRoadFails() {
        WorldMapLayout layout = new WorldMapLayout();
        WorldMapRegion r = new WorldMapRegion();
        r.setLocationId(1L);
        r.setName("村中集市");
        r.setCenterX(250);
        r.setCenterZ(250);
        r.setRadius(20);
        layout.getRegions().add(r);
        WorldMapRoad road = new WorldMapRoad();
        road.setName("主道");
        road.setWidth(5);
        road.getPath().add(new double[]{250, 40});
        road.getPath().add(new double[]{250, 470});
        layout.getRoads().add(road);
        // 家在路中线上（x=250）
        layout.getHomes().add(home("路边屋", 250, 300, "npc:1"));

        Map<Long, ?> locations = Map.of(1L, loc(1L, "村中集市"));
        Map<String, MapCharacter> characters = Map.of("npc:1", chara("npc:1"));
        WorldMapValidator.Result result = validator.validate(layout, locations, characters,
                List.of(), props, sceneSize);
        assertFalse(result.valid(), "住所落在路上应校验失败");
        assertTrue(result.issues().stream().anyMatch(s -> s.contains("路上")));
    }

    @Test
    void uncoveredCharacterFails() {
        WorldMapLayout layout = new WorldMapLayout();
        WorldMapRegion r = new WorldMapRegion();
        r.setLocationId(1L);
        r.setName("村中集市");
        r.setCenterX(250);
        r.setCenterZ(250);
        r.setRadius(20);
        layout.getRegions().add(r);
        layout.getHomes().add(home("独居", 200, 200, "npc:1"));
        // npc:2 无住所
        Map<Long, ?> locations = Map.of(1L, loc(1L, "村中集市"));
        Map<String, MapCharacter> characters = Map.of("npc:1", chara("npc:1"), "npc:2", chara("npc:2"));
        WorldMapValidator.Result result = validator.validate(layout, locations, characters,
                List.of(), props, sceneSize);
        assertFalse(result.valid(), "存在无住所角色应校验失败");
        assertTrue(result.issues().stream().anyMatch(s -> s.contains("无住所")));
    }

    @Test
    void duplicateRefFails() {
        WorldMapLayout layout = new WorldMapLayout();
        WorldMapRegion r = new WorldMapRegion();
        r.setLocationId(1L);
        r.setName("村中集市");
        r.setCenterX(250);
        r.setCenterZ(250);
        r.setRadius(20);
        layout.getRegions().add(r);
        layout.getHomes().add(home("甲宅", 200, 200, "npc:1"));
        layout.getHomes().add(home("乙宅", 300, 200, "npc:1"));
        Map<Long, ?> locations = Map.of(1L, loc(1L, "村中集市"));
        Map<String, MapCharacter> characters = Map.of("npc:1", chara("npc:1"));
        WorldMapValidator.Result result = validator.validate(layout, locations, characters,
                List.of(), props, sceneSize);
        assertFalse(result.valid(), "角色重复入住应校验失败");
        assertTrue(result.issues().stream().anyMatch(s -> s.contains("重复入住")));
    }

    @Test
    void homeTooCloseFails() {
        WorldMapLayout layout = new WorldMapLayout();
        WorldMapRegion r = new WorldMapRegion();
        r.setLocationId(1L);
        r.setName("村中集市");
        r.setCenterX(250);
        r.setCenterZ(250);
        r.setRadius(30);
        layout.getRegions().add(r);
        layout.getHomes().add(home("甲宅", 200, 200, "npc:1"));
        layout.getHomes().add(home("乙宅", 205, 200, "npc:2"));
        Map<Long, ?> locations = Map.of(1L, loc(1L, "村中集市"));
        Map<String, MapCharacter> characters = Map.of("npc:1", chara("npc:1"), "npc:2", chara("npc:2"));
        WorldMapValidator.Result result = validator.validate(layout, locations, characters,
                List.of(), props, sceneSize);
        assertFalse(result.valid(), "住所间距过近应校验失败");
        assertTrue(result.issues().stream().anyMatch(s -> s.contains("距离过近")));
    }

    @Test
    void duplicateLocationIdFails() {
        WorldMapLayout layout = new WorldMapLayout();
        WorldMapRegion r1 = new WorldMapRegion();
        r1.setLocationId(1L);
        r1.setName("A");
        r1.setCenterX(200);
        r1.setCenterZ(200);
        r1.setRadius(15);
        layout.getRegions().add(r1);
        WorldMapRegion r2 = new WorldMapRegion();
        r2.setLocationId(1L);
        r2.setName("B");
        r2.setCenterX(300);
        r2.setCenterZ(200);
        r2.setRadius(15);
        layout.getRegions().add(r2);
        layout.getHomes().add(home("独居", 200, 200, "npc:1"));
        Map<Long, ?> locations = Map.of(1L, loc(1L, "A"));
        Map<String, MapCharacter> characters = Map.of("npc:1", chara("npc:1"));
        WorldMapValidator.Result result = validator.validate(layout, locations, characters,
                List.of(), props, sceneSize);
        assertFalse(result.valid(), "区域 locationId 重复应校验失败");
    }

    @Test
    void repairSpatialFixesOverlapAndHomeClash() {
        // 构造 AI 常见缺陷：区域重叠 + 住所落路 + 住所过近（repairSpatial 应修正后通过校验）
        WorldMapLayout layout = new WorldMapLayout();
        WorldMapRegion big = new WorldMapRegion();
        big.setLocationId(1L);
        big.setName("后山竹林");
        big.setCenterX(300);
        big.setCenterZ(250);
        big.setRadius(70);
        layout.getRegions().add(big);
        WorldMapRegion other = new WorldMapRegion();
        other.setLocationId(2L);
        other.setName("村口");
        other.setCenterX(250);
        other.setCenterZ(250);
        other.setRadius(40);
        layout.getRegions().add(other);
        // 大半径重叠（圆心距 50 < 70+40+4）
        WorldMapRoad road = new WorldMapRoad();
        road.setName("主道");
        road.setWidth(5);
        road.getPath().add(new double[]{250, 40});
        road.getPath().add(new double[]{250, 470});
        layout.getRoads().add(road);
        // 甲宅落主道（x=250）且与乙宅过近
        layout.getHomes().add(home("甲宅", 250, 300, "npc:1"));
        layout.getHomes().add(home("乙宅", 252, 300, "npc:2"));

        Map<Long, ?> locations = Map.of(1L, loc(1L, "后山竹林"), 2L, loc(2L, "村口"));
        Map<String, MapCharacter> characters = Map.of("npc:1", chara("npc:1"), "npc:2", chara("npc:2"));

        // 修复前：重叠 + 落路 + 过近
        WorldMapValidator.Result before = validator.validate(layout, locations, characters,
                List.of(), props, sceneSize);
        assertFalse(before.valid(), "修复前应校验失败");

        int fixed = validator.repairSpatial(layout, props, sceneSize);
        assertTrue(fixed > 0, "空间修复应产生动作，fixed=" + fixed);

        WorldMapValidator.Result after = validator.validate(layout, locations, characters,
                List.of(), props, sceneSize);
        assertTrue(after.valid(), "修复后应通过校验，issues=" + after.issues());
    }
}
