import { defineConfig } from "vite";
export default defineConfig({build:{rollupOptions:{input:{admin:"index.html",viewer:"file-viewer.html",demo:"demo.html"}}}});
