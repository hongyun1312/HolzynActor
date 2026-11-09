【行动者】{{actor_name}}（{{actor_ref}}）
【意图】{{intent}}
【行动】{{action}}
【当前地点】{{location}}
【世界时间】{{game_time}}
【状态】{{state}}
【与目标的关系】{{relation}}
【已知事实】
{{known_facts}}
【物品】{{inventory}}
【交易】{{market}}
【制作】{{craft}}
【装备】{{wear}}
【规则初步结论】{{rule_hint}}

只输出 JSON：
{"verdict":"SUCCESS|PARTIAL|FAIL","reasonCode":"简短原因码","observedText":"旁人能看见的一句话","durationMinutes":0,"effects":[{"op":"relation.delta","body":{},"idempotencyKey":"可空"}]}
