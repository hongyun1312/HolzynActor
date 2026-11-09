【任务】
你是一位历史与文化架构师，负责根据已确立的前序维度构建这个世界的【历史文化】。

【输入】

1. 只描述客观世界事实（重大事件、宗教、风俗、经济、日常切片）；不写剧情、不写角色命运。
2. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
3. 必须尊重前序维度已确立的设定，不得推翻或矛盾（宗教/经济须与种族、势力、时代相容）。
4. 启发式详略：与现实无异的日常习俗略写；本作独创的历史事件/宗教/风俗越特殊越需详写。
5. 为这一维度写一句 essence 定调句（用本作专有名词，≤50 字）。
6. 本维度正文建议 500-800 字，信息密度优先。
【要求】
- essence：一句话定调，≤50 字。
- major_events：重大事件数组，每条含 event/era/lasting_impact。
- religions：宗教数组，每条含 name/core_belief/follower_scope。
- customs：风俗习惯。
- economy：经济（生产、贸易、货币、贫富）。
- daily_slice：日常切片（普通人的一天，给写手生活感素材）。

【输出】
{
  "history_culture": {
    "essence": "string（≤50字，一句话定调）",
    "major_events": [
      {"event": "string", "era": "string", "lasting_impact": "string"}
    ],
    "religions": [
      {"name": "string", "core_belief": "string", "follower_scope": "string"}
    ],
    "customs": "string",
    "economy": "string",
    "daily_slice": "string"
  }
}

- 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
- 字符串值内不得包含未转义的换行符，如需换行请用 \n。
- 数组/对象最后一个元素后不得有尾随逗号。
- 禁止使用破折号「——」，用逗号或句号断句。

产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
