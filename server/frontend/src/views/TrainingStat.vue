<script setup>
import { computed, onMounted, ref } from 'vue'
import request from '@/api/request'
import Icon from '@/components/Icon.vue'
import { ElMessage } from 'element-plus'

/**
 * C1 培训教育 · 学习情况统计（PC 端）
 * 对齐原型 pc/train_stat.html：人员 / 计划 / 观看进度 / 学习状态 / 考核结果。
 * 数据来自小程序端真实上报的观看进度与在线考核结果。
 */
const rows = ref([])
const plans = ref([])
const planId = ref('')
const status = ref('')
const loading = ref(false)
const showDetail = ref(false)
const detail = ref({})

const statusLabel = { not_started: '未开始', learning: '学习中', qualified: '学时达标',
  exam_pending: '待考核', completed: '已完成', failed: '考核未通过' }
const statusTag = { not_started: 'tag-gray', learning: 'tag-yellow', qualified: 'tag-blue',
  exam_pending: 'tag-blue', completed: 'tag-green', failed: 'tag-red' }

async function loadPlans() {
  try { plans.value = (await request.get('/training/plan/list')).data || [] } catch (e) { /* 忽略 */ }
}
async function load() {
  loading.value = true
  try {
    const res = await request.get('/training/stat/list', {
      params: { planId: planId.value || undefined, status: status.value || undefined },
    })
    rows.value = res.data || []
  } finally { loading.value = false }
}
function reset() { planId.value = ''; status.value = ''; load() }

const summary = computed(() => {
  const t = rows.value.length
  const done = rows.value.filter((r) => r.status === 'completed').length
  const learning = rows.value.filter((r) => r.status === 'learning' || r.status === 'qualified').length
  const failed = rows.value.filter((r) => r.status === 'failed').length
  const passed = rows.value.filter((r) => r.exam_result === 'pass').length
  return { t, done, learning, failed, passed, rate: t ? Math.round(done * 100 / t) : 0 }
})

function progressOf(r) {
  // watch_progress 是 JSON：每个视频 {position,duration}
  try {
    const p = typeof r.watch_progress === 'string' ? JSON.parse(r.watch_progress) : (r.watch_progress || {})
    const vals = Object.values(p)
    if (!vals.length) return 0
    let pos = 0, dur = 0
    for (const v of vals) { pos += Number(v.position || 0); dur += Number(v.duration || 0) }
    return dur ? Math.min(100, Math.round(pos * 100 / dur)) : 0
  } catch (e) { return 0 }
}
function viewDetail(r) {
  request.get(`/training/m/exam/${r.assign_id}/result`).then((res) => {
    detail.value = { ...r, exam: res.data || {} }
    showDetail.value = true
  })
}
async function exportCsv() {
  const head = ['姓名', '计划名称', '年月', '观看进度(%)', '观看时长(分)', '学习状态', '考试次数', '最近得分', '考核结果']
  const lines = rows.value.map((r) => [
    r.person_name || '', r.plan_name || '', `${r.year}-${r.month}`, progressOf(r),
    r.watch_minutes || 0, statusLabel[r.status] || r.status, r.exam_times || 0,
    r.score == null ? '' : r.score, r.exam_result === 'pass' ? '通过' : (r.exam_result === 'fail' ? '未通过' : '—'),
  ].map((x) => `"${String(x).replace(/"/g, '""')}"`).join(','))
  const csv = '\uFEFF' + [head.join(','), ...lines].join('\n')
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  a.download = '学习情况统计.csv'
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已导出统计表')
}

onMounted(async () => { await loadPlans(); await load() })
</script>

