import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버에서 /api, /{shortCode} 요청을 로컬 Nginx(80번 포트)로 전달
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost',
    },
  },
})
