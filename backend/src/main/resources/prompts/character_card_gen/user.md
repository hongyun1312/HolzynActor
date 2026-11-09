【任务】
你是角色档案构建师，为虚构世界中的一位角色构建【角色统一档案 v2】（11 维）。
（G1a：角色卡旧 Schema 已废弃——不要输出 identity.name / personality / background / speechStyle / knowledge / behaviorPatterns 这类旧结构，统一输出下方 11 维 dims。）
请严格基于给定的【世界观设定】与【角色档案】，严格输出 JSON 对象，禁止输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

【输入】

{{world_setting}}

{{character_input}}
【要求】
A. 耦合维度 —— 严格锚定（①身份与社会 ③生理基线 ⑤价值观与动机 ⑥能力与知识 ⑧习惯与日常 ⑨经历与创伤 ⑩发育·谱系）：
   1. ①必须贴合世界观的时代/地理/势力/文化/规则，并严格贴合【角色档案】给出的姓名/性别/种族/归属/职业/地点/年龄；财产与声望的量级要与身份相称；不得新造与世界观地理冲突的地名。
   2. ③的数值会直接写入 NPC 状态模块作为 per-NPC 生理参数，驱动饥饿/口渴/精力/睡眠/耐痛的衰减与阈值计算（不是装饰文本）：作息型 chronotype 取 early_bird/intermediate/night_owl；sleep_need_hours 取 5-10（默认 8）；meal_cycle_hours 取 4-12（默认 8，须与 meals_per_day 相容）；meals_per_day 取 1-5；metabolism 取 slow/normal/fast；pain_tolerance/thirst_tolerance/cold_tolerance/heat_tolerance 取 0-10 整数。年龄偏老者应体现衰退，年轻力壮者相反。
   3. ⑤的 life_goals/current_goals 必须由身份、职业与当前处境自然推出；moral_bottom_lines 要写成可判定的具体行为，不得写成笼统美德。
   4. ⑥必须落在世界观允许的知识范围内——不得出现超出该世界观时代/技术水平的知识、技术、器物、制度、地名或概念；skills 键名必须是该世界观里的具体技艺（如 农事耕作/水利修渠/记账算筹），熟练度 0-10。
   5. ⑧的 schedule 必须与计划模块的日程粒度兼容：给 4-8 条，每条格式为「时段 + 地点/活动」，时段须覆盖他清醒的一整天并与 ③ 的作息型一致。
   6. ⑨必须能挂到记忆模块与世界编年史上：key_events 每条都要能在世界时间线上定位（age + 发生了什么含地点/涉及谁/结果，source 一律 "preset"）；严禁凭空发明影响他人或世界史的大事件；traumas 必须带具体触发物。
   7. ⑩的谱系只能使用关系模块已支持的亲属类型（父/母/配偶/子女/兄弟姐妹/祖辈/孙辈），已有关系里没有的亲属一律不写；子女年龄必须小于本人年龄且与 ⑨ 的经历自洽。
B. 非耦合维度 —— 大胆创造（②外貌与体质 ④气质与人格 ⑦语言与表达 ⑪社会面具）：
   鼓励大胆、具体、个体化的发明，这是"个体辨识度"的唯一来源：疤痕/老花/口音/称呼习惯/口头禅/小动作/面具切换阈值都要具体到可感知；OCEAN 五个 0-1 数值不得一律 0.5；面具的 context_suppression 八个情境码（before_authority/before_partner/before_stranger/before_peer/before_child/before_rival/public_place/private_place）各给 0-1 抑制强度，要体现"对谁压得最狠、对谁几乎不设防"。
C. 允许并鼓励"未解心结与秘密"作为内部驱动力，但必须可追溯到【角色档案】或世界观中已有的事实。

