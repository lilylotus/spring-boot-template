import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    host: '127.0.0.1',
    // 开发环境代理：后端未开启 CORS，本地联调时把 /api 转发到后端服务（默认端口 45678，
    // 见 backend/src/main/resources/application.yaml 的 server.port）。
    proxy: {
      '/api': {
        target: 'http://localhost:45678',
        changeOrigin: true,
      },
    },
  },
})
