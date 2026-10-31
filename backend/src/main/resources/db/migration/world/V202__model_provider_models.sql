-- 供应商下的模型清单（与目录库 V103 同构；世界库里的项目级供应商同样可挂多个模型）
alter table actor_model_provider add column models_json TEXT;
