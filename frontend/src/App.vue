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
//   FINANCE   数据底座 + 业财核算(含模型配置) + 资金风控(付款+审批) + 智能决策
//   APPROVER  资金风控(审批-通过/驳回) + 智能决策
//   CASHIER   资金风控(审批-标记已付款) + 智能决策
//   OPERATOR  数据底座(只读) + 业财核算(只读,无模型配置) + 资金风控(付款) + 智能决策
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
      { title: '银行流水对账', path: '/data/bank-reconciliation', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
    ]
  },
  {
    title: '业财核算', icon: 'Histogram', roles: ['ADMIN', 'FINANCE', 'OPERATOR'], children: [
      { title: '利润报表', path: '/accounting/profit-report', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '分摊模型配置', path: '/accounting/model-config', roles: ['ADMIN', 'FINANCE'] }
    ]
  },
  {
    title: '资金风控', icon: 'Wallet', roles: ['ADMIN', 'FINANCE', 'APPROVER', 'CASHIER', 'OPERATOR'], children: [
      { title: '付款申请', path: '/fund/payment-apply', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] },
      { title: '审批工作台', path: '/fund/approval-center', roles: ['ADMIN', 'APPROVER', 'CASHIER'] }
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

// 角色中文标签（扩充到 6 种）
const roleLabels = {
  ADMIN: '管理员',
  FINANCE: '财务',
  APPROVER: '审批经理',
  CASHIER: '出纳',
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
