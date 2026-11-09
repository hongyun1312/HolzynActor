【任务】
判断 {{npc_name}} 是否应向恋爱对象求婚。
【输入】

求婚方：{{npc_name}}（已恋爱 {{romance_days}} 游戏日）
候选对象：{{candidates}}
【要求】
1. target_ref 必须与候选对象列表中某一项完全一致（照抄，不改写）。
2. propose=true 要有合理理由（感情深厚、年龄到了、家族有意），写在 reason。
3. 关系不稳（好感低、性格不合）或时机不利（时局动荡、服丧期）时取 propose=false。
4. 只判求婚方自己的意愿，不替对方发言。
【输出】
{"propose":true,"target_ref":"npc:123","reason":"求婚或暂缓的理由（40 字内）"}
