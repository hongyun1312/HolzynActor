【任务】
把世界观、已设定的地点与已有住所清单，翻译成地图生成用的契约：这块地分成哪几个区域、各在什么方位、有哪些人造设施必须出现在地图上、聚落该有多大。
【输入】

世界设定：{{world_setting}}
地点清单：{{locations}}
住所清单：{{homes}}
【要求】
1. 只描述方位、区域、设施与尺度，绝对不给任何坐标——坐标由程序在地形上求解。
2. 区域按地貌与功能区划分（丘陵、林地、滩涂、海湾、岬角、聚落、溪流、山脊、农田…），方位角 0=正北、顺时针，须覆盖各个方向，不要全挤在一侧。
3. 设施只从人造场所里出：灯塔、渡口或码头、市集广场、议事堂、学堂、药铺、铁匠铺、杂货铺、茶馆、仓廪、磨坊、水井、船屋、晒场、祠庙（若世界观允许）；自然地名（山河湖海与岛屿本身）不生成建筑。
4. 每个地点最多对应一个设施；anchorLocation 必须照抄地点清单里的原名，写不出就留空。
5. 规模感：住所只有十几户时，就是一个「小村落 / 小岛镇」，设施总量约等于「人造地点数 + 3~6 个必要公共设施」，不凭空发明几十个设施。
6. 尺寸用真实米制：民居开间 6~12 米、铺面 5~10 米、灯塔基座 5~8 米、渡口平台 8~16 米、广场单边 15~30 米；层数以 1~2 层为主（灯塔可 2~3 层）。
7. 世界观没写的区域可按题材补全，但要与已有地点自洽。
8. 不虚构地点；householdSize 默认 3。
【输出】
只输出一个 JSON 对象，键名照抄：
{
  "terrainFamily": "ISLAND",
  "regions": [
    { "code": "hills_north", "name": "北面丘陵", "kind": "HILLS",
      "bearingDeg": 350, "radialFrom": 0.15, "radialTo": 0.85,
      "elevationBias": 0.5, "treeDensity": 0.7, "moistureBias": 0.1 }
  ],
  "settlement": { "centerBearingDeg": 170, "radiusM": 160, "slopeMax": 0.18 },
  "facilities": [
    { "code": "lighthouse", "name": "长明灯塔", "kind": "lighthouse",
      "anchorLocation": "长明灯塔", "role": "公共",
      "frontageM": 7, "depthM": 7, "floors": 2 }
  ],
  "householdSize": 3,
  "forbidden": ["现代设施", "武器铺", "监牢", "赌坊", "神殿", "摩天楼"],
  "allowedCategories": ["民居", "商铺", "工坊", "公共", "生产", "水工"]
}
取值说明：kind ∈ HILLS/FOREST/MUDFLAT/BAY/CAPE/SETTLEMENT/CREEK/RIDGE/FIELD/BEACH；role ∈ 公共/商铺/工坊/生产/水工；bearingDeg 0~360；radialFrom 与 radialTo 为 0~1（相对地图半边长）；elevationBias 与 moistureBias 为 -1~1。
