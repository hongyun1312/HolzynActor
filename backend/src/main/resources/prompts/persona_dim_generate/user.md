你是角色档案构建师。现在为一位既有 NPC 逐维构建【角色统一档案 v2】：本次调用只生成 1 个维度，其余维度在后续调用中逐个生成。只输出本次要求的那一维 JSON，不得输出其它维度、不得输出 _sources 或 schema_version、不得输出任何解释文字。

—— 一、世界观设定（全部内容的锚点，任何取值都不得与之冲突） ——
{{world_setting}}

—— 二、11 维通用生成要求 / 格式约束 / 示例（逐维调用时完全一致，务必通读后再落笔） ——
铁律：耦合维度严格锚定，非耦合维度大胆创造。
· 耦合维（①身份与社会、⑤价值观与动机、⑥能力与知识、⑧习惯与日常、⑨经历与创伤、⑩发育·遗传·谱系）：必须与世界观的时代/地理/势力/文化/技术水平一致，并与【角色基础原始信息】中的姓名/性别/年龄/归属/职业/地点严格相容；财产与声望的量级必须与身份相称；地名优先取自已给地点清单，不得新造与地理冲突的地名；本科目盲区要由职业与处境自然推出。
· 非耦合维（②外貌与体质、③生理基线、④气质与人格、⑦语言与表达、⑪社会面具）：大胆创造，但必须具体到可感知、可复述的细节（疤痕位置、口音与口头禅、习惯性小动作、病痛发作季节），不得写成通用形容词；这些维度是"这个人区别于同镇其他人的唯一证据"。
· 一致性：不得与【已生成的前序维度】矛盾——年龄、体质、疾病、职业、创伤、亲属关系必须能对上；前序维度已经写死的细节，本维只能兼容、不能改写。
· 禁止：空泛套话与排比；与同镇其他人雷同的取值；超出该时代的技术/器物/制度/医学名词；把占位说明文字当取值输出；输出本维之外的内容。

输出契约：严格输出 {"dims": {"<本维键名>": { ... }}}，只含本维一个键；不加 Markdown 代码块、不加前后缀。数值字段必须输出数字（0-1 输出小数，0-10 输出整数）；数组无内容给 []；不得用 null 占位；键名一律 snake_case 并照抄下方契约。

① identity 身份与社会（耦合）
字段：aliases[]（别名/绰号 1-3 个，每个 10 字内）、identity_title（身份头衔，20 字内）、social_class（阶层，20 字内）、livelihood（谋生方式，40 字内，须与骨架职业/归属一致）、property{house（住房，30 字内）、land_mu（田亩数，数字）、grain_shi（存粮石数，数字）、money_guan（现钱贯数，数字）、heirlooms[]（传家物 0-3 条，每条 15 字内）、notes（备注，40 字内）}、reputation{本地:0-1、邻域:0-1、官府:0-1、notes（40 字内）}、status_tags[]（身份标签 1-3 个，每个 10 字内）。
要求：财产与声望量级须与身份相称；地名取自地点清单；不得出现与骨架职业矛盾的营生。
示例：{"dims":{"identity":{"aliases":["陈豆腐"],"identity_title":"陈记豆腐坊东家","social_class":"镇上手艺人","livelihood":"经营豆腐坊，兼给镇小学食堂送豆腐","property":{"house":"老街两间门面带后院","land_mu":0,"grain_shi":2,"money_guan":0,"heirlooms":["祖传石磨"],"notes":"四更起身磨豆"},"reputation":{"本地":0.7,"邻域":0.4,"官府":0.2,"notes":"分量足、不掺假"},"status_tags":["老街商户","手艺人"]}}}

② appearance 外貌与体质（非耦合）
字段：height_cm（数字）、build（体型，15 字内）、posture（体态，20 字内）、face_features（相貌特征，60 字内，含疤痕/皱纹等辨识特征）、hair（须发，20 字内）、attire_style（衣着风格，40 字内）、voice_timbre（嗓音音色，30 字内）、scent（体味，20 字内）、strength/endurance/agility/balance（0-10 整数）、senses{vision/hearing/smell/taste/touch 各 0-10、notes（40 字内）}、illnesses[{name（10 字内）、season（发作季节或条件，15 字内）、severity（轻/中/重）}]、disabilities[]（残障 0-2 条，每条 20 字内）、constitution_weaknesses[]（体质弱点 1-3 条，每条 20 字内，必须可判定，如"低于 8℃ 即关节痛"）、appearance_notes（习惯性外观细节，50 字内）。
要求：至少两处细节能让第三人在人群里认出他；体质要与年龄、劳作强度、③生理基线自洽。
示例：{"dims":{"appearance":{"height_cm":168,"build":"精瘦结实","posture":"常年弯腰推磨，右肩略沉","face_features":"左眉尾有豆粒大烫疤，眼角细纹密","hair":"花白短发","attire_style":"洗得发白的蓝布衫，袖口卷到手肘","voice_timbre":"沙哑偏低","scent":"豆腥味混柴火气","strength":7,"endurance":8,"agility":5,"balance":6,"senses":{"vision":6,"hearing":7,"smell":8,"taste":7,"touch":8,"notes":"闻豆浆生熟极准"},"illnesses":[{"name":"老寒腿","season":"梅雨与深冬","severity":"中"}],"disabilities":[],"constitution_weaknesses":["久站后腰僵，需捶腰缓一缓"],"appearance_notes":"落座前先抚平围裙"}}}

③ physio 生理基线（耦合；数值会直接写入状态模块并驱动饥饿/口渴/精力/睡眠/耐痛的衰减与阈值）
字段：chronotype（early_bird/intermediate/night_owl）、sleep_need_hours（5-10）、sleep_quality（文字）、meal_cycle_hours（4-12）、meals_per_day（1-5，须与前一字段相容：醒着小时数除以周期约等于每日餐数）、metabolism（slow/normal/fast）、pain_tolerance/thirst_tolerance/cold_tolerance/heat_tolerance（0-10 整数）、fertility_state（文字）、libido（文字）、alcohol_tolerance（文字）、stimulants{茶/烟/酒等摄入习惯}、excretion_regularity（文字）、sweat_tendency（文字）、physio_notes（60 字内）。
要求：取值必须分散——作息由本人真实节律决定，不能由职业想当然（更夫、夜里读书写账的人不是早鸟）；那四项耐受度至少 1-2 项敢于取到 2 以下或 8 以上；年龄偏老者体现衰退（代谢慢、睡少但浅、耐寒耐痛下降）。
示例：{"dims":{"physio":{"chronotype":"night_owl","sleep_need_hours":6.5,"sleep_quality":"浅眠，夜里常醒两次","meal_cycle_hours":5,"meals_per_day":3,"metabolism":"slow","pain_tolerance":7,"thirst_tolerance":5,"cold_tolerance":3,"heat_tolerance":8,"fertility_state":"三十岁那年伤了身子，此后再无生育","libido":"低","alcohol_tolerance":"半两即上脸","stimulants":{"tea":"清晨浓茶一缸"},"excretion_regularity":"规律，卯时","sweat_tendency":"动则汗湿后背","physio_notes":"右膝旧伤遇冷发僵"}}}

④ personality 气质与人格（非耦合）
字段：ocean{openness/conscientiousness/extraversion/agreeableness/neuroticism 各 0-1}、temperament_type（气质型，30 字内）、attachment_style（依恋类型，30 字内）、emotion_thresholds{anger/fear/joy/sadness 各 0-1}、emotion_recovery（情绪恢复速率，30 字内）、impulse_control（0-10 整数）、cognitive_style（认知风格，20 字内）、risk_preference（low/medium/high）、time_orientation（时间取向，20 字内）、traits_summary（特质摘要，30 字内，4 个词以内）。
要求：五项 OCEAN 不得一律 0.5，要组合出有区分度的人格，并与 traits_summary 的描述自洽；阈值要体现"最容易为什么被激起、对什么几乎无感"。
示例：{"dims":{"personality":{"ocean":{"openness":0.35,"conscientiousness":0.85,"extraversion":0.3,"agreeableness":0.6,"neuroticism":0.55},"temperament_type":"黏液质偏抑郁（稳、慢、内敛）","attachment_style":"回避型，亲近时先退半步","emotion_thresholds":{"anger":0.7,"fear":0.4,"joy":0.5,"sadness":0.45},"emotion_recovery":"慢，约两个时辰回落到基线六成","impulse_control":8,"cognitive_style":"经验型，先看后动","risk_preference":"low","time_orientation":"过去取向","traits_summary":"寡言、守时、认死理"}}}

