<script setup>
import { ref, onMounted } from 'vue'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

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

// 改名
async function rename(post) {
  const { value } = await ElMessageBox.prompt(
    `修改「${post.postName}」的显示名`,
    '岗位改名',
    { inputValue: post.postName, confirmButtonText: '确定', cancelButtonText: '取消' },
  )
  if (!value || value === post.postName) return
  try {
    await request.put(`/org/post/${post.id}/rename`, { name: value })
    ElMessage.success('改名成功，已联动责任制等制度内容')
    loadPosts()
  } catch (e) {
    // 已提示
  }
}

// 禁用/启用
async function toggle(post) {
  const action = post.enabled === 1 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确认${action}岗位「${post.postName}」？禁用后将从架构图和责任制中剔除。`,
      '提示',
      { type: 'warning' },
    )
    await request.put(`/org/post/${post.id}/toggle`)
    ElMessage.success(`${action}成功`)
    loadPosts()
  } catch (e) {
    // 取消或错误（强制岗位会报错）
  }
}

onMounted(loadPosts)
</script>

<template>
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <span style="font-weight: 700">岗位信息管理</span>
    </template>

    <el-table :data="posts" stripe>
      <el-table-column label="岗位名" min-width="140">
        <template #default="{ row }">
          <span>{{ row.postName }}</span>
          <span v-if="row.required === 1" style="color: #ef4444"> *</span>
        </template>
      </el-table-column>
      <el-table-column prop="funcKey" label="职能编码" width="100" />
      <el-table-column label="是否强制" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.required === 1" type="danger" size="small">强制</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
            {{ row.enabled === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="岗位目标" prop="goals" min-width="200" show-overflow-tooltip />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="rename(row)">改名</el-button>
          <el-button
            v-if="row.required !== 1"
            size="small"
            :type="row.enabled === 1 ? 'warning' : 'success'"
            @click="toggle(row)"
          >
            {{ row.enabled === 1 ? '禁用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 12px; font-size: 12px; color: var(--text-3)">
      <span style="color: #ef4444">*</span> 表示强制岗位（总经理/安全员/监控员），不可禁用。
    </div>
  </el-card>
</template>
