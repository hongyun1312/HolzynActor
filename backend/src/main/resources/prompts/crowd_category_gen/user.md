【任务】
为「{{world_name}}」世界的普通平民拟定一套标准字段数据（种族含次级种族 / 归属 / 职业），并选出最适合人群分类调度的两个字段（一主一次）。
【输入】

世界设定：{{world_setting}}
【要求】
1. classification.primary 与 secondary 从 race（种族）/ affiliation（归属）/ occupation（职业）中取两个不同字段，选最能区分人群、影响调度立场的两个。
2. race：level1 是种族大类（如人族/妖族），level2 是该大类下的次级种族（如汉族/猫妖）；affiliation 的 level1 是归属、势力、组织、村落、家族，level2 留空；occupation 的 level1 是具体职业或身份，level2 留空。
3. 每个字段值都要标注 source 出处（引用世界观的具体字段或段落），禁止捏造与该世界矛盾的内容（古代或奇幻世界不得出现现代职业）。
4. 规模：race 一级 3–10 个、每个下二级 2–8 个；affiliation 4–15 个；occupation 5–30 个；名称贴合时代与氛围，具体可辨。
【输出】
只输出一个 JSON 对象：
{
  "classification": { "primary": "主分类字段", "secondary": "次分类字段" },
  "fields": {
    "race": [ { "level1": "种族大类", "level2": "次级种族", "source": "出处" } ],
    "affiliation": [ { "level1": "归属/势力/组织/村落/家族名", "level2": "", "source": "出处" } ],
    "occupation": [ { "level1": "职业/身份名", "level2": "", "source": "出处" } ]
  }
}
