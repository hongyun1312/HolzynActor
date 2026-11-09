【对话参与者】
{{characters_context}}
【玩家】{{player_name}}
【地点】{{location}}
【已知事实】（STATEMENT 只能引用这里的 fact_key；没有命中就只写“听说”，不要新建事实）
{{known_facts}}
【最近对话】
{{recent_dialog}}
【任务】
1. 为每位参与角色判定关系变化与值得记住的要点（宁缺毋滥）。
2. 抽取本轮明确发生的强声明：PROMISE（承诺/约定/借贷/雇佣）、STATEMENT（对已知事实的陈述）或 GIFT（当场把某件东西递给了对方）。
【输出】
只输出 JSON：
{"relations":[{"character_ref":"npc:1","affinity_delta":0,"trust_delta":0,"stage":"新阶段或省略","note":"备注或省略","state_summary":"跨会话关系摘要（≤80字）","memory_points":["与玩家明确发生的事/共识/承诺"]}],"claims":[{"claim_type":"PROMISE","speaker_ref":"npc:1","subject_ref":"npc:2","fact_key":"可空，服务端会生成","name":"承诺的简短名称","state_text":"承诺内容（一句话）","visibility":"private","due_game_minutes":4320,"confidence":90}]}
约束：
- character_ref / speaker_ref / subject_ref 必须是【对话参与者】里的引用，不要带角色名。
- affinity_delta / trust_delta 为 -20~20 的整数；无变化取 0。
- memory_points 每条 ≤60 字、0~3 条；只写明确发生的。
- STATEMENT 的 fact_key 必须是【已知事实】里的键；PROMISE 的 fact_key 可留空。
- GIFT 只在本轮对话里**真的把东西递过去了**才写：fact_key 填物品名（如「面包」「柴刀」），qty 填件数（默认 1），subject_ref 填收下的那个人；只是说「回头给你」属于 PROMISE，不是 GIFT。
- claims 最多 3 条；没有就输出空数组。
