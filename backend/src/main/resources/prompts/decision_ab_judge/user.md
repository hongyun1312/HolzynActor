【任务】
请对下面这条【决策模型 判定样本】按四维 rubric 逐维独立评分：每维先给出证据（引用样本中的具体内容），
再给 1-5 分；最后给出总分与一句话总评。

【输入】

{{state_text}}
【要求】
① consistency 判定与角色一致性（OOC）：1=判定明显违背角色性格/身份/立场；3=基本符合但有轻微偏离；5=完全符合其性格、处境与说话方式。
② reasonability 判定合理性：1=判定动机与逻辑不通/不可信；3=合理但权衡不足；5=判据运用得当、结论与情境自洽。
③ context_usage 情境利用：1=完全无视给出的情境；3=部分利用情境；5=充分利用情境中的状态/在场者/地点/事件信息（情境未给足信息时给 3 中性分）。
④ naturalness 判定自然度：1=机械/模板化/不像真人会做的选择；3=自然但略有套路；5=像真实的人在那一刻会有的判断。

1. 绝对评分：禁止与其他样本比较；禁止受文本长度影响（长不一定好，短不一定差）。
2. 逐维先评：先写该维证据，再给该维分数（chain-of-thought rubric）。
3. 分数必须是 1-5 的整数，严格按锚点描述对号入座，不要一律给 3。

角色：{{npc_name}}（{{npc_ref}}）｜场景：{{scene}}｜题型：{{question_type}}

{{question}}

【输出】
{
  "scores": { "consistency": 1-5, "reasonability": 1-5, "context_usage": 1-5, "naturalness": 1-5 },
  "reasons": { "consistency": "证据...", "reasonability": "证据...", "context_usage": "证据...", "naturalness": "证据..." },
  "summary": "一句话总评（30 字内）"
}

{{decision_answer}}
