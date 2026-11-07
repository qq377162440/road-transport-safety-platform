<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import request from '@/api/request'
import Icon from '@/components/Icon.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { AUTO_LOGIN } from '@/config/devFlag'

/**
 * C1 培训教育 · 岗中月度培训计划（PC 下发端）
 * 对齐原型 pc/training.html：计划列表 + 新建计划 + 下发人员 + 学时开关 + 跳小程序学习端。
 */
const plans = ref([])
const courses = ref([])
const papers = ref([])
const persons = ref([])
const loading = ref(false)

const showEditor = ref(false)
const form = reactive({ id: '', planName: '', year: new Date().getFullYear(), month: new Date().getMonth() + 1,
  courseId: '', paperId: '', passScore: 60, maxRetry: 3, forceLearn: 0, learnHours: 2, description: '' })

const showAssign = ref(false)
const assignPlan = ref(null)
const checkedPersons = ref([])

const showDetail = ref(false)
const detail = ref({})

const statusLabel = { draft: '草稿', published: '已发布', ongoing: '进行中', finished: '已完成' }
const statusTag = { draft: 'tag-gray', published: 'tag-blue', ongoing: 'tag-yellow', finished: 'tag-green' }
const assignStatusLabel = { not_started: '未开始', learning: '学习中', qualified: '学时达标',
  exam_pending: '待考核', completed: '已完成', failed: '考核未通过' }

async function loadAll() {
  loading.value = true
  try {
    const [p, c, pp, pe] = await Promise.all([
      request.get('/training/plan/list'),
      request.get('/training/course/list', { params: { pageSize: 50 } }),
      request.get('/training/paper/list'),
      request.get('/training/person/list'),
    ])
    plans.value = p.data || []
    courses.value = c.data?.records || []
    papers.value = pp.data || []
    persons.value = pe.data || []
  } finally { loading.value = false }
}

function openEditor(row) {
  const p = row || null
  Object.assign(form, {
    id: p ? String(p.id) : '',
    planName: p ? p.plan_name : `${new Date().getFullYear()}年${new Date().getMonth() + 1}月岗中安全教育培训`,
    year: p ? p.year : new Date().getFullYear(),
    month: p ? p.month : new Date().getMonth() + 1,
    courseId: p?.course_id ? String(p.course_id) : (courses.value[0] ? String(courses.value[0].id) : ''),
    paperId: p?.paper_id ? String(p.paper_id) : (papers.value[0] ? String(papers.value[0].id) : ''),
    passScore: p ? p.pass_score : 60,
    maxRetry: p ? p.max_retry : 3,
    forceLearn: p ? p.force_learn : 0,
    learnHours: p ? (p.learn_hours || 2) : 2,
    description: p ? (p.description || '') : '',
  })
  showEditor.value = true
}
function onCourseChange() {
  const c = courses.value.find((x) => String(x.id) === form.courseId)
  if (c?.paper_id) form.paperId = String(c.paper_id)
}
async function savePlan() {
  if (!form.planName.trim()) { ElMessage.warning('请填写计划名称'); return }
  if (!form.courseId) { ElMessage.warning('请选择课程'); return }
  if (!form.paperId) { ElMessage.warning('请选择考试卷'); return }
  await request.post('/training/plan', { ...form, id: form.id || null })
  ElMessage.success(form.id ? '计划已更新' : '计划已创建')
  showEditor.value = false
  loadAll()
}
async function publish(row) {
  await request.post(`/training/plan/${row.id}/publish`)
  ElMessage.success('计划已发布，可下发人员')
  loadAll()
}
async function toggleForce(row) {
  const next = row.force_learn === 1 ? 0 : 1
  await request.put(`/training/plan/${row.id}/force-learn`, { forceLearn: next })
  ElMessage.success(next ? '已开启学时管控：须学满全部视频才可考核' : '已关闭学时管控：随时可参加考核')
  loadAll()
}

