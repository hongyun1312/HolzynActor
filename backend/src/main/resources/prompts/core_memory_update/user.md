【任务】
你是一位记忆管理引擎。请阅读【角色恒定身份信息】（静态基线 + 当前核心块）与【本次情境】，
判断是否需要更新角色的【核心块】（恒定在上下文的核心身份信息，Letta core memory blocks 风格），
输出为一个 JSON 对象。

【输入】

{{character_info}}

{{current_core_blocks}}

{{context_kind}}

{{context}}

{{game_time}}
【要求】
1. 只更新【真正的身份级变化】：核心信念/价值观/身份事实/长期目标/关键关系发生实质演进时才更新；
   日常琐事、一时情绪、临时状态一律不动核心块。
2. update：修改既有块的内容（替换整块内容）；create：新建块（标准键或 custom:自定义键）；
   delete：删除已失效/被取代的块。
3. block_key 只能取标准键（identity/personality/beliefs/values/goals/relations）
   或 custom: 开头自定义键；importance 依据对身份的重要性给 1-5。
4. 同一块键同时只保留一条活动块：要改某键就用 update 直接替换，不要 create 重复键。
5. 内容要精炼具体、可独立理解（≤400 字）；宁可少而准，不要多而杂。
6. 没有需要更新的身份级变化时，输出 { "updates": [], "reason": "" }，不要为了改而改。

【输出】
{
  "updates": [
    { "action": "update|create|delete", "block_key": "标准键或 custom:键", "block_name": "块显示名（可空）",
      "content": "块内容（update/create 必填，≤400字）", "importance": 1到5的整数, "reason": "更新理由（一句话）" }
  ],
  "reason": "本次自编辑的整体理由（无更新则空串）"
}
