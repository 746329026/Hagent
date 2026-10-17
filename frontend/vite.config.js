import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 前端开发服务器配置。
 *
 * ★ port + strictPort 的取舍（踩过就知道为什么要写）：
 *   Vite 默认就是 5173，但端口被占用时它会【静默换到 5174】——
 *   而后端的 CORS 白名单里只有 5173，于是浏览器报"被 CORS 策略阻止"，
 *   服务端日志干干净净，看起来像后端配错了。
 *   strictPort: true 让它【直接启动失败】并说明端口被占用 ——
 *   错在一眼能看见的地方，而不是变成一次跨域排查。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
  },
})