function openAssign(row) {
  assignPlan.value = row
  checkedPersons.value = []
  showAssign.value = true
  request.get(`/training/plan/${row.id}`).then((res) => {
    checkedPersons.value = (res.data?.assigns || []).map((a) => String(a.user_id))
  })
}
function togglePerson(id) {
  const i = checkedPersons.value.indexOf(String(id))
  i >= 0 ? checkedPersons.value.splice(i, 1) : checkedPersons.value.push(String(id))
}
function selectAll() { checkedPersons.value = persons.value.map((p) => String(p.id)) }
function clearAll() { checkedPersons.value = [] }
async function submitAssign() {
  if (!checkedPersons.value.length) { ElMessage.warning('请至少选择一名人员'); return }
  const res = await request.post(`/training/plan/${assignPlan.value.id}/assign`, { personIds: checkedPersons.value })
  ElMessage.success(`已下发 ${res.data} 人（重复人员自动跳过）`)
  showAssign.value = false
  loadAll()
}

function viewDetail(row) {
  request.get(`/training/plan/${row.id}`).then((res) => {
    detail.value = res.data || {}
    showDetail.value = true
  })
}

/** 带 token 打开小程序学习端（逐项测试用，最后可去掉 token 透传） */
function goStudy() {
  const token = localStorage.getItem('token') || ''
  const url = `/m/study?token=${encodeURIComponent(token)}`
  window.open(url, '_blank')
}
const studyHref = computed(() => `/m/study?token=${encodeURIComponent(localStorage.getItem('token') || '')}`)

onMounted(loadAll)
</script>

