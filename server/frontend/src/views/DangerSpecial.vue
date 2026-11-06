<script setup>
import { onMounted, reactive, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import { downloadPdf, openPdf } from '@/utils/pdf'

/** D4 危货专项深化：六项强制台账 + 电子运单（押运员在岗/车辆查验/800km 双押双驾校验）
 *  + 停车场管理；客运全部冻结，危货应急预案不由本系统管理。 */
const types = ref([])
const completeness = ref({ total: 6, ready: 0, qualified: false, items: [] })
const ledgers = ref([])
const waybills = ref([])
const parking = ref({ total: 0, qualified: 0, list: [] })
const pdfInfo = ref(null)
const previewVisible = ref(false)
const previewHtml = ref('')

const ledgerForm = reactive({ ledger_type: 'load_check', biz_date: '', waybill_no: '', plate_no: '',
  cargo_name: '', content: '', file_url: '' })
const waybillForm = reactive({ waybill_no: '', cargo_name: '', danger_class: '易燃', un_no: '', quantity: null,
  origin: '', destination: '', vehicle_id: null, plate_no: '', driver_id: null, escort_id: null,
  distance_km: 0, escort_on_duty: 1, vehicle_checked: 1, double_driver: 0 })

async function load() {
  const [t, c, l, w, p] = await Promise.all([
    request.get('/danger/ledger-types'),
    request.get('/danger/completeness'),
    request.get('/danger/ledger/list'),
    request.get('/danger/waybill/list'),
    request.get('/danger/parking'),
  ])
  types.value = t.data?.list || []
  completeness.value = c.data || completeness.value
  ledgers.value = l.data || []
  waybills.value = w.data || []
  parking.value = p.data || parking.value
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

function addLedger() { return act('登记专项台账', () => request.post('/danger/ledger', { ...ledgerForm })) }
function createWaybill() { return act('生成电子运单', () => request.post('/danger/waybill', { ...waybillForm })) }
function setStatus(id, status) { return act('运单状态流转', () => request.put(`/danger/waybill/${id}/status`, { status })) }
async function preview() {
  try {
    const r = await request.get('/danger/archive')
    previewHtml.value = r.data.html
    previewVisible.value = true
  } catch (e) { ElMessage.error(e.message) }
}
async function generatePdf() {
  pdfInfo.value = await act('生成盒六归档 PDF', () => request.post('/danger/archive/pdf'))
}
onMounted(load)
</script>

<template>
  <div class="phase3">
    <el-card shadow="never" class="head">
      <h2>危货专项深化 <el-tag size="small" type="warning" effect="plain">D4 · 盒六</el-tag></h2>
      <p>依据《危险化学品安全法》（2026.5.1 施行）与道路危险货物运输管理规定：电子运单、装卸查验、危险品运输台账、押运员在岗、800 公里以上线路台账、安全卡（一书一签）。<strong>客运全部冻结；危货应急预案不由本系统管理。</strong></p>
      <el-alert :type="completeness.qualified ? 'success' : 'warning'" :closable="false"
        :title="`六项专项台账齐备性：${completeness.ready}/${completeness.total}（${completeness.qualified ? '齐备' : '待补齐'}）`" />
    </el-card>

    <el-card shadow="never">
      <template #header><b>六项强制台账清单</b></template>
      <el-table :data="completeness.items" border size="small">
        <el-table-column prop="name" label="台账" />
        <el-table-column prop="code" label="编码" width="130" />
        <el-table-column prop="freq" label="频次" width="110" />
        <el-table-column prop="basis" label="法规依据" />
        <el-table-column prop="count" label="记录数" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.ready ? 'success' : 'warning'" effect="plain">{{ row.ready ? '已留存' : '待补齐' }}</el-tag></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-row :gutter="14">
      <el-col :span="14">
        <el-card shadow="never">
          <template #header><b>危险货物电子运单（法律强制）</b></template>
          <el-form :model="waybillForm" label-width="100px" size="small">
            <el-row :gutter="8">
              <el-col :span="12"><el-form-item label="运单号"><el-input v-model="waybillForm.waybill_no" placeholder="留空自动生成" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="货物品名"><el-input v-model="waybillForm.cargo_name" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="危险类别"><el-input v-model="waybillForm.danger_class" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="UN 编号"><el-input v-model="waybillForm.un_no" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="数量"><el-input-number v-model="waybillForm.quantity" :controls="false" :precision="2" style="width: 100%" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="起运地"><el-input v-model="waybillForm.origin" /></el-form-item></el-col>
              <el-col :span="12"><el-form-item label="送达地"><el-input v-model="waybillForm.destination" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="车辆ID"><el-input v-model="waybillForm.vehicle_id" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="驾驶员ID"><el-input v-model="waybillForm.driver_id" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="押运员ID"><el-input v-model="waybillForm.escort_id" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="车牌"><el-input v-model="waybillForm.plate_no" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="里程 km"><el-input-number v-model="waybillForm.distance_km" :controls="false" style="width: 100%" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="押运员在岗"><el-switch v-model="waybillForm.escort_on_duty" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="车辆已查验"><el-switch v-model="waybillForm.vehicle_checked" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
              <el-col :span="8"><el-form-item label="双押双驾"><el-switch v-model="waybillForm.double_driver" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
            </el-row>
            <el-form-item><el-button type="primary" @click="createWaybill">生成电子运单并联动台账</el-button></el-form-item>
          </el-form>
          <p class="hint">校验规则：押运员在岗 + 车辆资质查验通过方可生成；里程 ≥ 800 km 必须双押双驾。</p>
          <el-table :data="waybills" border size="small" max-height="240">
            <el-table-column prop="waybill_no" label="运单号" width="170" />
            <el-table-column prop="cargo_name" label="货物品名" />
            <el-table-column prop="plate_no" label="车牌" width="100" />
            <el-table-column prop="distance_km" label="里程" width="80" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column label="流转" width="160">
              <template #default="{ row }">
                <el-button v-for="s in ['transit','completed','archived']" :key="s" link type="primary" size="small"
                  @click="setStatus(row.id, s)">{{ s }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never">
          <template #header><b>专项台账登记</b></template>
          <el-form :model="ledgerForm" label-width="90px" size="small">
            <el-form-item label="台账类型">
              <el-select v-model="ledgerForm.ledger_type" style="width: 100%">
                <el-option v-for="t in types" :key="t.code" :label="t.name" :value="t.code" />
              </el-select>
            </el-form-item>
            <el-form-item label="日期"><el-input v-model="ledgerForm.biz_date" placeholder="2026-11-06" /></el-form-item>
            <el-form-item label="运单号"><el-input v-model="ledgerForm.waybill_no" /></el-form-item>
            <el-form-item label="车牌"><el-input v-model="ledgerForm.plate_no" /></el-form-item>
            <el-form-item label="内容"><el-input v-model="ledgerForm.content" type="textarea" :rows="2" /></el-form-item>
            <el-form-item label="附件"><el-input v-model="ledgerForm.file_url" placeholder="上传后填入对象键" /></el-form-item>
            <el-form-item><el-button type="primary" @click="addLedger">登记并归档</el-button></el-form-item>
          </el-form>
        </el-card>
        <el-card shadow="never" style="margin-top: 14px">
          <template #header><b>危货专用停车场（盒七）</b></template>
          <p>已登记 {{ parking.total }} 处，资料齐备 {{ parking.qualified }} 处（地址 / 面积 / 土地证明 / 平面布置图）。</p>
          <el-table :data="parking.list" border size="small" max-height="180">
            <el-table-column prop="address" label="地址" show-overflow-tooltip />
            <el-table-column prop="area" label="面积㎡" width="90" />
            <el-table-column prop="lease_end" label="租期止" width="120" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header><b>专项台账记录（{{ ledgers.length }}）</b></template>
      <el-table :data="ledgers" border size="small" max-height="260">
        <el-table-column prop="biz_date" label="日期" width="120" />
        <el-table-column prop="ledger_type" label="类型" width="140" />
        <el-table-column prop="waybill_no" label="运单号" width="170" />
        <el-table-column prop="plate_no" label="车牌" width="110" />
        <el-table-column prop="content" label="内容" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="110" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="foot">
      <template #header><b>盒六归档与下载</b></template>
      <el-button @click="preview">归档预览</el-button>
      <el-button type="primary" @click="generatePdf">生成归档 PDF</el-button>
      <template v-if="pdfInfo">
        <el-button @click="openPdf(pdfInfo.fileId)">打开/打印</el-button>
        <el-button @click="downloadPdf(pdfInfo.fileId, '盒六-危货专项台账.pdf')">下载 PDF</el-button>
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
.head p { margin: 0 0 12px; color: var(--el-text-color-secondary); font-size: 13px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin: 4px 0 10px; }
</style>
