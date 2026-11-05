<script setup>
import { ref, onMounted } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

const info = ref({})
const loading = ref(false)
const saving = ref(false)

async function loadInfo() {
  loading.value = true
  try {
    const res = await request.get('/tenant/info')
    info.value = res.data || {}
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const res = await request.put('/tenant/info', info.value)
    info.value = res.data
    ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}

onMounted(loadInfo)
</script>

<template>
  <el-card shadow="never" v-loading="loading">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span style="font-weight: 700">企业信息管理</span>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </template>

    <el-descriptions :column="2" border>
      <el-descriptions-item label="法定代表人">
        {{ info.legalPerson || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="安全主要负责人">
        {{ info.safetyCharge || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="统一社会信用代码">
        {{ info.creditCode || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="营运类型">
        {{ info.bizType === 'freight' ? '普货' : info.bizType }}
      </el-descriptions-item>
      <el-descriptions-item label="所在地">
        {{ info.region || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="实际经营地址" :span="2">
        {{ info.address || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="所属行业">
        {{ info.industry || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="负责人电话">
        {{ info.chargePhone || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="车辆规模">
        {{ info.vehicleScale || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="专职安全员数量">
        {{ info.safetyStaff || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="经营范围" :span="2">
        {{ info.bizScope || '-' }}
      </el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>