1. 11 个维度都要有实质内容（不允许空对象/空数组的空壳维）；文本字段不得超过字数上限。
2. 完整保留铁律：角色档案中的【每一项设定与细节都必须完整保留】到对应字段——身份/外貌/性格/能力/语言风格/语录/目标/心结/习惯/经历一个都不能少；只可多不可少：内容可以比原信息更丰富、更展开，但【绝不可省略、不可概括压缩、不可改写原意、不可丢弃】；信息量大时优先增加输出长度来完整承载。
3. 所有内容必须与世界观一致，禁止出现与世界观/角色档案冲突的设定（杜绝 OOC）。
4. 键名一律 snake_case；数值字段必须是数字（不要把区间说明文字当取值输出）；无内容的数组给空数组，禁止 null 占位。
5. _sources 必须原样照抄、11 键齐全，不得改动。
6. 只输出上述 JSON 对象，不要用 Markdown 代码块包裹，不要输出任何解释文字。

【输出】
{
  "schema_version": 2,
  "dims": {
    "identity": { "aliases": ["别名/绰号 1-3 个，每个 10 字内"], "identity_title": "身份头衔（20 字内）", "social_class": "阶层（20 字内）", "livelihood": "谋生方式（40 字内）", "property": { "house": "住房（30 字内）", "land_mu": "田亩数", "grain_shi": "存粮石数", "money_guan": "现钱贯数", "heirlooms": ["传家物 0-3 条"], "notes": "备注（40 字内）" }, "reputation": { "本地": "0-1", "邻域": "0-1", "官府": "0-1", "notes": "声望说明（40 字内）" }, "status_tags": ["身份标签 1-3 个"] },
    "appearance": { "height_cm": "身高厘米数", "build": "体型（15 字内）", "posture": "体态（20 字内）", "face_features": "相貌特征（60 字内，含疤痕等辨识特征）", "hair": "须发（20 字内）", "attire_style": "衣着风格（40 字内）", "voice_timbre": "嗓音音色（30 字内）", "scent": "体味（20 字内）", "strength": "0-10 整数", "endurance": "0-10 整数", "agility": "0-10 整数", "balance": "0-10 整数", "senses": { "vision": "0-10", "hearing": "0-10", "smell": "0-10", "taste": "0-10", "touch": "0-10", "notes": "感官特点或缺陷（40 字内）" }, "illnesses": [ { "name": "病名", "season": "发作季节/条件", "severity": "轻/中/重" } ], "disabilities": [], "constitution_weaknesses": ["体质弱点 1-3 条（如：怕冷，低于 8℃ 即关节痛）"], "appearance_notes": "习惯性外观细节（50 字内）" },
    "physio": { "chronotype": "early_bird 或 intermediate 或 night_owl", "sleep_need_hours": "5-10", "sleep_quality": "睡眠质量（20 字内）", "meal_cycle_hours": "4-12", "meals_per_day": "1-5 整数", "metabolism": "slow 或 normal 或 fast", "pain_tolerance": "0-10", "thirst_tolerance": "0-10", "cold_tolerance": "0-10", "heat_tolerance": "0-10", "fertility_state": "生育状态（20 字内）", "libido": "亲密状态（20 字内）", "alcohol_tolerance": "酒量（20 字内）", "stimulants": { "茶": "摄入习惯", "酒": "摄入习惯" }, "excretion_regularity": "排泄规律（20 字内）", "sweat_tendency": "出汗倾向（20 字内）", "physio_notes": "生理补充（40 字内）" },
    "personality": { "ocean": { "openness": "0-1", "conscientiousness": "0-1", "extraversion": "0-1", "agreeableness": "0-1", "neuroticism": "0-1" }, "temperament_type": "气质型（30 字内）", "attachment_style": "依恋类型（30 字内）", "emotion_thresholds": { "anger": "0-1", "fear": "0-1", "joy": "0-1", "sadness": "0-1" }, "emotion_recovery": "情绪恢复速率（30 字内）", "impulse_control": "0-10 整数", "cognitive_style": "认知风格（20 字内）", "risk_preference": "low 或 medium 或 high", "time_orientation": "时间取向（20 字内）", "traits_summary": "特质摘要（30 字内）" },
    "values": { "value_weights": { "价值名（取自世界观的价值范畴）": "0-1" }, "core_fears": ["1-3 条，每条 25 字内"], "core_desires": ["1-3 条，每条 25 字内"], "moral_bottom_lines": ["2-4 条，每条 25 字内，必须可判定"], "life_goals": ["2-3 条，每条 30 字内"], "current_goals": ["1-3 条，每条 30 字内"], "regrets": ["1-3 条，每条 25 字内"] },
    "ability": { "skills": { "技能名": "0-10" }, "knowledge_domains": ["1-5 条，每条 15 字内"], "knowledge_blind_spots": ["1-5 条，每条 15 字内"], "literacy": "识字程度（30 字内）", "languages": [ { "name": "语言/方言名", "level": "熟练度" } ], "talents": ["天赋 1-3 条"], "self_efficacy": { "领域（本人主要技能/生活领域 3-6 个）": "1-5（本人主观评估：1 很弱…5 很强；允许与 skills 有偏差——自信/谦逊）" }, "self_comment": "一句话整体自评（40 字内，如：手上有几把力气，就是不识字，遇事先慌）", "secrets": ["知情秘密 0-3 条，每条 30 字内"] },
    "voice": { "sentence_length": "句长倾向（20 字内）", "register": "用词雅俗（30 字内）", "speech_rate": "语速（15 字内）", "pitch": "音高（10 字内）", "address_habits": { "to_junior": "对晚辈的称呼", "to_peer": "对同辈的称呼", "self_reference": "自称" }, "catchphrases": ["口头禅 2-5 条"], "dialect": "口音腔调（20 字内）", "taboo_topics": ["忌讳话题 1-3 条"], "silence_tendency": "沉默倾向（25 字内）", "rhetoric_preference": "修辞偏好（25 字内）", "written_vs_spoken": "书面与口头的差异（25 字内）", "vocabulary_keys": ["特征词表 3-8 个"] },
    "habits": { "schedule": ["时段 + 地点/活动 4-8 条"], "sleep_habits": ["1-5 条"], "quirks": ["癖好 1-5 条"], "micro_movements": ["小动作 1-5 条"], "food_preferences": "饮食偏好（30 字内）", "addictions": ["嗜好 0-4 条"], "ritual_behaviors": ["仪式性行为 0-4 条"] },
    "experience": { "origin": "出身（60 字内）", "key_events": [ { "age": "年龄整数", "what": "事件（40 字内，含地点/涉及谁/结果）", "source": "preset" } ], "traumas": [ { "event": "创伤事件（30 字内）", "age": "年龄整数", "triggers": ["触发物 1-3 个"] } ], "honors": ["0-3 条"], "unresolved_knots": ["0-3 条，每条 30 字内"] },
    "development": { "stage": "阶段码（INFANT/TODDLER/CHILD/ADOLESCENT/YOUTH/PRIME/MIDDLE/ELDER/ANCIENT）", "genome": { "位点名": "等位基因对" }, "epigenetic_marks": ["表观修饰 0-2 条"], "lineage": { "father": "父（无则省略该键）", "mother": "母", "spouse": "配偶", "children": ["子女"], "siblings": ["兄弟姐妹"] } },
    "mask": { "context_suppression": { "before_authority": "0-1", "before_partner": "0-1", "before_stranger": "0-1", "before_peer": "0-1", "before_child": "0-1", "before_rival": "0-1", "public_place": "0-1", "private_place": "0-1" }, "allowed_expressions": { "情境码": ["允许表达 1-3 个"] }, "forbidden_expressions": { "情境码": ["禁止表达 1-3 个"] }, "breaches": [ { "trigger": "触发物", "suppression_delta": "-1 到 1 的数（负值=失态）" } ] }
  },
  "_sources": { "identity": {"kind": "MIXED", "module": "actor_profile"}, "appearance": {"kind": "PERSONA", "module": ""}, "physio": {"kind": "MODULE", "module": "actor_state"}, "personality": {"kind": "PERSONA", "module": ""}, "values": {"kind": "MIXED", "module": "actor_goal"}, "ability": {"kind": "MIXED", "module": "actor_knowledge_doc"}, "voice": {"kind": "PERSONA", "module": ""}, "habits": {"kind": "MIXED", "module": "actor_state"}, "experience": {"kind": "MIXED", "module": "actor_memory"}, "development": {"kind": "MIXED", "module": "actor_relation"}, "mask": {"kind": "PERSONA", "module": "actor_state"} }
}
