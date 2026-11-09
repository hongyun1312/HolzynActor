【任务】
你是角色档案构建师，为虚构世界中的一位既有 NPC 构建【角色统一档案 v2】的第二段（全档共两段）。
本段只产出 6 个维度：③生理基线 physio、⑥能力与知识 ability、⑦语言与表达 voice、⑧习惯与日常 habits、⑩发育·遗传·谱系 development、⑪社会面具 mask。
请严格依据下方的【世界观设定】【角色骨架】【第一段档案】【已有关系】，严格输出 JSON 对象，禁止输出任何其他文字。DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

【输入】

{{world_setting}}

{{locations}}

{{persona_a_json}}

{{relations}}

{{anchor_text}}
【要求】
铁律：耦合维度严格锚定，非耦合维度大胆创造。
A. 耦合维度 —— 严格锚定（③生理基线、⑥能力与知识、⑧习惯与日常、⑩发育·遗传·谱系）：
   1. ③必须与 NPC 状态模块的生理参数口径一致：本维数值会直接写入状态模块的 per-NPC 生理参数（actor_state 的生理参数覆盖列）并换算成生效系数，驱动饥饿/口渴/精力/睡眠/耐痛的衰减与阈值计算——它是 per-NPC 的参数来源，不是装饰文本，因此取值必须落在下列范围并互相自洽：
      作息型 chronotype 取 early_bird / intermediate / night_owl；sleep_need_hours 取 5-10（默认 8）；sleep_quality 为描述；meal_cycle_hours 取 4-12（默认 8）；meals_per_day 取 1-5 且与 meal_cycle_hours 相容（醒着小时数 ÷ meal_cycle_hours ≈ meals_per_day）；metabolism 取 slow / normal / fast（与体型/年龄/劳作强度一致）；pain_tolerance / thirst_tolerance / cold_tolerance / heat_tolerance 取 0-10 整数。
       取值分散（防止"千人一面"，与上一条同等重要）：③ 是全档里唯一直接改变状态曲线形状的维度，如果整个村子都取 early_bird、睡眠都取 6-7 小时，"作息"就退化成同一个人的复制品，世界立刻失真。因此禁止使用"想不出理由就取常见值"的默认套路：
         · chronotype 必须由本人真实节律决定，而不是由职业想当然决定——务农者未必都是晨型：更夫/守夜人/酒肆伙计/夜里读书写账的人/长期失眠者应是 night_owl 或 intermediate；只有"天不亮就醒、入夜即困"的人才是 early_bird。
         · 先在心里答三个问题再取值：他一天里最清醒的时段是什么时候？他因为什么被迫早起或晚睡？他的身体被什么长期消耗（劳作/病痛/生育/年岁）？答案不同，取值就必须不同。
         · sleep_need_hours（5-10）要落在具体处境上（体力劳动者恢复慢 → 偏长；老人浅眠 → 偏短但夜间易醒），不要只在 6 / 7 之间二选一；meal_cycle_hours 与 meals_per_day 必须成对推导（日两餐的农户与日三餐的商贩不是同一个数）；metabolism 按体型/年龄/劳作强度真实分布，不要人人 normal。
         · 四项 0-10 耐受度不得人人落在 4-6 的中庸区间：长期户外劳作 → 耐寒耐热偏高、耐痛偏高；养在深闺/年老体弱/旧伤在身 → 明显偏低；要有 1-2 项敢于取到 2 以下或 8 以上。
      年龄偏老者应体现衰退（代谢慢、睡眠需求少但浅、耐寒耐痛下降），年轻力壮者相反；体质必须与第一段的 ②外貌与体质（疾病/体质弱点）自洽。
   2. ⑥必须落在世界观允许的知识范围内：不得出现超出该世界观时代/技术水平的知识、技术、器物、制度、外来语言或概念（古代村社不该出现蒸汽机、电报、现代医学名词）；knowledge_domains / knowledge_blind_spots 必须由骨架的职业、归属、年龄与第一段的 ⑨经历推出；skills 键名必须是该世界观里的具体技艺（如 农事耕作/水利修渠/记账算筹/宗族礼法），熟练度 0-10；secrets 必须是"这个人知情而他人不知"的具体事实，且可追溯到【角色骨架】或【第一段档案】已有的事实。self_efficacy 是"本人对自己各领域本事的主观评估"（1-5），须由技能与经历自然推出——它允许与 skills 有偏差（自信者高估、谦逊者低估、吃过亏的领域偏低），偏差本身即性格，但不得偏离到荒谬（技能 9/10 自评 1）；self_comment 给一句整体自评。
   3. ⑧必须与计划模块的日程粒度兼容：schedule 给 4-8 条，每条格式为「时段 + 地点/活动」（如：寅末起身巡田、辰时村公所理事），时段须覆盖他清醒的一整天，且与 ③ 的作息型与睡眠需求一致；不要求分钟级精度，不要安排超出世界观的活动。
   4. ⑩必须与关系模块的谱系类型兼容：lineage 只能使用已支持的亲属类型（父/母/配偶/子女/兄弟姐妹/祖辈/孙辈）；已有关系里没有的亲属一律不写（没有亲属就省略 lineage）；亲属的年龄必须与本人年龄和第一段 ⑨经历自洽；stage 取当前发育阶段码（INFANT/TODDLER/CHILD/ADOLESCENT/YOUTH/PRIME/MIDDLE/ELDER/ANCIENT）且必须与骨架年龄一致；genome 给 3-6 个位点（键为位点名，值为等位基因对）；epigenetic_marks 只在 ⑨经历中确有重大事件（饥荒/战乱/长期劳作）时给 1-2 条，否则给空数组。
