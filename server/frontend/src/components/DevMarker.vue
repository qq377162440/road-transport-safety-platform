<script setup>
import { DEV_MODE, DEV_LABEL } from '@/config/devFlag'
import { computed } from 'vue'
const show = computed(() => DEV_MODE)
const now = new Date()
const stamp = `${now.getFullYear()}.${String(now.getMonth() + 1).padStart(2, '0')}.${String(now.getDate()).padStart(2, '0')}`
</script>

<template>
  <div v-if="show" class="dev-marker" title="模块逐项对齐中：功能/文案/占位均为待验收状态，正式发布前统一清除">
    <span class="dot" />
    {{ DEV_LABEL }} · {{ stamp }}
  </div>
</template>

<style scoped>
.dev-marker {
  position: fixed;
  right: 14px;
  bottom: 14px;
  z-index: 9999;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 13px;
  border-radius: 20px;
  background: rgba(220, 38, 38, 0.92);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.3px;
  box-shadow: 0 4px 14px rgba(220, 38, 38, 0.35);
  pointer-events: none;
  user-select: none;
}
.dev-marker .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #fff;
  animation: devpulse 1.4s infinite;
}
@keyframes devpulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
</style>
