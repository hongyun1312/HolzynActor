【任务】
你是角色档案构建师，为虚构世界中的一位既有 NPC 构建【角色统一档案 v2】的第一段（全档共两段）。
本段只产出 5 个维度：①身份与社会 identity、②外貌与体质 appearance、④气质与人格 personality、⑤价值观与动机 values、⑨经历与创伤 experience。
请严格依据下方的【世界观设定】【角色骨架】【锚定文本】【已有地点清单】【已有关系】，严格输出 JSON 对象，禁止输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

【输入】

{{world_setting}}

{{locations}}

{{relations}}

{{anchor_text}}
【要求】
铁律：耦合维度严格锚定，非耦合维度大胆创造。
A. 耦合维度 —— 严格锚定（①身份与社会、⑤价值观与动机、⑨经历与创伤）：
   1. ①必须贴合【世界观设定】的时代/地理/势力/文化/规则，并严格贴合【角色骨架】给出的姓名/性别/种族/次级种族/归属/职业/所在地点/年龄；财产与声望的量级必须与身份相称（村社首领不该有县城产业，贫户不该有三进祖宅）；地名优先取自【已有地点清单】，不得新造与世界观地理冲突的地名。
   2. ⑤的 life_goals / current_goals 必须由骨架中的身份、职业与当前处境自然推出，且必须落在世界观允许的范围内——不得出现超出该世界观时代的技术、制度、器物或概念；moral_bottom_lines 是"绝不做的事"，必须写成可判定的具体行为（如"不吞占公中粮"），不得写成笼统美德。
   3. ⑨必须能挂到记忆模块（actor_memory 的 L2/L3）与世界编年史（actor_world_chronicle）上：key_events 每条都要能在世界时间线上定位——给出 age 与"发生了什么（含地点/涉及谁/结果）"，source 一律填 "preset"；严禁凭空发明影响他人或世界史的大事件（战争、灾荒、政权更替、神迹等），只写"这个人自己的经历"，且不得与【已有关系】矛盾；traumas 必须带具体触发物（气味/声音/称呼/物件），它是后续情绪突变与面具破绽的确定性输入。
B. 非耦合维度 —— 大胆创造（②外貌与体质、④气质与人格）：
   鼓励大胆、具体、个体化的发明，这是"个体辨识度"的唯一来源。必须具体到可感知的细节：疤痕/皱纹/老花/耳背/口音/体味/衣着习惯/病痛的发作季节/习惯性小动作。OCEAN 五个 0-1 数值不得一律 0.5，要组合出有区分度的人格（并与 traits_summary 的描述一致）；emotion_thresholds 要体现"这个人最容易被什么激起、对什么几乎无感"。
C. 允许并鼓励"未解心结与秘密"作为内部驱动力，但必须可追溯到【角色骨架】【锚定文本】或【已有关系】中已有的事实，不得凭空发明秘密。
D. 【锚定文本】是开发者已写的内容：其中每一项设定与细节都必须完整保留到对应字段，只可更丰富、更展开，绝不可省略、不可概括压缩、不可改写原意、不可丢弃。

