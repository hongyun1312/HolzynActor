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

package com.holzyn.actor.domain.settings.service;

import com.holzyn.actor.domain.settings.entity.ModelProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「是否已接上模型」判定断言（世界画廊「还没接上模型」横幅的判据）。
 *
 * <p>缺陷背景：横幅此前只看前端 localStorage 的「关掉了」标记，完全没查配置，
 * 用户填完 API 再进画廊依旧提示。判定改为后端统一给结论后，这里钉住四种容易漏掉的边界：
 * 停用的供应商不算、没 Key 不算、只有向量模型不算、只配了决策模型也算（决策模型能跑世界）。</p>
 */
class AiReadinessTest {

    /** 造一个「看起来配好了」的供应商：启用 + baseUrl + Key + 一个默认文本模型 */
    private ModelProvider provider() {
        ModelProvider p = new ModelProvider();
        p.setUserId(1L);
        p.setName("豆包");
        p.setBaseUrl("https://ark.cn-beijing.volces.com/api/v3");
        p.setApiKeyCipher("enc:abcdef");
        p.setEnabled(1);
        p.setModelsJson("""
                [{"id":"m1","name":"doubao-seed","role":"text","isDefault":1,"enabled":1}]
                """);
        return p;
    }

    @Test
    @DisplayName("启用 + 有 Key + 有文本模型 → 已接上")
    void readyWithTextModel() {
        assertTrue(ModelApiService.usableForAi(provider()));
    }

    @Test
    @DisplayName("供应商被停用 → 不算已接上")
    void disabledProviderIsNotReady() {
        ModelProvider p = provider();
        p.setEnabled(0);
        assertFalse(ModelApiService.usableForAi(p));
    }

    @Test
    @DisplayName("没有 baseUrl / 没有 Key → 不算已接上")
    void missingBaseUrlOrKeyIsNotReady() {
        ModelProvider noUrl = provider();
        noUrl.setBaseUrl("  ");
        assertFalse(ModelApiService.usableForAi(noUrl));

        ModelProvider noKey = provider();
        noKey.setApiKeyCipher(null);
        assertFalse(ModelApiService.usableForAi(noKey));
    }

    @Test
    @DisplayName("只有向量模型 → 不算已接上（世界跑不起来）")
    void embeddingOnlyIsNotReady() {
        ModelProvider p = provider();
        p.setModelsJson("""
                [{"id":"m2","name":"bge-m3","role":"embedding","isDefault":1,"enabled":1}]
                """);
        assertFalse(ModelApiService.usableForAi(p));
    }

    @Test
    @DisplayName("只配了决策模型 → 算已接上（决策模型能驱动世界）")
    void decisionOnlyIsReady() {
        ModelProvider p = provider();
        p.setModelsJson("""
                [{"id":"m3","name":"judge-model","role":"decision","isDefault":1,"enabled":1}]
                """);
        assertTrue(ModelApiService.usableForAi(p));
    }

    @Test
    @DisplayName("文本模型被禁用且无其它模型 → 不算已接上")
    void allModelsDisabledIsNotReady() {
        ModelProvider p = provider();
        p.setModelsJson("""
                [{"id":"m1","name":"doubao-seed","role":"text","isDefault":0,"enabled":0}]
                """);
        assertFalse(ModelApiService.usableForAi(p));
    }

    @Test
    @DisplayName("旧库（无 models_json，只有 model 字段）→ 按旧字段推导，仍算已接上")
    void legacyRowStillReady() {
        ModelProvider p = provider();
        p.setModelsJson(null);
        p.setPurpose("chat");
        p.setModel("qwen-plus");
        p.setEmbeddingEnabled(0);
        assertTrue(ModelApiService.usableForAi(p));
    }
}
