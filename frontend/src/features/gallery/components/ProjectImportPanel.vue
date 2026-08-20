<template>
  <div class="project-import-panel">
    <!-- 顶部说明 -->
    <div class="page-header">
      <div>
        <div class="page-title">项目导入</div>
        <div class="page-desc">导入 .holzyn 项目包（ZIP 容器，含世界观/角色/对话/知识/记忆/设置），导入后完整还原项目；支持可选密码加密包。</div>
      </div>
    </div>

    <div class="import-card tech-card">
      <el-alert type="info" :closable="false" show-icon
        title="导入校验：包版本/完整性 → 幂等检测（projectUid 已存在则拒绝）→ 确认导入（含 API 配置时按密码解密，失败则置空提示重新配置）。" />
      <el-upload drag :auto-upload="false" :limit="1" accept=".holzyn,.zip" :on-change="onFileChange" :on-remove="() => (file = null)" class="holzyn-upload">
        <el-icon class="el-icon--upload"><HIcon name="UploadFilled" /></el-icon>
        <div class="el-upload__text">拖拽 .holzyn 文件到此处，或 <em>点击选择</em></div>
      </el-upload>
      <el-form label-width="90px" style="margin-top: 12px; max-width: 480px">
        <el-form-item label="密码（可选）">
          <el-input v-model="password" type="password" show-password placeholder="包内含密码加密的 API 配置时填写" />
        </el-form-item>
      </el-form>
      <div class="import-foot">
        <el-button type="primary" :loading="importing" :disabled="!file" @click="doImport">
          <el-icon><HIcon name="UploadFilled" /></el-icon>&nbsp;校验并导入
        </el-button>
        <span class="import-tip">导入成功后进入项目空间仪表盘</span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 项目导入面板（2026-08-19 新建项目页重构：布局适配）。
 * <p>职责：导入 .holzyn 项目包（完整还原项目），成功后进入项目空间仪表盘。</p>
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { importProjectPackage } from '@/shared/api'

const router = useRouter()

const file = ref(null)
const password = ref('')
const importing = ref(false)

/** 选择 .holzyn 文件 */
function onFileChange(f) {
  file.value = f.raw
}

/** 校验并导入 .holzyn 包（幂等拦截 + 密码可选） */
async function doImport() {
  if (!file.value) return
  importing.value = true
  try {
    const res = await importProjectPackage(file.value, password.value || undefined)
    ElMessage.success(`项目「${res.name}」导入成功（角色 ${res.characterCount} 位）`)
    router.replace(`/project/${res.projectId}/dashboard`)
  } catch (e) {
    ElMessage.error(e.message || '导入失败')
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.project-import-panel { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; gap: 14px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; }
.page-title { font-size: 1.25rem; font-weight: 700; color: var(--text-primary); }
.page-desc { font-size: 0.82rem; color: var(--text-secondary); margin-top: 4px; }
.import-card { padding: 22px 26px; max-width: 760px; }
.holzyn-upload { margin-top: 16px; }
.import-foot { margin-top: 16px; display: flex; align-items: center; gap: 12px; }
.import-tip { font-size: 0.78rem; color: var(--text-secondary); }
</style>
