<script setup>
/** 法律法规清单 · 平台安全生产适用法律法规标准清单
 *  数据：law_catalog（tenant_id=0 平台内置 88 条 + 企业可补充）；
 *  权限：服务商/超管（isPlatform）可增删改；企业只读 + 下载 PDF。
 *  refs 为引用关系 JSON：每部法规指向引用它的制度/台账范本/档案盒归档项。 */
import { computed, onMounted, ref } from 'vue'
import request from '@/api/request'
import { useAuthStore } from '@/store/auth'
import { ElMessage, ElMessageBox } from 'element-plus'
import Icon from '@/components/Icon.vue'

const auth = useAuthStore()
const canEdit = computed(() => auth.isPlatform)

const data = ref({ list: [], total: 0, active: 0, superseded: 0, refCount: 0, categories: [] })
const keyword = ref('')
const statusFilter = ref('')
const categoryFilter = ref('')
const levelFilter = ref('')
const bizFilter = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const r = await request.get('/flow/law/overview', {
      params: {
        keyword: keyword.value || undefined,
        status: statusFilter.value || undefined,
        category: categoryFilter.value || undefined,
        applyLevel: levelFilter.value || undefined,
        bizType: bizFilter.value || undefined,
      },
    })
    data.value = r.data || { list: [], total: 0 }
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}
onMounted(load)

const statusText = { active: '使用中', superseded: '待更新', repealed: '已废止' }
const levelColor = { 法律: 'tag-blue', 行政法规: 'tag-green', 部门规章: 'tag-purple', 标准: 'tag-orange', 规范性文件: 'tag-gray' }
const applyLevelColor = { 国家: 'tag-blue', 省级: 'tag-purple', 市级: 'tag-green', 区县级: 'tag-gray' }
const refTypeText = { module: '业务模块', tpl: '台账范本', doc: '档案归档' }
const bizText = { all: '通用', cargo: '普货', danger: '危货' }
const bizTag = { all: 'tag-gray', cargo: 'tag-blue', danger: 'tag-red' }

// ---------------- 详情抽屉：展示引用制度 ----------------
const drawer = ref(false)
const cur = ref(null)

function openDetail(row) {
  cur.value = row
  drawer.value = true
}

// ---------------- 下载 PDF（企业只读也能用） ----------------
const exporting = ref(false)
async function downloadPdf() {
  exporting.value = true
  try {
    const r = await request.get('/flow/law/export/pdf', { responseType: 'blob' })
    // 响应拦截器会把非 {code,msg,data} 的响应体（Blob）原样返回
    const blob = r instanceof Blob ? r : (r.data instanceof Blob ? r.data : new Blob([r.data], { type: 'application/pdf' }))
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = '适用安全生产法律法规标准清单.pdf'
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) { /* 拦截器已提示 */ }
  finally { exporting.value = false }
}

// ---------------- 新增 / 编辑（仅服务商/超管） ----------------
const dialog = ref(false)
const editing = ref(false)
const editingId = ref('')
const blankForm = () => ({
  lawName: '', lawNo: '', lawLevel: '部门规章', applyLevel: '国家', region: '全国',
  category: '综合安全', versionLabel: '', effectiveDate: '', reviseDate: '',
  status: 'active', bizType: 'all', isApplicable: 'yes',
  summary: '', remark: '', refsText: '',
})
const form = ref(blankForm())
const saving = ref(false)

