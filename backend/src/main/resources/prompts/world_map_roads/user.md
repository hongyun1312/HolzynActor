【任务】
你是村镇道路规划师。请依据地理与聚落性格，设计这片土地的<b>道路拓扑</b>——
只说明「哪些地方必须连通、这条路是什么性格」，<b>不要给坐标</b>：
具体走线会由最小代价寻路在真实地形上求出（会自动绕陡坡、跨河架桥、避开房屋）。

【输入】

{{world_setting}}

{{profile_summary}}

{{asset_summary}}

{{landmarks}}

core（中心枢纽）/ gate（对外门户/码头/渡口）/ waterfront（临水）/ residential（民居群）/
workshop（工坊区）/ farm（生产区）/ landmark（地标）
【要求】
1. 一个核心：镇中心（广场/井台/大树下）是全图的枢纽，至少 3 条路在此交汇。
2. 门与水的连接：通往码头/渡口/村口的「门户路」必须是主干（level=major，宽 4~6 米）。
3. 主街贯穿：一条主街从门户串到核心区，商铺沿它两侧分布。
4. 支路成网：民居群用巷道（level=minor，宽 2~3 米）连主街，不要每户单独接主干
   （那会在图上形成梳齿状的假路网）。
5. 生产性道路：通往农田/林地/盐田的路是土路（surface=dirt），宽度 2~3 米，
   数量少而长，从聚落边缘放射出去。
6. 跨水必须成桥：若路要过河，kind 给 bridge（后续会自动在该处生成桥）。
7. 不要生成「环岛」「立交」「宽阔大道」这类超出技术代差的东西。
8. 路的总条数 8~18 条：主干 2~3 条，巷道 5~12 条，生产路 2~4 条。

【输出】
settlement-center / coast / river / settlement-edge / high-ground / farmland / forest

输出严格 JSON 对象（键名严格照抄，禁止输出任何其他文字）：
{
  "nodes": [
    { "code": "plaza_center", "name": "镇中心小广场", "role": "core",
      "prefer": "settlement-center", "note": "一口老井，镇上人都在这里碰面" },
    { "code": "harbor_gate", "name": "码头口", "role": "gate",
      "prefer": "coast", "note": "渔船靠岸、货物上岸的地方" }
  ],
  "roads": [
    { "code": "main_street", "name": "正街", "kind": "street", "level": "major",
      "width": 5.0, "surface": "stone", "from": "harbor_gate", "to": "plaza_center",
      "bend": 0.2, "priority": 10 }
  ]
}
kind 只能取 street / lane / path / bridge / stairs / pier；surface 取 stone / dirt / wood / gravel。
bend 是「弯曲倾向」0~1（0 笔直，1 随地形大绕）。priority 1~10（越大越优先保证走通）。
铁律：不给坐标；from/to 必须是已定义节点；node code 全局唯一。
DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）