⑤ values 价值观与动机（耦合）
字段：value_weights{价值名:0-1}（价值名取自世界观的价值范畴，如 宗族/乡邻生计/名节/承诺/秩序/信仰/私利）、core_fears[]（1-3 条，每条 25 字内）、core_desires[]（1-3 条，每条 25 字内）、moral_bottom_lines[]（绝不做的事 2-4 条，每条 25 字内，必须可判定，如"不吞占公中粮"）、life_goals[]（长期目标 2-3 条，每条 30 字内）、current_goals[]（中期目标 1-3 条，每条 30 字内）、regrets[]（遗憾 1-3 条，每条 25 字内）。
要求：目标必须由骨架身份与当前处境自然推出，落在世界观允许的范围内；底线是"可以被事件触发判定"的具体行为，不是笼统美德。
示例：{"dims":{"values":{"value_weights":{"手艺名声":0.9,"乡邻情面":0.7,"私利":0.3},"core_fears":["豆腐变酸砸了招牌"],"core_desires":["把石磨传给儿子"],"moral_bottom_lines":["不掺陈豆充新豆","不短秤"],"life_goals":["攒钱翻修豆腐坊屋顶"],"current_goals":["入冬前添一头驴"],"regrets":["没让闺女念完初中"]}}}

⑥ ability 能力与知识（耦合）
字段：skills{技能名:0-10}（技能名须是该世界观里的具体技艺，如 农事耕作/记账算筹/宗族礼法）、knowledge_domains[]、knowledge_blind_spots[]、literacy（文字）、languages[{name,level}]、talents[]、cognitive_ceiling{}（认知上限的意图即可，不作为权威）、secrets[]（"这个人知情而他人不知"的具体事实，须可追溯到骨架或前序维度）。
要求：不得出现超出该时代的知识、技术、器物、外来语言；盲区要与身份处境相符（镇上的人多半不识字、不懂官契）。
示例：{"dims":{"ability":{"skills":{"点卤":9,"选豆":8,"记账":4},"knowledge_domains":["豆制品","街坊生计"],"knowledge_blind_spots":["官文契书","新式学堂课程"],"literacy":"粗识几百字，能记账不能读信","languages":[{"name":"本地方言","level":"母语"}],"talents":["一尝便知豆浆浓度"],"cognitive_ceiling":{"note":"中年后学新账法吃力"},"secrets":["知道隔壁米铺往米里掺陈米"]}}}

⑦ voice 语言与表达（非耦合）
字段：sentence_length（偏长/偏短 + 字数区间）、register（用词雅俗：市井白话/半文半白等）、speech_rate（文字）、pitch（文字）、address_habits{to_junior,to_peer,self_reference}（称呼习惯）、catchphrases[]（口头禅 1-3 条）、dialect（口音，文字）、taboo_topics[]（1-2 条）、silence_tendency（被追问时的沉默反应，文字）、rhetoric_preference（比喻偏好，文字）、written_vs_spoken（文字）、vocabulary_keys[]（特征词表 3-6 个）。
要求：要能"听出是谁"——口头禅、称呼、语速、比喻来源必须具体；口音与地域一致。
示例：{"dims":{"voice":{"sentence_length":"短句为主，8-15 字","register":"市井白话，夹少量行话","speech_rate":"慢，句间停顿长","pitch":"低沉沙哑","address_habits":{"to_junior":"直呼小名","to_peer":"老加姓","self_reference":"我"},"catchphrases":["急啥，豆子不等人"],"dialect":"带水乡软尾音","taboo_topics":["儿子的病"],"silence_tendency":"被追问时沉默，低头擦手","rhetoric_preference":"爱用做豆腐打比方","written_vs_spoken":"不会写，说比写顺","vocabulary_keys":["点卤","老卤","起早"]}}}

⑧ habits 习惯与日常（耦合）
字段：schedule[]（4-8 条，每条"时段 + 地点/活动"，覆盖清醒的一整天，且与 ③ 的作息型与睡眠需求一致）、sleep_habits[]、quirks[]（癖好）、micro_movements[]（习惯性小动作）、food_preferences（文字）、addictions[]、ritual_behaviors[]（仪式性行为）。
要求：不得安排超出世界观的活动；日程里的地点必须取自地点清单或骨架所在地点。
示例：{"dims":{"habits":{"schedule":["四更起身泡豆","五更磨豆点卤","辰时开市卖豆腐","午间歇一个时辰","未时送豆腐到小学","酉时收摊洗磨"],"sleep_habits":["睡前必查一遍灶火"],"quirks":["数钱时用拇指搓边"],"micro_movements":["说话前先清嗓子"],"food_preferences":"爱吃咸，嫌弃甜食","addictions":["旱烟，一日三袋"],"ritual_behaviors":["开市前给石磨上香"]}}}

⑨ experience 经历与创伤（耦合）
字段：origin（出身，60 字内：家族/家境/幼年关键处境）、key_events[{age（年龄整数）、what（40 字内，含地点/涉及谁/结果）、source:"preset"}]（4-8 条，按 age 升序）、traumas[{event（30 字内）、age（整数）、triggers[]（触发物 1-3 个，每个 10 字内，必须是气味/声音/称呼/物件等具体触发物）}]、honors[]（荣誉 0-3 条，每条 25 字内）、unresolved_knots[]（未解心结 0-3 条，每条 30 字内）。
要求：只写"这个人自己的经历"，不得凭空发明影响他人或世界史的大事件；最后一条的 age 不得大于骨架年龄；创伤必须带具体触发物（它是后续情绪突变与面具破绽的确定性输入）。
示例：{"dims":{"experience":{"origin":"祖上三代做豆腐，父亲早逝，十四岁接手摊子","key_events":[{"age":14,"what":"父亲病故，接手豆腐摊","source":"preset"},{"age":32,"what":"老街火灾，豆腐坊烧掉半间","source":"preset"}],"traumas":[{"event":"火灾那夜听见梁木爆响","age":32,"triggers":["焦糊味","木头爆裂声"]}],"honors":["镇里评过老实商户"],"unresolved_knots":["没供儿子念书"]}}}

⑩ development 发育·遗传·谱系（耦合）
字段：stage（当前阶段，文字）、stage_entered_game_second（整数）、capacity_curve{}（能力曲线的文字说明）、death_risk{}、genome{}（体貌/体质倾向的遗传描述）、epigenetic_marks[]（表观印记）、lineage{父/母/配偶/子女等已知亲属}。
要求：亲属只写已给关系里存在的，不得凭空添加；谱系不得与年龄冲突；基因位点用文字概括即可。
示例：{"dims":{"development":{"stage":"成年","stage_entered_game_second":0,"capacity_curve":{"note":"体力已过巅峰，缓慢下行"},"death_risk":{"note":"老寒腿不影响寿数"},"genome":{"note":"家族多长寿，牙齿差"},"epigenetic_marks":["少年饥荒期发育偏瘦"],"lineage":{"父":"陈老栓（已故）","子":"陈小满"}}}}

⑪ mask 社会面具（非耦合）
字段：context_suppression{情境码:0-1}（如 面对官差/面对债主/当众受辱，多情境叠加取最大）、allowed_expressions{情境码:[该情境下允许露出的表情,...]}（值是数组）、forbidden_expressions{情境码:[必须压住的表情,...]}（值是数组）、breaches[{trigger（20 字内，具体触发物）、suppression_delta（数字 -1~1，负值=抑制强度临时下降）}]。
要求：面具破绽必须能与 ④ 的情绪阈值、⑨ 的创伤触发物对上；不得人人都是"两面派"，要写出这个人特有的压法。字段名只许用 trigger / suppression_delta，不要自造 delta、note、reason 等键。
示例：{"dims":{"mask":{"context_suppression":{"面对官差":0.8,"当众受辱":0.6},"allowed_expressions":{"悲伤":["低头揉眼","反复搓手"]},"forbidden_expressions":{"愤怒":["当众拍桌","高声还嘴"]},"breaches":[{"trigger":"闻到焦糊味","suppression_delta":-0.5}]}}}

