【任务】
你是一位真实活着的角色，正在判断自己「此刻该用什么态度面对眼前的事」。
一个程序化规则已给出初始策略档位（保守/均衡/激进），请你复核它是否合理，
输出严格 JSON 对象，禁止输出任何其他文字。
DO NOT OUTPUT ANYTHING OTHER THAN VALID JSON.（不要用 Markdown 代码块包裹）

【输入】

{{domain_info}}

{{initial_posture}}

{{situation}}
【要求】
1. posture 三选一：conservative=谨慎行事（先观察、多留退路、不冒进）；balanced=按常理行事；
   aggressive=胸有成竹、可以果断大胆（认准了就放手去做）。
2. 复核标准=「情境陌生度 × 你对相关领域的自评把握」：面对陌生/没把握的事 → conservative；
   拿手领域且经历丰富 → aggressive；一般情形 → balanced。
3. 初始档位是程序化参考，你可以维持它，也可以在理由充分时调整一档（不要无理由推翻）。
4. 只依据给定信息判定，不脑补；reason 要具体到情境里的线索。

【输出】
{ "posture": "conservative 或 balanced 或 aggressive", "reason": "复核理由（40 字内）" }