<template>
  <div class="pc-page">
    <div class="pc-header">
      <div>
        <h1>岗中培训计划</h1>
        <div class="desc">每月一次线上学习 · 选课程与考试卷 → 下发从业人员 → 小程序端看视频做考核</div>
      </div>
      <div class="actions">
        <a class="btn btn-outline" :href="studyHref" target="_blank"><Icon name="smartphone" :size="16" /> 打开小程序学习端</a>
        <button class="btn btn-outline" @click="$router.push('/training/course')"><Icon name="bookOpen" :size="16" /> 课程库</button>
        <button class="btn btn-primary" @click="openEditor()"><Icon name="plus" :size="16" /> 新建计划</button>
      </div>
    </div>

    <div class="hint">
      <Icon name="info" :size="16" />
      <div>
        <b>岗中培训＝每月一次线上学习</b>，归档到「安全生产月度培训」学习报告。
        服务商/安全员在 PC 端下发课程与人员，从业人员在小程序端完成<b>看视频（记录进度）+ 在线考核（自动判分）</b>，
        结果实时汇总到<a href="javascript:;" @click="$router.push('/training/stat')">学习情况统计</a>。
        <b>学时开关</b>默认关闭（随时可考），开启后须学满全部视频才解锁考核。
      </div>
    </div>

    <div class="card" style="padding: 0; overflow: hidden">
      <div class="card-title" style="padding: 16px 20px 0">培训计划列表 <span class="tag tag-gray">{{ plans.length }}</span></div>
      <div style="padding: 0 20px 16px">
        <table class="table">
          <thead><tr>
            <th>计划名称</th><th>年度/月份</th><th>课程</th><th>考试卷</th><th>学时管控</th>
            <th>学习形式</th><th>已下发/已完成</th><th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr v-for="p in plans" :key="p.id">
              <td class="tl">{{ p.plan_name }}</td>
              <td>{{ p.year }} 年 {{ p.month }} 月</td>
              <td>{{ p.course_name || '—' }}</td>
              <td>{{ p.paper_name || '—' }}</td>
              <td>
                <span class="tag" :class="p.force_learn === 1 ? 'tag-yellow' : 'tag-gray'" style="cursor: pointer" @click="toggleForce(p)" :title="'点击切换学时开关'">
                  {{ p.force_learn === 1 ? '开启·学满才考' : '关闭·随时可考' }}
                </span>
              </td>
              <td>线上学习</td>
              <td>{{ p.assign_count || 0 }} / {{ p.done_count || 0 }} 人</td>
              <td><span class="tag" :class="statusTag[p.status] || 'tag-gray'">{{ statusLabel[p.status] || p.status }}</span></td>
              <td class="ops">
                <a class="btn btn-primary btn-sm" @click="viewDetail(p)">详情</a>
                <a class="btn btn-outline btn-sm" @click="openAssign(p)">下发人员</a>
                <a v-if="p.status === 'draft'" class="btn btn-outline btn-sm" @click="publish(p)">发布</a>
                <a class="btn btn-outline btn-sm" @click="openEditor(p)">编辑</a>
              </td>
            </tr>
            <tr v-if="!plans.length"><td colspan="9" class="empty">暂无岗中培训计划，点击右上角「新建计划」</td></tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="flow-note">
      <b>完整闭环</b>
      <span>① 课程库维护视频与考题 → ② 本页新建月度计划并下发人员 → ③ <a :href="studyHref" target="_blank">小程序端看视频、做考核</a> → ④ 学习情况统计查看进度与成绩</span>
    </div>

    <!-- 新建/编辑计划 -->
    <div class="modal-mask" :class="{ show: showEditor }" @click.self="showEditor = false">
      <div class="modal-box">
        <div class="modal-head"><span>{{ form.id ? '编辑岗中培训计划' : '新建岗中培训计划' }}</span><span class="x" @click="showEditor = false">×</span></div>
        <div class="modal-body">
          <div class="form-mini">
            <div class="form-item"><label class="form-label">计划名称 <span class="req">*</span></label>
              <input v-model="form.planName" class="form-input" /></div>
            <div class="form-row">
              <div class="form-item"><label class="form-label">年度</label><input v-model.number="form.year" class="form-input" type="number" /></div>
              <div class="form-item"><label class="form-label">月份</label>
                <select v-model.number="form.month" class="form-select"><option v-for="m in 12" :key="m" :value="m">{{ m }} 月</option></select></div>
              <div class="form-item"><label class="form-label">应学学时（提示/强制标准）</label><input v-model.number="form.learnHours" class="form-input" type="number" min="1" /></div>
            </div>
            <div class="form-row">
              <div class="form-item"><label class="form-label">选用课程 <span class="req">*</span></label>
                <select v-model="form.courseId" class="form-select" @change="onCourseChange">
                  <option v-for="c in courses" :key="c.id" :value="String(c.id)">{{ c.course_name }}</option>
                </select></div>
              <div class="form-item"><label class="form-label">考试卷 <span class="req">*</span></label>
                <select v-model="form.paperId" class="form-select">
                  <option v-for="p in papers" :key="p.id" :value="String(p.id)">{{ p.paper_name }}（{{ p.question_count }} 题）</option>
                </select></div>
            </div>
            <div class="form-row">
              <div class="form-item"><label class="form-label">考核及格线（分）</label><input v-model.number="form.passScore" class="form-input" type="number" min="1" max="100" /></div>
              <div class="form-item"><label class="form-label">最大考试次数</label><input v-model.number="form.maxRetry" class="form-input" type="number" min="1" /></div>
              <div class="form-item"><label class="form-label">学时开关</label>
                <select v-model.number="form.forceLearn" class="form-select">
                  <option :value="0">关闭（随时可考，默认）</option><option :value="1">开启（学满才解锁考核）</option>
                </select></div>
            </div>
            <div class="form-item"><label class="form-label">培训说明</label>
              <textarea v-model="form.description" class="form-input" rows="2" placeholder="本月学习内容说明，展示给从业人员"></textarea></div>
            <div class="hint-line">保存后自动把所选课程的视频挂到本月计划；下发人员后即可在小程序端开始学习。</div>
          </div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline" @click="showEditor = false">取消</button>
          <button class="btn btn-primary" @click="savePlan">{{ form.id ? '保存修改' : '创建计划' }}</button>
        </div>
      </div>
    </div>

    <!-- 下发人员 -->
    <div class="modal-mask" :class="{ show: showAssign }" @click.self="showAssign = false">
      <div class="modal-box">
        <div class="modal-head"><span>下发人员 · {{ assignPlan?.plan_name }}</span><span class="x" @click="showAssign = false">×</span></div>
        <div class="modal-body">
          <div class="assign-bar">
            <span>从企业人员名册选择要参加本月培训的人员（已下发的不重复添加）</span>
            <a class="btn btn-outline btn-sm" @click="selectAll">全选</a>
            <a class="btn btn-outline btn-sm" @click="clearAll">清空</a>
          </div>
          <div class="person-grid">
            <label v-for="p in persons" :key="p.id" class="person-opt" :class="{ on: checkedPersons.includes(String(p.id)) }">
              <input type="checkbox" :checked="checkedPersons.includes(String(p.id))" @change="togglePerson(p.id)" />
              <Icon name="user" :size="15" /> {{ p.name }}
            </label>
          </div>
          <div v-if="!persons.length" class="empty">企业人员名册为空</div>
          <div class="hint-line">已选 {{ checkedPersons.length }} 人。下发后会自动发布计划，从业人员即可在小程序端看到学习任务。</div>
        </div>
        <div class="modal-foot">
          <button class="btn btn-outline" @click="showAssign = false">取消</button>
          <button class="btn btn-primary" @click="submitAssign">确认下发</button>
        </div>
      </div>
    </div>

    <!-- 计划详情 -->
    <div class="modal-mask" :class="{ show: showDetail }" @click.self="showDetail = false">
      <div class="modal-box">
        <div class="modal-head"><span>计划详情 · {{ detail.plan_name }}</span><span class="x" @click="showDetail = false">×</span></div>
        <div class="modal-body">
          <div class="tag-row">
            <span class="tag tag-blue">{{ detail.year }} 年 {{ detail.month }} 月</span>
            <span class="tag tag-gray">课程：{{ detail.course_name || '—' }}</span>
            <span class="tag tag-gray">考试卷：{{ detail.paper_name || '—' }}</span>
            <span class="tag" :class="detail.force_learn === 1 ? 'tag-yellow' : 'tag-gray'">{{ detail.force_learn === 1 ? '学满才考' : '随时可考' }}</span>
            <span class="tag">及格 {{ detail.pass_score }} 分 · 最多 {{ detail.max_retry }} 次</span>
          </div>
          <p v-if="detail.description" class="desc-line">{{ detail.description }}</p>
          <div class="mini-h">本月课程视频（{{ (detail.videos || []).length }} 段）</div>
          <div v-for="v in detail.videos || []" :key="v.id" class="vid-item">
            <Icon name="video" :size="15" /><span>{{ v.video_name }}</span><span class="vd">{{ v.duration_sec }} 秒</span>
          </div>
          <div class="mini-h">已下发人员（{{ (detail.assigns || []).length }} 人）</div>
          <table class="mini-table" v-if="(detail.assigns || []).length">
            <thead><tr><th>姓名</th><th>学习状态</th><th>观看时长</th><th>考试次数</th><th>最近得分</th></tr></thead>
            <tbody><tr v-for="a in detail.assigns" :key="a.id">
              <td>{{ a.person_name || a.user_id }}</td>
              <td><span class="tag" :class="a.status === 'completed' ? 'tag-green' : (a.status === 'failed' ? 'tag-red' : 'tag-gray')">{{ assignStatusLabel[a.status] || a.status }}</span></td>
              <td>{{ a.watch_minutes || 0 }} 分钟</td>
              <td>{{ a.exam_times || 0 }}</td>
              <td>{{ a.last_score == null ? '—' : a.last_score }}</td>
            </tr></tbody>
          </table>
          <div v-else class="empty">尚未下发人员，点击列表「下发人员」</div>
        </div>
        <div class="modal-foot"><button class="btn btn-primary" @click="showDetail = false">关闭</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hint { display: flex; align-items: flex-start; gap: 10px; background: #EFF6FF; border: 1px solid #BFDBFE;
  border-radius: 8px; padding: 11px 16px; font-size: 13px; color: #475569; line-height: 1.8; margin-bottom: 16px; }
.hint b { color: #1E40AF; }
.hint a { color: #1D4ED8; font-weight: 600; text-decoration: underline; }
.flow-note { margin-top: 16px; background: #F8FAFC; border: 1px solid var(--border); border-radius: 8px;
  padding: 12px 16px; font-size: 13px; color: var(--text-2); line-height: 1.8; }
.flow-note b { color: var(--brand-600); margin-right: 10px; }
.flow-note a { color: #1D4ED8; font-weight: 600; text-decoration: underline; }
.table th, .table td { font-size: 13px; }
.table td.tl { text-align: left; }
.ops { white-space: nowrap; }
.ops .btn { margin-right: 4px; }
.empty { padding: 24px; text-align: center; color: var(--text-3); font-size: 14px; }

.modal-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, .48); display: none; align-items: center; justify-content: center; z-index: 200; padding: 20px; }
.modal-mask.show { display: flex; }
.modal-box { background: #fff; border-radius: 12px; width: min(720px, 100%); max-height: 90vh; overflow-y: auto; box-shadow: 0 8px 40px rgba(0,0,0,.28); }
.modal-head { display: flex; align-items: center; justify-content: space-between; padding: 16px 22px; border-bottom: 1px solid var(--border); font-size: 16px; font-weight: 700; }
.modal-head .x { cursor: pointer; color: var(--text-3); font-size: 22px; line-height: 1; }
.modal-body { padding: 18px 22px; }
.modal-foot { padding: 14px 22px; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; gap: 10px; }
.form-mini { display: flex; flex-direction: column; gap: 12px; }
.form-row { display: flex; gap: 12px; }
.form-row .form-item { flex: 1; }
.form-item { flex: 1; }
.form-label { font-size: 12.5px; font-weight: 600; color: var(--text-2); display: block; margin-bottom: 5px; }
.form-select, .form-input { min-height: 34px; font-size: 13px; width: 100%; padding: 6px 10px;
  border: 1px solid var(--border-strong); border-radius: 6px; background: #fff; }
.req { color: #DC2626; }
.hint-line { font-size: 12px; color: var(--text-3); margin-top: 4px; }
.assign-bar { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-2); margin-bottom: 12px; }
.assign-bar span { flex: 1; }
.person-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.person-opt { display: flex; align-items: center; gap: 7px; border: 1px solid var(--border); border-radius: 8px;
  padding: 10px 12px; font-size: 14px; cursor: pointer; background: #fff; }
.person-opt.on { background: #EFF6FF; border-color: var(--brand-500); color: var(--brand-600); font-weight: 600; }
.mini-h { font-size: 13px; font-weight: 700; color: var(--text-2); margin: 14px 0 8px; }
.tag-row { display: flex; gap: 8px; flex-wrap: wrap; }
.desc-line { font-size: 13px; color: var(--text-2); line-height: 1.8; margin: 12px 0 0; }
.vid-item { display: flex; align-items: center; gap: 8px; padding: 9px 12px; border: 1px solid var(--border); border-radius: 8px; margin-bottom: 6px; font-size: 13px; }
.vid-item .vd { margin-left: auto; color: var(--text-3); font-size: 12px; }
.mini-table { width: 100%; border-collapse: collapse; }
.mini-table th, .mini-table td { border: 1px solid var(--border); padding: 8px 10px; text-align: left; font-size: 12.5px; }
.mini-table th { background: #f8fafd; font-weight: 700; color: var(--text-2); }
</style>