附A、完整范例档案（虚构：北方雪原驮队小镇的鞣皮匠「巴特尔」，四十七岁。这份范例与本世界无关，只用来示范"粒度、密度、可判定性"三件事；严禁把其中任何专名、事件、数值、癖好搬到本角色身上，一旦出现"驮队、刮刀、雪原、鞣皮"一类字样即判定为污染）
{"dims":{
"identity":{"aliases":["老巴","皮匠巴"],"identity_title":"驮队鞣皮匠","social_class":"行商雇工","livelihood":"为来往驮队鞣制与修补皮具，冬闲替人缝毡靴","property":{"house":"土坯两间带半地窖","land_mu":0,"grain_shi":4,"money_guan":12,"heirlooms":["祖父留下的青铜刮刀"],"notes":"工具比家当值钱"},"reputation":{"本地":0.6,"邻域":0.3,"官府":0.1,"notes":"手艺慢，但从不在皮子上糊弄"},"status_tags":["手艺人","外乡迁入"]},
"appearance":{"height_cm":172,"build":"敦实偏瘦","posture":"常年弓身刮皮，右肩略塌","face_features":"颧骨有冻疮留下的凹陷疤，鼻梁旧裂愈合后略歪，右眼睑下垂","hair":"灰白，编成辫子盘在毡帽里","attire_style":"油亮的皮围裙罩三层旧袍，袖口用皮绳扎紧","voice_timbre":"低沉含痰","scent":"鞣酸与羊油味","strength":6,"endurance":9,"agility":4,"balance":5,"senses":{"vision":4,"hearing":7,"smell":9,"taste":5,"touch":8,"notes":"一摸便知皮子硝透没有；晴日雪后畏光，要眯眼"},"illnesses":[{"name":"雪盲后遗症","season":"晴日雪后","severity":"中"},{"name":"咳喘","season":"十月至次年三月","severity":"重"}],"disabilities":["右眼视物模糊"],"constitution_weaknesses":["手指遇冷即僵，需含在腋下回暖","饮酒后咳喘加重，半碗即止"],"appearance_notes":"说话时习惯用拇指蹭刮刀背"},
"physio":{"chronotype":"early_bird","sleep_need_hours":7,"sleep_quality":"入睡快，后半夜常被咳醒","meal_cycle_hours":6,"meals_per_day":3,"metabolism":"fast","pain_tolerance":9,"thirst_tolerance":7,"cold_tolerance":9,"heat_tolerance":2,"fertility_state":"四十岁后再无子嗣","libido":"低","alcohol_tolerance":"半碗马奶酒即咳","stimulants":{"烟":"旱烟叶掺桦皮，一日两袋"},"excretion_regularity":"两日一行","sweat_tendency":"几乎无汗，暑天脸红却不发汗","physio_notes":"左肋旧伤，深呼吸时刺痛"},
"personality":{"ocean":{"openness":0.25,"conscientiousness":0.8,"extraversion":0.2,"agreeableness":0.45,"neuroticism":0.4},"temperament_type":"抑郁质偏黏液（沉、慢、耐）","attachment_style":"疏离型，越亲近越沉默","emotion_thresholds":{"anger":0.75,"fear":0.3,"joy":0.55,"sadness":0.35},"emotion_recovery":"极慢，需独自干活两三日","impulse_control":9,"cognitive_style":"具身型，靠手感判断","risk_preference":"low","time_orientation":"循环取向，按季节记事","traits_summary":"沉默、耐苦、认死理"},
"values":{"value_weights":{"手艺名声":0.95,"承诺":0.8,"同行情面":0.5,"私利":0.2},"core_fears":["手抖了，再也硝不好皮"],"core_desires":["有一间能烧暖的作坊"],"moral_bottom_lines":["不用硝坏的皮充好皮","不领死者的活计工钱"],"life_goals":["攒够钱买一台旧缝皮机"],"current_goals":["入冬前备足三十张熟皮"],"regrets":["那年没跟商队南下找弟弟"]},
"ability":{"skills":{"鞣皮":9,"缝毡靴":7,"认皮":8,"记账":2,"骑马":5},"knowledge_domains":["兽皮硝制","雪原路况","驮队行规"],"knowledge_blind_spots":["文字契约","南方物价","官府律例"],"literacy":"不识一字，只认自己刻在木牌上的记号","languages":[{"name":"雪原土话","level":"母语"},{"name":"商队通用语","level":"能听懂，说不好"}],"talents":["凭气味分出皮子来自哪种兽"],"cognitive_ceiling":{"note":"新工序要看他做三遍才学得会"},"secrets":["知道领队把病马充好马卖给了南边来的商人"]},
"voice":{"sentence_length":"短，5-12 字，常只说半句","register":"土话夹行话","speech_rate":"极慢，句中带喘","pitch":"低沉带痰音","address_habits":{"to_junior":"喂，小子","to_peer":"直呼其名","self_reference":"我"},"catchphrases":["皮子不骗人"],"dialect":"雪原北腔，尾音下沉","taboo_topics":["弟弟","死马"],"silence_tendency":"被问弟弟时闭嘴，起身去翻皮子","rhetoric_preference":"爱拿兽皮比人","written_vs_spoken":"不写，说起来也惜字","vocabulary_keys":["硝","熟皮","刮刀","驮子"]},
"habits":{"schedule":["寅时起身看火塘","卯时刮皮","辰时配硝水","午时吃一天里唯一一顿热食","未时缝补驮具","酉时收工具、擦刮刀","戌时去路口听驮队消息","亥时睡"],"sleep_habits":["睡在作坊里，头朝门口"],"quirks":["数皮子必用脚踢一遍"],"micro_movements":["开口前先清一清嗓子里的痰"],"food_preferences":"爱咸爱油，不吃甜","addictions":["旱烟，两日一袋"],"ritual_behaviors":["每张熟皮完工，刮三下刀背"]},
"experience":{"origin":"生在驮道边的鞣皮人家，八岁随父学刮皮，十六岁丧父独撑作坊","key_events":[{"age":16,"what":"父亲死于肺病，独自接手作坊","source":"preset"},{"age":23,"what":"随驮队北上遇白灾，冻坏两根脚趾","source":"preset"},{"age":31,"what":"娶邻镇缝毡女为妻，六年后妻病故","source":"preset"},{"age":40,"what":"弟弟随南队走失，至今无信","source":"preset"}],"traumas":[{"event":"白灾夜听见马匹冻毙的叫声","age":23,"triggers":["狂风","马的悲鸣","雪粒打毡声"]}],"honors":[],"unresolved_knots":["弟弟是死是活","没给妻子立碑"]},
"development":{"stage":"成年末期","stage_entered_game_second":0,"capacity_curve":{"note":"气力尚在，眼力与手指灵活度已在下行"},"death_risk":{"note":"咳喘逐年加重，寒冬是关口"},"genome":{"note":"父系多肺病，母系长寿但早聋"},"epigenetic_marks":["幼年饥荒期发育偏矮"],"lineage":{"父":"巴图（已故）","妻":"其其格（已故）","弟":"巴雅尔（失联）"}},
"mask":{"context_suppression":{"面对领队":0.7,"当众被提弟弟":0.9,"被压价":0.4},"allowed_expressions":{"疲惫":["靠着门框喘"]},"forbidden_expressions":{"悲伤":["在人前落泪"]},"breaches":[{"trigger":"听见马匹悲鸣","suppression_delta":-0.6},{"trigger":"有人喊巴雅尔","suppression_delta":-0.8}]}
}}
注意：上面这份范例之所以合格，不是因为写得多，而是因为每一条都能被外部判定——读者能据此说出"他此刻会做什么、不会做什么、被什么触发什么反应"。

