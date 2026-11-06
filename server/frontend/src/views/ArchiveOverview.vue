<script setup>
import { onMounted, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

/** B7 电子档案柜完整版：10/11 盒目录树 + 归档全景 + 缺项提示 + 批量生成 */
const bizType = ref('GENERAL')
const overview = ref({ boxes: [], boxCount: 0, archiveItemCount: 0 })
const missing = ref([])
const busy = ref(false)

async function load() {
  const [o, m] = await Promise.all([
    request.get('/flow/archive/overview', { params: { bizType: bizType.value } }),
    request.get('/flow/archive/missing', { params: { bizType: bizType.value } }),
  ])
  overview.value = o.data
  missing.value = m.data
}

async function batchGenerate() {
  busy.value = true
  try {
    const res = await request.post('/flow/archive/batch-generate', null, { params: { bizType: bizType.value } })
    ElMessage.success(`已登记 ${res.data.generated} 个待生成归档项`)
    await load()
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="pc-header">
      <div>
        <h1>电子档案柜 · 全景与缺项</h1>
        <div class="desc">按档案盒目录树查看归档全景，红标为政府检查前必须补齐的缺项。</div>
      </div>
      <div class="actions">
        <el-radio-group v-model="bizType" @change="load">
          <el-radio-button value="GENERAL">普货 10 盒</el-radio-button>
          <el-radio-button value="DANGER">危货 11 盒</el-radio-button>
        </el-radio-group>
        <el-button type="primary" :loading="busy" @click="batchGenerate">批量生成缺项</el-button>
      </div>
    </div>

    <el-row :gutter="12" class="stats">
      <el-col :span="8"><el-statistic title="档案盒" :value="overview.boxCount" /></el-col>
      <el-col :span="8"><el-statistic title="已登记归档项" :value="overview.archiveItemCount" /></el-col>
      <el-col :span="8"><el-statistic title="缺项" :value="missing.length" /></el-col>
    </el-row>

    <el-alert
      v-if="missing.length"
      type="warning"
      :closable="false"
      :title="`还有 ${missing.length} 个目录子项缺少归档资料，政府检查前请补齐`"
      show-icon
    />

    <el-collapse class="boxes">
      <el-collapse-item v-for="b in overview.boxes" :key="b.id" :name="b.id">
        <template #title>
          <span class="box-title">{{ b.name }}</span>
          <el-tag size="small" type="info" effect="plain">{{ (b.children || []).length }} 个子项</el-tag>
          <el-tag size="small" type="success" effect="plain">已归档 {{ b.item_count }}</el-tag>
        </template>
        <div class="sub-grid">
          <div v-for="s in b.children" :key="s.id" class="sub">
            <span>{{ s.name }}</span>
            <el-tag size="small" :type="s.item_count > 0 ? 'success' : 'danger'" effect="plain">
              {{ s.item_count > 0 ? s.item_count + ' 项' : '缺项' }}
            </el-tag>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.spacer { flex: 1; }
.stats { margin-bottom: 16px; }
.boxes { margin-top: 16px; }
.box-title { font-weight: 600; margin-right: 12px; }
.sub-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px; }
.sub {
  display: flex; justify-content: space-between; align-items: center;
  padding: 8px 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; font-size: 13px;
}
</style>
