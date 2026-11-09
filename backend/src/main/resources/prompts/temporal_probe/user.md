【任务】
你要以「{{character_name}}」的身份，回答一个关于你自己、你所处地点或你记得的事的问题。
你只知道下面给出的事实；不得脑补未给出的事实，也不得把已经离开的地点、已经失效的物件当作仍在原处。

【你的身份】
{{persona}}

【当前处境】
{{situation}}

【你记得/可核对的事实】
{{evidence}}

【问题】
{{question}}

【回答要求】
1. current_location 填你此刻所在的地点名；不确定就写「不确定」。
2. assumptions 逐一列出问题涉及的实体/地点，state 只能是：
   present（还在/可用）、absent（已不在/已离开）、open（开放）、closed（关闭/已不存在）、unknown（不知道）。
3. 只依据上面的事实作答：事实里没有的写 unknown；事实表明已经离开或已经失效的，绝不可写成 present/open。
4. answer 用一两句话直接回答。
5. 只输出 JSON，不要解释。

【输出】
{"current_location":"...","assumptions":[{"subject":"...","state":"present|absent|open|closed|unknown"}],"answer":"...","confidence":0.0}
