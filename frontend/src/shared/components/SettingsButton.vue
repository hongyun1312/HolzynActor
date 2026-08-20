<template>
  <!-- 统一「设置」入口按钮：plain 描边 + 设置图标 + 可定制文案；点击行为由使用方通过 @click 注入 -->
  <el-button plain>
    <el-icon><HIcon name="Setting" /></el-icon>&nbsp;{{ label }}
  </el-button>
</template>

<script setup>
/**
 * 统一的「设置」入口按钮（项目空间外的全局设置弹窗入口，全站样式一致）。
 * <p>背景：此前首页操作区 / 空态 / 新增项目页等「设置」按钮大小、主题、图标各异（次对接文档 §15），
 * 本组件收敛为同一种视觉（plain 描边 + 齿轮图标 + 默认尺寸），保证各入口 UI 统一。</p>
 * <p>用法：点击后由父组件注入 @click（通常为打开 GlobalSettingsDialog 弹窗，零路由依赖）；</p>
 * <p>说明：顶部导航栏（浅灰导航 ConsoleTopbar）内的「设置」仍是顶栏文字链接（顶栏自有风格），不在此组件范围内。</p>
 */
defineProps({
  /** 按钮文案（默认「设置」；空态引导可传「配置 API」） */
  label: { type: String, default: '设置' }
})
</script>