<template>
  <div class="pc-page">
    <div class="pc-header">
      <div>
        <h1>学习情况统计</h1>
        <div class="desc">从业人员观看进度与在线考核结果 · 数据由小程序端学习实时回传</div>
      </div>
      <div class="actions">
        <button class="btn btn-outline" @click="exportCsv"><Icon name="download" :size="16" /> 导出</button>
        <button class="btn btn-outline" @click="load"><Icon name="refresh" :size="16" /> 刷新</button>
      </div>
    </div>

    <div class="kpi-row">
      <div class="stat-card"><div class="k-label">应学人次</div><div class="k-val">{{ summary.t }}</div></div>
      <div class="stat-card"><div class="k-label">已完成</div><div class="k-val green">{{ summary.done }}</div></div>
      <div class="stat-card"><div class="k-label">学习中</div><div class="k-val blue">{{ summary.learning }}</div></div>
      <div class="stat-card"><div class="k-label">考核未通过</div><div class="k-val red">{{ summary.failed }}</div></div>
      <div class="stat-card"><div class="k-label">完成率</div><div class="k-val">{{ summary.rate }}%</div></div>
    </div>

    <div class="filter-bar">
      <div class="f-item"><label>计划名称</label>
        <select v-model="planId" class="form-select" @change="load">
          <option value="">全部计划</option>
          <option v-for="p in plans" :key="p.id" :value="String(p.id)">{{ p.plan_name }}</option>
        </select></div>
      <div class="f-item"><label>学习状态</label>
        <select v-model="status" class="form-select" @change="load">
          <option value="">全部</option>
          <option v-for="(v, k) in statusLabel" :key="k" :value="k">{{ v }}</option>
        </select></div>
      <div class="f-actions">
        <button class="btn btn-outline btn-sm" @click="reset">重置</button>
      </div>
    </div>

    <div class="card" style="padding: 0; overflow: hidden">
      <table class="table">
        <thead><tr>
          <th>用户姓名</th><th>计划名称</th><th>观看进度</th><th>学习时长</th>
          <th>学习状态</th><th>考试次数</th><th>考核得分</th><th>操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="r in rows" :key="r.assign_id">
            <td>{{ r.person_name || r.user_id }}</td>
            <td class="tl">{{ r.plan_name }}<span class="sub">（{{ r.year }} 年 {{ r.month }} 月）</span></td>
            <td>
              <div class="prog"><div class="prog-bar"><i :style="{ width: progressOf(r) + '%' }" /></div><span>{{ progressOf(r) }}%</span></div>
            </td>
            <td>{{ r.watch_minutes || 0 }} 分钟</td>
            <td><span class="tag" :class="statusTag[r.status] || 'tag-gray'">{{ statusLabel[r.status] || r.status }}</span></td>
            <td>{{ r.exam_times || 0 }} 次</td>
            <td>
              <span v-if="r.score != null" :class="r.exam_result === 'pass' ? 'score-pass' : 'score-fail'">{{ r.score }} 分</span>
              <span v-else class="muted">—</span>
            </td>
            <td><a class="btn btn-primary btn-sm" @click="viewDetail(r)">证明</a></td>
          </tr>
          <tr v-if="!rows.length"><td colspan="8" class="empty">暂无学习记录，请先在「岗中培训计划」下发人员并在小程序端学习</td></tr>
        </tbody>
      </table>
      <div class="table-foot"><span>共 {{ rows.length }} 条记录</span></div>
    </div>

    <!-- 培训证明 / 考核详情 -->
    <div class="modal-mask" :class="{ show: showDetail }" @click.self="showDetail = false">
      <div class="modal-box">
        <div class="modal-head"><span>培训证明 · {{ detail.person_name }}</span><span class="x" @click="showDetail = false">×</span></div>
        <div class="modal-body">
          <div class="cert-head">{{ detail.plan_name }}</div>
          <table class="mini-table">
            <tbody>
              <tr><th>姓名</th><td>{{ detail.person_name || '—' }}</td><th>学习状态</th><td>{{ statusLabel[detail.status] || detail.status }}</td></tr>
              <tr><th>观看进度</th><td>{{ progressOf(detail) }}%</td><th>累计学习时长</th><td>{{ detail.watch_minutes || 0 }} 分钟</td></tr>
              <tr><th>考核得分</th><td>{{ detail.score == null ? '—' : detail.score + ' 分' }}</td>
                  <th>考核结果</th><td>{{ detail.exam_result === 'pass' ? '通过' : (detail.exam_result === 'fail' ? '未通过' : '未考核') }}</td></tr>
            </tbody>
          </table>
          <div class="mini-h">考核试题与作答留痕</div>
          <div v-for="(q, idx) in detail.exam?.detail || []" :key="idx" class="q-item">
            <div class="q-stem">{{ idx + 1 }}. {{ q.stem }}
              <span class="tag" :class="q.correct ? 'tag-green' : 'tag-red'">{{ q.correct ? '答对' : '答错' }}</span></div>
            <div class="q-ans">正确答案：{{ q.answer }}　|　本人作答：{{ q.userAnswer || '未作答' }}</div>
          </div>
          <div v-if="!(detail.exam?.detail || []).length" class="empty">尚未参加考核，无试题留痕</div>
        </div>
        <div class="modal-foot"><button class="btn btn-primary" @click="showDetail = false">关闭</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kpi-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 16px; }