function openCreate() {
  editing.value = false; form.value = blankForm(); dialog.value = true
}
function openEdit(row) {
  editing.value = true; editingId.value = String(row.id)
  form.value = {
    lawName: row.law_name || '', lawNo: row.law_no || '', lawLevel: row.law_level || '部门规章',
    applyLevel: row.apply_level || '国家', region: row.region || '全国',
    category: row.category || '综合安全', versionLabel: row.version_label || '',
    effectiveDate: row.effective_date || '', reviseDate: row.revise_date || '',
    status: row.status || 'active', bizType: row.biz_type || 'all',
    isApplicable: row.is_applicable || 'yes', summary: row.summary || '', remark: row.remark || '',
    refsText: (row.refs || []).map((r) => r.name).join('、'),
  }
  dialog.value = true
}
async function save() {
  if (!form.value.lawName.trim()) { ElMessage.warning('法规名称必填'); return }
  saving.value = true
  try {
    await request.post('/flow/law/save', { ...form.value, id: editing.value ? editingId.value : 0 })
    ElMessage.success(editing.value ? '法规已更新' : '法规已新增')
    dialog.value = false
    await load()
  } finally { saving.value = false }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.law_name}」？仅可删除自行新增的条目。`, '提示', { type: 'warning' })
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
        <div class="desc">安全生产适用法律法规标准清单 · 制度引用的法规可追溯</div>
        <div class="law-meta">
          <span class="tag tag-blue">合规依据</span>
          <span class="tag" :class="canEdit ? 'tag-purple' : 'tag-gray'">{{ canEdit ? '服务商可增删改' : '企业只读' }}</span>
          <span class="law-tables">覆盖消防 / 职业健康 / 交通 / 应急 / 教育培训等七类</span>
        </div>
      </div>
      <div class="actions">
        <button class="btn btn-outline" @click="load()"><Icon name="refresh" :size="15" /> 刷新</button>
        <button class="btn btn-outline" :disabled="exporting" @click="downloadPdf">
          <Icon name="download" :size="15" /> {{ exporting ? '生成中…' : '下载 PDF' }}
        </button>
        <button v-if="canEdit" class="btn btn-primary" @click="openCreate"><Icon name="plus" :size="15" /> 新增法规</button>
      </div>
    </div>

    <div class="law-hint"><Icon name="info" :size="15" />
      <template v-if="canEdit">服务商 / 超管可对清单新增、编辑、删除；法规修订后请及时更新版本并同步修订对应制度。</template>
      <template v-else>清单为平台维护的合规依据，企业只读；可下载 PDF 留存备查。</template>
    </div>

    <div class="law-stat-row">
      <div class="stat-card"><div class="num">{{ data.total }}</div><div class="lbl">法规总数</div></div>
      <div class="stat-card"><div class="num" style="color:var(--ok,#16a34a)">{{ data.active }}</div><div class="lbl">使用中</div></div>
      <div class="stat-card"><div class="num" style="color:#d97706">{{ data.superseded || 0 }}</div><div class="lbl">待更新（有新版未同步）</div></div>
      <div class="stat-card"><div class="num" style="color:var(--brand-600,#1a5bb0)">{{ data.refCount }}</div><div class="lbl">制度引用条目</div></div>
    </div>

    <div class="filter-bar law-filter">
      <div class="f-item"><label>关键字</label><input v-model="keyword" type="text" placeholder="法规名 / 文号 / 备注 / 引用制度" @keyup.enter="load()"></div>
      <div class="f-item"><label>分类</label>
        <select v-model="categoryFilter">
          <option value="">全部</option>
          <option v-for="c in data.categories" :key="c" :value="c">{{ c }}</option>
        </select>
      </div>
      <div class="f-item"><label>适用层级</label>
        <select v-model="levelFilter">
          <option value="">全部</option><option value="国家">国家</option><option value="省级">省级</option><option value="市级">市级</option>
        </select>
      </div>
      <div class="f-item"><label>适用业务</label>
        <select v-model="bizFilter">
          <option value="">全部</option><option value="all">通用</option><option value="cargo">普货</option><option value="danger">危货</option>
        </select>
      </div>
      <div class="f-item"><label>状态</label>
        <select v-model="statusFilter">
          <option value="">全部</option><option value="active">使用中</option>
          <option value="superseded">待更新</option><option value="repealed">已废止</option>
        </select>
      </div>
      <div class="f-actions">
        <button class="btn btn-primary btn-sm" @click="load()"><Icon name="search" :size="14" /> 查询</button>
        <button class="btn btn-outline btn-sm" @click="keyword=''; statusFilter=''; categoryFilter=''; levelFilter=''; bizFilter=''; load()">重置</button>
      </div>
    </div>

    <div class="card" style="padding:0;overflow:hidden">
      <div class="card-title" style="padding:16px 20px 0">
        法规清单 <span class="law-sub">共 {{ data.list.length }} 部 · 点击「引用制度」查看该法规支撑了哪些制度</span>
      </div>
      <div style="padding:0 20px 16px">
        <table class="table law-table" v-loading="loading">
          <thead><tr>
            <th style="width:24%">法规名称</th><th style="width:82px">类型</th><th style="width:90px">分类</th>
            <th style="width:56px">层级</th><th style="width:80px">地区</th><th style="width:66px">业务</th>
            <th style="width:88px">施行日期</th><th style="width:70px">状态</th>
            <th style="width:66px">引用</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="row in data.list" :key="row.id">
              <td>
                <b>{{ row.law_name }}</b>
                <div class="law-sub" v-if="row.law_no">{{ row.law_no }}</div>
                <div class="law-sub" v-if="row.version_label">最新修订 {{ row.version_label }}</div>
              </td>
              <td><span class="tag" :class="levelColor[row.law_level] || 'tag-gray'">{{ row.law_level }}</span></td>
              <td>{{ row.category }}</td>
              <td><span class="tag" :class="applyLevelColor[row.apply_level] || 'tag-gray'">{{ row.apply_level }}</span></td>
              <td>{{ row.region }}</td>
              <td>
                <span class="tag" :class="bizTag[row.biz_type] || 'tag-gray'">{{ bizText[row.biz_type] || '通用' }}</span>
                <span v-if="row.is_applicable === 'conditional'" class="law-sub">（有条件）</span>
              </td>
              <td>{{ row.effective_date || '—' }}</td>
              <td><span class="tag" :class="row.status === 'active' ? 'tag-green' : row.status === 'superseded' ? 'tag-yellow' : 'tag-gray'">{{ statusText[row.status] || row.status }}</span></td>
              <td><span class="law-ref-cnt">{{ row.refCount }}</span></td>
              <td class="law-ops">
                <a class="btn btn-primary btn-sm" @click="openDetail(row)">详情</a>
                <template v-if="canEdit">
                  <a class="btn btn-outline btn-sm" @click="openEdit(row)">编辑</a>
                  <a class="btn btn-outline btn-sm" @click="remove(row)">删除</a>
                </template>
              </td>
            </tr>
            <tr v-if="!data.list.length && !loading">
              <td colspan="10" style="text-align:center;color:var(--text-3);padding:28px">暂无匹配的法规记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ============ 详情抽屉 ============ -->
    <div class="law-mask" :class="{ on: drawer }" @click.self="drawer = false">
      <div class="law-drawer">
        <div class="law-dh">
          <div>
            <h2>{{ cur?.law_name }}</h2>
            <div class="law-sub">
              {{ cur?.law_no }} · {{ cur?.law_level }} · {{ cur?.apply_level }} · {{ cur?.region }}
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
            <div class="law-kv"><span class="k">类型</span>{{ cur.law_level || '—' }}</div>
            <div class="law-kv"><span class="k">适用层级</span>{{ cur.apply_level || '—' }}</div>
            <div class="law-kv"><span class="k">适用地区</span>{{ cur.region || '—' }}</div>
            <div class="law-kv"><span class="k">适用业务</span>{{ bizText[cur.biz_type] || '通用' }}{{ cur.is_applicable === 'conditional' ? '（有条件适用）' : '' }}</div>
            <div class="law-kv"><span class="k">最新修订</span>{{ cur.revise_date || cur.version_label || '—' }}</div>
            <div class="law-kv"><span class="k">备注</span><span style="flex:1">{{ cur.remark || '—' }}</span></div>
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

    <!-- 新增 / 编辑法规弹窗（仅服务商/超管） -->
    <el-dialog v-model="dialog" :title="(editing ? '编辑' : '新增') + '法规'" width="680px" destroy-on-close>
      <div class="law-form-grid">
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">法规名称 <span class="req">*</span></label>
          <input v-model="form.lawName" class="form-input" placeholder="《...》或全名"></div>
        <div class="form-item"><label class="form-label">令号 / 文号 / 版本</label>
          <input v-model="form.lawNo" class="form-input" placeholder="如 国务院令第406号 / GB 7258-2017"></div>
        <div class="form-item"><label class="form-label">法规类型</label>
          <select v-model="form.lawLevel" class="form-input">
            <option>法律</option><option>行政法规</option><option>部门规章</option><option>标准</option><option>规范性文件</option>
          </select></div>
        <div class="form-item"><label class="form-label">适用层级</label>
          <select v-model="form.applyLevel" class="form-input">
            <option>国家</option><option>省级</option><option>市级</option><option>区县级</option>
          </select></div>
        <div class="form-item"><label class="form-label">适用地区</label>
          <input v-model="form.region" class="form-input" placeholder="全国 / 广东省 / 佛山市"></div>
        <div class="form-item"><label class="form-label">分类</label>
          <input v-model="form.category" class="form-input" placeholder="消防安全 / 交通安全 / 职业健康…"></div>
        <div class="form-item"><label class="form-label">适用业务</label>
          <select v-model="form.bizType" class="form-input">
            <option value="all">通用</option><option value="cargo">普货</option><option value="danger">危货</option>
          </select></div>
        <div class="form-item"><label class="form-label">是否适用</label>
          <select v-model="form.isApplicable" class="form-input">
            <option value="yes">适用</option><option value="conditional">有条件适用</option>
          </select></div>
        <div class="form-item"><label class="form-label">施行日期</label>
          <input v-model="form.effectiveDate" class="form-input" placeholder="2023-08-21"></div>
        <div class="form-item"><label class="form-label">最新修订时间</label>
          <input v-model="form.reviseDate" class="form-input" placeholder="2023-08-21"></div>
        <div class="form-item"><label class="form-label">状态</label>
          <select v-model="form.status" class="form-input">
            <option value="active">使用中</option><option value="superseded">待更新</option><option value="repealed">已废止</option>
          </select></div>
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">摘要</label>
          <input v-model="form.summary" class="form-input" placeholder="一句话说明该法规管什么"></div>
        <div class="form-item" style="grid-column:1/-1"><label class="form-label">备注</label>
          <input v-model="form.remark" class="form-input" placeholder="如：企业自有停车场适用 / 已更新至2026年修正版"></div>
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
.law-filter input, .law-filter select { height: 36px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px; min-width: 140px; }
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
.law-mask { position: fixed; inset: 0; background: rgba(20,28,43,.45); z-index: 12000; display: none; justify-content: flex-end; }
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
.tag-orange { background: #FFF7ED; color: #c2410c; }
.tag-red { background: #FEF2F2; color: #dc2626; }
</style>
