<script setup>
/**
 * C1 培训教育 · 小程序「学习」Tab（正式版接入 4 Tab 外壳）
 *
 * 保留已验收的闭环：真实 H.264 视频播放 + 定时进度心跳 + 断点续播
 *   → 在线考核（单选/多选/判断）+ 自动判分 + 试题作答留痕 → PC 学习统计同步。
 * 数据由 PC 端「岗中培训计划」下发（training_plan / training_assign），
 * 学习结果实时回传 PC 统计，是小程序与 PC 数据互通的核心一环。
 *
 * 注意：下列类名是端到端验证脚本的断言锚点，勿改：
 *   .progress-card / .active-task .mini-btn / .task-item / .exam-card .big-btn
 *   .video-box video / .big-btn.outline / .q-card / .q-opt / .result-card / .r-title
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini } from '@/store/mini'

const route = useRoute()
const view = ref('home')           // home | play | exam | result
const loading = ref(true)
const errMsg = ref('')
const home = ref({})
const learners = ref([])
const personId = ref('')

// 视频播放
const current = ref(null)
const videoEl = ref(null)
const videoSrc = ref('')
const playPos = ref(0)
const playDur = ref(0)
const playing = ref(false)
let lastReport = 0

// 考核
const exam = ref({})
const answers = ref({})
const result = ref({})
const submitting = ref(false)
const examErr = ref('')

const progressPercent = computed(() => home.value.progress || 0)
const continueCourse = computed(() => (home.value.courses || []).find((c) => !c.finished) || null)
const examInfo = computed(() => home.value.exam || {})

async function loadLearners() {
  try {
    learners.value = (await request.get('/training/m/learners')).data || []
    const fromUrl = route.query.personId ? String(route.query.personId) : ''
    if (!personId.value) {
      personId.value = String(mini.personId || '') || fromUrl ||
        (learners.value.length ? String(learners.value[0].user_id) : '')
    }
  } catch (e) { /* 忽略 */ }
}

async function loadHome() {
  loading.value = true
  errMsg.value = ''
  try {
    const res = await request.get('/training/m/home', { params: { personId: personId.value || undefined } })
    home.value = res.data || {}
    if (!home.value.plan) errMsg.value = '暂无已发布的岗中培训计划，请先在 PC 端创建并下发'
  } catch (e) {
    errMsg.value = '加载失败：' + (e.message || '请检查网络或登录状态')
  } finally { loading.value = false }
}

// ===== 看视频 + 进度留痕 =====
function openVideo(c) {
  current.value = c
  videoSrc.value = c.video_url
  playDur.value = c.duration_sec || 0
  playPos.value = c.position || 0
  playing.value = true
  view.value = 'play'
  lastReport = 0
  setTimeout(() => {
    const v = videoEl.value
    if (!v) return
    if (playPos.value > 0 && playPos.value < playDur.value - 1) {
      try { v.currentTime = playPos.value } catch (e) { /* 忽略 */ }
    }
    v.play?.().catch(() => { /* 浏览器禁止自动播放时由用户点播放 */ })
  }, 350)
}

function onTimeUpdate() {
  const v = videoEl.value
  if (!v) return
  playPos.value = Math.floor(v.currentTime || 0)
  const now = Date.now()
  if (now - lastReport > 3000) { lastReport = now; report(false) }
}
function onLoadedMetadata() {
  const v = videoEl.value
  if (v && Number.isFinite(v.duration) && v.duration > 0) playDur.value = Math.round(v.duration)
}
function onEnded() { playPos.value = playDur.value; report(true) }
async function report(finished) {
  if (!home.value.assignId || !current.value) return
  try {
    await request.post('/training/m/progress', {
      assignId: home.value.assignId,
      videoId: current.value.videoId || current.value.id,
      position: finished ? playDur.value : playPos.value,
      duration: playDur.value,
    })
    if (finished) {
      current.value.finished = true
      setTimeout(async () => { await loadHome(); view.value = 'home' }, 700)
    }
  } catch (e) { /* 静默，避免打断播放 */ }
}
function togglePlay() {
  const v = videoEl.value
  if (!v) return
  if (v.paused) { v.play(); playing.value = true } else { v.pause(); playing.value = false }
}
function leavePlay() {
  report(false)
  playing.value = false
  view.value = 'home'
  loadHome()
}

