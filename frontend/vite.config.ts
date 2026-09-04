import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// /api 와 /ws 는 backend 로 프록시. 브라우저는 5173 한 오리진만 본다.
// compose 가 백엔드를 호스트 8081 에 노출한다 (8080 은 다른 프로젝트가 쓰는 경우가 있음).
const BACKEND_PORT = process.env.BACKEND_PORT ?? '8081';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': `http://localhost:${BACKEND_PORT}`,
      '/ws': { target: `ws://localhost:${BACKEND_PORT}`, ws: true },
    },
  },
});
