【任务】
你是「{{world_name}}」世界的居民档案生成器。请基于世界观设定、标准字段数据与地点清单，生成一批符合该世界的普通平民档案（结构列 + 每位居民自己的 11 维【角色统一档案 v2】），输出为一个 JSON 数组。
单元素结构见本节末示例（键名与层级照抄，引号内说明文字须替换成实际取值）：

[
  {
    "name": "姓名（贴合世界观的时代/文化/种族命名习惯，不得与已存在居民重名）",
    "gender": "男 / 女 / 无性",
    "race": "种族（取自标准字段数据一级）",
    "sub_race": "次级种族（取自对应二级）",
    "age": 年龄整数,
    "affiliation": "归属（取自标准字段数据）",
    "location": "当前所在地（取自地点清单或合理补充）",
    "occupation": "职业（取自标准字段数据）",
    "detail": "角色详情（120-200 字：背景/性格/谋生方式/家庭/与世界观的联系）",
    "schema_version": 2,
    "dims": {
      "identity": { "aliases": ["别名/绰号 0-2 个，每个 10 字内"], "identity_title": "身份头衔（20 字内）", "social_class": "阶层（20 字内）", "livelihood": "谋生方式（30 字内，须与结构列的职业/归属一致）", "property": { "house": "住房（20 字内）", "land_mu": "田亩数", "grain_shi": "存粮石数", "money_guan": "现钱贯数", "heirlooms": ["传家物 0-2 条，每条 12 字内"], "notes": "备注（30 字内）" }, "reputation": { "本地": "0-1", "邻域": "0-1", "官府": "0-1", "notes": "声望说明（30 字内）" }, "status_tags": ["身份标签 1-3 个，每个 8 字内"] },
      "appearance": { "height_cm": "身高厘米数（按种族与年龄合理）", "build": "体型（12 字内）", "posture": "体态（15 字内）", "face_features": "相貌特征（40 字内，含疤痕/皱纹等辨识特征）", "hair": "须发（15 字内）", "attire_style": "衣着风格（25 字内）", "voice_timbre": "嗓音音色（15 字内）", "scent": "体味（12 字内）", "strength": "0-10 整数", "endurance": "0-10 整数", "agility": "0-10 整数", "balance": "0-10 整数", "senses": { "vision": "0-10", "hearing": "0-10", "smell": "0-10", "taste": "0-10", "touch": "0-10", "notes": "感官特点或缺陷（25 字内）" }, "illnesses": [ { "name": "病名（8 字内）", "season": "发作季节/条件（12 字内）", "severity": "轻/中/重" } ], "disabilities": [], "constitution_weaknesses": ["体质弱点 0-2 条，每条 15 字内"], "appearance_notes": "习惯性外观细节（30 字内，如：走路右肩略沉）" },
      "physio": { "chronotype": "early_bird 或 intermediate 或 night_owl", "sleep_need_hours": "5-10", "sleep_quality": "睡眠质量（12 字内）", "meal_cycle_hours": "4-12", "meals_per_day": "1-5 整数", "metabolism": "slow 或 normal 或 fast", "pain_tolerance": "0-10", "thirst_tolerance": "0-10", "cold_tolerance": "0-10", "heat_tolerance": "0-10", "fertility_state": "生育状态（12 字内）", "libido": "亲密状态（12 字内）", "alcohol_tolerance": "酒量（12 字内）", "stimulants": { "茶": "摄入习惯（12 字内）", "酒": "摄入习惯（12 字内）" }, "excretion_regularity": "排泄规律（12 字内）", "sweat_tendency": "出汗倾向（12 字内）", "physio_notes": "生理补充（25 字内）" },
      "personality": { "ocean": { "openness": "0-1", "conscientiousness": "0-1", "extraversion": "0-1", "agreeableness": "0-1", "neuroticism": "0-1" }, "temperament_type": "气质型（20 字内）", "attachment_style": "依恋类型（20 字内）", "emotion_thresholds": { "anger": "0-1", "fear": "0-1", "joy": "0-1", "sadness": "0-1" }, "emotion_recovery": "情绪恢复速率（20 字内）", "impulse_control": "0-10 整数", "cognitive_style": "认知风格（15 字内）", "risk_preference": "low 或 medium 或 high", "time_orientation": "时间取向（15 字内）", "traits_summary": "特质摘要（20 字内，4 个词以内）" },
      "values": { "value_weights": { "价值名（取自世界观的价值范畴，如 宗族/乡邻生计/名节/承诺/信仰/私利）": "0-1" }, "core_fears": ["0-2 条，每条 18 字内"], "core_desires": ["0-2 条，每条 18 字内"], "moral_bottom_lines": ["0-2 条，每条 18 字内，必须可判定"], "life_goals": ["1-2 条，每条 22 字内"], "current_goals": ["1-2 条，每条 22 字内"], "regrets": ["0-2 条，每条 18 字内"] },
      "ability": { "skills": { "技能名（世界观内的具体技艺）": "0-10" }, "knowledge_domains": ["1-3 条，每条 12 字内"], "knowledge_blind_spots": ["1-3 条，每条 12 字内"], "literacy": "识字程度（20 字内）", "languages": [ { "name": "语言/方言名", "level": "熟练度（如 母语 / 会听，说时夹土音）" } ], "talents": ["0-2 条，每条 12 字内"], "self_efficacy": { "领域（1-3 个，对齐 skills 键名）": "1-5（本人主观评估，允许与 skills 有偏差）" }, "self_comment": "一句话整体自评（30 字内）", "secrets": ["知情秘密 0-2 条，每条 20 字内"] },
      "voice": { "sentence_length": "句长倾向（15 字内）", "register": "用词雅俗（20 字内）", "speech_rate": "语速（12 字内）", "pitch": "音高（10 字内）", "address_habits": { "to_junior": "对晚辈的称呼", "to_peer": "对同辈的称呼", "self_reference": "自称" }, "catchphrases": ["口头禅 2-4 条，每条 12 字内"], "dialect": "口音腔调（15 字内）", "taboo_topics": ["忌讳话题 0-2 条，每条 10 字内"], "silence_tendency": "沉默倾向（18 字内）", "rhetoric_preference": "修辞偏好（18 字内）", "written_vs_spoken": "书面与口头的差异（18 字内）", "vocabulary_keys": ["特征词表 3-6 个，每个 6 字内"] },
      "habits": { "schedule": ["时段 + 地点/活动，4-8 条，须与作息型一致"], "sleep_habits": ["1-3 条，每条 15 字内"], "quirks": ["癖好 1-3 条，每条 15 字内"], "micro_movements": ["小动作 1-3 条，每条 15 字内"], "food_preferences": "饮食偏好（20 字内）", "addictions": ["嗜好 0-3 条，每条 12 字内"], "ritual_behaviors": ["仪式性行为 0-3 条，每条 15 字内"] },
      "experience": { "origin": "出身（40 字内：家族/家境/幼年关键处境）", "key_events": [ { "age": "年龄整数", "what": "事件（30 字内，含地点/涉及谁/结果）", "source": "preset" } ], "traumas": [ { "event": "创伤事件（20 字内）", "age": "年龄整数", "triggers": ["触发物 1-2 个，每个 8 字内"] } ], "honors": ["0-2 条，每条 15 字内"], "unresolved_knots": ["0-2 条，每条 20 字内"] },
      "development": { "stage": "阶段码（INFANT/TODDLER/CHILD/ADOLESCENT/YOUTH/PRIME/MIDDLE/ELDER/ANCIENT）", "genome": { "位点名": "等位基因对" }, "epigenetic_marks": ["表观修饰 0-2 条"], "lineage": { "father": "父（无则省略该键）", "mother": "母", "spouse": "配偶", "children": ["子女"], "siblings": ["兄弟姐妹"] } },
      "mask": { "context_suppression": { "before_authority": "0-1", "before_partner": "0-1", "before_stranger": "0-1", "before_peer": "0-1", "before_child": "0-1", "before_rival": "0-1", "public_place": "0-1", "private_place": "0-1" }, "allowed_expressions": { "情境码": ["允许表达 1-3 个"] }, "forbidden_expressions": { "情境码": ["禁止表达 1-3 个"] }, "breaches": [ { "trigger": "触发物", "suppression_delta": "-1 到 1 的数（负值=失态）" } ] }
    },
    "_sources": { "identity": {"kind": "MIXED", "module": "actor_profile"}, "appearance": {"kind": "PERSONA", "module": ""}, "physio": {"kind": "MODULE", "module": "actor_state"}, "personality": {"kind": "PERSONA", "module": ""}, "values": {"kind": "MIXED", "module": "actor_goal"}, "ability": {"kind": "MIXED", "module": "actor_knowledge_doc"}, "voice": {"kind": "PERSONA", "module": ""}, "habits": {"kind": "MIXED", "module": "actor_state"}, "experience": {"kind": "MIXED", "module": "actor_memory"}, "development": {"kind": "MIXED", "module": "actor_relation"}, "mask": {"kind": "PERSONA", "module": "actor_state"} }
  }
]

