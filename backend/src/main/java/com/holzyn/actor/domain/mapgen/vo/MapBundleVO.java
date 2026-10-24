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

package com.holzyn.actor.domain.mapgen.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 地图渲染包——前端画一张图需要的<b>全部</b>数据，一次请求拿完。
 *
 * <p>为什么做成「一个包」而不是「要素一个接口、道路一个接口」：地图是全量渲染的——
 * 少拿一层数据就是一次空白、一次闪动。1024² 的图在 LOD 低时只需要道路骨架，
 * 但道路骨架和建筑必须<b>同一次</b>到位，否则缩放时会出现「房子已经出现、路还没画」的错帧。</p>
 *
 * <p>坐标全部是<b>米</b>（原点西南角，X 向东、Z 向南），前端乘 {@code metersPerCell} 换算像素——
 * 这样换分辨率不动数据，也便于和 Unity 端共用。</p>
 */
@Data
public class MapBundleVO {

    /** 概览（同一份对象，避免前端再拉一次） */
    private WorldMapOverviewVO overview;

    /** 调色板（地形底图之外的矢量图元用色，后端唯一来源） */
    private Palette palette;

    /** 道路（按等级升序：低等级先画） */
    private List<RoadVO> roads;

    /** 地图要素（建筑/农田/林地/码头/桥/广场/围墙） */
    private List<FeatureVO> features;

    /** 院落/街区/田片 */
    private List<GroupVO> groups;

    /** 街区性格（S2 产出，前端做区域名/悬浮说明） */
    private List<Map<String, Object>> districts;

    /** 环境快照（季节/天气/潮汐/昼夜，按世界时钟实时算） */
    private Map<String, Object> ambient;

    /** 角色（家 + 一天的日程锦点；前端按世界时钟让角色在图上走动） */
    private List<CharacterVO> characters;

    /**
     * 一个角色在地图上的存在。
     *
     * <p>为什么把日程直接塞进渲染包而不是单开接口：角色位置是「每帧都要用」的数据，
     * 单开接口会让地图与角色点出现几十毫秒的错帧（房子已经画好、人还没到）。
     * 12 个角色 × 10 段只有几 KB，一次拿完最省事。</p>
     */
    @Data
    public static class CharacterVO {

        /** 角色统一引用（npc:12） */
        private String ref;

        /** 角色名 */
        private String name;

        /** 住所引用（actor_npc_home.id 的字符串） */
        private String homeRef;

        /** 家对应的地图要素 code */
        private String homeFeatureCode;

        /** 家的坐标（米） */
        private Double x;

        /** 家的坐标（米） */
        private Double z;

        /** 当前活动（服务端按世界时钟算；前端也可自行插值） */
        private String activity;

        /** 当前活动的中文描述 */
        private String activityLabel;

        /** 当前是否在室内 */
        private Boolean indoor;

        /** 一天的日程段 */
        private List<RoutineVO> segments;
    }

    /** 日程段。 */
    @Data
    public static class RoutineVO {

        /** 起始分钟（0~1439） */
        private Integer startMinute;

        /** 结束分钟（0~1440） */
        private Integer endMinute;

        /** 落点类型：home/work/market/field/shore/well/social */
        private String placeKind;

        /** 落点名字 */
        private String placeRef;

        /** 落点要素 code */
        private String featureCode;

        /** 落点 X（米） */
        private Double x;

        /** 落点 Z（米） */
        private Double z;

        /** 活动（sleep/meal/work/errand/chat/chores） */
        private String activity;

        /** 中文描述 */
        private String label;

        /** 是否室内 */
        private Boolean indoor;
    }

    /**
     * 调色板。
     * <p>道路色不是前端硬编码的：{@link #roadColors} 由后端
     * {@code MapCompositePainter.roadColorHex} 生成，与「地形+道路」合成图<b>同一个函数</b>——
     * 否则小图和大图会出现同一段路两种颜色。</p>
     */
    @Data
    public static class Palette {

