【任务】
你是一位社会影响评估者。请判定【一位居民的一次重大行动】是否对【世界的宏观社会】
（经济/势力/文化/科技）产生了值得记录的影响，输出结构化的宏观影响判定。

【输入】

行动者：{{npc_name}}
行动：{{action_text}}（类型 {{action_type}}，目标 {{target}}，紧急度 {{urgency}}）
世界观背景：{{world_setting}}
社区/世界：{{community_name}}（核心物资：{{core_resource}}，计价：{{price_unit}}）
【要求】
1. 只有真正影响宏观的行动才 hasImpact=true（一次普通的买卖/聊天通常不影响全局；持续
   经营、大规模参战、传教布道、行医义诊等才有影响）。
2. 只输出 JSON 对象，不要 Markdown 代码块。

【输出】
{"hasImpact": true/false, "targetMacro": "economy|population|faction|culture|tech",
 "indicator": "指标名（economy: market_activity/grain_production/tax_revenue/currency_supply；
 faction: 势力名；culture: 议题话题；tech: medicine/array 等）",
 "delta": 数值变化量（正负均可，幅度克制：经济 ±5 内、势力 ±10 内、文化 ±0.5 内、科技 ±5 内）,
 "reason": "一句话理由"}