三、输出格式（键名严格照抄；dims 含 11 个键；引号内的区间/说明文字是占位说明，必须替换成你判断的实际取值；数组恰好 {{count}} 个元素）：

【输入】

世界设定：{{world_setting}}
标准字段数据：{{field_dict}}
现有地点清单：{{locations}}
已存在居民名单：{{existing_names}}
【要求】
A. 耦合维度 —— 严格锚定（①身份与社会 identity、③生理基线 physio、⑤价值观与动机 values、⑥能力与知识 ability、⑧习惯与日常 habits、⑨经历与创伤 experience、⑩发育·谱系 development）：
   1. ① 必须贴合【世界观设定】的时代/地理/势力/文化/规则，并严格贴合本居民结构列给出的 种族/次级种族/归属/职业/所在地点/年龄/性别；财产与声望的量级要与身份相称（村社贫户不该有三进祖宅）；地名优先取自【现有地点清单】，不得新造与世界观地理冲突的地名。
   2. ③ 的数值会直接写入 NPC 状态模块作为 per-NPC 生理参数，驱动饥饿/口渴/精力/睡眠/耐痛的衰减与阈值计算（不是装饰文本）：作息型 chronotype 取 early_bird/intermediate/night_owl；sleep_need_hours 取 5-10（默认 8）；meal_cycle_hours 取 4-12（默认 8，须与 meals_per_day 相容）；meals_per_day 取 1-5；metabolism 取 slow/normal/fast；pain_tolerance/thirst_tolerance/cold_tolerance/heat_tolerance 取 0-10 整数。年龄偏老者应体现衰退，年轻力壮者相反。
   3. ⑤ 的 life_goals/current_goals 必须由结构列的职业、归属、年龄与当前处境自然推出，并落在世界观允许的范围内（不得出现超出该世界时代的技术/制度/器物/概念）；moral_bottom_lines 要写成可判定的具体行为（如「不吞占公中粮」），不得写成笼统美德。
   4. ⑥ 必须落在世界观允许的知识范围内——不得出现超出该世界观时代/技术水平的知识、技术、器物、制度、地名或概念；skills 键名必须是该世界观里的具体技艺（如 农事耕作/水利修渠/记账算筹/渔汛辨识），熟练度 0-10；knowledge_domains/knowledge_blind_spots 由职业、归属与年龄推出。
   5. ⑧ 的 schedule 必须与计划模块的日程粒度兼容：给 4-8 条，每条格式为「时段 + 地点/活动」，时段须覆盖他清醒的一整天并与 ③ 的作息型一致。
   6. ⑨ 必须能挂到记忆模块与世界编年史上：key_events 每条都要能在世界时间线上定位（age + 发生了什么含地点/涉及谁/结果，source 一律 "preset"）；严禁凭空发明影响他人或世界史的大事件（战争/灾荒/政权更替/神迹）；traumas 必须带具体触发物。
   7. ⑩ 的谱系只能使用关系模块已支持的亲属类型（父/母/配偶/子女/兄弟姐妹/祖辈/孙辈）；亲属姓名只能引用【已存在居民名单】里的人或本批数组中已出现过的元素，不得凭空发明亲属；无可引用的亲属就省略 lineage；stage 必须与结构列年龄一致。
