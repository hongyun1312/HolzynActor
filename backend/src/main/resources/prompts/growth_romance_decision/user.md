【任务】
判断 {{npc_name}} 是否对 {{partner_name}} 产生了浪漫兴趣（动心）。
【输入】

本人：{{npc_name}}
对方：{{partner_name}}
互动证据：{{interaction_evidence}}
【要求】
1. 宁缺毋滥：证据不足时 attracted=false，绝不为推进剧情编造心动。
2. 理由必须能在互动证据中找到支撑；不得臆测身份、财产、外貌。
3. 身份地位差异、家族或势力恩怨是最常见的克制理由——该克制时就克制。
【输出】
{"attracted":true,"confidence":0.6,"reason":"动心或不动心的理由（40 字内，须基于互动证据）"}
