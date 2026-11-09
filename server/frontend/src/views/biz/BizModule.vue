<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/api/request'
import { BIZ_PAGES, STATUS_MAP } from '@/config/bizPages'
import { bizModules } from '@/config/modules.generated.js'
import Icon from '@/components/Icon.vue'
import BizFeature from './BizFeature.vue'

/** 业务模块专用页容器：按 meta.moduleCode 读取展示配置，接后端真实表。
 *  设计依据：详细设计/00~21 + prototype/pc/*.html。
 *  全部 18 个动态模块共用此容器，避免 18 份重复代码。 */
const route = useRoute()
const code = computed(() => String(route.meta.moduleCode || route.params.module || ''))
const meta = computed(() => bizModules.find((m) => m.code === code.value) || {})
const cfg = computed(() => BIZ_PAGES[code.value] || null)

const rows = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const keyword = ref('')
const filterVals = reactive({})
const fields = ref([])
const dialogVisible = ref(false)
const editing = ref(false)
const form = ref({})
const saving = ref(false)

// 归档盒 -> 电子档案柜
const archiveBoxNo = computed(() => {
  const m = String(cfg.value?.box || '')
  const d = m.match(/盒([一二三四五六七八九十])/)
  return d ? d[1] : ''
})

const visibleFields = computed(() => fields.value.filter(
  (f) => f.control !== 'hidden' && f.name !== 'id' && f.name !== 'tenant_id',
))

async function loadFields() {
  if (!cfg.value) return
  try {
    const res = await request.get(`/biz/${cfg.value.table}/fields`)
    fields.value = res.data.fields || []
  } catch (e) { fields.value = [] }
}

async function load() {
  if (!cfg.value) return
  loading.value = true
  try {
    const params = { pageNo: pageNo.value, pageSize: pageSize.value }
    if (keyword.value) params.keyword = keyword.value
    for (const [k, v] of Object.entries(filterVals)) {
      if (v !== '' && v !== null && v !== undefined) params[k] = v
    }
    const res = await request.get(`/biz/${cfg.value.table}`, { params })
    const d = res.data || {}
    rows.value = d.records || []
    total.value = Number(d.total || 0)
  } catch (e) {
    rows.value = []
    total.value = 0
  } finally { loading.value = false }
}

function doSearch() { pageNo.value = 1; load() }
function resetFilter() {
  keyword.value = ''
  for (const k of Object.keys(filterVals)) delete filterVals[k]
  pageNo.value = 1; load()
}

function parseFilter(spec) {
  const parts = String(spec).split(':')
  return { type: parts[0] || 'input', options: parts[1] ? parts[1].split('/') : [] }
}