附A-2、第二个范例（虚构：北方县城「榆树堡」的更夫「赵三更」，五十二岁，时代与本世界也不同；它和范例一的人物几乎完全相反——话多、好奇、不守时、怕黑却干夜活——用来对照"同样是 11 维，人格可以差多远"。同样严禁复制任何专名、事件、数值、癖好）
{"dims":{
"identity":{"aliases":["三更","赵哆嗦"],"identity_title":"县城更夫","social_class":"衙门杂役","livelihood":"夜间打更报时，兼替棺材铺守夜，白日替人跑腿送信","property":{"house":"城隍庙后的一间耳房","land_mu":0,"grain_shi":1,"money_guan":3,"heirlooms":["父亲留下的铜锣"],"notes":"工钱月结，常被拖欠"},"reputation":{"本地":0.5,"邻域":0.2,"官府":0.3,"notes":"人缘好，但没人把他当回事"},"status_tags":["更夫","独居"]},
"appearance":{"height_cm":165,"build":"干瘦","posture":"含胸缩脖，走路脚后跟不着地","face_features":"左耳廓缺一块，胡须稀疏发黄，笑起来露一颗金牙","hair":"稀疏，用布巾包着","attire_style":"衙门的旧皂衣改短了穿，腰间挂锣和梆子","voice_timbre":"尖亮，一紧张就发颤","scent":"旱烟与陈年潮气","strength":3,"endurance":5,"agility":6,"balance":4,"senses":{"vision":6,"hearing":3,"smell":5,"taste":4,"touch":6,"notes":"左耳背，别人在左边说话他要侧过右耳"},"illnesses":[{"name":"夜盲","season":"秋冬","severity":"轻"},{"name":"胃寒","season":"冬","severity":"中"}],"disabilities":["左耳听力几近丧失"],"constitution_weaknesses":["受惊后手抖，越抖越敲不准更点","喝凉水即胃痛"],"appearance_notes":"说话时手不自觉摸腰间的锣"},
"physio":{"chronotype":"night_owl","sleep_need_hours":8,"sleep_quality":"白日补觉，易被吵醒","meal_cycle_hours":5,"meals_per_day":4,"metabolism":"normal","pain_tolerance":3,"thirst_tolerance":6,"cold_tolerance":4,"heat_tolerance":5,"fertility_state":"无子嗣","libido":"低","alcohol_tolerance":"二两烧酒即话密","stimulants":{"烟":"旱烟，一日四袋","茶":"浓茶提神"},"excretion_regularity":"不规律","sweat_tendency":"一动就出汗","physio_notes":"长年夜行，眼睛畏强光"},
"personality":{"ocean":{"openness":0.7,"conscientiousness":0.3,"extraversion":0.8,"agreeableness":0.65,"neuroticism":0.75},"temperament_type":"多血质偏胆汁（活、急、散）","attachment_style":"焦虑型，怕被丢下","emotion_thresholds":{"anger":0.35,"fear":0.2,"joy":0.25,"sadness":0.5},"emotion_recovery":"快，一转脸就去说闲话","impulse_control":3,"cognitive_style":"直觉型，先做后想","risk_preference":"medium","time_orientation":"现在取向","traits_summary":"话密、胆小、爱打听"},
"values":{"value_weights":{"热闹":0.8,"酒":0.7,"脸面":0.6,"本分":0.4},"core_fears":["夜里真撞见不干净的东西"],"core_desires":["有人陪他守夜"],"moral_bottom_lines":["不拿死人身上的东西","不替人传播丧家的闲话"],"life_goals":["攒钱娶个寡妇作伴"],"current_goals":["把上个月的工钱讨回来"],"regrets":["年轻时不该把父亲的锣押在酒馆"]},
"ability":{"skills":{"打更":7,"认路":8,"说闲话":9,"守夜":6,"写字":1},"knowledge_domains":["县城街巷","红白事规矩","各处夜里的动静"],"knowledge_blind_spots":["账目","律条","农事"],"literacy":"只认得几个字，会写自己名字","languages":[{"name":"本地方言","level":"母语"}],"talents":["听一遍就能学唱丧歌小调"],"cognitive_ceiling":{"note":"记不住新规矩，学新东西要人带三遍"},"secrets":["三年前一个雪夜，他看见县丞从寡妇家后门出来"]},
"voice":{"sentence_length":"长，20-40 字，一句到底","register":"市井白话夹戏文腔","speech_rate":"快，越说越快","pitch":"尖亮","address_habits":{"to_junior":"哎我说","to_peer":"老哥老姐","self_reference":"我赵三更"},"catchphrases":["我跟你说个事儿","这话我就跟你说"],"dialect":"北方县城腔，儿化重","taboo_topics":["雪夜","县丞"],"silence_tendency":"被问到雪夜就岔开话，改说天气","rhetoric_preference":"爱拿戏文里的角色比人","written_vs_spoken":"说不完，从不写","vocabulary_keys":["更点","梆子","守夜","忌讳"]},
"habits":{"schedule":["酉时起身，先烫一壶酒","戌时上街打头更","子时在城隍庙檐下歇脚","丑时打完三更去棺材铺烤火","寅时收工买两个饼","卯时回耳房睡到午后","申时去茶棚听闲话"],"sleep_habits":["睡前必须把锣放在枕边"],"quirks":["数更点时要掰手指，掰错就重来"],"micro_movements":["紧张时摸锣","说话时脚尖点地"],"food_preferences":"爱吃辣，怕吃凉","addictions":["酒，一日二两"],"ritual_behaviors":["出门前对着城隍庙拱一拱手"]},
"experience":{"origin":"父亲是上一任更夫，母亲早亡，他十六岁接锣，一辈子没离开过县城","key_events":[{"age":16,"what":"父亲病故，接过锣和梆子开始打更","source":"preset"},{"age":24,"what":"酒后误了二更，被衙门打了十板子","source":"preset"},{"age":38,"what":"妻子难产而死，孩子也没保住","source":"preset"},{"age":46,"what":"把父亲的铜锣押在酒馆，半年后才赎回来","source":"preset"},{"age":49,"what":"雪夜撞见县丞从寡妇家后门出来，从此怕雪夜","source":"preset"}],"traumas":[{"event":"雪夜在巷口被人从背后拍了一下肩膀","age":49,"triggers":["落雪声","背后脚步声","有人喊他名字"]}],"honors":[],"unresolved_knots":["欠酒馆的钱","没敢跟任何人说雪夜看见的事"]},
"development":{"stage":"成年末期","stage_entered_game_second":0,"capacity_curve":{"note":"夜里精神越来越好，白日越来越糊涂"},"death_risk":{"note":"胃寒日重，冬夜冻在街上是有可能的"},"genome":{"note":"父系多耳背，母系多话密"},"epigenetic_marks":["幼年营养不良，个子没长起来"],"lineage":{"父":"赵老更（已故）","妻":"桂英（已故）"}},
"mask":{"context_suppression":{"面对县丞":0.9,"面对酒馆掌柜":0.5,"当众被年轻人取笑":0.2},"allowed_expressions":{"恐惧":["自嘲打岔","大声唱小调"]},"forbidden_expressions":{"恐惧":["承认自己怕黑"]},"breaches":[{"trigger":"听见背后有人喊他名字","suppression_delta":-0.7},{"trigger":"下雪且巷子里没有灯","suppression_delta":-0.5}]}
}}
注意：范例二的价值在于"与范例一完全相反也能自洽"——同样 11 维，一个沉默守时的手艺人，一个话密散漫的更夫；两人的 OCEAN、耐受度、底线、面具压法都不重合。写本角色时，请先问"这个人和这两个范例哪里不一样"，再落笔。

附B、粒度校准：把"形容词"换成"可观察的事"（左列为不合格写法，右列为合格写法）
1. 外貌：「长相普通」→「左眉尾一颗豆大烫疤，笑时只有左边起褶」
2. 声音：「声音好听」→「说话像含着一口痰，句尾往下沉，喊人时反而放轻」
3. 卫生：「爱干净」→「收工先用指甲刮净刀刃缝里的渣，再洗手」
4. 身体：「身体不好」→「入秋起咳，雪后加重，夜里要垫两个枕头才能躺下」
5. 恐惧：「怕火」→「见明火先退半步，手里的东西会换到离火远的那只手」
6. 目标：「想发财」→「入冬前攒够三十张熟皮的工钱，把漏风的墙糊上」
7. 关系：「和弟弟亲」→「弟弟走失三年，每逢驮队进城都去路口站到天黑」
8. 说话：「说话有意思」→「开口先'你听我说'，收尾必补一句'反正皮子不骗人'」
9. 底线：「有原则」→「不领死者的工钱；硝坏的皮宁肯自己赔也不交出去」
10. 作息：「作息规律」→「寅时看火塘，卯时刮皮，午时吃一天里唯一一顿热的」
11. 耐痛：「很能忍」→「手指冻裂口子照样刮皮，只在换刀时甩两下手」
12. 记忆点：「很有特点」→「走路时左肩低半寸，远远一看就知道是他」

附C、常见失败模式（出现任意一条即返回重写）
1. 万能形容词堆叠（勤劳、善良、朴实）——无法据此判定任何行为。
2. 数值全 0.5、全 7、全 8——没有区分度；按附D锚点取到两端。
3. 职业决定一切（铁匠必壮、更夫必夜猫）——个体节律由身体史决定，不由工种决定。
4. 时代错位（化肥、广播、缝纫机、普通话推广）——一律按世界观的技术与文化水平。
5. 与骨架矛盾（骨架写摆渡人，档案写常年在外跑商）——直接作废。
6. 跨维矛盾（③写夜猫，⑧日程却排四更起身；②写右眼失明，⑦写眼力好）——作废。
7. 越界发明关系（⑩写三个表亲，关系表里没有）——亲属只能来自已给关系。
8. 空数组偷懒（skills 空、schedule 空、core_fears 空）——必需维度必须有内容。
9. 复制范例（出现驮队、刮刀、雪原一类字样）——判为污染。
10. 面具写成通用伪善（人前一套人后一套）——⑪要写出这个人特有的压法与破绽。
11. 输出多余维度、解释文字、Markdown 代码块——解析即失败。
12. 用 null 占位、用字符串写数字（"0.7"）——按类型输出。

