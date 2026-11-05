<script setup>
import { onMounted, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

/**
 * 专用业务闭环工具箱：把有状态流转的接口集中暴露，供安全员快速操作与验收脚本驱动。
 * 覆盖 B1/B2 档案检查、B4 三检月报、B5 隐患闭环、B6 会议四段式、
 * C1 培训考试、C6 自定义签署、B10/C7 模块健康。
 */
const out = ref('等待操作…')
const health = ref({ modules: [], moduleCount: 0, tables: 0 })

const monthly = ref({ year: new Date().getFullYear(), month: new Date().getMonth() + 1 })
const danger = ref({ id: null, accept_result: 'pass' })
const meeting = ref({ id: null, section: 'notice', content: '' })
const exam = ref({ plan_id: null, assign_id: null, score: 85 })
const sign = ref({ taskId: null })
const archiveCheck = ref({ personId: null, vehicleId: null, bizType: 'DANGER' })

async function call(label, fn) {
  try {
    const res = await fn()
    out.value = `${label} →\n${JSON.stringify(res.data, null, 2)}`
    ElMessage.success(label + '成功')
  } catch (e) {
    out.value = `${label} 失败：${e.message}`
    ElMessage.error(e.message || '操作失败')
  }
}

onMounted(async () => {
  try { health.value = (await request.get('/flow/health/modules')).data } catch (e) { /* 忽略 */ }
})
</script>

<template>
  <div class="flow-tools">
    <el-row :gutter="14">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>B4 · 三检月报汇总（明细不装盒）</b></template>
          <el-form inline>
            <el-form-item label="年"><el-input-number v-model="monthly.year" :controls="false" style="width: 100px" /></el-form-item>
            <el-form-item label="月"><el-input-number v-model="monthly.month" :min="1" :max="12" :controls="false" style="width: 80px" /></el-form-item>
            <el-form-item>
              <el-button type="primary" @click="call('生成月报', () => request.get('/flow/check/monthly-report', { params: monthly }))">生成并归档</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>B5 · 隐患整改验收闭环</b></template>
          <el-form inline>
            <el-form-item label="隐患ID"><el-input v-model="danger.id" style="width: 150px" placeholder="danger_record.id" /></el-form-item>
            <el-form-item label="结果">
              <el-select v-model="danger.accept_result" style="width: 100px"><el-option label="通过" value="pass" /><el-option label="驳回" value="reject" /></el-select>
            </el-form-item>
            <el-form-item>
              <el-button @click="call('整改', () => request.post(`/flow/danger/${danger.id}/rectify`, { rectify_require: '限期整改' }))">下发整改</el-button>
              <el-button type="primary" @click="call('验收', () => request.post(`/flow/danger/${danger.id}/accept`, danger))">验收闭环</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>B6 · 会议四段式</b></template>
          <el-form inline>
            <el-form-item label="会议ID"><el-input v-model="meeting.id" style="width: 150px" /></el-form-item>
            <el-form-item label="段落">
              <el-select v-model="meeting.section" style="width: 120px">
                <el-option v-for="s in ['notice','sign','record','photos']" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="call('四段式归档', () => request.post(`/flow/meeting/${meeting.id}/section`, { ...meeting, notice_title: '会议通知', content: meeting.content, venue_photos: '[]' }))">提交</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>C1 · 培训考核留痕（学时开关）</b></template>
          <el-form inline>
            <el-form-item label="计划ID"><el-input v-model="exam.plan_id" style="width: 110px" /></el-form-item>
            <el-form-item label="分配ID"><el-input v-model="exam.assign_id" style="width: 110px" /></el-form-item>
            <el-form-item label="分数"><el-input-number v-model="exam.score" :controls="false" style="width: 80px" /></el-form-item>
            <el-form-item>
              <el-button type="primary" @click="call('提交考核', () => request.post('/flow/training/exam', exam))">提交</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>C6 · 自定义签署 PDF 合成</b></template>
          <el-form inline>
            <el-form-item label="任务ID"><el-input v-model="sign.taskId" style="width: 170px" /></el-form-item>
            <el-form-item>
              <el-button type="primary" @click="call('合成签署凭证', () => request.post(`/flow/sign/${sign.taskId}/render`))">生成 PDF</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>B1/B2 · 一人一档 / 一车一档检查</b></template>
          <el-form inline>
            <el-form-item label="人员ID"><el-input v-model="archiveCheck.personId" style="width: 110px" /></el-form-item>
            <el-form-item label="车辆ID"><el-input v-model="archiveCheck.vehicleId" style="width: 110px" /></el-form-item>
            <el-form-item>
              <el-button @click="call('危货强制体检检查', () => request.get(`/flow/person/${archiveCheck.personId}/archive-check`, { params: { bizType: 'DANGER' } }))">一人一档</el-button>
              <el-button @click="call('一车一档检查', () => request.get(`/flow/vehicle/${archiveCheck.vehicleId}/archive-check`))">一车一档</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 14px">
      <template #header><b>接口返回</b></template>
      <pre class="out">{{ out }}</pre>
    </el-card>

    <el-card shadow="never" style="margin-top: 14px">
      <template #header><b>B10 / C7 · 模块健康与归档归属（{{ health.moduleCount }} 模块 / {{ health.tables }} 表）</b></template>
      <el-table :data="health.modules" border size="small">
        <el-table-column prop="phase" label="阶段" width="80" />
        <el-table-column prop="code" label="模块" width="130" />
        <el-table-column prop="title" label="名称" />
        <el-table-column prop="tables" label="表数" width="80" />
        <el-table-column prop="catalog" label="归档归属" width="130" />
        <el-table-column prop="archiveItems" label="归档项" width="90" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.el-col { margin-bottom: 14px; }
.out { white-space: pre-wrap; font-size: 12px; max-height: 260px; overflow: auto; margin: 0; }
</style>