// ===== 做考核 =====
async function startExam() {
  examErr.value = ''
  if (examInfo.value.locked) { examErr.value = '请先学完本月全部课程视频，再参加考核'; return }
  try {
    const res = await request.get(`/training/m/exam/${home.value.assignId}`)
    exam.value = res.data || {}
    answers.value = {}
    view.value = 'exam'
  } catch (e) { examErr.value = e.message || '获取试卷失败' }
}
function pick(q, key) {
  if (q.question_type === 'multi') {
    const cur = Array.isArray(answers.value[q.id]) ? [...answers.value[q.id]] : []
    const i = cur.indexOf(key)
    i >= 0 ? cur.splice(i, 1) : cur.push(key)
    answers.value = { ...answers.value, [q.id]: cur }
  } else {
    answers.value = { ...answers.value, [q.id]: key }
  }
}
function isPicked(q, key) {
  const a = answers.value[q.id]
  return Array.isArray(a) ? a.includes(key) : a === key
}
const answeredCount = computed(() => (exam.value.questions || []).filter((q) => {
  const a = answers.value[q.id]
  return Array.isArray(a) ? a.length > 0 : (a != null && a !== '')
}).length)
const typeLabel = { single: '单选题', multi: '多选题', judge: '判断题' }

async function submitExam() {
  const qs = exam.value.questions || []
  if (answeredCount.value < qs.length) { examErr.value = `还有 ${qs.length - answeredCount.value} 题未作答`; return }
  submitting.value = true
  try {
    const res = await request.post(`/training/m/exam/${home.value.assignId}/submit`, { answers: answers.value })
    result.value = res.data || {}
    view.value = 'result'
    await loadHome()
  } catch (e) { examErr.value = e.message || '交卷失败' }
  finally { submitting.value = false }
}
function backHome() { view.value = 'home'; loadHome() }

onMounted(async () => {
  await ensureMini(route)
  await loadLearners()
  await loadHome()
})
onBeforeUnmount(() => { if (playing.value) report(false) })
</script>

