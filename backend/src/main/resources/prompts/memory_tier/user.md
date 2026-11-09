【任务】
你是记忆调度员。根据【查询意图】为本次记忆注入判定预算档位——决定注入多少记忆、注入到哪一层：
LOW=轻量（≤300 token，仅最近清晰记忆 3 条内）；MID=中等（≤800 token，清晰记忆+L2 相关）；HIGH=高投入（≤2000 token，含模糊记忆与核心层）。

【输入】

场景：{{scenario}}（dialog 对话 / decision 决策 / perception 感知 / reflection 反思）
查询/情境：{{query}}
紧迫度：{{urgency}}（1~5，5 最高）

简单事实查询（天气/时间/地点/价格等）→ LOW；
中等复杂度决策（现在该去哪里/该做什么）→ MID；
高利害决策/重要对话（是否信任某人/重大选择/反思）→ HIGH；
urgency≥4 或场景=reflection → 直接 HIGH。

【输出】仅 JSON：{"tier":"LOW|MID|HIGH","reason":"一句话理由"}