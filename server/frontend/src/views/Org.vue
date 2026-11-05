<script setup>
import { ref, onMounted, computed } from 'vue'
import request from '@/api/request'

const posts = ref([])
const loading = ref(false)

async function loadPosts() {
  loading.value = true
  try {
    const res = await request.get('/org/posts')
    posts.value = res.data || []
  } finally {
    loading.value = false
  }
}

// 启用岗位
const enabledPosts = computed(() => posts.value.filter((p) => p.enabled === 1))

onMounted(loadPosts)
</script>

<template>
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <span style="font-weight: 700">安全管理架构</span>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
      title="架构图仅展示启用岗位；禁用岗位自动从架构图和安全生产责任制中剔除。"
    />

    <!-- 简易树形架构图 -->
    <div class="org-chart">
      <div v-for="p in enabledPosts" :key="p.id" class="org-node">
        <div class="node-name">{{ p.postName }}</div>
        <div class="node-key">{{ p.funcKey }}</div>
        <div v-if="p.required === 1" class="node-badge">强制</div>
      </div>
    </div>

    <el-empty v-if="!enabledPosts.length" description="暂无启用岗位" />
  </el-card>
</template>

<style scoped>
.org-chart {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 20px;
}
.org-node {
  position: relative;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 16px 20px;
  min-width: 140px;
  text-align: center;
  background: #fff;
}
.node-name {
  font-size: 15px;
  font-weight: 600;
}
.node-key {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 4px;
}
.node-badge {
  position: absolute;
  top: -8px;
  right: -8px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 10px;
}
</style>
