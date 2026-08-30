import { defineConfig } from 'vite'

export default defineConfig({
  root: 'ui',
  base: './',
  build: {
    outDir: '../dist',
    emptyOutDir: true,
    target: 'chrome120',
  },
})
