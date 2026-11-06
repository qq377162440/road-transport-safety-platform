<script setup>
import { onMounted, reactive, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import { downloadPdf, openPdf } from '@/utils/pdf'

/** D2 安全生产费用：营收 × 比例（普货 1% / 危货 1.5%）→ 应提；
 *  按财资〔2022〕136 号 11 类登记支出与发票，实时汇总已用/结余并归档盒五。 */
const year = ref(new Date().getFullYear())
const summary = ref({ planAmount: 0, usedAmount: 0, balance: 0, usageRate: 0, complianceTip: '未计提', matrix: [] })
const categories = ref([])
const expenses = ref([])
const plan = ref(null)
const pdfInfo = ref(null)
const previewVisible = ref(false)
const previewHtml = ref('')

const planForm = reactive({ year: new Date().getFullYear(), revenue: null, cargo_type: '', revenue_file: '' })
const expenseForm = reactive({ plan_id: null, category_id: null, expense_date: '', expense_desc: '', amount: null, receipt_file: '' })
const newCategory = reactive({ category_name: '' })

async function load() {
  const [s, c, e] = await Promise.all([
    request.get(`/budget/summary/${year.value}`),
    request.get('/budget/category'),
    request.get('/budget/expense/list'),
  ])
  summary.value = s.data || summary.value
  categories.value = c.data || []
  expenses.value = e.data || []
  expenseForm.plan_id = summary.value.planId || null
  try { plan.value = (await request.get(`/budget/plan/${year.value}`)).data } catch { plan.value = null }
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

function savePlan() { return act('年度计提', () => request.post('/budget/plan', { ...planForm })) }
function addExpense() {
  return act('登记支出', () => request.post('/budget/expense', {
    ...expenseForm,
    plan_id: expenseForm.plan_id || summary.value.planId,
    expense_date: expenseForm.expense_date || new Date().toISOString().slice(0, 10),
  }))
}
function addCategory() { return act('新增费用类别', () => request.post('/budget/category', { ...newCategory })) }
async function preview() {
  try {
    const r = await request.get('/budget/archive', { params: { year: year.value } })
    previewHtml.value = r.data.html
    previewVisible.value = true
  } catch (e) { ElMessage.error(e.message) }
}
async function generatePdf() {
  pdfInfo.value = await act('生成盒五归档 PDF', () =>
    request.post('/budget/archive/pdf', null, { params: { year: year.value } }))
}
onMounted(load)
</script>

<template>
  <div class="phase3">
    <el-card shadow="never" class="head">
      <h2>安全生产费用 <el-tag size="small" effect="plain">D2 · 盒五</el-tag></h2>
      <p>计提基数 = 上年度营业收入；比例按营运类型自动匹配：普货 <strong>1%</strong>、危货 <strong>1.5%</strong>（客运冻结）。支出按财资〔2022〕136 号 11 类登记，每笔留发票。</p>
      <el-row :gutter="12" class="kpi">
        <el-col :span="6"><el-statistic title="应提金额（元）" :value="Number(summary.planAmount)" :precision="2" /></el-col>
        <el-col :span="6"><el-statistic title="已用金额（元）" :value="Number(summary.usedAmount)" :precision="2" /></el-col>
        <el-col :span="6"><el-statistic title="结余（元）" :value="Number(summary.balance)" :precision="2" /></el-col>
        <el-col :span="6">
          <div class="tip">使用率 {{ summary.usageRate }}%</div>
          <el-tag :type="summary.complianceTip === '超支提醒' ? 'danger' : 'warning'" effect="plain">{{ summary.complianceTip }}</el-tag>
        </el-col>
      </el-row>
    </el-card>

    <el-row :gutter="14">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>年度计提（营收 × 比例）</b></template>
          <el-form :model="planForm" label-width="110px">
            <el-form-item label="计提年度"><el-input-number v-model="planForm.year" :controls="false" style="width: 140px" /></el-form-item>
            <el-form-item label="上年度营收"><el-input-number v-model="planForm.revenue" :controls="false" :precision="2" :min="0" style="width: 220px" /></el-form-item>
            <el-form-item label="营运类型">
              <el-select v-model="planForm.cargo_type" clearable placeholder="自动读取企业信息" style="width: 200px">
                <el-option label="普货（1%）" value="general" />
                <el-option label="危货（1.5%）" value="danger" />
              </el-select>
            </el-form-item>
            <el-form-item label="营收证明"><el-input v-model="planForm.revenue_file" placeholder="上传后填入对象键" /></el-form-item>
            <el-form-item><el-button type="primary" @click="savePlan">生成提取标准及计划</el-button></el-form-item>
          </el-form>
          <p v-if="plan" class="hint">已计提：营收 {{ plan.revenue }} 元 × 计提比例 {{ Number((Number(plan.rate) * 100).toFixed(2)) }}% = {{ plan.plan_amount }} 元（{{ plan.status }}）</p>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><b>支出登记（含发票/收据）</b></template>
          <el-form :model="expenseForm" label-width="100px">
            <el-form-item label="费用类别">
              <el-select v-model="expenseForm.category_id" clearable filterable style="width: 250px">
                <el-option v-for="c in categories" :key="c.id" :label="c.category_name" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="支出日期"><el-input v-model="expenseForm.expense_date" placeholder="2026-11-06" style="width: 160px" /></el-form-item>
            <el-form-item label="事由"><el-input v-model="expenseForm.expense_desc" /></el-form-item>
            <el-form-item label="金额（元）"><el-input-number v-model="expenseForm.amount" :controls="false" :precision="2" :min="0" style="width: 180px" /></el-form-item>
            <el-form-item label="发票"><el-input v-model="expenseForm.receipt_file" placeholder="上传后填入对象键" /></el-form-item>
            <el-form-item><el-button type="primary" @click="addExpense">登记支出</el-button></el-form-item>
          </el-form>
          <el-divider content-position="left">自定义类别</el-divider>
          <el-form inline>
            <el-form-item><el-input v-model="newCategory.category_name" placeholder="其他类支出名称" style="width: 220px" /></el-form-item>
            <el-form-item><el-button @click="addCategory">新增</el-button></el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header><b>使用台账 · 11 类 × 12 月矩阵</b></template>
      <el-table :data="summary.matrix" border size="small" max-height="360">
        <el-table-column prop="categoryName" label="费用类别" fixed width="200" />
        <el-table-column v-for="m in 12" :key="m" :prop="'m' + m" :label="m + '月'" width="80" />
        <el-table-column prop="total" label="合计" width="100" fixed="right" />
      </el-table>
      <el-divider content-position="left">逐笔支出明细</el-divider>
      <el-table :data="expenses" border size="small" max-height="300">
        <el-table-column prop="expense_date" label="日期" width="120" />
        <el-table-column prop="category_name" label="费用类别" width="200" />
        <el-table-column prop="expense_desc" label="事由" />
        <el-table-column prop="amount" label="金额（元）" width="120" />
        <el-table-column label="凭证" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.receipt_file ? 'success' : 'warning'" effect="plain">{{ row.receipt_file ? '已留存' : '待上传' }}</el-tag></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="foot">
      <template #header><b>盒五归档与下载</b></template>
      <el-button @click="preview">归档预览</el-button>
      <el-button type="primary" @click="generatePdf">生成归档 PDF</el-button>
      <template v-if="pdfInfo">
        <el-button @click="openPdf(pdfInfo.fileId)">打开/打印</el-button>
        <el-button @click="downloadPdf(pdfInfo.fileId, '盒五-安全生产费用.pdf')">下载 PDF</el-button>
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
.tip { font-size: 13px; margin-bottom: 6px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin: 6px 0 0; }
</style>
