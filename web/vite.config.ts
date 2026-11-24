import path from "path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/v1": {
        target: "http://127.0.0.1:8081",
        changeOrigin: true,
      },
    },
  },
  build: {
    // 直接构建输出到 Spring Boot 静态资源目录，服务器零 Node 依赖
    outDir: path.resolve(__dirname, "../src/main/resources/static"),
    emptyOutDir: true,
  },
});
