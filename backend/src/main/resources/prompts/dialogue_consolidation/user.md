【任务】
从玩家与对谈角色的这段对话里，为每位对谈角色判定关系变化与值得记住的记忆要点。
【输入】

在场角色：{{characters_context}}
玩家：{{player_name}}
最近对话：{{recent_dialog}}
【要求】
1. affinity_delta 与 trust_delta 为 -20~20 的整数，无明显变化取 0；不臆测，不大幅波动。
2. stage 无变化就不填；note 与 state_summary 无内容可省略。
3. memory_points 只写明确发生的、与玩家有关的事、共识与承诺，每条 ≤60 字、1~3 条，宁缺毋滥。
4. 无实质变化时 relations 可以是空数组。
【输出】
只输出 JSON：
{"relations":[{"character_ref":"角色引用","affinity_delta":0,"trust_delta":0,"stage":"新关系阶段或省略","note":"备注或省略","state_summary":"跨会话关系状态摘要（≤80 字）","memory_points":["要点"]}]}
