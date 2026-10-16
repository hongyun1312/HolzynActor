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
  <div class="dlg-group-bar" data-role="group-bar">
    <div class="dgb-scene">
      <span class="dgb-label">📍</span>
      <span class="dgb-loc" :title="'场景地点 ID：' + (session?.locationId ?? '—')">
        {{ session?.locationName || '未选地点' }}
      </span>
      <el-button size="small" text type="primary" @click="openChangeLoc">换场</el-button>
    </div>

    <div class="dgb-members">
      <span v-if="!members.length" class="dgb-empty">暂无成员，请先换场或拉人</span>
      <span v-for="m in members" :key="m.characterRef" class="dgb-member" :class="m.source">
        {{ m.characterName || m.characterRef }}
        <span class="dgb-src" :title="m.source === 'manual' ? '拉人加入（换场保留）' : '同地点在场（换场重算）'">
          {{ m.source === 'manual' ? '拉' : '在' }}
        </span>
        <button type="button" class="dgb-x" title="移除成员" @click="remove(m)">×</button>
      </span>
      <el-button size="small" text type="primary" @click="openInvite">拉人</el-button>
    </div>

    <!-- 换场对话框 -->
    <el-dialog v-model="changeLoc.visible" title="换场（重算在场成员）" width="420px" append-to-body>
      <p class="dgb-tip">换场后：同地点在场角色重新入群；拉人加入的成员保留。</p>
      <el-select v-model="changeLoc.locationId" filterable placeholder="选择新地点（留空自动选在场最多的地点）" style="width: 100%">
        <el-option :value="null" label="自动（在场最多的地点）" />
        <el-option v-for="l in locations" :key="l.locationId" :label="l.locationName" :value="l.locationId" />
      </el-select>
      <template #footer>
        <el-button @click="changeLoc.visible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="doChangeLoc">确认换场</el-button>
      </template>
    </el-dialog>

    <!-- 拉人对话框 -->
    <el-dialog v-model="invite.visible" title="拉人入群" width="420px" append-to-body>
      <el-select v-model="invite.characterRef" filterable placeholder="选择要拉入的角色" style="width: 100%">
        <el-option v-for="c in inviteCandidates" :key="'npc:' + c.id" :label="c.name" :value="'npc:' + c.id" />
      </el-select>
      <template #footer>
        <el-button @click="invite.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!invite.characterRef" :loading="busy" @click="doInvite">拉入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 对谈群聊场景条（M5）。
 * <p>职责：群聊会话的场景化成员管理——地点展示/换场（重算在场）、拉人（manual 标记）、
 * 成员列表（在场/拉人徽标）与移除。只做展示与事件转发，数据与 API 由 DialogueView 统一持有。</p>
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  /** 当前群聊会话（含 locationName/locationId） */
  session: { type: Object, default: null },
  /** 群聊成员列表（DialogueGroupMemberVO[]） */
  members: { type: Array, default: () => [] },
  /** 可选地点列表 [{locationId, locationName}] */
  locations: { type: Array, default: () => [] },
  /** 项目角色列表（拉人候选） */
  characters: { type: Array, default: () => [] }
})
const emit = defineEmits(['change-location', 'invite', 'remove', 'refresh'])

const busy = ref(false)
const changeLoc = reactive({ visible: false, locationId: null })
const invite = reactive({ visible: false, characterRef: null })

/** 拉人候选：未在群中的角色（去重 by id） */
const inviteCandidates = computed(() => {
  const inGroup = new Set(props.members.map(m => m.characterRef))
  return props.characters.filter(c => !inGroup.has('npc:' + c.id))
})

function openChangeLoc() {
  changeLoc.locationId = props.session?.locationId ?? null
  changeLoc.visible = true
}

async function doChangeLoc() {
  busy.value = true
  try {
    await emit('change-location', changeLoc.locationId)
    changeLoc.visible = false
    ElMessage.success('已换场，在场成员已更新')
  } catch (e) {
    ElMessage.error(e.message || '换场失败')
  } finally {
    busy.value = false
  }
}

function openInvite() {
  invite.characterRef = null
  invite.visible = true
}

async function doInvite() {
  busy.value = true
  try {
    await emit('invite', invite.characterRef)
    invite.visible = false
    ElMessage.success('已拉入群聊')
  } catch (e) {
    ElMessage.error(e.message || '拉人失败')
  } finally {
    busy.value = false
  }
}

async function remove(m) {
  try {
    await emit('remove', m.characterRef)
  } catch (e) {
    ElMessage.error(e.message || '移除失败')
  }
}
</script>

<style scoped>
.dlg-group-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 16px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-layer-1);
  flex-wrap: wrap;
}
.dgb-scene, .dgb-members { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.dgb-label { font-size: 0.8rem; }
.dgb-loc { font-size: 0.8rem; font-weight: 600; color: var(--text-primary); }
.dgb-empty { font-size: 0.74rem; color: var(--text-tertiary); }
.dgb-member {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 6px 2px 8px; border-radius: 999px;
  font-size: 0.74rem; color: var(--text-primary);
  border: 1px solid var(--border-l2); background: var(--bg-layer-2);
}
.dgb-member.manual { border-color: color-mix(in srgb, var(--state-primary) 40%, transparent); }
.dgb-src { font-size: 0.64rem; opacity: 0.75; }
.dgb-x {
  border: none; background: transparent; cursor: pointer;
  color: var(--text-tertiary); font-size: 0.9rem; line-height: 1;
  padding: 0 2px; border-radius: 4px;
}
.dgb-x:hover { color: #f56c6c; }
.dgb-tip { font-size: 0.76rem; color: var(--text-secondary); margin: 0 0 8px; }
</style>
