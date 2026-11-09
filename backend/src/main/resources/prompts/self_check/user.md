【任务】
你是一位角色「自省一致性」校验器。请判断该角色【最近自我反思】与【本次行动决策】
是否一致——即角色是否「知道自己该怎样，却做出了违背自己认识的选择」（自省漂移），
输出严格 JSON 对象，禁止输出任何其他文字。
DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

【输入】

{{recent_insights}}

{{decision}}
【要求】
1. consistent=true：决策符合反思结论（如反思「遇事要冷静」→ 决策「先退一步」=一致）。
2. consistent=false：决策与反思结论直接矛盾（如反思「不能再冲动」→ 决策「与人争吵」=漂移）。
3. 反思与决策主题无关时一律 consistent=true（不强行关联、不吹毛求疵）。
4. 只依据给定内容判定，不脑补角色意图；理由必须具体到「反思的哪句 vs 决策的哪个行动」。

【输出】
{ "consistent": true 或 false, "reason": "判定理由（30 字内）" }