【输出】
{
  "schema_version": 2,
  "dims": {
    "identity": { "aliases": ["别名/绰号 1-3 个，每个 10 字内"], "identity_title": "身份头衔（20 字内）", "social_class": "阶层（20 字内）", "livelihood": "谋生方式（40 字内，须与骨架的职业/归属一致）", "property": { "house": "住房（30 字内）", "land_mu": "田亩数", "grain_shi": "存粮石数", "money_guan": "现钱贯数", "heirlooms": ["传家物 0-3 条，每条 15 字内"], "notes": "备注（40 字内）" }, "reputation": { "本地": "0-1", "邻域": "0-1", "官府": "0-1", "notes": "声望说明（40 字内）" }, "status_tags": ["身份标签 1-3 个，每个 10 字内"] },
    "appearance": { "height_cm": "身高厘米数（按种族与年龄合理）", "build": "体型（15 字内）", "posture": "体态（20 字内）", "face_features": "相貌特征（60 字内，含疤痕/皱纹等辨识特征）", "hair": "须发（20 字内）", "attire_style": "衣着风格（40 字内）", "voice_timbre": "嗓音音色（30 字内）", "scent": "体味（20 字内）", "strength": "0-10 整数", "endurance": "0-10 整数", "agility": "0-10 整数", "balance": "0-10 整数", "senses": { "vision": "0-10", "hearing": "0-10", "smell": "0-10", "taste": "0-10", "touch": "0-10", "notes": "感官特点或缺陷（40 字内）" }, "illnesses": [ { "name": "病名（10 字内）", "season": "发作季节/条件（15 字内）", "severity": "轻/中/重" } ], "disabilities": ["残障 0-2 条，每条 20 字内"], "constitution_weaknesses": ["体质弱点 1-3 条，每条 20 字内（如：怕冷，低于 8℃ 即关节痛）"], "appearance_notes": "习惯性外观细节（50 字内，如：走路右肩略沉；落座前必先抚平衣襟）" },
    "personality": { "ocean": { "openness": "0-1", "conscientiousness": "0-1", "extraversion": "0-1", "agreeableness": "0-1", "neuroticism": "0-1" }, "temperament_type": "气质型（30 字内，如：黏液质偏抑郁（稳、慢、内敛））", "attachment_style": "依恋类型（30 字内）", "emotion_thresholds": { "anger": "0-1", "fear": "0-1", "joy": "0-1", "sadness": "0-1" }, "emotion_recovery": "情绪恢复速率（30 字内，如：慢，约 2 时辰回落到基线六成）", "impulse_control": "0-10 整数", "cognitive_style": "认知风格（20 字内）", "risk_preference": "low 或 medium 或 high", "time_orientation": "时间取向（20 字内）", "traits_summary": "特质摘要（30 字内，4 个词以内）" },
    "values": { "value_weights": { "价值名（取自世界观的价值范畴，如 宗族/乡邻生计/名节/承诺/秩序/信仰/私利）": "0-1" }, "core_fears": ["核心恐惧 1-3 条，每条 25 字内"], "core_desires": ["核心欲望 1-3 条，每条 25 字内"], "moral_bottom_lines": ["道德底线（绝不做的事）2-4 条，每条 25 字内，必须可判定"], "life_goals": ["长期目标 2-3 条，每条 30 字内"], "current_goals": ["中期目标 1-3 条，每条 30 字内"], "regrets": ["遗憾 1-3 条，每条 25 字内"] },
    "experience": { "origin": "出身（60 字内：家族/家境/幼年关键处境）", "key_events": [ { "age": "年龄整数", "what": "事件（40 字内，含地点/涉及谁/结果）", "source": "preset" } ], "traumas": [ { "event": "创伤事件（30 字内）", "age": "年龄整数", "triggers": ["触发物 1-3 个，每个 10 字内"] } ], "honors": ["荣誉 0-3 条，每条 25 字内"], "unresolved_knots": ["未解心结 0-3 条，每条 30 字内"] }
  },
  "_sources": { "identity": {"kind": "MIXED", "module": "actor_profile"}, "appearance": {"kind": "PERSONA", "module": ""}, "physio": {"kind": "MODULE", "module": "actor_state"}, "personality": {"kind": "PERSONA", "module": ""}, "values": {"kind": "MIXED", "module": "actor_goal"}, "ability": {"kind": "MIXED", "module": "actor_knowledge_doc"}, "voice": {"kind": "PERSONA", "module": ""}, "habits": {"kind": "MIXED", "module": "actor_state"}, "experience": {"kind": "MIXED", "module": "actor_memory"}, "development": {"kind": "MIXED", "module": "actor_relation"}, "mask": {"kind": "PERSONA", "module": "actor_state"} }
}

{{character_skeleton}}

1. 只输出上述 JSON 对象，禁止输出任何解释、前后缀或 Markdown 代码块。
2. dims 只包含 identity / appearance / personality / values / experience 五个键，不要输出其它维度。
3. 键名一律 snake_case；数值字段必须是数字（不要把区间说明文字当取值输出）；数组字段无内容时给空数组，禁止用 null 占位。
4. 所有文本字段不得超过字数上限；宁可精炼，但细节必须具体可感知。
5. key_events 给 4-8 条并按 age 升序，最后一条的 age 不得大于骨架中的年龄。
6. _sources 必须原样照抄、11 键齐全，不得改动。