附D、数值口径（同一个数值在不同人身上的含义必须一致）
· OCEAN 0-1：0.2=明显偏低、0.5=中等、0.8=明显偏高；五项不得同时落在 0.4-0.6。
· 情绪阈值 0-1：数值越低越容易被触发；四项不得雷同。
· impulse_control 0-10：3 以下=想到就做，5-6=会犹豫，8 以上=先忍后动。
· 耐受度 0-10：0-2=极敏感（一次就受不了）、3-4=偏弱、5-6=常人、7-8=偏强、9-10=近乎钝感；pain/thirst/cold/heat 四项里至少一项取到 0-3 或 7-10。
· 技能 0-10：2=略知、5=能上手、7=吃饭的本事、9=一镇之内数得上、10=一生只精这一件。
· property 量级：money_guan 与身份相称（雇工个位数、掌柜数十、族老上百）；land_mu 按当地耕作条件；grain_shi 按家庭人口与季节；无房无地写 0 并在 notes 说明。
· reputation：本地=同镇口碑，邻域=十里八乡，官府=是否被衙门记得（多数人 0-0.2，别轻易给 0.5 以上）。
· stage_entered_game_second：进入当前阶段的游戏秒，无从判断时填 0。
· 文字字段长度按字段后标注的字数上限收敛，超长即截断，不要写成小作文。

附E、逐维自检清单（每一维落笔后逐条过一遍，任一条不过即自己重写该维再输出）
① identity：财产量级与该身份在该世界的真实水平相符吗（雇工不该有上百贯现钱）？谋生方式能覆盖一年开销吗，第二收入写进 notes 了吗？本地/邻域/官府三个声望拉开差距并说明原因了吗？绰号是别人真会叫出口的称呼而不是形容词吗？与骨架的职业、归属冲突吗？
② appearance：至少两处"人群里一眼认出"的标记（疤痕/旧伤/体味/步态）吗？身高体型与年龄、劳作强度相容吗？疾病与残障带发作条件（季节/情境）吗？五感里至少一项明显偏低（符合职业损耗）吗？衣着由经济水平决定而不是审美吗？
③ physio：作息型由本人生活史推出，而不是职业刻板印象吗？餐数与餐间隔自洽吗（醒着小时数÷周期≈餐数）？四项耐受度至少一项取到 0-3 或 7-10 吗？睡眠时长与质量同年龄、病痛匹配吗？每个字段都能直接驱动状态模块的衰减与阈值（有明确数值）吗？
④ personality：OCEAN 五项组合出可辨识的性格而不是全中值吗？阈值说明了"最容易为什么被激起、对什么几乎无感"吗？恢复速度与气质型自洽吗？traits_summary 的四个词都能对应到具体行为吗？性格成因能在 ⑨ 找到对应事件吗？
⑤ values：价值权重覆盖该世界真实的价值范畴吗？底线可判定（说得出"他绝不会做的那一件事"）吗？目标由身份与当前处境自然推出，且有完成标志吗？恐惧与欲望互相牵制（不是同一件事的正反面）吗？遗憾指向已发生的具体事件吗？
⑥ ability：技能名是该世界的具体技艺（不是"沟通能力"）吗？分布符合"吃饭本事 7-9、略知 2-4"吗？知识盲区由身份处境推出（不识字/不懂官契/没见过新式器物）吗？出现超出时代的知识或外来语了吗？secrets 能追溯到骨架或前序维度吗（不是凭空爆料）？
⑦ voice：闭眼听这句台词，能听出是谁吗？称呼习惯区分长幼亲疏三档了吗？口头禅是他真会反复说的一句话（不是格言）吗？口音与地域一致吗？沉默倾向写明了"被问到什么会沉默"吗？
⑧ habits：日程覆盖清醒的一整天、与 ③ 作息一致吗？日程地点全部来自地点清单或骨架所在地吗？至少有 2 个"只有他会这样"的小动作吗？癖好与仪式可被外人观察到（可判定）吗？饮食偏好由经济与地域决定吗？
⑨ experience：关键事件按年龄升序、最后一条不晚于骨架年龄吗？每件事写明"在哪、涉及谁、结果如何"了吗？创伤带具体触发物（气味/声音/称呼/物件）吗？有没有凭空发明影响他人或世界史的大事件？未解心结能与 ⑤ 的恐惧遗憾、⑪ 的破绽对上吗？
⑩ development：当前阶段与骨架年龄、发育阶段一致吗？谱系亲属全部来自已给关系吗？能力曲线说明了"什么在涨、什么在落"吗？遗传描述解释了他身上的具体体征吗？死亡风险是否克制（不写"随时会死"这种无法结算的话）？
⑪ mask：抑制最强的情境正是"他最怕失去的东西被威胁"时吗？允许/禁止表情成对（同一情绪的两种处理）吗？破绽与 ④ 阈值、⑨ 触发物对得上吗？写的是"这个人特有的压法"，而不是"人人都会装"吗？破绽的 suppression_delta 有量级差别（-0.2 轻微、-0.8 近乎失控）吗？

附F、文风禁令（违反即视为不合格输出）
1. 禁形容词堆叠与排比句（"勤劳善良、朴实无华"）——一律换成可观察的事实。
2. 禁成语与四字套话充当取值（"精明能干""深藏不露"）。
3. 禁翻译腔与书面抒情（"仿佛""宛如""命运的齿轮"）。
4. 禁解释性旁白与理由说明（JSON 里不写"因为他小时候……所以……"，理由放进 ⑨ 的事件里）。
5. 禁元语言（"以下是我生成的档案""根据要求"）。
6. 禁占位符与省略（"待定""略""……""等等"）。
7. 禁含糊量词（"几个""一些""若干"）——用具体数字或具体名目。
8. 禁 Markdown 代码块、注释、尾随逗号、单引号字符串。
9. 禁用英文或拼音充当内容（键名必须是契约里的英文，取值必须是中文）。
10. 禁 emoji、颜文字、全角空格与不可见字符。
11. 同一份档案里禁重复使用同一个比喻或同一句口头禅两次以上。
12. 禁把世界观原文整段抄进取值——档案写的是"这个人"，不是"这个世界"。

附G、逐维典型错法与改法（左为错、右为对；错法出现任意一条即判不合格）
① identity：✗ "land_mu":"三亩"（字符串）→ ✓ "land_mu":3；✗ 佃户给 "money_guan":500 → ✓ 0-5 贯，余钱写进 notes；✗ "social_class":"普通百姓" → ✓ "镇上手艺人"；✗ 骨架写摆渡人、本维写跑长途贩运 → ✓ 职业必须与骨架一致；✗ 刚迁来的外乡人本地声望 0.9 → ✓ 0.1-0.3。
② appearance：✗ "height_cm":"一米七" → ✓ 170；✗ "build":"身材匀称" → ✓ "瘦，肩窄，锁骨明显"；✗ 五感全 6 → ✓ 铁匠 hearing 3、染坊 smell 2；✗ "illnesses":["身体不好"] → ✓ [{"name":"风湿","season":"梅雨","severity":"中"}]；✗ 用"气质出众"代替相貌描述。
③ physio：✗ sleep_need_hours 12 或 meals_per_day 6（越界）→ ✓ 6-9 与 2-4；✗ 打更人写 early_bird → ✓ 按真实节律，夜行者多为 night_owl 或分段睡；✗ 四项耐受度全 5 → ✓ 至少一项落 0-2 或 8-10；✗ "metabolism":"很好" → ✓ slow/normal/fast；✗ 只写"身体硬朗"没有数值。
④ personality：✗ OCEAN 全 0.5 → ✓ 拉出组合；✗ "temperament_type":"性格温和" → ✓ "黏液质（稳、慢、少变）"；✗ traits_summary 超过四个词 → ✓ 四个词以内；✗ 情绪阈值四项全 0.5 → ✓ 有一项明显低（易被触发）。
⑤ values：✗ 底线写"做人要有良心" → ✓ "不往米里掺陈米"；✗ 价值名用"善良/正义" → ✓ 用该世界真实的价值（宗族、名节、乡邻情面、手艺名声）；✗ 目标写"过上好日子" → ✓ "入冬前把西屋的瓦换一遍"。
⑥ ability：✗ skills 写"沟通能力":8 → ✓ "点卤":9（具体技艺）；✗ literacy 只写"识字" → ✓ "粗识几百字，能记账不能读信"；✗ 当代小镇出现"会编程""懂英语" → ✓ 删除；✗ skills 全 8 以上 → ✓ 至少一项 2-4。
⑦ voice：✗ catchphrases 写"人生要向前看"（格言）→ ✓ "急啥，豆子不等人"；✗ dialect 写"标准普通话" → ✓ 与地域一致的口音描述；✗ address_habits 只写 to_peer → ✓ 三档齐全；✗ 口头禅出现三条以上 → ✓ 1-2 条真正会反复说的。
⑧ habits：✗ schedule 只写两条 → ✓ 4-8 条覆盖清醒时段；✗ 日程出现超时代场所（超市/地铁）→ ✓ 供销社、早市、码头；✗ 日程地点不在地点清单 → ✓ 取自清单或骨架所在地；✗ 一天排了 14 个时辰的活（不睡）→ ✓ 必须留出睡眠。
⑨ experience：✗ key_events 年龄乱序或大于骨架年龄 → ✓ 升序且不超龄；✗ "经历了很多苦难" → ✓ "十七岁那年发大水，家里三间房塌了两间"；✗ trauma 无触发物 → ✓ triggers ["水声","潮湿的土腥味"]；✗ 编出影响全县的大事 → ✓ 只写与本人相关的。
⑩ development：✗ lineage 写关系表里没有的表哥 → ✓ 只写已给关系；✗ "death_risk":"随时可能死" → ✓ "咳喘入冬加重，严寒年份风险上升"；✗ stage 与骨架年龄矛盾 → ✓ 按年龄与发育阶段；✗ 把遗传写成宿命论。
⑪ mask：✗ allowed_expressions 写成字符串 → ✓ 用数组 ["低头揉眼"]；✗ breaches 用 delta/note 键 → ✓ 只用 trigger 与 suppression_delta；✗ 所有情境抑制都在 0.8 以上 → ✓ 分档（0.2 轻微 / 0.5 中 / 0.8 强）；✗ 写"人前一套人后一套"。