function openCreate() {
  editing.value = false
  form.value = {}
  dialogVisible.value = true
}
function openEdit(row) {
  editing.value = true
  form.value = { ...row }
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    const allowed = new Set(visibleFields.value.map((f) => f.name))
    const payload = {}
    for (const [k, v] of Object.entries(form.value)) {
      if (allowed.has(k) && v !== null && v !== '') payload[k] = v
    }
    if (editing.value) await request.put(`/biz/${cfg.value.table}/${form.value.id}`, payload)
    else await request.post(`/biz/${cfg.value.table}`, payload)
    ElMessage.success('保存成功（已自动登记归档项）')
    dialogVisible.value = false
    await load()
    featureKey.value++
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { saving.value = false }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('确认删除这条记录？', '删除', { type: 'warning' })
    await request.delete(`/biz/${cfg.value.table}/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 取消 */ }
}

function cellText(row, col) {
  const v = row[col[0]]
  if (v === null || v === undefined || v === '') return '—'
  if (col[0] === 'is_trailer') return Number(v) === 1 ? '挂' : '车'
  if (col[0] === 'gender') return Number(v) === 1 ? '男' : '女'
  if (col[0] === 'person_type') return v === 'manager' ? '管理人员' : '从业人员'
  if (col[0] === 'phase') return ({ out: '出车前', mid: '行车中', in: '收车后' })[v] || v
  return String(v)
}
function isStatusCol(col) { return ['status', 'result', 'danger_level', 'risk_level', 'severity'].includes(col[0]) }
// 原型 css 只提供 blue/gray/green/red/yellow 五种 tag，收敛到这五种
const TAG_ALIAS = { warning: 'yellow', primary: 'blue', success: 'green', danger: 'red', info: 'gray' }
function statusType(v) { return TAG_ALIAS[STATUS_MAP[String(v)]] || (STATUS_MAP[String(v)] && ['blue', 'gray', 'green', 'red', 'yellow'].includes(STATUS_MAP[String(v)]) ? STATUS_MAP[String(v)] : 'gray') }
function goArchive() {
  const box = archiveBoxNo.value
  window.location.href = box ? `/archive?box=${encodeURIComponent(box)}` : '/archive'
}

const featureKey = ref(0)
watch(code, async () => {
  keyword.value = ''
  for (const k of Object.keys(filterVals)) delete filterVals[k]
  pageNo.value = 1
  await loadFields()
  await load()
  featureKey.value++
})
onMounted(async () => { await loadFields(); await load() })
</script>

<template>
  <div v-if="cfg" class="bm-page">
    <!-- 页头：对齐 prototype/pc 的 pc-header -->
    <div class="pc-header bm-header">
      <div>
        <h1>{{ cfg.title }}</h1>
        <div class="desc">{{ cfg.desc }}</div>
        <div class="bm-meta">
          <span class="tag tag-blue">阶段 {{ meta.phase }}</span>
          <span class="tag tag-gray">{{ meta.group }}</span>
          <span v-if="cfg.box" class="tag tag-green">归档 {{ cfg.box }}</span>
          <span class="bm-tables">{{ meta.tables?.length || 0 }} 张业务表</span>
        </div>
      </div>
      <div class="actions">
        <button v-if="cfg.box" class="btn btn-outline" @click="goArchive">
          <Icon name="archive" :size="15" /> 存档到电子档案柜{{ cfg.box ? '（' + cfg.box + '）' : '' }}
        </button>
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
        <button class="btn btn-primary" @click="openCreate"><Icon name="plus" :size="15" /> 新增</button>
      </div>
    </div>

    <!-- 特色区块：按 feature 挂载，重点模块对齐原型 -->
    <BizFeature :key="featureKey" :code="code" :feature="cfg.feature" :rows="rows" :box="cfg.box" @changed="load" />

    <!-- 筛选栏 -->
    <div class="filter-bar bm-filter">
      <div class="f-item"><label>关键字</label><input v-model="keyword" type="text" placeholder="输入关键字搜索" @keyup.enter="doSearch"></div>
      <div v-for="f in (cfg.filters || [])" :key="f[0]" class="f-item">
        <label>{{ f[1] }}</label>
        <select v-if="parseFilter(f[2]).type === 'select'" v-model="filterVals[f[0]]">
          <option value="">全部</option>
          <option v-for="o in parseFilter(f[2]).options" :key="o" :value="o">{{ o }}</option>
        </select>
        <input v-else v-model="filterVals[f[0]]" type="text" :placeholder="'请输入' + f[1]" @keyup.enter="doSearch">
      </div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="doSearch"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="resetFilter">重置</button>
      </div>
    </div>

    <!-- 数据表格：真实后端数据 -->
    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0">{{ cfg.title }}记录 <span style="font-size:13px;color:var(--text-3);font-weight:400">共 {{ total }} 条 · 数据来源：业务真实表</span></div>
      <div style="padding:0 20px 16px">
        <table class="table bm-table" v-loading="loading">
          <thead><tr>
            <th v-for="c in cfg.cols" :key="c[0]">{{ c[1] }}</th>
            <th style="width:150px">操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td v-for="c in cfg.cols" :key="c[0]" :style="{ maxWidth: (c[2] || 140) + 'px' }">
                <span v-if="isStatusCol(c)" class="tag" :class="'tag-' + statusType(cellText(row, c))">{{ cellText(row, c) }}</span>
                <span v-else class="bm-cell" :title="cellText(row, c)">{{ cellText(row, c) }}</span>
              </td>
              <td>
                <a class="btn btn-primary btn-sm" @click="openEdit(row)">编辑</a>
                <a class="btn btn-outline btn-sm" @click="remove(row)">删除</a>
              </td>
            </tr>
            <tr v-if="!rows.length && !loading">
              <td :colspan="cfg.cols.length + 1" style="text-align:center;color:var(--text-3);padding:28px">暂无记录，点右上角「新增」录入</td>
            </tr>
          </tbody>
        </table>
        <div class="table-foot">
          <span>共 {{ total }} 条记录</span>
          <div class="pagination">
            <button :disabled="pageNo <= 1" @click="pageNo--; load()">&lt;</button>
            <button class="active">{{ pageNo }}</button>
            <button :disabled="pageNo * pageSize >= total" @click="pageNo++; load()">&gt;</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增/编辑弹窗：按 fields 元数据自动生成表单 -->
    <el-dialog v-model="dialogVisible" :title="(editing ? '编辑' : '新增') + ' · ' + cfg.title" width="720px" destroy-on-close>
      <div class="bm-form-grid">
        <div v-for="f in visibleFields" :key="f.name" class="form-item">
          <label class="form-label">{{ f.label }} <span v-if="f.required" class="req">*</span></label>
          <input v-model="form[f.name]" class="form-input" :placeholder="'请输入' + f.label">
        </div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存并登记归档</el-button>
      </template>
    </el-dialog>
  </div>
  <div v-else class="bm-empty">未找到该业务模块（{{ code }}）</div>
</template>

<style scoped>
.bm-page { display: flex; flex-direction: column; gap: 16px; }
.bm-header { align-items: flex-start; }
.bm-header h1 { margin: 0 0 6px; font-size: 22px; }
.bm-meta { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.bm-tables { font-size: 12.5px; color: var(--text-3); }
.bm-filter { flex-wrap: wrap; }
.bm-filter input, .bm-filter select { height: 36px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px; min-width: 150px; }
.bm-table { width: 100%; }
.bm-cell { display: inline-block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; vertical-align: bottom; }
.bm-table a { cursor: pointer; margin-right: 6px; }
.bm-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; max-height: 60vh; overflow: auto; }
.bm-form-grid .form-input { width: 100%; }
.bm-empty { padding: 60px; text-align: center; color: var(--text-3); }
</style>
