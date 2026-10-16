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
<!-- HolzynActor 前端根组件：所有页面直接渲染（登录/注册/控制台/管理后台均自带布局） -->
<template>
  <!-- 全局更新横幅（桌面端 U1）：发现新版本时窗口顶部轻量提示，不打断任何页面操作 -->
  <UpdateBanner />
  <router-view />
</template>

<script setup>
import UpdateBanner from '@/shared/components/UpdateBanner.vue'
</script>