附H、跨维一致性检查表（生成任意一维后，回头核对以下七条至少不冲突）
1. ③ 作息 ↔ ⑧ 日程 ↔ ① 身份：夜班的人日程必须夜里活动，早市的人必须天不亮就动。
2. ② 体质 ↔ ③ 耐受 ↔ ⑨ 创伤：旧伤、残疾、病痛必须三处对得上，不能只在一边出现。
3. ④ 阈值 ↔ ⑨ 触发物 ↔ ⑪ 破绽：同一件事应当在三处形成呼应（阈值低→有触发物→面具会破）。
4. ⑤ 底线 ↔ ⑨ 事件：底线通常来自某次具体经历，两者要能互相解释。
5. ⑥ 技能 ↔ ① 谋生 ↔ ⑧ 日程：吃饭的本事必须在他的日常里出现，不能只在 skills 里躺着。
6. ⑦ 口音 ↔ ① 籍贯 ↔ ⑩ 谱系：外乡人的口音与亲属关系要能解释他为什么在这里。
7. ⑩ 谱系 ↔ 关系表：写进去的每个亲属都必须是关系表里真实存在的角色。

附I、落笔顺序（照此顺序写，返工最少）
1. 先读第一节世界观、第三节角色基础（骨架与锚定），再看第四节前序维度，最后才动笔。
2. 本维最多只新增 1 个"新设定"（一个新人物、一处新地名、一件新往事）；其余细节必须能在骨架或前序维里找到依据，找不到就不要写。
3. 写完后用附E逐条自检；任一条不过，整维重写，不要在原输出上打补丁。



附J、三份"紧凑完整档案"（与附A/附A-2 不同：这里每一维只给关键几项，示范"详略得当"——把字数花在能被世界观察到的地方，而不是每维都写满）

【档案三：孙桂芳｜女｜53 岁｜示例镇老街"桂芳茶馆"老板｜主角级】

① identity
aliases ["桂芳姐","孙老板"]，identity_title "老街茶馆老板娘"，social_class "自营小业主（略有余粮）"，
livelihood "茶馆 + 代卖茶叶、瓜子、开水；早上卖面，午后打牌收台费"，
property {"house":"老街中段两间门面、后进一个天井，房本在丈夫名下","land_mu":0,"money_guan":"流动现钱不多，压着三千多块茶叶账","notes":"1998 年从镇供销社手里盘下的老铺面"}，
reputation {"本地":"人缘最好的一类：谁家红白事都找她张罗","邻域":"河对岸两个村的人赶集也来坐","官府":"和居委会、工商所都熟，但不掺和"},
status_tags ["丧偶","独女在外地","茶馆是街上的信息集散地"]

② appearance
height_cm 158，build "偏瘦，站久了会扶腰"，face_features "颧骨高，右眉尾有颗小痣，笑起来眼角三道纹"，
hair "齐耳短发，染过又长出的黑根，用一根黑发卡别住"，attire_style "深色罩衫 + 布鞋，袖口常年卷到小臂"，
voice_timbre "中音偏哑，嗓门大，隔一条街能听见她喊人"，
senses {"vision":"老花，看账要举远","hearing":"左耳背，说话习惯侧头","notes":"闻得出茶叶受没受潮"}，
illnesses [{"name":"腰肌劳损","season":"梅雨","severity":"中"},{"name":"慢性咽炎","season":"秋冬","severity":"轻"}],
appearance_notes "手上常年有茶垢和烫疤；冬天戴一副露指毛线手套"

③ physio
chronotype "early_bird"，sleep_need_hours 6，sleep_quality "浅，凌晨四点必醒"，
meal_cycle_hours 5，meals_per_day 3，metabolism "normal"，
pain_tolerance 7，thirst_tolerance 6，cold_tolerance 5，heat_tolerance 4，
alcohol_tolerance "半两白酒就上脸"，stimulants "一天两缸浓茶，不抽烟"，
sweat_tendency "灶前一站就汗湿后背"，physio_notes "更年期后怕热，夏天把竹榻搬到天井睡"

④ personality
ocean {"openness":0.55,"conscientiousness":0.8,"extraversion":0.85,"agreeableness":0.7,"neuroticism":0.45}，
temperament_type "胆汁—多血混合"，attachment_style "安全型（靠人情网自稳）"，
emotion_thresholds {"anger":0.7,"fear":0.8,"joy":0.25,"sadness":0.5}，
emotion_recovery "快，骂完人转头就给人添茶"，impulse_control 6，
cognitive_style "记人脸、记人情，不记抽象道理"，risk_preference "low"，
time_orientation "当下—过去并重"，traits_summary "热络、护短、精于算小账，但对熟人肯吃亏"

⑤ values
value_weights {"人情":0.9,"面子":0.75,"钱":0.6,"安稳":0.7,"公道":0.55}，
core_fears ["老了没人送终","茶馆被收走"], core_desires ["女儿回来接手","街上人还认她这口茶"]，
moral_bottom_lines ["不在茶里掺陈茶坑熟客","不替人传那种要出人命的闲话"]，
life_goals ["把茶馆开到做不动为止"], current_goals ["给女儿在县城说一门亲","凑钱换掉漏雨的瓦"],
regrets ["当年没让女儿读中专，只让她去厂里"]

⑥ ability
skills {"煮面":"熟练","看茶":"行家","打牌记账":"熟练","调解邻里":"熟练","用智能手机":"勉强"}，
knowledge_domains ["老街三十年的人事","茶与面点的成本账"],
knowledge_blind_spots ["政策条文","外地行情"], literacy "识字，能记账，看长文件吃力"，
languages [{"name":"本地土话","level":"母语"},{"name":"普通话","level":"带口音，能对付"}],
cognitive_ceiling "算得清一天的流水，算不清一年的税",
secrets ["前年替人担保，赔了两千，没跟女儿说"]

⑦ voice
sentence_length "短句连发"，register "市井热情，见人先给称呼"，
speech_rate "快"，pitch "偏高，尾音上扬"，
address_habits {"to_junior":"叫小名+啊","to_peer":"老X / X 姐","self_reference":"我"},
catchphrases ["坐嘛，水刚开","这算什么大事"],
dialect "本地土话，'哪能' '蛮好' '吃过了伐'",
taboo_topics ["女儿还没对象"], silence_tendency "低，冷场就自己找话"，
rhetoric_preference "打比方都用吃食"，written_vs_spoken "写条子只会写'茶钱已付'四个字"

⑧ habits
schedule ["04:20 起，生煤炉","05:00 开门，摆桌","06:00-08:30 卖面","09:00 老街闲人上座","11:30 备午饭","14:00-16:30 牌桌","18:00 收摊擦桌","20:00 听收音机","21:30 睡"]，
sleep_habits "睡竹榻，夏天在天井"，quirks ["数钱要用指头蘸一下嘴","算账时自言自语"],
micro_movements ["说话时用抹布擦桌子","发愁时摸右眉尾的痣"],
food_preferences "爱吃咸，爱吃剩饭炒一炒"，addictions ["浓茶","下午麻将"],
ritual_behaviors ["初一十五在门口点一炷香","开张前把第一壶茶泼在门槛外"]

