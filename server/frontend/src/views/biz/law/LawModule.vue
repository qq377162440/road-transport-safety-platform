<script setup>
/** 法律法规清单 · 展示目前制度引用的法规列表
 *  数据：law_catalog（tenant_id=0 平台内置 + 企业补充）；
 *  refs 为引用关系 JSON：每部法规指向引用它的制度/台账范本/档案盒归档项，
 *  详情抽屉逐条展示「哪些制度在用这部法规」，做到制度—法规可追溯。 */
import { computed, onMounted, ref } from 'vue'
import request from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import Icon from '@/components/Icon.vue'

const data = ref({ list: [], total: 0, active: 0, superseded: 0, refCount: 0, categories: [] })
const keyword = ref('')
const statusFilter = ref('')
const categoryFilter = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await request.get('/flow/law/overview', {
      params: {
        keyword: keyword.value || undefined,
        status: statusFilter.value || undefined,
        category: categoryFilter.value || undefined,
      },
    })
    data.value = r.data || { list: [], total: 0 }
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

const statusText = { active: '使用中', superseded: '待更新', repealed: '已废止' }
const levelColor = { 法律: 'tag-blue', 行政法规: 'tag-green', 部门规章: 'tag-purple', 规范性文件: 'tag-gray' }
const refTypeText = { module: '业务模块', tpl: '台账范本', doc: '档案归档' }
const scopeText = { all: '普货/危货通用', danger: '仅危货' }

// ---------------- 详情抽屉：展示引用制度 ----------------
const drawer = ref(false)
const cur = ref(null)

function openDetail(row) {
  cur.value = row
  drawer.value = true
}

// ---------------- 新增 / 编辑 ----------------
const dialog = ref(false)
const editing = ref(false)
const editingId = ref('')
const blankForm = () => ({
  lawName: '', lawNo: '', lawLevel: '法律', category: '综合安全',
  versionLabel: '', effectiveDate: '', status: 'active', applyScope: 'all',
  summary: '', refsText: '',
})
const form = ref(blankForm())
const saving = ref(false)