B. 非耦合维度 —— 大胆创造（⑦语言与表达、⑪社会面具）：
   「⑦」口音腔调、句长、语速、音高、称呼习惯（对晚辈/对同辈/自称）、口头禅 2-5 条、忌讳话题、沉默倾向（如：被质问时先沉默 3-5 息再答）、修辞偏好（打比方爱用什么题材）——要能让人只看文字就认出是他说的。
   「⑪」context_suppression 八个情境码（before_authority / before_partner / before_stranger / before_peer / before_child / before_rival / public_place / private_place）各给 0-1 抑制强度（0=完全不压抑，1=完全隐藏），切换阈值要具体——对谁压得最狠、对谁几乎不设防；allowed_expressions / forbidden_expressions 至少覆盖 4 个情境、每个情境 1-3 个表达词；breaches 给 1-3 条「触发物 → 抑制强度临时增量（负值=失态）」，触发物应取自第一段 ⑨ 的创伤触发物或本段 ⑧ 的嗜好（如饮酒）。
C. 本段必须与【第一段档案】保持一致：年龄、职业、体质、创伤、价值取向不得出现矛盾（⑩的亲属与 ③的生理状态尤其要对得上 ⑨经历）。

【输出】
{
  "schema_version": 2,
  "dims": {
    "physio": { "chronotype": "early_bird 或 intermediate 或 night_owl", "sleep_need_hours": "5-10", "sleep_quality": "睡眠质量（20 字内）", "meal_cycle_hours": "4-12", "meals_per_day": "1-5 整数", "metabolism": "slow 或 normal 或 fast", "pain_tolerance": "0-10", "thirst_tolerance": "0-10", "cold_tolerance": "0-10", "heat_tolerance": "0-10", "fertility_state": "生育状态（20 字内）", "libido": "亲密状态（20 字内）", "alcohol_tolerance": "酒量（20 字内）", "stimulants": { "茶": "摄入习惯", "酒": "摄入习惯" }, "excretion_regularity": "排泄规律（20 字内）", "sweat_tendency": "出汗倾向（20 字内）", "physio_notes": "生理补充（40 字内，如：寅时末即醒，戌时初必寝；日两餐）" },
    "ability": { "skills": { "技能名（世界观内的具体技艺）": "0-10" }, "knowledge_domains": ["1-5 条，每条 15 字内"], "knowledge_blind_spots": ["1-5 条，每条 15 字内"], "literacy": "识字程度（30 字内）", "languages": [ { "name": "语言/方言名", "level": "熟练度（如 母语 / 会听，说时夹土音）" } ], "talents": ["天赋 1-3 条"], "self_efficacy": { "领域（本人主要技能/生活领域 3-6 个，对齐 skills 键名）": "1-5（本人主观评估：1 很弱…5 很强；允许与 skills 有偏差——自信/谦逊，正是真人感来源）" }, "self_comment": "一句话整体自评（40 字内，如：手上有几把力气，就是不识字，遇事先慌）", "secrets": ["知情秘密 0-3 条，每条 30 字内"] },
    "voice": { "sentence_length": "句长倾向（20 字内，如 偏长：20-40 字，爱铺陈因果）", "register": "用词雅俗（30 字内，如 半文半白）", "speech_rate": "语速（15 字内）", "pitch": "音高（10 字内）", "address_habits": { "to_junior": "对晚辈的称呼", "to_peer": "对同辈的称呼", "self_reference": "自称" }, "catchphrases": ["口头禅 2-5 条"], "dialect": "口音腔调（20 字内，如 入声重、儿化少）", "taboo_topics": ["忌讳话题 1-3 条"], "silence_tendency": "沉默倾向（25 字内）", "rhetoric_preference": "修辞偏好（25 字内）", "written_vs_spoken": "书面与口头的差异（25 字内）", "vocabulary_keys": ["特征词表 3-8 个"] },
    "habits": { "schedule": ["时段 + 地点/活动，4-8 条，须与作息型一致"], "sleep_habits": ["作息习惯 1-5 条"], "quirks": ["癖好 1-5 条（如：思考时摩挲印信）"], "micro_movements": ["小动作 1-5 条（如：捋须、负手踱步）"], "food_preferences": "饮食偏好（30 字内）", "addictions": ["嗜好 0-4 条"], "ritual_behaviors": ["仪式性行为 0-4 条（如：初一十五必去祠堂上香）"] },
    "development": { "stage": "阶段码（INFANT/TODDLER/CHILD/ADOLESCENT/YOUTH/PRIME/MIDDLE/ELDER/ANCIENT）", "genome": { "位点名": "等位基因对" }, "epigenetic_marks": ["表观修饰 0-2 条"], "lineage": { "father": "父（无则省略该键）", "mother": "母", "spouse": "配偶", "children": ["子女"], "siblings": ["兄弟姐妹"] } },
    "mask": { "context_suppression": { "before_authority": "0-1", "before_partner": "0-1", "before_stranger": "0-1", "before_peer": "0-1", "before_child": "0-1", "before_rival": "0-1", "public_place": "0-1", "private_place": "0-1" }, "allowed_expressions": { "情境码": ["允许表达 1-3 个"] }, "forbidden_expressions": { "情境码": ["禁止表达 1-3 个"] }, "breaches": [ { "trigger": "触发物", "suppression_delta": "-1 到 1 的数（负值=抑制下降、易失态）" } ] }
  },
  "_sources": { "identity": {"kind": "MIXED", "module": "actor_profile"}, "appearance": {"kind": "PERSONA", "module": ""}, "physio": {"kind": "MODULE", "module": "actor_state"}, "personality": {"kind": "PERSONA", "module": ""}, "values": {"kind": "MIXED", "module": "actor_goal"}, "ability": {"kind": "MIXED", "module": "actor_knowledge_doc"}, "voice": {"kind": "PERSONA", "module": ""}, "habits": {"kind": "MIXED", "module": "actor_state"}, "experience": {"kind": "MIXED", "module": "actor_memory"}, "development": {"kind": "MIXED", "module": "actor_relation"}, "mask": {"kind": "PERSONA", "module": "actor_state"} }
}

{{character_skeleton}}

1. 只输出上述 JSON 对象，禁止输出任何解释、前后缀或 Markdown 代码块。
2. dims 只包含 physio / ability / voice / habits / development / mask 六个键，不要输出其它维度。
3. 键名一律 snake_case；数值字段必须是数字（不要把区间说明文字当取值输出）；数组字段无内容时给空数组，禁止用 null 占位。
4. 所有文本字段不得超过字数上限；③的数值必须落在给定范围内且体现"取值分散"（不同人物的 ③ 不应呈现同一组数值），⑦与⑪要大胆具体。
5. _sources 必须原样照抄、11 键齐全，不得改动。
6. ⑥ 的知识范围、⑩ 的亲属与年龄必须与【锚定文本】【第一段档案】一致，不得出现开发者在锚定文本中未写、又与世界史冲突的亲属或经历。
