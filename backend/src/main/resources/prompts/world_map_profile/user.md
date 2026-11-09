【任务】
把这片土地的地理性格翻译成一组参数，供程序据此生成真实地形（高度场、水系、海岸、生物群系）。
【输入】

世界设定：{{world_setting}}
已设定的地点：{{locations}}
题材提示：{{genre_hint}}
地图边长 {{size_meters}} 米，1 格 = 1 米。按「步行可达的小镇村落」考虑尺度：镇子核心区通常 300~600 米见方，走完一条街只要几分钟。
【要求】
1. 忠于世界观：写「低矮石屋」就不要给高楼，写「多雾多风」就让湿度与海岸破碎度偏高。
2. 宜居优先：这是有人长期生活的地方，必须留出足够平地，不要把整座岛做成山地；只有明确写「高山/雪岭/险峻」时才允许海拔 600 米以上与高峰。
3. 水系合理：有河必有谷；岛上的河都不长（1~3 公里），riverCount 不超过 3。
4. 缺口自补：设定没写的地理细节（海湾数量、林密度等）按题材补全，但要自洽。
5. 不输出任何坐标；landmarks 的 sit 必须是空间语义（岬角最东端的高处 / 河口西岸 / 山脊南坡）。
6. 数值必须在合法范围内：
   genreProfile 取 island（海岛）/ river_valley_village（河谷村）/ coastal_town（海滨镇）/ mountain_hamlet（山村）/ plain_city（平原城）；
   reliefHeight 0.12~0.62（海岛小镇 0.22~0.34，山地 0.45~0.60）；elevationMeters 30~900（海岛 60~150 即可）；
   islandRadiusRatio 0.18~1.05；elongation 0.7~2.0；rotationDeg 0~360；coastRoughness 0~1（治愈系海岛 0.4~0.65）；
   bays 0~5；coastShelf 10~120；reliefStrength 0.2~1.4；peakCount 0~5；peakHeight 0.05~0.55；ridgeBearingDeg 0~360（0=南北向）；
   plainFraction 0~0.9（田园小镇 0.5~0.75，山地 0.15~0.3）；plainRadius 0.10~0.60；plainCenterX/Z 0~1；settlementRadius 0.08~0.6；
   riverCount 0~4；lakeCount 0~3；temperature 0~1（0 寒 1 热）；moisture 0~1（0 干 1 湿）；forestBias 0.3~1.4；beachWidth 1~12；erosionStrength 0~0.5。
   允许的建筑大类从 民居/商铺/工坊/公共/生产/水工/军事/宗教 中选。
【输出】
只输出一个 JSON 对象，键名照抄：
{
  "mapName": "地图名（如 青屿镇）",
  "genreProfile": "island",
  "climate": "气候的一句话描述",
  "narrative": "整体地貌的 2~3 句叙述（给策划看，不进渲染）",
  "reliefHeight": 0.30, "elevationMeters": 110,
  "islandRadiusRatio": 0.36, "elongation": 1.14, "rotationDeg": 18,
  "coastRoughness": 0.58, "bays": 3, "coastShelf": 52,
  "reliefStrength": 0.58, "peakCount": 2, "peakHeight": 0.30, "ridgeBearingDeg": 148,
  "plainFraction": 0.55, "plainRadius": 0.34,
  "plainCenterX": 0.52, "plainCenterZ": 0.62,
  "settlementRadius": 0.34,
  "riverCount": 2, "lakeCount": 1,
  "temperature": 0.68, "moisture": 0.60, "forestBias": 0.85, "beachWidth": 3,
  "erosionStrength": 0.18,
  "landmarks": [
    { "name": "老灯塔", "kind": "lighthouse", "sit": "岬角最东端的高处", "note": "当地的精神性地标，灯长年不灭" }
  ],
  "forbidden": ["现代设施", "武器铺", "监牢", "赌坊"],
  "allowedCategories": ["民居", "商铺", "工坊", "公共", "生产", "水工"]
}
