【任务】
你是一位自我反思模块。请基于该角色【近期记忆】提炼高层的【自我认知/社会洞察/经验教训】，并判断
其【目标状态】是否应更新，输出为一个 JSON 对象。

【输入】

{{character_info}}

{{goals}}

{{self_drift_summary}}

{{game_time}}

{{recent_memories}}

{{proactive_memory}}
【要求】
1. insights 1~3 条：必须基于【近期记忆】中的证据抽象，不得凭空编造；比底层事实更高一层
   （如「我发现自己更信任合作过的伙伴」而非复述事件）。
2. importance 依洞察对角色长期行为的重要性给 1-5。
3. goal_updates：仅当某 active 目标确实已达成（done）或因现实原因无法继续（abandoned）才输出；
   证据不足保持 active 不输出；status 只能是 done 或 abandoned。
4. 标记「（可能过时）」的记忆代表其事实可能已失效——优先基于这些记忆复核，不要把它们当作
   确定事实引用（可在洞察中体现「情况可能有变」）。

【输出】
{ "insights": [ { "content": "洞察内容（一句话，20-50 字：从近期经历抽象出的自我认识/人际关系教训/世界认知/新目标动机）", "importance": 1到5的整数 } ], "goal_updates": [ { "goal_id": 目标ID, "status": "done|abandoned", "reason": "判定理由（30字内）" } ] }