.stat-card { background: #fff; border: 1px solid var(--border); border-radius: 10px; padding: 14px 18px; }
.k-label { font-size: 12.5px; color: var(--text-3); margin-bottom: 6px; }
.k-val { font-size: 26px; font-weight: 800; color: var(--text); }
.k-val.green { color: #15803D; } .k-val.blue { color: #1D4ED8; } .k-val.red { color: #DC2626; }
.filter-bar { display: flex; align-items: flex-end; gap: 14px; margin-bottom: 14px; flex-wrap: wrap; }
.f-item { display: flex; flex-direction: column; gap: 5px; }
.f-item label { font-size: 12px; color: var(--text-3); font-weight: 600; }
.form-select { min-height: 34px; min-width: 200px; padding: 6px 10px; border: 1px solid var(--border-strong); border-radius: 6px; background: #fff; font-size: 13px; }
.f-actions { display: flex; gap: 8px; }
.table th, .table td { font-size: 13px; }
.table td.tl { text-align: left; }
.sub { color: var(--text-3); font-size: 12px; }
.muted { color: var(--text-3); }
.score-pass { color: #15803D; font-weight: 700; }
.score-fail { color: #DC2626; font-weight: 700; }
.prog { display: flex; align-items: center; gap: 8px; }
.prog-bar { width: 90px; height: 8px; background: #E2E8F0; border-radius: 6px; overflow: hidden; }
.prog-bar i { display: block; height: 100%; background: var(--brand-500); border-radius: 6px; }
.prog span { font-size: 12px; color: var(--text-2); }
.table-foot { padding: 10px 20px; font-size: 12.5px; color: var(--text-3); border-top: 1px solid var(--border); }
.empty { padding: 24px; text-align: center; color: var(--text-3); font-size: 14px; }
.modal-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, .48); display: none; align-items: center; justify-content: center; z-index: 200; padding: 20px; }
.modal-mask.show { display: flex; }
.modal-box { background: #fff; border-radius: 12px; width: min(680px, 100%); max-height: 90vh; overflow-y: auto; box-shadow: 0 8px 40px rgba(0,0,0,.28); }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 22px; border-bottom: 1px solid var(--border); font-size: 16px; font-weight: 700; }
.modal-head .x { cursor: pointer; color: var(--text-3); font-size: 22px; line-height: 1; }
.modal-body { padding: 18px 22px; }
.modal-foot { padding: 14px 22px; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; }
.cert-head { text-align: center; font-size: 17px; font-weight: 700; margin-bottom: 14px; }
.mini-table { width: 100%; border-collapse: collapse; }
.mini-table th, .mini-table td { border: 1px solid var(--border); padding: 8px 10px; font-size: 13px; }
.mini-table th { background: #f8fafd; font-weight: 700; color: var(--text-2); width: 18%; }
.mini-h { font-size: 13px; font-weight: 700; color: var(--text-2); margin: 16px 0 8px; }
.q-item { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin-bottom: 8px; }
.q-stem { font-size: 13px; line-height: 1.6; }
.q-ans { font-size: 12.5px; color: var(--text-2); margin-top: 5px; }
</style>
