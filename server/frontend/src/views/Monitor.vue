<script setup>
import { onMounted, reactive, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import { downloadPdf, openPdf } from '@/utils/pdf'

/** D1 动态监控台账：台账留存 → 违规识别统计 → 靶向教育 → 落实跟踪 → 归档盒六。
 *  边界：实时定位/超速疲劳监测归外部动态监控平台，本系统只做台账与合规闭环。 */
const tab = ref('ledger')
const ledgers = ref([])
const violations = ref([])
const stats = ref({ totalViolations: 0, vehicleCount: 0, educatedCount: 0, educationRate: 0, byPlate: [] })
const outsources = ref([])
const pdfInfo = ref(null)
const previewVisible = ref(false)

const ledgerForm = reactive({ ledger_name: '', ledger_type: '工作记录', source: '动态监控平台', period: '', file_url: '' })
const violationForm = reactive({ ledger_id: null, vehicle_plate: '', vehicle_id: null, driver_id: null,
  violation_type: '超速行驶', violation_time: '', description: '', source: '动态监控平台' })
const outsourceForm = reactive({ outsource_type: 'monitor', outsource_org: '', enabled: 1 })
const eduStatus = reactive({ id: null, status: 'pushed' })

async function load() {
  const [l, v, s, o] = await Promise.all([
    request.get('/monitor/ledger/list'),
    request.get('/monitor/violation/list'),
    request.get('/monitor/violation/stats'),
    request.get('/monitor/outsource'),
  ])
  ledgers.value = l.data || []
  violations.value = v.data || []
  stats.value = s.data || stats.value
  outsources.value = o.data || []
}

async function act(label, fn) {
  try {
    const res = await fn()
    ElMessage.success(label + '成功')
    await load()
    return res.data
  } catch (e) {
    ElMessage.error(e.message || label + '失败')
    return null
  }
}

function uploadLedger() {
  return act('台账留存', () => request.post('/monitor/ledger', { ...ledgerForm }))
}
function registerViolation() {
  return act('违规登记', () => request.post('/monitor/violation', {
    ...violationForm,
    ledger_id: violationForm.ledger_id || ledgers.value[0]?.id || 0,
  }))
}
function targetedEdu(id) {
  return act('生成靶向教育', () => request.post(`/monitor/violation/${id}/edu`))
}
function advanceEdu(id, status) {
  return act('推进教育状态', () => request.put(`/monitor/violation/${id}/edu-status`, { status }))
}
function saveOutsource() {
  return act('保存委托信息', () => request.post('/monitor/outsource', { ...outsourceForm }))
}
async function preview() {
  try {
    const r = await request.get('/monitor/archive')
    previewHtml.value = r.data.html
    previewVisible.value = true
  } catch (e) { ElMessage.error(e.message) }
}
async function generatePdf() {
  pdfInfo.value = await act('生成盒六归档 PDF', () => request.post('/monitor/archive/pdf'))
}
onMounted(load)
</script>

<template>
  <div class="phase3">
    <el-card shadow="never" class="head">
      <h2>动态监控台账 <el-tag size="small" effect="plain">D1 · 盒六</el-tag></h2>
      <p>实时监测由外部动态监控平台负责；本模块只做<strong>台账上传留存、违规车辆识别统计、驾驶员登记、靶向教育联动与落实跟踪</strong>。</p>
      <el-row :gutter="12" class="kpi">
        <el-col :span="6"><el-statistic title="留存台账" :value="ledgers.length" /></el-col>
        <el-col :span="6"><el-statistic title="违规记录" :value="stats.totalViolations" /></el-col>
        <el-col :span="6"><el-statistic title="涉及车辆" :value="stats.vehicleCount" /></el-col>
        <el-col :span="6"><el-statistic title="教育落实率" :value="stats.educationRate" suffix="%" /></el-col>
      </el-row>
    </el-card>

    <el-card shadow="never">
      <el-tabs v-model="tab">
        <el-tab-pane label="台账留存" name="ledger">
          <el-form :model="ledgerForm" inline>
            <el-form-item label="台账名称"><el-input v-model="ledgerForm.ledger_name" style="width: 200px" /></el-form-item>
            <el-form-item label="类型">
              <el-select v-model="ledgerForm.ledger_type" style="width: 140px">
                <el-option v-for="t in ['工作记录','设备故障','在线率','月报','交警抄报']" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
            <el-form-item label="周期"><el-input v-model="ledgerForm.period" placeholder="2026-11" style="width: 110px" /></el-form-item>
            <el-form-item label="附件"><el-input v-model="ledgerForm.file_url" placeholder="上传后填入对象键/URL" style="width: 190px" /></el-form-item>
            <el-form-item><el-button type="primary" @click="uploadLedger">上传留存</el-button></el-form-item>
          </el-form>
          <el-table :data="ledgers" border size="small">
            <el-table-column prop="ledger_name" label="台账名称" />
            <el-table-column prop="ledger_type" label="类型" width="110" />
            <el-table-column prop="source" label="来源" width="140" />
            <el-table-column prop="period" label="周期" width="100" />
            <el-table-column prop="violation_count" label="识别违规数" width="100" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="违规识别与登记" name="violation">
          <el-form :model="violationForm" inline>
            <el-form-item label="车牌"><el-input v-model="violationForm.vehicle_plate" style="width: 130px" /></el-form-item>
            <el-form-item label="违规类型"><el-input v-model="violationForm.violation_type" style="width: 130px" /></el-form-item>
            <el-form-item label="驾驶员ID"><el-input v-model="violationForm.driver_id" style="width: 110px" /></el-form-item>
            <el-form-item label="时间"><el-input v-model="violationForm.violation_time" placeholder="2026-11-06 10:00:00" style="width: 180px" /></el-form-item>
            <el-form-item><el-button type="primary" @click="registerViolation">登记违规</el-button></el-form-item>
          </el-form>
          <el-table :data="violations" border size="small">
            <el-table-column prop="vehicle_plate" label="车牌" width="110" />
            <el-table-column prop="violation_type" label="违规类型" width="130" />
            <el-table-column prop="violation_count" label="累计次数" width="90" />
            <el-table-column prop="education_status" label="教育状态" width="110" />
            <el-table-column prop="violation_time" label="违规时间" width="180" />
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="targetedEdu(row.id)">靶向教育</el-button>
              </template>
            </el-table-column>
          </el-table>
          <h4>按车牌归并统计</h4>
          <el-table :data="stats.byPlate" border size="small">
            <el-table-column prop="vehicle_plate" label="车牌" />
            <el-table-column prop="times" label="违规次数" width="110" />
            <el-table-column prop="educated" label="已落实教育" width="120" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="靶向教育跟踪" name="edu">
          <el-form :model="eduStatus" inline>
            <el-form-item label="违规ID"><el-input v-model="eduStatus.id" style="width: 160px" /></el-form-item>
            <el-form-item label="推进到">
              <el-select v-model="eduStatus.status" style="width: 150px">
                <el-option v-for="s in ['pushed','learning','passed','archived']" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item><el-button type="primary" @click="advanceEdu(eduStatus.id, eduStatus.status)">推进状态</el-button></el-form-item>
          </el-form>
          <p class="hint">闭环：生成 → pushed 已推送 → learning 学习中 → passed 已考核 → archived 已归档（自动写落实时间并登记盒六）。</p>
          <el-table :data="violations" border size="small">
            <el-table-column prop="id" label="ID" width="180" />
            <el-table-column prop="vehicle_plate" label="车牌" width="110" />
            <el-table-column prop="education_plan_id" label="教育计划ID" width="180" />
            <el-table-column prop="education_status" label="状态" width="110" />
            <el-table-column label="快捷推进">
              <template #default="{ row }">
                <el-button v-for="s in ['learning','passed','archived']" :key="s" link type="primary" size="small"
                  @click="advanceEdu(row.id, s)">{{ s }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="委托监控" name="outsource">
          <el-form :model="outsourceForm" inline>
            <el-form-item label="第三方机构"><el-input v-model="outsourceForm.outsource_org" style="width: 240px" /></el-form-item>
            <el-form-item label="是否已委托">
              <el-switch v-model="outsourceForm.enabled" :active-value="1" :inactive-value="0" />
            </el-form-item>
            <el-form-item><el-button type="primary" @click="saveOutsource">保存</el-button></el-form-item>
          </el-form>
          <el-table :data="outsources" border size="small">
            <el-table-column prop="outsource_type" label="委托类型" width="130" />
            <el-table-column prop="outsource_org" label="机构名称" />
            <el-table-column prop="enabled" label="已委托" width="100" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-card shadow="never" class="foot">
      <template #header><b>盒六归档与下载</b></template>
      <el-button @click="preview">归档预览</el-button>
      <el-button type="primary" @click="generatePdf">生成归档 PDF</el-button>
      <template v-if="pdfInfo">
        <el-button @click="openPdf(pdfInfo.fileId)">打开/打印</el-button>
        <el-button @click="downloadPdf(pdfInfo.fileId, '盒六-动态监控.pdf')">下载 PDF</el-button>
        <el-tag type="success" effect="plain">{{ pdfInfo.size }} bytes · 已登记归档</el-tag>
      </template>
      <el-dialog v-model="previewVisible" title="归档预览" width="820px" destroy-on-close>
        <iframe :srcdoc="previewHtml" style="width:100%;height:60vh;border:1px solid #ddd" />
      </el-dialog>
    </el-card>
  </div>
</template>

<style scoped>
.phase3 { display: flex; flex-direction: column; gap: 14px; }
.head h2 { margin: 0 0 8px; font-size: 19px; }
.head p { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.kpi { margin-top: 14px; }
h4 { margin: 14px 0 8px; font-size: 14px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