        /** 等级 → 路面色（键是 "0"/"1"/"2"/"3"） */
        private Map<String, String> roadColors;

        /** 等级 → 路肩色 */
        private Map<String, String> shoulderColors;

        /** 等级 → 中文名（「田埂土路」） */
        private Map<String, String> roadLabels;

        /** 等级 → 典型宽度（米） */
        private Map<String, Double> roadWidths;
    }

    /**
     * 一条路。
     */
    @Data
    public static class RoadVO {

        /** 编码 */
        private String code;

        /** 名称 */
        private String name;

        /** 类型（street/lane/path/track/connector/pier） */
        private String kind;

        /** 语义等级（track/village/street/trunk） */
        private String level;

        /** 数值等级 0~3（越高越好） */
        private Integer grade;

        /** 等级中文名 */
        private String gradeLabel;

        /** 路面宽（米） */
        private Double width;

        /** 路面材质 */
        private String surface;

        /** 折线（米，{@code [[x,z],...]}） */
        private List<double[]> path;

        /** 长度（米） */
        private Double length;

        /** 是否跨水（桥） */
        private Boolean bridge;
    }

    /**
     * 一个地图要素。
     */
    @Data
    public static class FeatureVO {

        /** 编码 */
        private String code;

        /** 名称 */
        private String name;

        /** 大类（民居/商铺/工坊/公共/生产/水工/自然） */
        private String category;

        /** 几何类型（building/field/forest/pier/bridge/plaza/wall） */
        private String kind;

        /** 资产类型编码 */
        private String assetCode;

        /** 中心 X（米） */
        private Double x;

        /** 中心 Z（米） */
        private Double z;

        /** 宽（米） */
        private Double w;

        /** 深（米） */
        private Double d;

        /** 旋转（度） */
        private Double rot;

        /** 高（米） */
        private Double height;

        /** 层数 */
        private Integer floors;

        /** 墙色 */
        private String wallColor;

        /** 屋顶色 */
        private String roofColor;

        /** 屋顶形制 */
        private String roofShape;

        /** 多边形（米，{@code [[x,z],...]}；建筑是外轮廓，农田/林地是地块边界） */
        private List<double[]> polygon;

        /** 门的世界坐标 X（米） */
        private Double doorX;

        /** 门的世界坐标 Z（米） */
        private Double doorZ;

        /** 门的朝向（度） */
        private Double doorDir;

        /** 是否住人（1=可作角色住所） */
        private Integer residency;

        /** 重要度 1~3 */
        private Integer importance;

        /** 归属地点 code（语义层锚点） */
        private String locationCode;

        /** 语义补充（JSON，前端做悬浮卡） */
        private String semantic;

        /** 归属住所 ref（V5：这栋房子属于哪个 {@code actor_npc_home}） */
        private String homeRef;

        /** 锚定的世界地点名（「月牙湾」；报告里核对选址用） */
        private String anchorLocation;

        /** 所属世界区域编码 */
        private String regionCode;

        /** 户主住所 ref（附属房/条田用：属于哪一户） */
        private String ownerHomeRef;

        /** 渲染/筛选标记（{@code {"garden":true}} / {@code {"strip":true,"ridge":true}}） */
        private Map<String, Object> flags;
    }

    /**
     * 院落/街区/田片。
     */
    @Data
    public static class GroupVO {

        /** 编码 */
        private String code;

        /** 名称 */
        private String name;

        /** 类型（compound/block/field/hamlet/dock） */
        private String kind;

        /** 中心 X（米） */
        private Double cx;

        /** 中心 Z（米） */
        private Double cz;

        /** 半径（米） */
        private Double radius;

        /** 旋转（度） */
        private Double rotation;

        /** 风格编码 */
        private String styleCode;

        /** 说明 */
        private String description;
    }
}