B. 非耦合维度 —— 大胆创造（②外貌与体质 appearance、④气质与人格 personality、⑦语言与表达 voice、⑪社会面具 mask）：
   鼓励大胆、具体、个体化的发明，这是"个体辨识度"的唯一来源：疤痕/老花/口音/称呼习惯/口头禅/小动作/面具切换阈值都要具体到可感知；OCEAN 五个 0-1 数值不得一律 0.5；面具的 context_suppression 八个情境码（before_authority/before_partner/before_stranger/before_peer/before_child/before_rival/public_place/private_place）各给 0-1 抑制强度，要体现"对谁压得最狠、对谁几乎不设防"。
C. 本批 {{count}} 位居民彼此必须可区分：职业/归属尽量分散（贴合标准字段数据与地点清单的分布），年龄与性别不要整齐划一；禁止"复制粘贴式"的重复人格（OCEAN、口头禅、小动作、面具阈值至少有一处明显不同）。

【输出】
1. 每个元素先给 9 个「结构列」键：name / gender / race / sub_race / age / affiliation / location / occupation / detail。它们是权威数据，会原样写入角色主表列；dims 内的内容必须与它们相容，且不得在 dims 内重复写姓名（姓名权威在表列，不在档案 JSON 内）。
2. 每个元素还必须给该居民自己的【角色统一档案 v2】三个契约键：schema_version（固定 2）、dims（恰好 11 个键）、_sources（11 键照抄，见下方结构）。只有结构列、没有 dims 的扁平输出已废弃：创意发散生成同样必须产出 11 维档案。
3. 键名一律 snake_case；数值字段必须是数字（不要把区间说明文字当取值输出）；数组字段无内容时给空数组，禁止 null 占位。
4. dims 的 11 个键一个都不能少、一个都不能是空壳（空对象/空数组视为残缺）；所有文本字段不得超过括号内标注的字数上限。
5. 本模板一次产出 {{count}} 位居民，输出长度有上限：若篇幅紧张，先保证 JSON 结构完整（数组与 11 个维键闭合），再适度精炼文本；绝不允许中途截断、也不允许省略维度。

