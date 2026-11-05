<script setup>
import { ref, onMounted, computed } from 'vue'
import request from '@/api/request'
import { useAuthStore } from '@/store/auth'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const sets = ref([])
const loading = ref(false)
const files = ref([])
const currentSet = ref(null)

const canManage = computed(() => ['provider', 'admin'].includes(auth.userType))

async function loadSets() {
  loading.value = true
  try {
    const res = await request.get('/tpl/set')
    sets.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function viewFiles(set) {
  currentSet.value = set
  const res = await request.get(`/tpl/set/${set.id}/files`)
  files.value = res.data || []
}

async function setDefault(set) {
  try {
    await request.put(`/tpl/set/${set.id}/default`)
    ElMessage.success(`已将「${set.name}」设为默认套`)
    loadSets()
  } catch (e) {
    // 已提示
  }
}

onMounted(loadSets)
</script>

<template>
  <el-row :gutter="16" v-loading="loading">
    <!-- 范本套列表 -->
    <el-col :span="12">
      <el-card shadow="never">
        <template #header>
          <div style="display: flex; justify-content: space-between; align-items: center">
            <span style="font-weight: 700">范本套（服务商全局）</span>
            <el-tag v-if="canManage" type="primary" size="small">可管理</el-tag>
            <el-tag v-else type="info" size="small">只读</el-tag>
          </div>
        </template>

        <el-table :data="sets" @row-click="viewFiles" highlight-current-row>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column label="运输类型" width="90">
            <template #default="{ row }">
              {{ row.bizType === 'GENERAL' ? '普货' : row.bizType === 'DANGER' ? '危货' : row.bizType }}
            </template>
          </el-table-column>
          <el-table-column label="默认" width="70">
            <template #default="{ row }">
              <el-tag v-if="row.isDefault === 1" type="success" size="small">默认</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button
                v-if="canManage && row.isDefault !== 1"
                size="small"
                @click.stop="setDefault(row)"
              >
                设默认
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-col>

    <!-- 套内文件 -->
    <el-col :span="12">
      <el-card shadow="never">
        <template #header>
          <span style="font-weight: 700">
            套内文件 {{ currentSet ? `· ${currentSet.name}` : '' }}
          </span>
        </template>
        <el-table :data="files">
          <el-table-column prop="name" label="文件名称" min-width="180" />
          <el-table-column prop="category" label="类型" width="90" />
          <el-table-column prop="code" label="编码" width="130" />
        </el-table>
        <el-empty v-if="!files.length" description="点击左侧范本套查看文件" />
      </el-card>
    </el-col>
  </el-row>
</template>
