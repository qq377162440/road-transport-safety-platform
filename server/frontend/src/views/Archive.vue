<script setup>
import { ref, onMounted } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import Icon from '@/components/Icon.vue'

const items = ref([])
const loading = ref(false)
const generating = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await request.get('/archive/responsibility/items')
    items.value = res.data || []
  } finally {
    loading.value = false
  }
}

// 生成责任制 PDF 并归档
async function generate() {
  generating.value = true
  try {
    await request.post('/archive/responsibility/generate')
    ElMessage.success('生成并归档成功')
    load()
  } finally {
    generating.value = false
  }
}

// 下载 PDF
async function download(item) {
  if (!item.pdfId) return
  const res = await fetch(`/api/archive/pdf/${item.pdfId}/download`, {
    headers: {
      Authorization: `Bearer ${localStorage.getItem('token')}`,
      'X-Token': `Bearer ${localStorage.getItem('token')}`,
    },
  })
  if (!res.ok) {
    ElMessage.error('下载失败')
    return
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `安全生产责任制_${item.archiveYear}.pdf`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="pc-header">
      <div>
        <h1>电子档案柜 · 盒二 安全生产责任制</h1>
        <div class="desc">归档项按自然年度隔离；生成后存 MinIO，可下载 PDF 打印装盒。</div>
      </div>
      <div class="actions">
        <el-button type="primary" :loading="generating" @click="generate">
          <Icon name="plus" :size="16" /> 生成责任制 PDF
        </el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
      title="本盒收录安全生产责任制相关归档项，政府检查前请确认 PDF 已生成并归档。"
    />

    <el-table :data="items" stripe v-loading="loading" empty-text="暂无归档项，点击右上角生成">
      <el-table-column prop="title" label="归档项" min-width="200" />
      <el-table-column prop="archiveYear" label="归档年度" width="110" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="['warning', 'success', 'success', 'success'][row.status] || 'info'" size="small">
            {{ ['待生成', '已生成', '已打印', '已归档'][row.status] || '未知' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.pdfId"
            size="small"
            type="primary"
            @click="download(row)"
          >
            <Icon name="download" :size="14" /> 下载
          </el-button>
          <span v-else class="text-3">—</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.text-3 { color: var(--text-3); font-size: 14px; }
</style>
