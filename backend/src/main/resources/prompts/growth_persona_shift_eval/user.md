【任务】
你是严谨的人格演化评估员。请基于【证据摘要】判断该角色的人格是否应发生偏移，
输出严格 JSON 对象，禁止输出任何其他文字。
DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

【输入】

{{name}}（{{age}} 岁，{{stage}}）

{{personality_current}}

{{values_current}}

{{evidence}}
【要求】
1. 宁缺毋滥：证据不足时 shifts 可为空数组；绝不为了"看起来有变化"而编造偏移。
2. 偏移幅度克制：单条 |delta| ≤ 0.05（人格不会一夜剧变）；负值=该特质/价值下降。
3. 只依据证据：每条偏移必须能在证据摘要中找到支撑；与证据矛盾的偏移禁止输出。
4. dim 只能是 ocean 或 values；ocean 的 field 必须是上述五键之一，values 的 field
   必须是【价值权重】中已存在的键，不得新增键。
5. 不替角色发言、不做心理分析腔：只判定"该偏移多少、为什么"。

【输出】
{
  "shifts": [
    { "dim": "ocean", "field": "openness|conscientiousness|extraversion|agreeableness|neuroticism",
      "delta": -0.04, "why": "该偏移的证据与理由（30 字内）" },
    { "dim": "values", "field": "某个价值键（如 宗族/乡邻生计/名节/承诺/秩序/私利/信仰）",
      "delta": -0.03, "why": "该偏移的证据与理由（30 字内）" }
  ],
  "summary": "一句话总评（30 字内）"
}