1. 每位居民都必须严格符合该世界观：姓名贴合世界观的时代/文化/种族/命名习惯，起名具体、有辨识度，禁止「张伟/小明/翠花」这类敷衍名，禁止凭空捏造与世界观冲突的设定（杜绝 OOC）。
2. race/sub_race 必须从「标准字段数据」中选取且层级对应（如 种族=人族 → 次级种族=汉族）；affiliation/occupation 必须从字段字典中选取。
3. location 优先从「现有地点清单」中选取；确需新地点时必须符合世界观地理设定。
4. 性别只能是「男 / 女 / 无性」三选一：绝大多数种族用 男 或 女；该种族无性别概念或无法区分时（如 植灵妖精、元素生命 等）用「无性」。禁止 雄性/雌性/雄/雌 等其他任何表述，禁止在性别后附加括号或任何补充说明（如「女（…）」「无性（…）」均不允许）。性别不得从姓名臆断，依角色设定合理选择。
5. 年龄与世界观一致、依种族寿命合理设定；姓名不得与「已存在居民名单」重复，同一批内也不得重复。
6. 恰好生成 {{count}} 名居民，全部有效，不要输出空壳或占位；结构列与档案 dims 均不允许省略。
7. 只输出上述 JSON 数组（以 [ 开头、以 ] 结尾），不要用 Markdown 代码块包裹，不要输出任何解释文字。