<template>
  <MobileShell title="学习中心" tab="study">
    <div class="content">
      <div v-if="loading" class="loading">正在加载本月学习任务…</div>
      <div v-else-if="errMsg" class="err-box">{{ errMsg }}</div>

      <!-- ===== 学习中心首页 ===== -->
      <template v-if="view === 'home' && !loading && !errMsg">
        <!-- 需求文档 1.4：岗前需知 / 安全学习 / 警示学习 三类入口 -->
        <div class="m-card" style="padding:12px 14px;margin-bottom:14px">
          <div style="font-size:15px;font-weight:700;color:#1E40AF;margin-bottom:6px">学习内容分类</div>
          <div style="font-size:13px;color:#64748B;line-height:1.7">
            岗前需知 · 安全学习（本月计划） · 违法违规警示教育
          </div>
        </div>

        <div class="progress-card">
          <div class="pc-title">本月学习进度 · {{ home.plan?.year }} 年 {{ home.plan?.month }} 月</div>
          <div class="pc-row">
            <div class="pc-num">{{ progressPercent }}<small>%</small></div>
            <div class="pc-right">
              <div class="pc-desc">已完成 {{ home.learnedCount }} 课 · 共 {{ home.totalCount }} 课</div>
              <div class="pc-bar"><i :style="{ width: progressPercent + '%' }" /></div>
            </div>
          </div>
          <div class="pc-plan">{{ home.plan?.plan_name }}</div>
        </div>

        <div class="sec-title">继续学习</div>
        <div v-if="continueCourse" class="task-item active-task" @click="openVideo(continueCourse)">
          <div class="t-ico blue">▶</div>
          <div class="t-info">
            <div class="t-title">{{ continueCourse.video_name }}</div>
            <div class="t-desc">已看 {{ continueCourse.percent }}% · {{ continueCourse.finished ? '已完成' : '看完才算完成' }}</div>
          </div>
          <button class="mini-btn" @click.stop="openVideo(continueCourse)">接着看</button>
        </div>
        <div v-else class="task-item done-task">
          <div class="t-ico green">✓</div>
          <div class="t-info"><div class="t-title">本月课程已全部学完</div><div class="t-desc">可以参加在线考核了</div></div>
        </div>

        <div class="sec-title">全部课程</div>
        <div v-for="c in home.courses || []" :key="c.videoId || c.id" class="task-item" @click="openVideo(c)">
          <div class="t-ico" :class="c.finished ? 'green' : 'grey'">{{ c.finished ? '✓' : '▶' }}</div>
          <div class="t-info">
            <div class="t-title">{{ c.video_name }}</div>
            <div class="t-desc">{{ c.finished ? '已学完' : '未开始' }} · {{ c.duration_sec }} 秒</div>
          </div>
          <span class="tag" :class="c.finished ? 'tag-green' : 'tag-gray'">{{ c.finished ? '已学完' : '待学习' }}</span>
        </div>

        <div class="sec-title">在线考核</div>
        <div class="exam-card">
          <div class="ec-row"><span>考试卷</span><b>{{ examInfo.paperName || '—' }}</b></div>
          <div class="ec-row"><span>及格线 / 已考次数</span><b>{{ examInfo.passScore }} 分 / {{ examInfo.times || 0 }} 次（最多 {{ examInfo.maxRetry }} 次）</b></div>
          <div class="ec-row"><span>考核状态</span>
            <b :class="examInfo.passed ? 'ok' : ''">{{ examInfo.passed ? `已通过（${examInfo.score} 分）` : '待通过' }}</b></div>
          <button class="big-btn" :class="{ locked: examInfo.locked || examInfo.passed }" @click="startExam">
            {{ examInfo.passed ? '查看/重考' : (examInfo.locked ? '学满后解锁考核' : '开始考核') }}
          </button>
          <div v-if="examErr" class="exam-err">{{ examErr }}</div>
          <div v-if="examInfo.forceLearn" class="ec-tip">企业已开启学时管控：须学完全部视频才可考核</div>
        </div>
      </template>

      <!-- ===== 视频播放 ===== -->
      <template v-if="view === 'play' && !loading">
        <div class="video-box">
          <video ref="videoEl" :src="videoSrc" controls playsinline
                 @timeupdate="onTimeUpdate" @loadedmetadata="onLoadedMetadata" @ended="onEnded" @play="playing = true" @pause="playing = false" />
        </div>
        <div class="video-title">{{ current?.video_name }}</div>
        <div class="video-desc">{{ current?.video_desc }}</div>
        <div class="play-progress">
          <div class="pp-bar"><i :style="{ width: (playDur ? Math.min(100, playPos * 100 / playDur) : 0) + '%' }" /></div>
          <span>已看 {{ Math.floor(playPos / 60) }}:{{ String(playPos % 60).padStart(2, '0') }} / {{ Math.floor(playDur / 60) }}:{{ String(playDur % 60).padStart(2, '0') }}</span>
        </div>
        <div class="tip-box">系统会自动记录你的观看进度，中途退出下次可接着看；看完本段才算完成。</div>
        <button class="big-btn" @click="togglePlay">{{ playing ? '暂停' : '继续播放' }}</button>
        <button class="big-btn outline" @click="leavePlay">学完了，返回学习中心</button>
      </template>

      <!-- ===== 在线考核 ===== -->
      <template v-if="view === 'exam' && !loading">
        <div class="exam-head">
          <div class="eh-title">{{ examInfo.paperName }}</div>
          <div class="eh-meta">共 {{ (exam.questions || []).length }} 题 · 及格 {{ exam.passScore }} 分 · 已答 {{ answeredCount }} 题</div>
        </div>
        <div v-for="(q, idx) in exam.questions || []" :key="q.id" class="q-card">
          <div class="q-head"><span class="q-no">{{ idx + 1 }}</span><span class="q-type">{{ typeLabel[q.question_type] }}</span></div>
          <div class="q-stem">{{ q.stem }}</div>
          <div class="q-opts">
            <div v-for="o in q.options" :key="o.key" class="q-opt" :class="{ on: isPicked(q, o.key) }" @click="pick(q, o.key)">
              <span class="q-key">{{ o.key }}</span><span class="q-text">{{ o.text }}</span>
              <span class="q-check">{{ isPicked(q, o.key) ? '✓' : '' }}</span>
            </div>
          </div>
        </div>
        <div v-if="examErr" class="exam-err">{{ examErr }}</div>
        <button class="big-btn" :disabled="submitting" @click="submitExam">{{ submitting ? '正在判分…' : '交卷' }}</button>
      </template>

      <!-- ===== 考核结果 ===== -->
      <template v-if="view === 'result' && !loading">
        <div class="result-card" :class="result.result === 'pass' ? 'pass' : 'fail'">
          <div class="r-ico">{{ result.result === 'pass' ? '✓' : '✕' }}</div>
          <div class="r-title">{{ result.result === 'pass' ? '考核通过' : '考核未通过' }}</div>
          <div class="r-score">{{ result.score }}<small>分</small></div>
          <div class="r-meta">及格线 {{ result.passScore }} 分 · 答对 {{ result.correct }} / {{ result.total }} 题</div>
        </div>
        <div class="sec-title">答题详情</div>
        <div v-for="(d, idx) in result.detail || []" :key="idx" class="q-review" :class="d.correct ? 'ok' : 'bad'">
          <div class="qr-stem">{{ idx + 1 }}. {{ d.stem }}</div>
          <div class="qr-ans">正确答案：{{ d.answer }}　你的作答：{{ d.userAnswer || '未作答' }}</div>
        </div>
        <button class="big-btn" @click="backHome">返回学习中心</button>
        <button v-if="result.result !== 'pass'" class="big-btn outline" @click="startExam">再考一次</button>
      </template>
    </div>
  </MobileShell>
