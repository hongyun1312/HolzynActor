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
  <div class="it-tab">
    <div class="it-filters">
      <el-select v-model="holder" filterable placeholder="选择角色 / 货架" class="it-f-holder" @change="loadHolder">
        <el-option v-for="h in holders" :key="h.holderRef"
          :label="`${h.name}（${kindLabel(h.kind)} · ${h.totalQty} 件）`" :value="h.holderRef" />
      </el-select>
      <el-button @click="reloadAll">
        <el-icon><HIcon name="Refresh" /></el-icon>&nbsp;刷新
      </el-button>
      <el-button type="primary" plain :loading="provisioning" @click="doProvision">
        <el-icon><HIcon name="Box" /></el-icon>&nbsp;补发开局供给
      </el-button>
      <span class="it-spacer"></span>
      <span class="it-count">{{ holders.length }} 个持有者</span>
    </div>

    <div v-if="!holders.length && !loading" class="it-empty">
      <p>这个世界还没有任何物品。</p>
      <p class="it-dim">
        物品域可能没开（<code>HOLOZYN_ACTOR_ITEM_ENABLED=true</code>），或者还没跑过开局供给。
        点「补发开局供给」可以按职业与店铺幂等补一次。
      </p>
    </div>

    <div v-else class="it-grid">
      <section class="it-panel">
        <header class="it-panel-head">
          <span>随身物品</span>
          <span class="it-dim">{{ items.length }} 层 · 共 {{ totalQty }} 件</span>
        </header>
        <div v-loading="loading" class="it-panel-body">
          <el-table :data="items" height="100%" row-key="rowKey" empty-text="身上空空的，什么也没带">
            <el-table-column label="物品" min-width="150">
              <template #default="{ row }">
                <span class="it-name">{{ row.name }}</span>
                <el-tag v-if="row.weaponLike" size="small" effect="plain" class="it-mini">武器</el-tag>
                <el-tag v-else-if="row.foodLike" size="small" type="success" effect="plain" class="it-mini">吃的</el-tag>
                <el-tag v-else-if="row.drinkLike" size="small" type="info" effect="plain" class="it-mini">喝的</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="70" align="right">
              <template #default="{ row }">{{ row.quantity }}</template>
            </el-table-column>
            <el-table-column label="成色" width="120" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="condTag(row.condition)" effect="light">
                  {{ row.conditionLabel }} {{ row.condition }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="分类" width="88" align="center">
              <template #default="{ row }">{{ row.category || '—' }}</template>
            </el-table-column>
            <el-table-column label="槽位" width="72" align="center">
              <template #default="{ row }">{{ row.equipSlot ? slotLabel(row.equipSlot) : '—' }}</template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <section class="it-panel">
        <header class="it-panel-head">
          <span>装备</span>
          <span class="it-dim">{{ equipmentEnabled ? '手上 / 身上' : '损耗域未启用' }}</span>
        </header>
        <div class="it-slots">
          <div v-for="s in ['hand', 'body']" :key="s" class="it-slot">
            <div class="it-slot-label">{{ slotLabel(s) }}</div>
            <div class="it-slot-value">
              <template v-if="equippedOf(s)">
                <span class="it-name">{{ equippedOf(s).name }}</span>
                <el-tag size="small" :type="condTag(equippedOf(s).condition)" effect="light" class="it-mini">
                  {{ equippedOf(s).conditionLabel }} {{ equippedOf(s).condition }}
                </el-tag>
              </template>
              <span v-else class="it-dim">空着</span>
            </div>
          </div>
        </div>

        <header class="it-panel-head it-panel-head-gap">
          <span>最近流水</span>
          <span class="it-dim">新 → 旧</span>
        </header>
        <div class="it-panel-body">
          <el-table :data="ledger" height="100%" row-key="id" empty-text="还没有进出记录">
            <el-table-column label="物品" width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ row.name }}</template>
            </el-table-column>
            <el-table-column label="增减" width="72" align="right">
              <template #default="{ row }">
                <span :class="row.qtyDelta >= 0 ? 'it-plus' : 'it-minus'">
                  {{ row.qtyDelta > 0 ? '+' : '' }}{{ row.qtyDelta }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="原因" width="82" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ reasonLabel(row.reason) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="方向" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="it-mono">{{ row.fromRef || '产出' }} → {{ row.toRef || '消耗' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
/**
 * 物品与装备 Tab（对接欠下的物品面板）。
 *
 * <p>职责：让玩家第一次「看得见」物品域——谁身上有什么、成色多少、手上握着什么、
 * 最近进出过什么。此前这些只存在于数据库里，玩家与前端都读不到。</p>
 *
 * <p>数据全部只读；唯一带副作用的按钮是「补发开局供给」（幂等，调后端 provision）。</p>
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchItemHolders, fetchItemHolder, fetchItemLedger, provisionItems } from '@/shared/api'

const props = defineProps({
  projectId: { type: Number, required: true }
})

const loading = ref(false)
const provisioning = ref(false)
const holders = ref([])
const holder = ref('')
const items = ref([])
const equipment = ref([])
const equipmentEnabled = ref(false)
const ledger = ref([])

const totalQty = computed(() => items.value.reduce((sum, i) => sum + (i.quantity || 0), 0))

onMounted(loadHolders)

async function loadHolders() {
  loading.value = true
  try {
    holders.value = (await fetchItemHolders(props.projectId)) || []
    if (!holder.value && holders.value.length) {
      holder.value = holders.value[0].holderRef
    }
    if (holder.value) {
      await loadHolder()
    }
  } catch (e) {
    ElMessage.error(e.message || '物品清单加载失败')
  } finally {
    loading.value = false
  }
}

async function loadHolder() {
  if (!holder.value) return
  loading.value = true
  try {
    const detail = await fetchItemHolder(props.projectId, holder.value)
    items.value = detail.items || []
    equipment.value = detail.equipment || []
    equipmentEnabled.value = !!detail.equipmentEnabled
    ledger.value = (await fetchItemLedger(props.projectId, { holder: holder.value, limit: 50 })) || []
  } catch (e) {
    ElMessage.error(e.message || '背包加载失败')
  } finally {
    loading.value = false
  }
}

async function reloadAll() {
  holder.value = ''
  await loadHolders()
}

async function doProvision() {
  provisioning.value = true
  try {
    const report = await provisionItems(props.projectId)
    if (report.skipped) {
      ElMessage.warning(`未发放：${report.skipped}（物品域或供给开关关着）`)
    } else {
      ElMessage.success(`发放 ${report.grants} 条（角色 ${report.characters} / 地点 ${report.locations} / 穿上 ${report.worn}）`)
    }
    await reloadAll()
  } catch (e) {
    ElMessage.error(e.message || '补发失败')
  } finally {
    provisioning.value = false
  }
}

function equippedOf(slot) {
  return equipment.value.find((e) => e.slot === slot) || null
}

function slotLabel(slot) {
  return slot === 'hand' ? '手上' : slot === 'body' ? '身上' : slot
}

function kindLabel(kind) {
  return kind === 'location' ? '货架' : kind === 'npc' ? '角色' : '其它'
}

function condTag(condition) {
  if (condition >= 80) return 'success'
  if (condition >= 40) return 'warning'
  return 'danger'
}

function reasonLabel(reason) {
  const map = {
    spawn: '开局', initial: '初始', craft: '制作', loot: '拾取', trade: '交易',
    gift: '赠予', consume: '消耗', drink: '饮用', decay: '腐坏', wear: '磨损',
    break: '折断', repair: '修理'
  }
  return map[reason] || reason || '—'
}
</script>

<style scoped>
.it-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  gap: 12px;
}

.it-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.it-f-holder { width: 320px; }
.it-spacer { flex: 1; }
.it-count { color: var(--text-tertiary); font-size: 13px; }
.it-dim { color: var(--text-tertiary); font-size: 12px; }
.it-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.it-name { color: var(--text-primary); font-weight: 500; }
.it-mini { margin-left: 6px; }
.it-plus { color: #2f9e44; }
.it-minus { color: #c92a2a; }

.it-empty {
  padding: 32px;
  border: 1px dashed var(--border-l2);
  border-radius: 12px;
  color: var(--text-secondary);
  line-height: 1.7;
}

.it-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 12px;
  flex: 1;
  min-height: 0;
}

.it-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--border-l1);
  border-radius: 12px;
  background: var(--bg-layer-1);
  overflow: hidden;
}

.it-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  font-weight: 600;
  color: var(--text-regular);
  border-bottom: 1px solid var(--border-l1);
}

.it-panel-head-gap { margin-top: 8px; border-top: 1px solid var(--border-l1); }
.it-panel-body { flex: 1; min-height: 0; }

.it-slots {
  display: flex;
  gap: 12px;
  padding: 12px;
}

.it-slot {
  flex: 1;
  border: 1px solid var(--border-l1);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--bg-layer-2);
}

.it-slot-label { color: var(--text-tertiary); font-size: 12px; margin-bottom: 4px; }
.it-slot-value { color: var(--text-primary); }
</style>
