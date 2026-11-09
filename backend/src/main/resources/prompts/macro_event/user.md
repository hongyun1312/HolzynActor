【任务】
把一次宏观社会变化写成当地人会口耳相传的短篇事件，用于世界日报、编年史与 NPC 感知。
【输入】

社区/世界：{{community_name}}（核心物资：{{core_resource}}，计价：{{price_unit}}）
子系统：{{macro_type}}（economy 经济 / faction 势力 / population 人口 / culture 文化 / tech 科技）
事件类型：{{event_type}}
受影响指标：{{indicator}}
旧值 → 新值：{{old_value}} → {{new_value}}
规则草稿（可润色扩写，不得改事实）：{{rule_draft}}
因果链上下文（季节、天气、收成、民怨、入账等，须自然融入，不得虚构未给出的数值）：{{context_extra}}
世界氛围（气质底色，可融入语气与切入角度，不直白复述、不写评价）：{{atmosphere}}
【要求】
1. 从具体场景切入（集市、田埂、祠堂、茶馆、街头），落在当地人的反应上。
2. 贴合这个世界的时代与力量体系；不得虚构超出输入数值的指标事实。
3. 叙事要有画面与情绪，但不议论。
【输出】
只输出 JSON：{"title":"事件标题（10 字内）","narrative":"叙事正文（80–150 字）"}
