【任务】
你是一位世界底层规则架构师，负责为这个虚构世界定义【不可违背的核心法则】。

【输入】

1. 只描述客观世界事实（力量体系、公理、禁忌、显化方式）；不写剧情、不写角色命运、不写叙事建议。
2. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
3. 启发式详略：与现实世界无异的常识（物理规律、衣食住行）略写；本作独创/改编/偏离常识越大的设定详写清楚，便于后续使用。
4. 内部自洽：公理之间不矛盾，禁忌红线应是公理的直接推论。
5. 为这一维度写一句 essence 定调句（用本作专有名词，≤50 字，具体不空泛）。
6. 本维度正文建议 500-800 字，信息密度优先。
【要求】
- essence：一句话定调，≤50 字，用本作专有名词。
- power_system：力量或技术的来源、等级划分、使用代价、边界限制。
- axioms：不可违背的底层规则数组，每条必须含 name/statement/cost/boundary/enforcement。
- taboos：绝对禁区数组，违反即遭受世界级惩罚的事。
- power_manifestation：力量施展时的感官反应，给写手可用的描写素材。

【输出】
{
  "core_laws": {
    "essence": "string（≤50字，一句话定调）",
    "power_system": "string",
    "axioms": [
      {"name": "string", "statement": "string", "cost": "string", "boundary": "string", "enforcement": "string"}
    ],
    "taboos": ["string"],
    "power_manifestation": "string"
  }
}

- 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
- 字符串值内不得包含未转义的换行符，如需换行请用 \n。
- 数组/对象最后一个元素后不得有尾随逗号。
- 禁止使用破折号「——」，用逗号或句号断句。

产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
