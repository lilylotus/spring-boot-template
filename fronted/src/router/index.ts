import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由表：用户新增与编辑均由列表页弹窗承载，审批工作与流程配置使用独立页面。
 * - /users 用户列表
 * - /approvals 审批中心
 * - /approval-settings 审批配置
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/users',
    },
    {
      path: '/users',
      name: 'user-list',
      component: () => import('../views/UserListView.vue'),
    },
    {
      path: '/approvals',
      name: 'approval-center',
      component: () => import('../views/ApprovalCenterView.vue'),
    },
    {
      path: '/approval-settings',
      name: 'approval-settings',
      component: () => import('../views/ApprovalSettingsView.vue'),
    },
  ],
})

export default router
