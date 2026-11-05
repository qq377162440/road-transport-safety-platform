<script setup>
import { onMounted, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

/** B8 服务商代录工作台：跳过起草推送、复用历史签字，快速补齐台账（验收标准 4） */
const tasks = ref([])
const signatures = ref([])
const selected = ref(null)
const targetTable = ref('person')
const rowsText = ref('[{"name":"张三","telephone":"13800000000"}]')
const reuseSignature = ref('')
const busy = ref(false)

async function load() {
  const [t, s] = await Promise.all([
    request.get('/biz/collect_task', { params: { pageSize: 50 } }),
    request.get('/flow/collect/signatures'),
  ])
  tasks.value = t.data.records || []
  signatures.value = s.data || []
  if (!selected.value && tasks.value.length) selected.value = tasks.value[0].id
}

async function createTask() {
  await request.post('/biz/collect_task', { file_type: 'xlsx', target_type: 'person', status: 0 })
  ElMessage.success('已创建代录任务')
  await load()
}

async function fill() {
  if (!selected.value) { ElMessage.warning('请先选择任务'); return }
  let rows
  try { rows = JSON.parse(rowsText.value) } catch (e) { ElMessage.error('行数据不是合法 JSON'); return }
  busy.value = true
  try {
    const res = await request.post(`/flow/collect/${selected.value}/fill`, {
      rows, targetTable: targetTable.value, reuseSignature: reuseSignature.value || null,
    })
    ElMessage.success(`代录完成 ${res.data.filled} 行${res.data.reuseSignature ? '（复用历史签字）' : ''}`)
    await load()
  } finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <div class="collect">
    <el-card shadow="never">
      <template #header>
        <div class="card-head"><b>服务商代录工作台</b><el-button size="small" @click="createTask">新建代录任务</el-button></div>
      </template>
      <el-form label-width="110px">
        <el-form-item label="代录任务">
          <el-select v-model="selected" style="width: 100%">
            <el-option v-for="t in tasks" :key="t.id" :label="`#${t.id} ${t.target_type || ''} (${t.status})`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标业务表">
          <el-select v-model="targetTable" style="width: 100%">
            <el-option label="人员 person" value="person" />
            <el-option label="车辆 vehicle" value="vehicle" />
            <el-option label="会议 meeting" value="meeting" />
            <el-option label="隐患 danger_record" value="danger_record" />
            <el-option label="培训计划 training_plan" value="training_plan" />
          </el-select>
        </el-form-item>
        <el-form-item label="复用历史签字">
          <el-select v-model="reuseSignature" clearable placeholder="不复用" style="width: 100%">
            <el-option v-for="s in signatures" :key="s.id" :label="`签字 #${s.id}（用户 ${s.user_id}）`" :value="s.sign_image" />
          </el-select>
        </el-form-item>
        <el-form-item label="代录数据 (JSON 数组)">
          <el-input v-model="rowsText" type="textarea" :rows="5" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="fill">跳过推送，直接代录并归档</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <el-alert
      type="info" :closable="false" show-icon style="margin-top: 14px"
      title="代录模式说明"
      description="小微个体户无需自行操作：服务商上传资料 → 系统解析 → 直接落业务表并自动登记归档项，跳过范本起草与企业执行环节，可复用历史签字。"
    />
  </div>
</template>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