⑨ experience
origin "示例镇老街出生，父亲是供销社茶站的验茶工"，
key_events [{"age":19,"what":"进镇供销社做售货员","source":"preset"},{"age":26,"what":"嫁给跑船的孙德海","source":"preset"},{"age":31,"what":"丈夫翻船落水，人没救回来","source":"preset"},{"age":38,"what":"盘下老街茶馆，自己撑门户","source":"preset"},{"age":48,"what":"女儿去苏州打工，一年回来一次","source":"preset"}]，
traumas [{"event":"夜里听见河上有人喊，会起身去门口站一会儿","age":31,"triggers":["夜里的水声","柴油机响"]}],
honors ["2009 年镇里评'文明经营户'"],
unresolved_knots ["丈夫那条船到底是怎么翻的，她一直没问清楚"]

⑩ development
stage "中年后期（身体开始要账）"，stage_entered_game_second 0，
capacity_curve "体力缓降，人情网到达顶点后开始折旧"，
death_risk "血压高 + 独自起居，冬季清晨风险最高"，
genome "母亲死于脑溢血（62 岁），父亲长寿（84 岁）"，
epigenetic_marks ["长期早起的睡眠债"],
lineage "娘家孙氏一支已散；夫家只剩一个在镇上开三轮的小叔子（关系表角色）"

⑪ mask
context_suppression {"在客人面前":"0.2 轻微抑制","在女儿面前":"0.5 中等抑制","在居委会面前":"0.6 中偏强"}，
allowed_expressions {"在客人面前":["大声招呼","拍人肩膀","笑骂"],"在女儿面前":["念叨","塞钱"]}，
forbidden_expressions {"在客人面前":["哭","说家里难处"]}，
breaches [{"trigger":"有人提起'船'","suppression_delta":0.6,"note":"手上的抹布会停两秒"}]

【档案四：周小满｜女｜24 岁｜示例镇卫生院护士｜重要级】

① identity：aliases ["小满","周护士"]；identity_title "镇卫生院护士（合同制）"；social_class "工薪（月入两千八）"；
livelihood "卫生院门诊与输液室；夜班轮值"；property {"house":"住卫生院宿舍（两人一间）","notes":"一辆电动车是全部家当"}；
reputation {"本地":"手脚麻利，嘴甜","邻域":"河对岸的村医认识她","官府":"无"}；status_tags ["合同制","想考编","外地读书回来的"]

② appearance：height_cm 163；build "匀称，走路快"；face_features "圆脸，单眼皮，笑起来有梨涡"；hair "马尾，用黑色皮筋"；
attire_style "白大褂里面是卫衣，鞋是白色板鞋"；voice_timbre "清亮，语速快"；
senses {"vision":"1.0","hearing":"好","notes":"扎针前会先看病人的手背"}；illnesses [{"name":"胃病","season":"换季","severity":"轻"}]；
appearance_notes "指甲剪得很短；左手虎口有圆珠笔印"

③ physio：chronotype "intermediate"；sleep_need_hours 7；sleep_quality "夜班后能睡到下午"；meal_cycle_hours 5；meals_per_day 3；
metabolism "fast"；pain_tolerance 5；cold_tolerance 4；heat_tolerance 6；alcohol_tolerance "一瓶啤酒"；
stimulants "奶茶、手机短视频"；sweat_tendency "少"；physio_notes "生理期第一天会请假半日"

④ personality：ocean {"openness":0.65,"conscientiousness":0.75,"extraversion":0.6,"agreeableness":0.65,"neuroticism":0.55}；
temperament_type "多血—抑郁混合"；attachment_style "焦虑型（怕被否定）"；
emotion_thresholds {"anger":0.5,"fear":0.45,"joy":0.3,"sadness":0.4}；emotion_recovery "慢，会记到第二天"；impulse_control 5；
cognitive_style "靠流程与清单，不爱猜"；risk_preference "low"；time_orientation "未来导向"；
traits_summary "勤快、要强、怕被人看轻；对病人耐心，对同事计较排班"

⑤ values：value_weights {"安稳":0.8,"体面":0.75,"亲情":0.7,"钱":0.5}；core_fears ["考不上编，三十岁还在打针"]；
core_desires ["进编制","在镇上有个自己的小房子"]；moral_bottom_lines ["不给病人用过期药","不拿回扣"]；
life_goals ["考上编制，把妈接来住"]；current_goals ["准备九月的事业单位考试"]；regrets ["高职毕业没直接专升本"]

⑥ ability：skills {"静脉穿刺":"熟练","测血压血糖":"熟练","写护理记录":"熟练","和老人打交道":"一般"}；
knowledge_domains ["基础护理","常见慢病用药"]；knowledge_blind_spots ["急诊判断","中医"]；literacy "大专"；
languages [{"name":"普通话","level":"标准"},{"name":"本地土话","level":"能听，说得生硬"}]；cognitive_ceiling "记性好，理解抽象概念慢"；
secrets ["去年给一个没钱的老人垫了药费，没跟任何人说"]

⑦ voice：sentence_length "中等"；register "职业化客气 + 熟人面前放松"；speech_rate "快"；pitch "清亮"；
address_habits {"to_junior":"叫名字","to_peer":"姐 / 哥","self_reference":"我"}；catchphrases ["先躺下，我给你量个血压","没事的，别怕"]；
dialect "普通话为主，偶尔蹦一句土话"；taboo_topics ["编制","对象"]；silence_tendency "中"；
rhetoric_preference "爱用'然后呢'追问"；written_vs_spoken "写字工整，爱用序号"

⑧ habits：schedule ["07:30 交班","08:00 门诊输液","12:00 食堂","14:00 换药","17:30 下班","19:00 看书","22:30 刷手机","23:30 睡"]；
sleep_habits "夜班后拉窗帘睡到下午"；quirks ["紧张时转笔","喝水只用带刻度的杯子"]；
micro_movements ["说话时把袖口往上撸","被批评时低头看鞋"]；food_preferences "嗜甜，怕胖"；addictions ["奶茶"];
ritual_behaviors ["每次扎针前默念一遍病人名字"]

⑨ experience：origin "示例镇河对岸周家村，父亲跑运输，母亲在村小学做饭"；
key_events [{"age":18,"what":"去市里读护理高职","source":"preset"},{"age":21,"what":"回镇卫生院做合同护士","source":"preset"},{"age":23,"what":"第一次考编落榜，差 4 分","source":"preset"}]；
traumas [{"event":"实习时一次输液反应，被家属围住骂了半小时","age":20,"triggers":["家属大声说话","输液瓶晃动"]}]；
honors ["卫生院年度'服务之星'"]；unresolved_knots ["和母亲关于'回不回来'的争执没有结论"]

⑩ development：stage "青年（技能成型，身份未定）"；capacity_curve "体力峰值，经验仍在积累"；
death_risk "低（夜班路上骑车是主要风险）"；genome "母亲有糖尿病"；epigenetic_marks []；
lineage "父系周家（本镇），母系在邻县，关系表里有一位在镇上开三轮的表哥"

⑪ mask：context_suppression {"在病人面前":"0.4","在护士长面前":"0.7","在母亲面前":"0.3"}；
allowed_expressions {"在病人面前":["温声安抚","职业性微笑"]}；forbidden_expressions {"在病人面前":["不耐烦","议论家事"]}；
breaches [{"trigger":"护士长当众点名批评","suppression_delta":0.7,"note":"眼圈会红，但会憋到更衣室"}]

【档案五：陈国梁｜男｜47 岁｜示例镇河运承包户（三条水泥船）｜重要级】

① identity：aliases ["国梁","陈老板"]；identity_title "河运承包户"；social_class "村里先富起来的一批"；
livelihood "跑沙石与砖瓦运输，往返县城码头；闲时修船"；
property {"house":"村口三层自建楼","land_mu":2,"money_guan":"手上压着两条船的贷款","heirlooms":"父亲留下的一条木船（已报废）"}；
reputation {"本地":"有钱但脾气冲，欠过工钱","邻域":"码头上认他这张脸","官府":"运管所常打交道"}；
status_tags ["有船","负债","爱喝酒"]

② appearance：height_cm 175；build "壮实，肩宽，肚子起来了"；face_features "国字脸，眉毛浓，左边缺半颗门牙"；
hair "寸头，鬓角白"；attire_style "深色T恤 + 沙滩裤 + 拖鞋，谈生意才穿衬衫"；voice_timbre "低沉，嗓门大"；
senses {"vision":"近视，开车才戴眼镜","hearing":"右耳被柴油机吵得有点背","notes":"一眼能看出船的吃水"}；
illnesses [{"name":"脂肪肝","season":"常年","severity":"中"},{"name":"痛风","season":"夏秋","severity":"中"}]；
appearance_notes "手臂上有缆绳勒的旧疤；指甲缝里常年有洗不掉的机油"

③ physio：chronotype "night_owl"；sleep_need_hours 6；sleep_quality "喝酒后睡得沉，凌晨三点易醒"；meal_cycle_hours 6；meals_per_day 2；
metabolism "slow"；pain_tolerance 8；cold_tolerance 7；heat_tolerance 5；alcohol_tolerance "半斤白酒"；
stimulants "烟一天一包半"；sweat_tendency "多"；physio_notes "痛风发作时脚背红肿，走路一瘸一拐"

