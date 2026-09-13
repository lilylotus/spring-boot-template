<script setup lang="ts">
// 应用根组件：顶部提供主要业务导航，当前操作人选择器固定在右侧。
import OperatorSwitcher from './components/OperatorSwitcher.vue'
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-shell__header">
      <nav class="app-nav" aria-label="主要导航">
        <router-link class="app-nav__link" to="/users">用户管理</router-link>
        <router-link class="app-nav__link" to="/user-groups">用户组管理</router-link>
        <router-link class="app-nav__link" to="/approvals">审批中心</router-link>
        <router-link class="app-nav__link" to="/approval-settings">审批配置</router-link>
      </nav>
      <div class="app-shell__operator">
        <OperatorSwitcher />
      </div>
    </el-header>
    <el-main class="app-shell__main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}

.app-shell__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  height: 64px;
  padding: 0 24px;
  border-bottom: 1px solid var(--el-border-color-light);
  background: #fff;
}

.app-nav {
  display: flex;
  align-items: stretch;
  align-self: stretch;
  gap: 8px;
}

.app-nav__link {
  position: relative;
  display: flex;
  align-items: center;
  padding: 0 14px;
  color: var(--el-text-color-regular);
  font-size: 15px;
  text-decoration: none;
  transition: color 0.2s ease;
}

.app-nav__link::after {
  position: absolute;
  right: 14px;
  bottom: -1px;
  left: 14px;
  height: 2px;
  background: var(--el-color-primary);
  content: '';
  opacity: 0;
  transform: scaleX(0.4);
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.app-nav__link:hover,
.app-nav__link.router-link-active {
  color: var(--el-color-primary);
}

.app-nav__link.router-link-active::after {
  opacity: 1;
  transform: scaleX(1);
}

.app-nav__link:focus-visible {
  border-radius: 4px;
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: -2px;
}

.app-shell__operator {
  flex: 0 0 auto;
}

.app-shell__main {
  overflow-x: hidden;
  padding: 0;
}

@media (prefers-reduced-motion: reduce) {
  .app-nav__link,
  .app-nav__link::after {
    transition: none;
  }
}

@media (max-width: 620px) {
  .app-shell__header {
    align-items: stretch;
    flex-direction: column-reverse;
    gap: 0;
    height: auto;
    padding: 10px 12px 0;
  }

  .app-shell__operator {
    display: flex;
    justify-content: flex-end;
    padding-bottom: 8px;
  }

  .app-nav {
    height: 42px;
  }

  .app-nav__link {
    padding: 0 10px;
  }

  .app-nav__link::after {
    right: 10px;
    left: 10px;
  }
}
</style>