function openCreate() {
  editing.value = false; form.value = blankForm(); dialog.value = true
}
function openEdit(row) {
  editing.value = true; editingId.value = String(row.id)
  form.value = {
    lawName: row.law_name || '', lawNo: row.law_no || '', lawLevel: row.law_level || '法律',
    category: row.category || '综合安全', versionLabel: row.version_label || '',
    effectiveDate: row.effective_date || '', status: row.status || 'active',
    applyScope: row.apply_scope || 'all', summary: row.summary || '',
    refsText: (row.refs || []).map((r) => r.name).join('、'),
  }
  dialog.value = true
}
async function save() {
  if (!form.value.lawName.trim()) { ElMessage.warning('法规名称必填'); return }
  saving.value = true
  try {
    await request.post('/flow/law/save', { ...form.value, id: editing.value ? editingId.value : 0 })
    ElMessage.success(editing.value ? '法规已更新' : '法规已补充')
    dialog.value = false
    await load()
  } finally { saving.value = false }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.law_name}」？仅可删除企业自行补充的条目。`, '提示', { type: 'warning' })
  } catch (e) { return }
  await request.delete(`/flow/law/${row.id}`)
  ElMessage.success('已删除')
  await load()
}
</script>

<template>
  <div class="law-page">
    <div class="pc-header law-header">
      <div>
        <h1>法律法规清单</h1>
        <div class="desc">安全生产相关法律法规台账 · 展示目前制度引用的法规列表，做到制度—法规可追溯</div>
        <div class="law-meta">
          <span class="tag tag-blue">合规依据</span>
          <span class="tag tag-gray">平台内置 + 企业可补充</span>
          <span class="law-tables">法规引用关系（refs）逐条可查</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
        <button class="btn btn-primary" @click="openCreate"><Icon name="plus" :size="15" /> 新增法规</button>
      </div>
    </div>

    <div class="law-hint"><Icon name="info" :size="15" />
      平台内置 14 部道路运输企业常用法规（含版本与施行日期），每部法规都标注了「哪些制度 / 台账范本 / 档案归档项在引用它」；法规修订后请及时更新版本并同步修订对应制度。
    </div>

    <div class="law-stat-row">
      <div class="stat-card"><div class="num">{{ data.total }}</div><div class="lbl">法规总数</div></div>
      <div class="stat-card"><div class="num" style="color:var(--ok,#16a34a)">{{ data.active }}</div><div class="lbl">使用中</div></div>
      <div class="stat-card"><div class="num" style="color:#d97706">{{ data.superseded || 0 }}</div><div class="lbl">待更新（有新版未同步）</div></div>
      <div class="stat-card"><div class="num" style="color:var(--brand-600,#1a5bb0)">{{ data.refCount }}</div><div class="lbl">制度引用条目</div></div>
    </div>

    <div class="filter-bar law-filter">
      <div class="f-item"><label>关键字</label><input v-model="keyword" type="text" placeholder="法规名 / 摘要 / 引用制度" @keyup.enter="load()"></div>
      <div class="f-item"><label>状态</label>
        <select v-model="statusFilter">
          <option value="">全部</option><option value="active">使用中</option>
          <option value="superseded">待更新</option><option value="repealed">已废止</option>
        </select>
      </div>
      <div class="f-item"><label>分类</label>
        <select v-model="categoryFilter">
          <option value="">全部</option>
          <option v-for="c in data.categories" :key="c" :value="c">{{ c }}</option>
        </select>
      </div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="load()"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="keyword=''; statusFilter=''; categoryFilter=''; load()">重置</button>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0">
        法规清单 <span class="law-sub">共 {{ data.list.length }} 部 · 点击「引用制度」查看该法规支撑了哪些制度</span>
      </div>
      <div style="padding:0 20px 16px">
        <table class="table law-table" v-loading="loading">
          <thead><tr>
            <th style="width:34%">法规名称（版本）</th><th style="width:90px">层级</th><th style="width:110px">分类</th>
            <th style="width:110px">施行日期</th><th style="width:110px">适用</th><th style="width:90px">状态</th>
            <th style="width:110px">引用制度</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="row in data.list" :key="row.id">
              <td>
                <b>{{ row.law_name }}</b>
                <div class="law-sub" v-if="row.law_no">{{ row.law_no }}</div>
                <div class="law-sub" v-if="row.version_label">{{ row.version_label }}</div>
              </td>
              <td><span class="tag" :class="levelColor[row.law_level] || 'tag-gray'">{{ row.law_level }}</span></td>
              <td>{{ row.category }}</td>
              <td>{{ row.effective_date || '—' }}</td>
              <td>{{ scopeText[row.apply_scope] || '通用' }}</td>
              <td><span class="tag" :class="row.status === 'active' ? 'tag-green' : row.status === 'superseded' ? 'tag-yellow' : 'tag-gray'">{{ statusText[row.status] || row.status }}</span></td>
              <td><span class="law-ref-cnt">{{ row.refCount }}</span></td>
              <td class="law-ops">
                <a class="btn btn-primary btn-sm" @click="openDetail(row)">引用制度</a>
                <a class="btn btn-outline btn-sm" @click="openEdit(row)">编辑</a>
                <a v-if="row.id" class="btn btn-outline btn-sm" @click="remove(row)">删除</a>
              </td>
            </tr>
            <tr v-if="!data.list.length && !loading">
              <td colspan="8" style="text-align:center;color:var(--text-3);padding:28px">暂无法规记录，点右上角「新增法规」补充</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ 引用制度详情抽屉 ============ -->
    <div class="law-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="law-drawer">
        <div class="law-dh">
          <div>
            <h2>{{ cur?.law_name }}</h2>
            <div class="law-sub">
              {{ cur?.law_no }} · {{ cur?.law_level }} · {{ cur?.version_label }}
              <template v-if="cur?.effective_date"> · {{ cur.effective_date }} 施行</template>
            </div>
          </div>
          <button class="law-close" @click="drawer = false">✕</button>
        </div>
        <div v-if="cur" class="law-body">
          <div class="card law-group">
            <div class="card-title">法规信息</div>
            <div class="law-kv"><span class="k">状态</span>
              <span class="tag" :class="cur.status === 'active' ? 'tag-green' : cur.status === 'superseded' ? 'tag-yellow' : 'tag-gray'">{{ statusText[cur.status] || cur.status }}</span></div>
            <div class="law-kv"><span class="k">适用范围</span>{{ scopeText[cur.apply_scope] || '通用' }}</div>
            <div class="law-kv"><span class="k">摘要</span><span style="flex:1">{{ cur.summary || '—' }}</span></div>
          </div>

          <div class="card law-group">
            <div class="card-title">引用本法规的制度（{{ (cur.refs || []).length }}）<span class="law-sub">制度—法规可追溯：修订法规后请同步检查以下制度</span></div>
            <div v-if="!(cur.refs || []).length" class="law-sub" style="padding:8px 0">暂无引用记录</div>
            <div v-for="(r, i) in cur.refs || []" :key="i" class="law-ref">
              <span class="tag" :class="r.type === 'module' ? 'tag-blue' : r.type === 'tpl' ? 'tag-purple' : 'tag-green'">{{ refTypeText[r.type] || '制度' }}</span>
              <span class="nm">{{ r.name }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 新增 / 编辑法规弹窗 -->
    <el-dialog v-model="dialog" :title="(editing ? '编辑' : '新增') + '法规'" width="640px" destroy-on-close>
      <div class="law-form-grid">
        <div class="form-item"><label class="form-label">法规名称 <span class="req">*</span></label>
          <input v-model="form.lawName" class="form-input" placeholder="《...》或全名"></div>
        <div class="form-item"><label class="form-label">令号 / 文号</label>
          <input v-model="form.lawNo" class="form-input" placeholder="如 国务院令第406号"></div>
        <div class="form-item"><label class="form-label">层级</label>
          <select v-model="form.lawLevel" class="form-input">
            <option>法律</option><option>行政法规</option><option>部门规章</option><option>规范性文件</option>
          </select></div>
        <div class="form-item"><label class="form-label">分类</label>
          <input v-model="form.category" class="form-input" placeholder="综合安全 / 运输经营 / 危货专项…"></div>
        <div class="form-item"><label class="form-label">版本说明</label>
          <input v-model="form.versionLabel" class="form-input" placeholder="如 2021 年修正 / 2023 第五次修订"></div>
        <div class="form-item"><label class="form-label">施行日期</label>
          <input v-model="form.effectiveDate" class="form-input" placeholder="2023-08-21"></div>
        <div class="form-item"><label class="form-label">状态</label>
          <select v-model="form.status" class="form-input">
            <option value="active">使用中</option><option value="superseded">待更新</option><option value="repealed">已废止</option>
          </select></div>
        <div class="form-item"><label class="form-label">适用范围</label>
          <select v-model="form.applyScope" class="form-input">
            <option value="all">普货/危货通用</option><option value="danger">仅危货</option>
          </select></div>
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">摘要</label>
          <input v-model="form.summary" class="form-input" placeholder="一句话说明该法规管什么"></div>
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">引用制度（顿号/逗号分隔）</label>
          <input v-model="form.refsText" class="form-input" placeholder="如：安全生产责任制（逐级签订）、安全生产责任书（各级岗位）"></div>
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.law-page { display: flex; flex-direction: column; gap: 16px; }
.law-header h1 { margin: 0 0 6px; font-size: 22px; }
.law-meta { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.law-tables { font-size: 12.5px; color: var(--text-3); }
.law-filter { flex-wrap: wrap; }
.law-filter input, .law-filter select { height: 36px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px; min-width: 160px; }
.law-table { width: 100%; }
.law-table a { cursor: pointer; margin-right: 6px; }
.law-sub { font-size: 12.5px; color: var(--text-3); font-weight: 400; }
.law-hint { background: #FFFBEB; border: 1px solid #FCD34D; border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #8a5a00; display: flex; align-items: center; gap: 8px; }
.law-stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.stat-card { background: #fff; border: 1px solid var(--border); border-radius: 10px; padding: 16px 18px; }
.stat-card .num { font-size: 26px; font-weight: 700; color: var(--brand-600, #1a5bb0); line-height: 1.2; }
.stat-card .lbl { font-size: 12.5px; color: var(--text-3); margin-top: 4px; }
.law-ref-cnt { display: inline-block; min-width: 30px; text-align: center; background: #EFF6FF; color: var(--brand-600, #1a5bb0); border-radius: 10px; padding: 2px 8px; font-weight: 700; font-size: 12.5px; }
.law-ops { white-space: nowrap; }
/* 抽屉 */
.law-mask { position: fixed; inset: 0; background: rgba(20,28,43,.45); z-index: 10000; display: none; justify-content: flex-end; }
.law-mask.on { display: flex; }
.law-drawer { width: 760px; max-width: 94vw; height: 100%; background: #F5F7FB; display: flex; flex-direction: column; box-shadow: -8px 0 30px rgba(0,0,0,.15); }
.law-dh { background: #fff; padding: 18px 24px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: flex-start; }
.law-dh h2 { margin: 0 0 4px; font-size: 19px; }
.law-close { border: none; background: #F1F5F9; width: 32px; height: 32px; border-radius: 8px; cursor: pointer; font-size: 15px; }
.law-body { flex: 1; overflow: auto; padding: 18px 24px 40px; display: flex; flex-direction: column; gap: 14px; }
.law-group { padding: 16px 20px; }
.law-kv { display: flex; gap: 10px; padding: 7px 0; font-size: 13.5px; border-bottom: 1px dashed var(--border); }
.law-kv:last-child { border-bottom: none; }
.law-kv .k { color: var(--text-3); width: 70px; flex-shrink: 0; }
.law-ref { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid var(--border); font-size: 13px; }
.law-ref:last-child { border-bottom: none; }
.law-ref .nm { flex: 1; }
.law-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.law-form-grid .form-input { width: 100%; }
.tag-purple { background: #F5F3FF; color: #6d28d9; }
</style>
