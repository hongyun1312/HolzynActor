/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.holzyn.actor.domain.world.service;

import com.holzyn.actor.domain.world.config.MacroTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宏观社会演化 · 世界观模板服务。
 * <p>职责：为项目解析宏观世界观模板（{@link MacroTemplate} 双维度：文明形态 × 力量体系）：
 * ① <b>自动推导</b>——从世界观设定文本关键词打分判定（仙/灵/修/法→仙侠修仙、武/侠/江湖→
 * 武侠高武、城/市/网络→现代都市、星舰/未来/能源→科幻未来，兜底默认模板）；
 * ② <b>项目覆盖</b>——手动覆盖模板 id（POST /macro/template）后固定使用（记录 source=manual）；
 * ③ 结果随 MacroState 持久化（templateId 字段入快照 JSON），进程内缓存。</p>
 * <p>作用面： / 农耕硬编码的重构入口——引擎内核保持参数化（核心资源/计价单位/社区名/
 * 季节联动强度/科技维度名/事件叙事/极端事件名/初始议题全部由模板注入），模板即语义。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroTemplateService {

    /** 推导关键词权重表：模板 id → 关键词数组（命中计数加分） */
    private static final Map<String, String[]> DERIVE_KEYWORDS = new LinkedHashMap<>();
    static {
        DERIVE_KEYWORDS.put(MacroTemplate.ID_XIANXIA,
                new String[]{"仙", "灵", "修", "法", "术", "道", "妖", "精", "符", "阵", "丹", "庙"});
        DERIVE_KEYWORDS.put(MacroTemplate.ID_WUXIA,
                new String[]{"武", "侠", "内", "江", "门派", "帮派", "掌门", "江湖", "刀", "剑"});
        DERIVE_KEYWORDS.put(MacroTemplate.ID_MODERN,
                new String[]{"城", "市", "公司", "网络", "手机", "警察", "政府", "都市", "现代"});
        DERIVE_KEYWORDS.put(MacroTemplate.ID_FUTURE,
                new String[]{"星", "舰", "未来", "能源", "AI", "机器", "赛博", "宇宙", "虚拟"});
    }

    /** 项目覆盖记录：projectId → {templateId, source}（进程内存态；MacroState.templateId 为持久化权威） */
    private final Map<Long, String[]> overrides = new ConcurrentHashMap<>();

    /** 模板全集（内置 5 套；启动一次构建） */
    public static Map<String, MacroTemplate> templates() {
        return MacroTemplate.builtins();
    }

    /** 模板全集（实例入口）。 */
    public Map<String, MacroTemplate> all() {
        return MacroTemplate.builtins();
    }

    /** 按 id 取模板（未知 id 回退默认模板 xianxia）。 */
    public MacroTemplate of(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID);
        }
        return MacroTemplate.builtins().getOrDefault(templateId.trim(),
                MacroTemplate.builtins().get(MacroTemplate.DEFAULT_ID));
    }

    /** 项目模板（优先覆盖 → 默认模板；推导仅在无覆盖时由状态初始化触发）。 */
    public MacroTemplate forProject(Long projectId) {
        if (projectId != null) {
            String[] ov = overrides.get(projectId);
            if (ov != null) {
                return of(ov[0]);
            }
        }
        return of(MacroTemplate.DEFAULT_ID);
    }

    /** 项目是否有手动覆盖（source=manual）。 */
    public boolean isManual(Long projectId) {
        String[] ov = projectId == null ? null : overrides.get(projectId);
        return ov != null && "manual".equals(ov[1]);
    }

    /**
     * 手动覆盖项目模板（POST /macro/template；持久化权威=MacroState.templateId，
     * 本方法由 MacroStateService 落快照，进程缓存同步）。
     *
     * @param projectId 项目 ID
     *
     * @param templateId 模板 id（未知 id → 400 由调用方校验）
     *
     * @return 生效的模板
     */
    public MacroTemplate override(Long projectId, String templateId) {
        MacroTemplate tpl = of(templateId);
        if (!tpl.id().equals(templateId)) {
            throw new IllegalArgumentException("未知模板 id: " + templateId);
        }
        overrides.put(projectId, new String[]{tpl.id(), "manual"});
        log.info("[宏观模板] 手动覆盖: project={} template={}", projectId, tpl.id());
        return tpl;
    }

    /** 清除覆盖（回自动推导；主要测试用）。 */
    public void clearOverride(Long projectId) {
        overrides.remove(projectId);
    }

    /**
     * 从世界观设定文本推导模板（关键词打分；无文本/全未命中 → 默认模板——通用兜底）。
     *
     * @param worldSetting 世界观设定文本（可空）
     *
     * @return 推导出的模板 id（恒非空）
     */
    public String deriveFromSetting(String worldSetting) {
        if (worldSetting == null || worldSetting.isBlank()) {
            return MacroTemplate.DEFAULT_ID;
        }
        String best = MacroTemplate.DEFAULT_ID;
        int bestScore = 0;
        for (Map.Entry<String, String[]> e : DERIVE_KEYWORDS.entrySet()) {
            int score = 0;
            for (String kw : e.getValue()) {
                if (worldSetting.contains(kw)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        return best;
    }

    /** 模板 VO（读接口/调试前端用）。 */
    public Map<String, Object> templateVO(MacroTemplate tpl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tpl.id());
        m.put("name", tpl.name());
        m.put("civilization", tpl.civilization());
        m.put("power", tpl.power());
        m.put("coreResource", tpl.coreResource());
        m.put("currencyUnit", tpl.currencyUnit());
        m.put("priceUnit", tpl.priceUnit());
        m.put("communityName", tpl.communityName());
        m.put("seasonFactorScale", tpl.seasonFactorScale());
        m.put("techNames", tpl.techNames());
        m.put("extremeEventNames", tpl.extremeEventNames());
        m.put("initialIssues", tpl.initialIssues());
        return m;
    }
}
