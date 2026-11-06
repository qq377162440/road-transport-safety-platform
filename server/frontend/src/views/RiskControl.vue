<script setup>
import { onMounted, reactive, ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'
import { downloadPdf, openPdf } from '@/utils/pdf'

/** D3 风险分级管控（静态辨识）：五类风险点库 × 四色等级 → 手册/清单/岗位告知卡 → 盒八/盒九。
 *  隐患排查（动态）属 13 模块，不在此重复。 */
const four = ref({ levels: {}, total: 0, cards: 0, manuals: 0, catalog: 'BOX8' })
const points = ref([])
const selected = ref([])
const lists = ref([])
const cards = ref([])
const manuals = ref([])
const pdfInfo = ref(null)
const previewVisible = ref(false)
const previewHtml = ref('')
const genResult = ref(null)

const year = ref(new Date().getFullYear())
const newPoint = reactive({ risk_name: '', risk_category: 'manage', risk_level: '蓝', control_measure: '' })

async function load() {
  const [f, p, l, c, m] = await Promise.all([
    request.get('/risk/four-color'),
    request.get('/risk/point-dict'),
    request.get('/risk/list'),
    request.get('/biz/risk_card', { params: { pageSize: 100 } }),
    request.get('/biz/risk_manual', { params: { pageSize: 100 } }),
  ])
  four.value = f.data || four.value
  points.value = p.data?.list || []
  lists.value = l.data || []
  cards.value = c.data?.records || []
  manuals.value = m.data?.records || []
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

function genList() {
  return act('生成风险辨识清单', () =>
    request.post('/risk/list', { year: year.value, pointIds: selected.value }))
}
function genCards() { return act('生成岗位告知卡', () => request.post('/risk/card', { year: year.value })) }
async function genAll() {
  genResult.value = await act('一键生成全部资料', () =>
    request.post('/risk/generate', { year: year.value }))
}
function addPoint() { return act('新增自定义风险点', () => request.post('/risk/point-dict', { ...newPoint })) }
async function preview() {
  try {
    const r = await request.get('/risk/archive', { params: { year: year.value } })
    previewHtml.value = r.data.html
    previewVisible.value = true
  } catch (e) { ElMessage.error(e.message) }
}
async function generatePdf() {
  pdfInfo.value = await act('生成风险辨识归档 PDF', () =>
    request.post('/risk/archive/pdf', null, { params: { year: year.value } }))
}
const levelColor = { 红: 'danger', 橙: 'warning', 黄: 'primary', 蓝: 'info' }
onMounted(load)
</script>

<template>
  <div class="phase3">
    <el-card shadow="never" class="head">
      <h2>风险分级管控 <el-tag size="small" effect="plain">D3 · {{ four.catalog }}</el-tag></h2>
      <p>只做<strong>静态、事前</strong>风险辨识：勾选预置风险点库 → 模板生成手册、两级清单、每岗位一份告知卡、无重大风险说明；危货额外生成安全评估与无重大事故隐患声明。</p>
      <el-row :gutter="12" class="kpi">
        <el-col v-for="lv in ['红','橙','黄','蓝']" :key="lv" :span="4">
          <el-card shadow="never" class="color-card">
            <div class="lv" :class="'lv-' + lv">{{ lv }}色</div>
            <div class="num">{{ four.levels?.[lv] ?? 0 }}</div>
          </el-card>
        </el-col>
        <el-col :span="4"><el-statistic title="清单条目" :value="four.total" /></el-col>
        <el-col :span="4"><el-statistic title="岗位告知卡" :value="four.cards" /></el-col>
      </el-row>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="bar">
          <b>通用风险点库（勾选后生成清单）</b>
          <div>
            <el-input-number v-model="year" :controls="false" style="width: 100px" />
            <el-button @click="genList">生成清单</el-button>
            <el-button @click="genCards">生成告知卡</el-button>
            <el-button type="primary" @click="genAll">一键生成全部</el-button>
          </div>
        </div>
      </template>
      <el-table :data="points" border size="small" max-height="330" @selection-change="(v) => selected = v.map(x => x.id)">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="risk_name" label="风险点" />
        <el-table-column prop="risk_category" label="分类" width="110" />
        <el-table-column label="等级" width="90">
          <template #default="{ row }"><el-tag size="small" :type="levelColor[row.risk_level]" effect="dark">{{ row.risk_level }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="cargo_type" label="适用" width="90" />
        <el-table-column prop="control_measure" label="默认管控措施" show-overflow-tooltip />
      </el-table>
      <el-divider content-position="left">补充自定义风险点</el-divider>
      <el-form :model="newPoint" inline>
        <el-form-item><el-input v-model="newPoint.risk_name" placeholder="风险点名称" style="width: 200px" /></el-form-item>
        <el-form-item>
          <el-select v-model="newPoint.risk_category" style="width: 120px">
            <el-option v-for="c in ['driver','vehicle','cargo','env','manage']" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="newPoint.risk_level" style="width: 90px">
            <el-option v-for="l in ['红','橙','黄','蓝']" :key="l" :label="l" :value="l" />
          </el-select>
        </el-form-item>
        <el-form-item><el-input v-model="newPoint.control_measure" placeholder="管控措施" style="width: 280px" /></el-form-item>
        <el-form-item><el-button @click="addPoint">新增</el-button></el-form-item>
      </el-form>
      <el-alert v-if="genResult" type="success" :closable="false" class="gen"
        :title="`已生成：清单 ${genResult.list?.generated} 条 / 告知卡 ${genResult.cards?.created} 份 / 手册说明 ${genResult.manuals} 份（${genResult.bizType}，归档 ${genResult.catalog}）`" />
    </el-card>

    <el-row :gutter="14">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><b>风险辨识清单（{{ lists.length }}）</b></template>
          <el-table :data="lists" border size="small" max-height="280">
            <el-table-column prop="risk_desc" label="风险描述" show-overflow-tooltip />
            <el-table-column prop="risk_level" label="等级" width="70" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><b>岗位告知卡（{{ cards.length }}）</b></template>
          <el-table :data="cards" border size="small" max-height="280">
            <el-table-column prop="post_name" label="岗位" />
            <el-table-column prop="year" label="年度" width="80" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header><b>手册与说明（{{ manuals.length }}）</b></template>
          <el-table :data="manuals" border size="small" max-height="280">
            <el-table-column prop="doc_name" label="文档" />
            <el-table-column prop="doc_type" label="类型" width="120" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="foot">
      <template #header><b>风险辨识资料归档与下载</b></template>
      <el-button @click="preview">归档预览</el-button>
      <el-button type="primary" @click="generatePdf">生成归档 PDF</el-button>
      <template v-if="pdfInfo">
        <el-button @click="openPdf(pdfInfo.fileId)">打开/打印</el-button>
        <el-button @click="downloadPdf(pdfInfo.fileId, '风险辨识资料.pdf')">下载 PDF</el-button>
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
.bar { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
.color-card { text-align: center; }
.lv { font-size: 13px; font-weight: 600; }
.lv-红 { color: #d03050; } .lv-橙 { color: #e07800; } .lv-黄 { color: #b89000; } .lv-蓝 { color: #2f6fd0; }
.num { font-size: 26px; font-weight: 700; margin-top: 4px; }
.gen { margin-top: 12px; }
</style>
