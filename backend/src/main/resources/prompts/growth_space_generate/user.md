【任务】
你是世界运行引擎的【此刻可发生空间】生成器。请基于给定角色信息，推断出该角色【此刻】
（当前游戏时刻）可发生的全部可能状态与行动，输出严格 JSON 对象，禁止输出任何其他文字。
DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

【输入】

{{world_brief}}

{{character_skeleton}}

{{persona_shards}}

{{memories}}

{{current_state}}

{{game_time}}
【要求】
1. possible_actions 是【此刻可发生】的软引导清单：贴近人物性格、处境、记忆与状态；
   likelihood 0-1 表达倾向强度；3-8 条为宜，允许包含"反常但有原因"的行动（真实的人
   偶尔会做此刻不该做的事）。
2. forbidden 是硬边界：必须覆盖【发育阶段约束】中列出的全部禁止/受限项（不得遗漏、
   不得与硬门控冲突）；hard_gate 里的禁止项必须逐条出现在 forbidden 中。
3. 行动与状态必须与角色的发育阶段/年龄/能力匹配：婴幼儿不会耕作，垂暮老人不会
   长途跋涉，未掌握的技艺不会凭空出现（knowledge_blind_spots 是盲区不是技能）。
4. inner 要像真实的人：念头/欲望/恐惧来自性格、近期记忆与当前处境，不要空洞套话。
5. 只输出上述 JSON 对象，不要 Markdown 代码块，不要任何解释文字。

{{hard_gate}}

【输出】
{
  "inner": {
    "dominant_thought": "此刻最可能占据内心的念头（30 字内）",
    "desire": "此刻最想要的东西/状态（30 字内）",
    "fear": "此刻最担心的事（30 字内）",
    "conflict": "内心或处境的主要张力（30 字内）"
  },
  "possible_states": [
    { "state": "可发生的状态（如：坐在门槛上发呆/蹲在田埂上歇脚，15 字内）", "likelihood": 0-1, "trigger": "什么情况下会进入该状态（25 字内）" }
  ],
  "possible_actions": [
    { "action": "可发生的行动（具体到可执行，20 字内）", "likelihood": 0-1, "why": "为什么此刻会这样做（30 字内）" }
  ],
  "forbidden": [
    { "what": "此刻绝不可能做的事（10 字内）", "reason": "原因（20 字内）" }
  ]
}
