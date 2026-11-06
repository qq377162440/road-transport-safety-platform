<script setup>
import { ref, onMounted } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

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
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span style="font-weight: 700">电子档案柜 · 盒二安全生产责任制</span>
        <el-button type="primary" :loading="generating" @click="generate">
          生成责任制 PDF
        </el-button>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
      title="归档项按自然年度隔离；生成后存 MinIO，可下载 PDF 打印装盒。"
    />

    <el-table :data="items" stripe>
      <el-table-column prop="title" label="归档项" min-width="160" />
      <el-table-column prop="archiveYear" label="归档年度" width="100" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'warning'" size="small">
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
            下载 PDF
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!items.length" description="暂无归档项，点击右上角生成" />
  </el-card>
</template>
