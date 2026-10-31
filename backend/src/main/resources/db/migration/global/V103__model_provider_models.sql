-- 供应商下的模型清单（JSON 数组；一个供应商可挂多个模型，各自标注 文本/向量/决策 角色）
--
-- 为什么用 JSON 列而不是子表：供应商配置是"整行读、整行写"的小对象（单用户通常几条供应商、几十个模型），
-- 而 actor_model_provider 是"目录库 + 世界库 + 镜像同步 + .holzyn 脱敏导出"四套机制都覆盖的表；
-- 新增子表要同时改两库 schema、注册表清单、镜像同步与归档脱敏，收益不抵风险。
-- 读路径对 NULL 做了旧字段推导（model/embedding_model/purpose），因此老数据无需回填。
alter table actor_model_provider add column models_json TEXT;
