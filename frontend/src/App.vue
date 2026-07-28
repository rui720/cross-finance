<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store'
import { removeToken } from '@/utils/auth'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 侧边栏菜单（与后端模块对应）
// roles 标注允许查看的角色，未标注表示所有登录用户可见
// 权限矩阵：
//   ADMIN     全部菜单
//   FINANCE   数据底座 + 业财核算 + 智能决策
//   OPERATOR  数据底座(只读) + 业财核算(只读) + 智能决策
//   EMPLOYEE  智能决策（驾驶舱 + AI 顾问）
const allMenus = [
  {
    title: '系统管理', icon: 'Setting', roles: ['ADMIN'], children: [
      { title: '用户与权限', path: '/system/user', roles: ['ADMIN'] },
      { title: '操作审计日志', path: '/system/audit', roles: ['ADMIN'] }
    ]
  },
  {
    title: '数据底座', icon: 'Coin', roles: ['ADMIN', 'FINANCE', 'OPERATOR'], children: [
      { title: '账单导入清洗', path: '/data/bill-import', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '额外费用导入', path: '/data/cost-import', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '历史汇率导入', path: '/data/exchange-rate-import', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '银行流水导入', path: '/data/bank-reconciliation', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '数据管理', path: '/data/data-management', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
    ]
  },
  {
    title: '业财核算', icon: 'Histogram', roles: ['ADMIN', 'FINANCE', 'OPERATOR'], children: [
      { title: '利润明细', path: '/accounting/profit-detail', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '趋势分析', path: '/accounting/profit-trend', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '成本结构', path: '/accounting/cost-structure', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '利润诊断', path: '/accounting/profit-diagnosis', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '多维聚合', path: '/accounting/profit-aggregate', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
    ]
  },
  {
    title: '智能决策', icon: 'DataAnalysis', children: [
      { title: '经营驾驶舱', path: '/ai/dashboard' },
      { title: 'AI 合规顾问', path: '/ai/advisor' }
    ]
  }
]

// 检查当前用户是否有权限访问某菜单项
function hasPermission(itemRoles) {
  if (!itemRoles || itemRoles.length === 0) return true
  return userStore.roles.some(r => itemRoles.includes(r))
}

// 按角色过滤后的菜单
const menus = computed(() => {
  return allMenus
    .filter(m => hasPermission(m.roles))
    .map(m => ({
      ...m,
      children: (m.children || []).filter(c => hasPermission(c.roles))
    }))
    .filter(m => m.children.length > 0)
})

// 角色中文标签
const roleLabels = {
  ADMIN: '管理员',
  FINANCE: '财务',
  OPERATOR: '运营',
  EMPLOYEE: '普通员工'
}

const activeMenu = computed(() => route.path)

function handleLogout() {
  removeToken()
  userStore.reset()
  router.push('/login')
}
</script>

<template>
  <router-view v-if="route.path === '/login'" />
  <el-container v-else class="layout">
    <!-- 侧边栏 -->
    <el-aside width="220px" class="sidebar">
      <div class="logo">跨境金融平台</div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#001529"
        text-color="#cbd5e1"
        active-text-color="#409eff"
      >
        <el-sub-menu v-for="m in menus" :key="m.title" :index="m.title">
          <template #title>
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.title }}</span>
          </template>
          <el-menu-item v-for="c in m.children" :key="c.path" :index="c.path">
            {{ c.title }}
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- 顶栏 -->
      <el-header class="header">
        <div class="header-title">{{ route.meta.title || '工作台' }}</div>
        <el-dropdown @command="handleLogout">
          <span class="user-info">
            <el-icon><User /></el-icon>
            <span style="margin-left: 6px">{{ userStore.username || '管理员' }}</span>
            <el-tag
              v-for="r in userStore.roles"
              :key="r"
              size="small"
              type="primary"
              effect="plain"
              style="margin-left: 6px"
            >
              {{ roleLabels[r] || r }}
            </el-tag>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <!-- 内容区 -->
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}
.sidebar {
  background-color: #001529;
  overflow-y: auto;
}
.logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
  border-bottom: 1px solid #1f2937;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fff;
  border-bottom: 1px solid #e5e7eb;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}
.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}
.user-info {
  display: flex;
  align-items: center;
  cursor: pointer;
  color: #4b5563;
}
.main {
  background-color: #f0f2f5;
  padding: 16px;
  overflow-y: auto;
}
</style>
