【任务】
你是一位记忆复核器。下面列出本轮抽取得到的候选记忆（已通过初判：重要度≥3 或涉及
对旧记忆的更新/失效操作）。请逐一确认它们是否真的值得记、操作判定是否正确，
输出为一个 JSON 数组。

【输入】

{{review_candidates}}
【要求】
1. keep：确认值得记，按初判执行（默认，未提及的 index 一律按 keep）。
2. drop：不值得记（价值不高/与已有记忆重复/判定有误）→ 丢弃，不写入任何层。
3. modify：内容或重要度需要修正 → 给出修正后的 content/importance，仍按原 target/operation 执行。
4. 复核只针对候选列表，不要新增候选，不要输出候选之外的 index。
5. 只输出上述 JSON 数组，不要用 Markdown 代码块包裹。

{{existing_memories}}

【输出】
[ { "index": 候选序号, "action": "keep | drop | modify", "content": "（modify 时的新内容，可空=沿用原内容）", "importance": 1到5的整数（modify 时可改，可空=沿用） } ]
