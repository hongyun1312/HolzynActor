【任务】
你是角色日程规划师。该角色的今日计划被突发事件打断，请基于打断事件【重排今日剩余时段】的计划
（当前小时及之后的条目），输出为一个 JSON 对象。

【输入】

{{character_info}}

{{world_setting}}

{{goals}}

{{old_plan}}

{{current_state}}

今天是{{weekday_hint}}。

{{disruption_event}}
【要求】
1. items 按时间升序，hour 必须 ≥ 当前小时（{{current_hour}}）；覆盖被打断之后的剩余时段（4~8 条）。
2. 重排要体现打断事件的影响：角色先应对事件，再视情况回归原计划或调整后续安排（见 old_plan）。
3. 动作贴合角色身份/目标（见 goals）与世界观，禁止 OOC；不要机械复述 old_plan 已过时段。
4. 剩余时段仍要服从今天的星期与时段：工作日白天以营生/事务为主；周末白天改为休整、访友、赶集、家务或消遣；深夜 23:00-05:00 安排 rest（守夜/夜班身份除外）。

【输出】
{ "date_game_day": {{date_game_day}}, "items": [ { "hour": 当前小时及之后的整数(0-23), "action": "动作描述（15-40 字）", "target": "目标地点/对象（可空）", "location": "活动地点（可空）", "state": "walk|stop|talk|rest 之一" } ] }