</template>

<style scoped>
/* 保留已验收的学习页适老化样式；外壳由 MobileShell 统一提供 */
.content { padding: 0; }
.loading, .err-box { padding: 30px 16px; text-align: center; color: #64748B; font-size: 15px; }
.err-box { color: #B45309; background: #FFFBEB; border: 1px solid #FDE68A; border-radius: 10px; }

.progress-card { background: linear-gradient(120deg, #1E40AF, #2563EB); color: #fff; border-radius: 14px; padding: 16px; margin-bottom: 18px; }
.pc-title { font-size: 16px; font-weight: 700; margin-bottom: 10px; }
.pc-row { display: flex; align-items: center; gap: 14px; }
.pc-num { font-size: 40px; font-weight: 800; line-height: 1; }
.pc-num small { font-size: 18px; }
.pc-right { flex: 1; }
.pc-desc { font-size: 15px; opacity: .95; }
.pc-bar { height: 12px; background: rgba(255,255,255,.28); border-radius: 10px; margin-top: 8px; overflow: hidden; }
.pc-bar i { display: block; height: 100%; background: #fff; border-radius: 10px; transition: width .3s; }
.pc-plan { font-size: 13px; opacity: .85; margin-top: 10px; }
.sec-title { font-size: 17px; font-weight: 700; color: #1E293B; margin: 18px 0 10px; }
.task-item { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #E2E8F0;
  border-radius: 12px; padding: 12px; margin-bottom: 10px; cursor: pointer; }
.task-item.active-task { border: 2px solid #1E40AF; }
.task-item.done-task { background: #F0FDF4; border-color: #BBF7D0; }
.t-ico { width: 50px; height: 50px; border-radius: 12px; display: flex; align-items: center; justify-content: center;
  font-size: 22px; flex-shrink: 0; }
.t-ico.blue { background: #DBEAFE; color: #1E40AF; }
.t-ico.green { background: #DCFCE7; color: #15803D; }
.t-ico.grey { background: #F1F5F9; color: #64748B; }
.t-info { flex: 1; min-width: 0; }
.t-title { font-size: 16px; font-weight: 700; color: #1E293B; }
.t-desc { font-size: 13px; color: #64748B; margin-top: 3px; }
.mini-btn { min-height: 44px; padding: 0 16px; font-size: 16px; font-weight: 700; border: none; border-radius: 10px;
  background: #1E40AF; color: #fff; cursor: pointer; flex-shrink: 0; }
.tag { font-size: 13px; padding: 4px 10px; border-radius: 8px; font-weight: 700; white-space: nowrap; }
.tag-green { background: #DCFCE7; color: #15803D; }
.tag-gray { background: #F1F5F9; color: #64748B; }

.exam-card { background: #fff; border: 1px solid #E2E8F0; border-radius: 12px; padding: 14px; }
.ec-row { display: flex; justify-content: space-between; font-size: 14px; padding: 6px 0; color: #475569; }
.ec-row b { color: #1E293B; }
.ec-row b.ok { color: #15803D; }
.ec-tip { font-size: 13px; color: #92400E; background: #FFFBEB; border-radius: 8px; padding: 8px 10px; margin-top: 10px; }
.big-btn { width: 100%; min-height: 52px; font-size: 18px; font-weight: 700; border: none; border-radius: 12px;
  background: #1E40AF; color: #fff; cursor: pointer; margin-top: 14px; }
.big-btn.outline { background: #fff; color: #1E40AF; border: 2px solid #1E40AF; }
.big-btn.locked { background: #94A3B8; }
.big-btn:disabled { opacity: .6; }
.exam-err { color: #B91C1C; background: #FEF2F2; border: 1px solid #FECACA; border-radius: 8px;
  padding: 10px; font-size: 14px; margin-top: 10px; }

.video-box { background: #000; border-radius: 12px; overflow: hidden; }
.video-box video { width: 100%; display: block; }
.video-title { font-size: 19px; font-weight: 700; margin-top: 14px; color: #1E293B; }
.video-desc { font-size: 14px; color: #475569; line-height: 1.7; margin-top: 6px; }
.play-progress { margin-top: 12px; }
.pp-bar { height: 10px; background: #E2E8F0; border-radius: 8px; overflow: hidden; }
.pp-bar i { display: block; height: 100%; background: #2563EB; }
.play-progress span { font-size: 13px; color: #64748B; display: block; margin-top: 5px; }
.tip-box { font-size: 14px; color: #1E40AF; background: #EFF6FF; border-radius: 10px; padding: 12px; margin-top: 14px; line-height: 1.7; }

.exam-head { background: #fff; border-radius: 12px; padding: 14px; margin-bottom: 12px; border: 1px solid #E2E8F0; }
.eh-title { font-size: 18px; font-weight: 700; color: #1E293B; }
.eh-meta { font-size: 14px; color: #64748B; margin-top: 5px; }
.q-card { background: #fff; border: 1px solid #E2E8F0; border-radius: 12px; padding: 14px; margin-bottom: 12px; }
.q-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.q-no { width: 28px; height: 28px; border-radius: 8px; background: #1E40AF; color: #fff; font-size: 15px;
  font-weight: 700; display: flex; align-items: center; justify-content: center; }
.q-type { font-size: 13px; color: #64748B; background: #F1F5F9; padding: 3px 9px; border-radius: 6px; }
.q-stem { font-size: 16px; font-weight: 600; color: #1E293B; line-height: 1.7; }
.q-opts { margin-top: 10px; }
.q-opt { display: flex; align-items: center; gap: 10px; min-height: 48px; border: 2px solid #E2E8F0;
  border-radius: 10px; padding: 8px 12px; margin-bottom: 8px; cursor: pointer; font-size: 16px; }
.q-opt.on { border-color: #1E40AF; background: #EFF6FF; }
.q-key { width: 30px; height: 30px; border-radius: 8px; background: #F1F5F9; display: flex; align-items: center;
  justify-content: center; font-weight: 700; flex-shrink: 0; }
.q-opt.on .q-key { background: #1E40AF; color: #fff; }
.q-text { flex: 1; }
.q-check { color: #1E40AF; font-size: 20px; font-weight: 800; }

.result-card { border-radius: 14px; padding: 24px 16px; text-align: center; color: #fff; }
.result-card.pass { background: linear-gradient(120deg, #15803D, #22C55E); }
.result-card.fail { background: linear-gradient(120deg, #B91C1C, #EF4444); }
.r-ico { width: 60px; height: 60px; border-radius: 50%; background: rgba(255,255,255,.22); font-size: 34px;
  display: flex; align-items: center; justify-content: center; margin: 0 auto 10px; }
.r-title { font-size: 21px; font-weight: 700; }
.r-score { font-size: 52px; font-weight: 800; line-height: 1.2; }
.r-score small { font-size: 18px; }
.r-meta { font-size: 15px; opacity: .95; }
.q-review { border-radius: 10px; padding: 12px; margin-bottom: 8px; font-size: 14px; }
.q-review.ok { background: #F0FDF4; border: 1px solid #BBF7D0; }
.q-review.bad { background: #FEF2F2; border: 1px solid #FECACA; }
.qr-stem { font-size: 15px; font-weight: 600; color: #1E293B; line-height: 1.6; }
.qr-ans { font-size: 13.5px; color: #475569; margin-top: 5px; }
</style>
