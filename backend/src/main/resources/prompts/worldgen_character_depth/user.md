【任务】
你是一位角色塑造师，为下面这个角色生成【深度档案】——决定他/她「为什么是这样的人」「说话是什么样子」，让角色像真实的人一样被驱动、被约束、被认出。

【输入】

1. 深度档案只服务角色本身：写「这个人是谁」，不写剧情、不写命运安排。
2. 四段必须内部自洽：beliefs（信念）驱动 deep（深层）与 voice（声线）；skeleton（骨架）是外部事实。信念与深层不能互相矛盾。
3. 信念越具体越可信：不要「善良/勇敢」式空标签，要「救人之前先算清代价」这种可解释的立场。
4. 声线必须让角色「开口即被认出」：说话方式、口头禅、用词习惯要与身份/性格一致（如村长的官腔、说书人的抑扬顿挫）。
5. 忠于世界观：角色的出身/秘密/欲望不得与世界观设定（势力、禁忌、力量体系）冲突。
6. 严格按下方 JSON Schema 输出，字段不可缺失、不可超出。
【要求】
- skeleton：骨架（外部事实）——name（须与阵容一致）、title、gender、race、age、origin（出身）、background（过往经历概要）、appearance（外貌，供渲染用）。
- beliefs：信念（内部驱动）——core_values[]（核心价值，2~4 条具体立场）、desires[]（欲望，2~3 条）、fears[]（恐惧，2~3 条）、beliefs[]（处世信条，2~4 条）。
- deep：深层（不轻易示人的内核）——secret（秘密）、trauma（创伤）、moral_boundary（道德边界：绝不做/绝对会做）、growth_arc（可能的成长弧线，仅潜台词）。
- voice：声线（语言指纹）——speech_style（说话风格）、catchphrases[]（口头禅，2~3 个）、tone（语气基调）、language_habits（用词习惯：称谓/方言/忌讳词）。

【输出】
{
  "character_depth": {
    "skeleton": {
      "name": "string",
      "title": "string",
      "gender": "string",
      "race": "string",
      "age": "string",
      "origin": "string",
      "background": "string",
      "appearance": "string"
    },
    "beliefs": {
      "core_values": ["string"],
      "desires": ["string"],
      "fears": ["string"],
      "beliefs": ["string"]
    },
    "deep": {
      "secret": "string",
      "trauma": "string",
      "moral_boundary": "string",
      "growth_arc": "string"
    },
    "voice": {
      "speech_style": "string",
      "catchphrases": ["string"],
      "tone": "string",
      "language_habits": "string"
    }
  }
}

- 所有引号必须使用 ASCII 直引号 "（U+0022），禁止弯引号/中文引号。
- 字符串值内不得包含未转义的换行符，如需换行请用 \n。
- 数组/对象最后一个元素后不得有尾随逗号。
- 禁止使用破折号「——」，用逗号或句号断句。

产出语言：{{language}}。所有字段值必须使用 {{language}} 书写。
