<!--
  Copyright (c) 2026 HONGYUN
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  SPDX-License-Identifier: Apache-2.0
-->
<template>
  <!-- 全局页统一外壳（batch②）：不再自绘满屏容器与背景，改为 AppShell global + 居中内容列 -->
  <AppShell mode="global" brand-label="本地账户" :show-sidebar="false" :show-back="false">
    <div class="content-col account-page">
      <PageHeader
        title="本地账户设置"
        desc="这些信息将作为「NPC 认识你的档案」注入对话，影响角色对你的了解与回应。所有字段选填。"
      />
      <div class="ac-card">
        <!-- 与首次引导「我的形象」页字段一致的本地账户表单（基本信息 + NPC 眼中的你 + 玩家 persona 六维 + AI 补充生成） -->
        <LocalAccountForm @saved="onSaved" />
      </div>
    </div>
  </AppShell>
</template>

<script setup>
/**
 * 本地账户设置页（全局 /account，独立于项目空间）。
 * <p>职责：随时编辑本地账户（昵称/头像/签名/结构化档案/个人档案/对谈玩家 persona 六维），
 * 供顶栏账户菜单与设置页入口使用；表单字段与首次引导「我的形象」页完全一致（共享 LocalAccountForm）。</p>
 * <p>数据：LocalAccountForm（useAccountStore + /api/player-profile）。</p>
 */
import { useRouter } from 'vue-router'
import AppShell from '@/shared/layout/AppShell.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import LocalAccountForm from '@/shared/components/LocalAccountForm.vue'

const router = useRouter()

/** 保存成功 → 返回上一页（表单内已提示成功并回填最新值） */
function onSaved() {
  router.back()
}
</script>

<style scoped>
/* 页面容器与背景由外壳提供；卡片居中由 .content-col + 卡片自身 max-width 决定 */
.account-page { display: flex; flex-direction: column; align-items: center; }
.ac-card { width: 100%; max-width: var(--page-w-form, 720px); background: var(--bg-layer-1); border-radius: 18px; box-shadow: 0 10px 32px rgba(24, 50, 100, 0.10); padding: 32px 40px; height: fit-content; }
/* V2.0 批次二：断点 640 → 1024（全站三档；<1024 为单栏兜底档） */
@media (max-width: 1024px) { .ac-card { padding: 24px 20px; } }
</style>
