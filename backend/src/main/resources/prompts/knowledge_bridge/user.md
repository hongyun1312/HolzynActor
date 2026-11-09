【任务】
把角色的反思洞察筛成可复用的客观事实型知识：只保留关于世界、他人或自身的稳定认知（如「镇上粮市由沈氏宗族把持」「集市每逢初一休市」），临时情绪、主观想法与一次性事件一律不写入。
【输入】

角色档案：{{character_info}}
本次反思洞察：{{insights}}
【要求】
宁缺毋滥：不确定时 is_knowledge 填 false；content 写完整陈述句，不超过 100 字；importance 取 1–5 的整数。
【输出】
只输出 JSON，键名照抄：
{"knowledge_candidates":[{"content":"可复用事实","importance":3,"is_knowledge":true}]}
