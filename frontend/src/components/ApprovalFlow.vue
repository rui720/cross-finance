<script setup>
// 审批流展示组件：横向展示审批节点与状态
import { computed } from 'vue'

const props = defineProps({
  // 节点列表：[{ name, role, status, time, comment }]
  // status: done(已通过) / current(当前) / pending(待处理) / rejected(已驳回)
  nodes: {
    type: Array,
    default: () => []
  }
})

const statusMap = {
  done: { label: '已通过', color: '#67c23a', icon: 'Check' },
  current: { label: '处理中', color: '#409eff', icon: 'Loading' },
  pending: { label: '待处理', color: '#909399', icon: 'Clock' },
  rejected: { label: '已驳回', color: '#f56c6c', icon: 'Close' }
}

const displayNodes = computed(() =>
  props.nodes.map((n) => ({
    ...n,
    meta: statusMap[n.status] || statusMap.pending
  }))
)
</script>

<template>
  <div class="approval-flow">
    <div v-for="(node, index) in displayNodes" :key="index" class="flow-node">
      <div class="node-circle" :style="{ backgroundColor: node.meta.color }">
        <el-icon><component :is="node.meta.icon" /></el-icon>
      </div>
      <div class="node-content">
        <div class="node-name">{{ node.name }}</div>
        <div class="node-role">{{ node.role }}</div>
        <div class="node-status" :style="{ color: node.meta.color }">{{ node.meta.label }}</div>
        <div v-if="node.time" class="node-time">{{ node.time }}</div>
        <div v-if="node.comment" class="node-comment">{{ node.comment }}</div>
      </div>
      <div v-if="index < displayNodes.length - 1" class="node-line" :style="{
        backgroundColor: node.status === 'done' ? '#67c23a' : '#dcdfe6'
      }"></div>
    </div>
  </div>
</template>

<style scoped>
.approval-flow {
  display: flex;
  align-items: flex-start;
  padding: 16px 0;
  overflow-x: auto;
}
.flow-node {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.node-circle {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  flex-shrink: 0;
}
.node-content {
  margin: 0 12px;
  min-width: 120px;
}
.node-name {
  font-weight: 600;
  color: #1f2937;
}
.node-role {
  font-size: 12px;
  color: #6b7280;
  margin-top: 2px;
}
.node-status {
  font-size: 12px;
  margin-top: 4px;
}
.node-time {
  font-size: 12px;
  color: #9ca3af;
  margin-top: 2px;
}
.node-comment {
  font-size: 12px;
  color: #6b7280;
  margin-top: 4px;
  max-width: 160px;
  word-break: break-all;
}
.node-line {
  width: 60px;
  height: 2px;
  margin: 17px 0;
}
</style>
