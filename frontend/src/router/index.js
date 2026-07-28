// 路由定义：路径与组件映射
// meta.roles 标注允许访问的角色，未标注表示所有登录用户可访问
import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/utils/auth'
import { useUserStore } from '@/store'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    redirect: '/ai/dashboard'
  },
  {
    path: '/system/user',
    name: 'UserManage',
    component: () => import('@/views/system/UserManage.vue'),
    meta: { title: '用户与权限管理', roles: ['ADMIN'] }
  },
  {
    path: '/system/audit',
    name: 'AuditLog',
    component: () => import('@/views/system/AuditLog.vue'),
    meta: { title: '操作审计日志', roles: ['ADMIN'] }
  },
  {
    path: '/data/bill-import',
    name: 'BillImport',
    component: () => import('@/views/data/BillImport.vue'),
    meta: { title: '账单导入与清洗', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/data/exchange-rate-import',
    name: 'ExchangeRateImport',
    component: () => import('@/views/data/ExchangeRateImport.vue'),
    meta: { title: '历史汇率导入', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/data/bank-reconciliation',
    name: 'BankReconciliation',
    component: () => import('@/views/data/BankReconciliation.vue'),
    meta: { title: '银行流水导入', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/data/cost-import',
    name: 'ExtraCostImport',
    component: () => import('@/views/data/ExtraCostImport.vue'),
    meta: { title: '额外费用导入', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/data/data-management',
    name: 'DataManagement',
    component: () => import('@/views/data/DataManagement.vue'),
    meta: { title: '数据管理', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/accounting/profit-detail',
    name: 'ProfitDetail',
    component: () => import('@/views/accounting/ProfitDetail.vue'),
    meta: { title: '利润明细', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/accounting/profit-trend',
    name: 'ProfitTrend',
    component: () => import('@/views/accounting/ProfitTrend.vue'),
    meta: { title: '趋势分析', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/accounting/cost-structure',
    name: 'CostStructure',
    component: () => import('@/views/accounting/CostStructure.vue'),
    meta: { title: '成本结构', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/accounting/profit-diagnosis',
    name: 'ProfitDiagnosis',
    component: () => import('@/views/accounting/ProfitDiagnosis.vue'),
    meta: { title: '利润诊断', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/accounting/profit-aggregate',
    name: 'ProfitAggregate',
    component: () => import('@/views/accounting/ProfitAggregate.vue'),
    meta: { title: '多维聚合', roles: ['ADMIN', 'FINANCE', 'OPERATOR'] }
  },
  {
    path: '/ai/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/ai/Dashboard.vue'),
    meta: { title: '经营全景驾驶舱' }
  },
  {
    path: '/ai/advisor',
    name: 'AiAdvisor',
    component: () => import('@/views/ai/AiAdvisor.vue'),
    meta: { title: 'AI 合规顾问' }
  },
  {
    // 403 无权限页面
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/Forbidden.vue'),
    meta: { title: '无权限访问' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫：未登录跳转登录页 + 角色权限校验
router.beforeEach((to, from, next) => {
  const token = getToken()
  if (to.path === '/login') {
    next()
    return
  }
  if (!token) {
    next('/login')
    return
  }
  // 校验路由所需角色
  const requiredRoles = to.meta?.roles
  if (requiredRoles && requiredRoles.length > 0) {
    const userStore = useUserStore()
    const hasPermission = userStore.roles.some(r => requiredRoles.includes(r))
    if (!hasPermission) {
      next('/403')
      return
    }
  }
  next()
})

export default router
