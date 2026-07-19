<script setup>
// 403 无权限访问页面
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store'

const router = useRouter()
const userStore = useUserStore()

function goHome() {
  router.push('/ai/dashboard')
}
</script>

<template>
  <div class="forbidden-container">
    <div class="forbidden-box">
      <el-icon class="forbidden-icon"><Lock /></el-icon>
      <h2>403 无权限访问</h2>
      <p class="tip">您当前的角色（{{ userStore.roles.join('、') || '未分配' }}）无权访问此页面</p>
      <el-button type="primary" @click="goHome">返回首页</el-button>
    </div>
  </div>
</template>

<style scoped>
.forbidden-container {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.forbidden-box {
  text-align: center;
}
.forbidden-icon {
  font-size: 64px;
  color: #f56c6c;
  margin-bottom: 16px;
}
.forbidden-box h2 {
  color: #1f2937;
  margin-bottom: 8px;
}
.tip {
  color: #6b7280;
  margin-bottom: 24px;
}
</style>