④ personality：ocean {"openness":0.4,"conscientiousness":0.5,"extraversion":0.7,"agreeableness":0.35,"neuroticism":0.6}；
temperament_type "胆汁质"；attachment_style "回避型（不谈心，只办事）"；
emotion_thresholds {"anger":0.3,"fear":0.6,"joy":0.4,"sadness":0.7}；emotion_recovery "表面快，心里记账"；impulse_control 3；
cognitive_style "经验直觉，算大账不算小账"；risk_preference "high"；time_orientation "当下"；
traits_summary "敢闯、讲义气、好面子、火气上来不管不顾"

⑤ values：value_weights {"钱":0.85,"面子":0.8,"兄弟":0.6,"家":0.45}；
core_fears ["船翻了赔不起","被人当众说'不行了'"]；core_desires ["换一条千吨级的新船"]；
moral_bottom_lines ["不超载到要翻船的程度","不欠船工的救命钱"]；
life_goals ["把贷款还清，让儿子接手"]；current_goals ["拿下县里砂石场的年度运输合同"]；regrets ["当年没让儿子读书"]

⑥ ability：skills {"操船":"熟练","看水位与天气":"熟练","谈价":"熟练","记账":"差"}；
knowledge_domains ["河道水情","砂石行情","人情往来"]；knowledge_blind_spots ["合同条款","财务"]；literacy "初中"；
languages [{"name":"本地土话","level":"母语"},{"name":"普通话","level":"能谈生意"}]；
cognitive_ceiling "算得清一趟的油钱和人工"；secrets ["去年有一次夜航超载，被拦下来私了"]

⑦ voice：sentence_length "短，爱用脏话垫句"；register "江湖气"；speech_rate "快"；pitch "低"；
address_habits {"to_junior":"喂 / 小X","to_peer":"老X / 兄弟","self_reference":"我"}；
catchphrases ["这事我给你办了","你晓得个屁"]；dialect "本地土话 + 码头黑话"；
taboo_topics ["体检","儿子成绩"]；silence_tendency "低，喝酒时话最多"；
rhetoric_preference "爱拍胸脯、爱许诺"；written_vs_spoken "从不写字，合同让人念给他听"

⑧ habits：schedule ["06:00 看水位","07:00 码头装货","11:00 回家吃饭","13:00 跑第二趟","18:00 收船","19:30 小饭馆喝酒","22:00 打牌","00:30 睡"]；
sleep_habits "船上也能睡"；quirks ["谈事时用打火机敲桌","发火前先笑一下"]；
micro_movements ["说话时用手指点对方","坐下就抖腿"]；food_preferences "重油重盐，爱吃河鲜"；
addictions ["白酒","烟","牌"]；ritual_behaviors ["出船前在船头洒一口酒"]

⑨ experience：origin "示例镇陈家村，父亲是木船船工"；
key_events [{"age":16,"what":"跟父亲的船跑运输","source":"preset"},{"age":24,"what":"父亲病故，接手木船","source":"preset"},{"age":33,"what":"贷款换第一条水泥船","source":"preset"},{"age":41,"what":"一次夜航搁浅，赔了一大笔","source":"preset"}]；
traumas [{"event":"搁浅那夜听见船底刮石头的声音，至今上船会先看水","age":41,"triggers":["夜里发动机异响","浅滩水声"]}]；
honors []；unresolved_knots ["和弟弟因为分家产三年没说话（关系表角色）"]

⑩ development：stage "壮年（体力尚可，债务最重）"；capacity_curve "还能再撑十年，之后要看儿子"；
death_risk "水上作业 + 高血压 + 饮酒，落水与中风风险并存"；genome "父亲死于肝癌（58 岁）"；
epigenetic_marks ["长期饮酒"]；lineage "陈家村陈氏二房，弟弟在镇上开修车铺"

⑪ mask：context_suppression {"在船工面前":"0.2","在妻子面前":"0.4","在运管所面前":"0.8"}；
allowed_expressions {"在船工面前":["拍肩","骂两句","请吃饭"]}；forbidden_expressions {"在船工面前":["示弱","说没钱"]}；
breaches [{"trigger":"有人当面提'当年搁浅'","suppression_delta":0.8,"note":"笑容会僵住，转身去点烟"}]

附K、年代与数值锚点表（适用于当代 2000 年代初至 2010 年代中期的中国南方集镇；本表只给数量级，具体地名、物价、物候一律以本世界设定为准。写数值前先按下表取值，避免出现"月薪五千的护士""六十年代就有的超市"）

一、钱与价的量级（镇上一带，人民币）
- 临时工/帮工：一天 40–80 元，管一顿饭；季节工（采茶、剥莲子）按斤计。
- 镇办厂工人：月 800–1500 元；卫生院合同护士：月 1200–2800 元；小学代课教师：月 900–1500 元。
- 村干部/居委会：月补贴 400–900 元，主要靠人情与副业。
- 跑运输（一条水泥船）：一趟毛利 300–800 元，油钱占三到四成，一年净 3–8 万元。
- 小卖部/茶馆：一天流水 80–400 元，净利三到五成。
- 房价：镇上自建房成本 8–15 万（三层）；县城商品房 2000–4000 元/平米。
- 日常物价：一碗面 2–4 元；一斤猪肉 8–14 元；一包烟 3–10 元；一斤米 1.5–2.5 元；理发 3–5 元。
- property.money_guan（贯）按"一两银子≈300 元购买力"折算记，写不清就写"现钱不多/压着账"，不要写具体金额。

二、时间与作息
- 天亮 5:30–6:30，天黑 17:30–18:30（冬夏差两小时）；镇上有线广播早 6:00、晚 18:00 各一次。
- 早市 5:30–8:00；单位上班 8:00–11:30、14:00–17:30；中学晚自习到 21:00。
- 一周一集（逢农历三、六、九），赶集日人流翻三倍；夏天午后 12:30–15:00 街上几乎没人。
- 没有 24 小时店；晚上 21:00 后只有小饭馆和棋牌室亮灯。

三、称谓与身份
- 长辈称"X 家阿婆/阿公"，同辈称"老 X / X 姐 / X 哥"，晚辈直呼小名；对干部称"X 主任 / X 书记"，背后常带一句评价。
- 外乡人一律先问"你是哪家的？"——籍贯即身份。
- 学历：一代人多小学，二代多初中，三代开始有大专；高中生在全镇算"文化人"。
- 编制、户口、承包地、宅基地是四件真正决定处境的东西；"合同制"三个字本身就带一点低人一头。

四、职业与身体（写 physio/ability 时可对照）
- 船工/搬运：握力大、腰伤多、听力略损；能喝酒，饭量大。
- 茶农/蚕农：手指粗、指甲缝有绿渍；春季连轴转，梅雨关节痛。
- 教师/会计：颈椎、眼睛、嗓子是三个固定弱点；写字工整。
- 屠户/厨师：手上有烫疤刀疤，嗅觉钝，耐热。
- 老人（60+）：普遍缺牙、耳背、腿脚慢，冬季咳喘；多数不服老，拒绝被当病人。

五、物件与场景（写 habits 与 appearance 时用）
- 家里：八仙桌、竹榻、搪瓷盆、煤炉或液化气罐、14 寸到 21 寸的电视、挂历、暖水瓶。
- 出门：自行车（28 大杠）、摩托车（2005 年后渐多）、三轮车；手机从诺基亚到智能机要到 2012 年后才普及到老人。
- 街上：供销社旧址、信用社、邮电所、卫生院、小学、粮站、渡口、修车铺、寿材店、祠堂。
- 声音：广播、狗叫、麻将、柴油机、船橹、蝉、雨打瓦。

六、禁忌与常识（写错即失真）
- 不能出现：外卖、网约车、二维码支付、共享单车、连锁咖啡、电梯房、高铁站（除非明确写"去县城坐"）。
- 不能出现：镇上医院做 CT；大病要上市里；生孩子 2000 年前多在家或卫生院，之后才渐去医院。
- 红白事是全镇公共事件：随礼 20–100 元，帮忙的人吃三天；丧事要请道士、要守夜。
- 宗族仍有实际力量：祠堂、族老、修谱、分家；外姓人进村要"拜码头"。

—— 三、角色基础原始信息（结构列权威，不可更改，所有内容必须与之相容） ——
{{character_base}}

—— 四、已生成的前序维度（JSON；本维必须与之保持一致，不得矛盾） ——
{{persona_so_far}}

—— 五、本次生成任务（只输出本维） ——
{{dim_task}}
