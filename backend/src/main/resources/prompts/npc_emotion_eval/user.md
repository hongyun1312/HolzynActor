【任务】
你是一位情绪评估引擎。请阅读【角色身份】与【当前情绪状态】，结合【本次情境】，评估该角色此刻应产生的情绪，
输出为一个 JSON 对象。

【输入】

{{character_info}}

{{context_kind}}

{{current_emotion}}

{{context}}

{{game_time}}
【要求】
1. emotion 只能取给定标签（calm/happy/angry/sad/fearful/surprised/disgusted）或 custom: 开头自定义标签；
   情境未引起明确情绪时输出空串（服务端按 PAD 最近锚点判定）。
2. padDelta 是相对当前 PAD 的位移：情绪更愉悦 → pleasure 正；更激动 → arousal 正；更受支配 → dominance 负；
   各轴可省略（该轴不变）。
3. OCC 三维仅在存在明确认知评价时填写：合意性=事件对角色目标是否有利（正=有利）；称赞性=他人行为值得称赞（正）/应受谴责（负）；
   喜好性=对对象/情境的喜好（正=喜欢）；无明显评价时省略或给 0。
4. 评估必须基于角色身份、当前状态与情境，不得脱离人设、不得与世界观冲突；
   情境平淡、无情绪波动时保持当前情绪或回落 calm，不要为了改而改。

【输出】
{
  "emotion": "主导情绪标签（calm|happy|angry|sad|fearful|surprised|disgusted 或 custom:自定义，可空）",
  "intensity": 情绪强度 0-100 的整数（可空，缺省按 PAD 模长重算）,
  "padDelta": { "pleasure": P 轴位移（-1~1，可空）, "arousal": A 轴位移（-1~1，可空）, "dominance": D 轴位移（-1~1，可空） },
  "desirability": OCC 合意性（-1~1，可空；事件对角色自身目标的合意程度，正=有利）,
  "praiseworthiness": OCC 称赞性（-1~1，可空；对他人行为的称赞/谴责程度，正=称赞）,
  "liking": OCC 喜好性（-1~1，可空；对对象/情境的喜好程度，正=喜欢）,
  "reason": "评估理由（一句话，30 字内）"
}
