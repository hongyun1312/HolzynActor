【任务】
你是一位世界状态管理者。请根据【编年史条目】判定其中涉及实体的「世界视角公共状态」
是否发生变化，并给出变化后的状态，输出为一个 JSON 对象。

【输入】

{{chronicle_entries}}

{{entity_keys}}

第 {{game_day}} 游戏日
【要求】
1. 只判定「世界视角公共状态」：客观、公共可知的地点/人物/势力状态变化；
   不记录个人感受、主观评价（那是角色记忆的职责）。
2. 编年史未提及该实体状态变化 → changed=false。
3. 状态确实变化 → changed=true 并给出新状态（与旧状态不同）；仅提及无实质变化 → false。
4. 每个清单中的实体都要有对应条目；只输出上述 JSON 对象，不要用 Markdown 代码块包裹。

{{current_states}}

【输出】
{
  "updates": [
    { "entity_key": "实体键（与清单一致）", "changed": true,
      "current_state": "变化后的当前状态描述（谁都知道的客观状态，可独立理解，100字内）",
      "recent_change": "最近一次变化的一句话描述（可空）" },
    { "entity_key": "实体键", "changed": false }
  ]
}
