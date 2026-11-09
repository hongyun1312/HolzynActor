【任务】
你是一位社会结构架构师，负责根据已确立的前序维度构建这个世界的【社会权力格局】。

【输入】

1. 只描述客观世界事实（种族、阶层、政体、势力与关系、权力可见性）；不写剧情、不写角色命运。
2. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
3. 必须尊重前序维度已确立的设定，不得推翻或矛盾（阶层/政体须与时代、地理相容）。
4. 启发式详略：与现实无异的组织形态略写；本作独创的种族/势力/制度越特殊越需详写。
5. 为这一维度写一句 essence 定调句（用本作专有名词，≤50 字）。
6. 本维度正文建议 500-800 字，信息密度优先。
【要求】
- essence：一句话定调，≤50 字。
- races：种族数组，每条含 name/traits/population/social_status。
- class_structure：阶层结构。
- political_system：政治制度。
- forces：势力数组，每条含 name/type/base_of_power/goals/methods。
- force_relations：势力关系数组，每条含 source/target/relation/tension_point。
- power_visibility：权力在日常生活中的可见程度。

【输出】
{
  "society": {
    "essence": "string（≤50字，一句话定调）",
    "races": [
      {"name": "string", "traits": "string", "population": "string", "social_status": "string"}
    ],
    "class_structure": "string",
    "political_system": "string",
    "forces": [
      {"name": "string", "type": "string", "base_of_power": "string", "goals": "string", "methods": "string"}
    ],
    "force_relations": [
      {"source": "string", "target": "string", "relation": "string", "tension_point": "string"}
    ],
    "power_visibility": "string"
  }
}

- 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
- 字符串值内不得包含未转义的换行符，如需换行请用 \n。
- 数组/对象最后一个元素后不得有尾随逗号。
- 禁止使用破折号「——」，用逗号或句号断句。

产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
