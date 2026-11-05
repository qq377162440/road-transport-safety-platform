<script setup>
import { onMounted, ref } from 'vue'
import request from '@/api/request'

/** C5 驾驶舱（风险可视）：一眼看出当前最紧急的安全风险（验收标准 3） */
const data = ref({ safetyScore: 100, missingCount: 0, openDangerCount: 0, abnormalCheckCount: 0, topRisks: [], mostUrgent: '' })
const loading = ref(false)

async function load() {
  loading.value = true
  try { data.value = (await request.get('/flow/dashboard/risk')).data }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="risk-dashboard">
    <el-row :gutter="16">
      <el-col :span="8">
        <el-card shadow="never" class="score-card">
          <div class="score-label">企业安全分</div>
          <el-progress type="dashboard" :percentage="data.safetyScore" :width="180" :stroke-width="12">
            <template #default="{ percentage }">
              <span class="score-num">{{ percentage }}</span>
              <span class="score-unit">分</span>
            </template>
          </el-progress>
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card shadow="never" class="urgent">
          <div class="score-label">当前最紧急的安全风险</div>
          <h1 class="urgent-text">{{ data.mostUrgent }}</h1>
          <el-row :gutter="12" class="mini">
            <el-col :span="8"><el-statistic title="档案缺项" :value="data.missingCount" /></el-col>
            <el-col :span="8"><el-statistic title="未闭环隐患" :value="data.openDangerCount" /></el-col>
            <el-col :span="8"><el-statistic title="三检异常" :value="data.abnormalCheckCount" /></el-col>
          </el-row>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header><b>待处置风险清单</b></template>
      <el-table :data="data.topRisks" border size="small">
        <el-table-column prop="title" label="隐患描述" />
        <el-table-column prop="danger_level" label="等级" width="100" />
        <el-table-column prop="status" label="状态" width="130" />
        <el-table-column prop="find_time" label="发现时间" width="180" />
        <template #empty>暂无未闭环隐患</template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.score-label { font-size: 14px; color: var(--el-text-color-secondary); margin-bottom: 14px; }
.score-card { text-align: center; }
.score-num { font-size: 44px; font-weight: 700; }
.score-unit { font-size: 16px; margin-left: 4px; }
.urgent-text { font-size: 26px; margin: 8px 0 24px; color: #b42318; }
.mini { margin-top: 10px; }
</style>
