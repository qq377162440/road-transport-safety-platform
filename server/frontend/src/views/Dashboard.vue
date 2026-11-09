<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import Icon from '@/components/Icon.vue'

const auth = useAuthStore()
const router = useRouter()

// 演示态指标数据（后续接入 /dashboard/metrics 接口后替换）
const stats = [
  { icon: 'truck', bg: 'var(--blue-100)', color: 'var(--blue-800)', val: '24', unit: ' 辆', lbl: '运营车辆', delta: '本月新增 2 辆', dir: 'up' },
  { icon: 'users', bg: 'var(--ok-bg)', color: 'var(--ok)', val: '38', unit: ' 人', lbl: '在岗驾驶员', delta: '本月离职 1 人', dir: 'down' },
  { icon: 'calendar', bg: 'var(--warn-bg)', color: 'var(--warn)', val: '142', unit: ' 天', lbl: '连续安全服务', delta: '目标 200 天', dir: 'flat' },
  { icon: 'clock', bg: 'var(--danger-bg)', color: 'var(--danger)', val: '5', unit: ' 项', lbl: '证件即将到期', delta: '需及时处理', dir: 'down' },
]

const todos = [
  { step: 1, color: 'var(--danger)', title: '处理 3 项逾期隐患', desc: '3 项隐患超期未整改，需立即闭环', btn: '去整改', to: '/risk-control' },
  { step: 2, color: 'var(--warn)', title: '补传 5 个到期证件', desc: '5 个驾驶证/从业资格证即将到期', btn: '去更新', to: '/company' },
  { step: 3, color: 'var(--blue-800)', title: '督促今日三检', desc: '6 辆车还没做收车检查', btn: '去督促', to: '/monitor' },
]

const quick = [
  { name: '一日三检', icon: 'clipboard', to: '/monitor' },
  { name: '隐患排查', icon: 'search', to: '/risk-control' },
  { name: '安全会议', icon: 'calendar', to: '/archive' },
  { name: '安全培训', icon: 'bookOpen', to: '/template-manager' },
  { name: '车辆管理', icon: 'truck', to: '/company' },
  { name: '人员管理', icon: 'users', to: '/org' },
  { name: '制度文件', icon: 'fileText', to: '/responsibility' },
  { name: '档案管理', icon: 'folder', to: '/archive-overview' },
]

// 安全态势走势（演示静态数据）
const weeks = [
  { label: '第1周', h: 40, c: 70, s: 85 },
  { label: '第2周', h: 35, c: 75, s: 84 },
  { label: '第3周', h: 28, c: 78, s: 86 },
  { label: '第4周', h: 20, c: 82, s: 88 },
]
</script>

<template>
  <div>
    <!-- 顶部横幅 -->
    <div class="top-banner">
      <div>
        <div class="t1">企业安全管理驾驶舱</div>
        <div class="t2">{{ auth.tenantName || '—' }}</div>
      </div>
      <div class="score-box">
        <div>
          <div class="score-num">86.5</div>
          <div class="score-txt">企业安全分<b>较上月 +2.3</b></div>
        </div>
        <div class="score-tag">安全等级：良好</div>
      </div>
    </div>

    <!-- 接下来该做的三件事 -->
    <div class="card" style="margin-bottom:16px;border:2px solid var(--blue-800);background:linear-gradient(180deg,#f5f9ff,#fff)">
      <div class="card-title" style="font-size:17px;color:var(--blue-700)">
        <span style="display:flex;align-items:center;gap:8px"><Icon name="clipboard" :size="18" />接下来您该做这 3 件事</span>
        <span style="font-size:13px;color:var(--text-3);font-weight:400">根据企业当前缺项自动生成</span>
      </div>
      <div style="display:grid;grid-template-columns:repeat(3,1fr);gap:14px">
        <div v-for="t in todos" :key="t.step" class="todo-item">
          <div class="step-num" :style="{ background: t.color }">{{ t.step }}</div>
          <div class="flex1">
            <div style="font-weight:700;font-size:15px">{{ t.title }}</div>
            <div style="font-size:13px;color:var(--text-2);margin-top:3px">{{ t.desc }}</div>
          </div>
          <button class="btn btn-sm" :class="t.step === 3 ? 'btn-primary' : (t.step === 2 ? 'btn-warning' : 'btn-danger')" @click="router.push(t.to)">
            {{ t.btn }}
          </button>
        </div>
      </div>
    </div>

    <!-- 常用功能快捷入口 -->
    <div class="quick-nav">
      <div v-for="q in quick" :key="q.name" class="quick-item" @click="router.push(q.to)">
        <div class="qi-ico"><Icon :name="q.icon" :size="30" /></div>
        <div class="qi-name">{{ q.name }}</div>
      </div>
    </div>

    <!-- 核心指标 -->
    <div class="stat-grid">
      <div v-for="s in stats" :key="s.lbl" class="stat-card">
        <div class="ic" :style="{ background: s.bg, color: s.color }"><Icon :name="s.icon" :size="24" /></div>
        <div>
          <div class="val">{{ s.val }}<span style="font-size:14px;color:var(--text-2)">{{ s.unit }}</span></div>
          <div class="lbl">{{ s.lbl }}</div>
          <div class="delta" :class="s.dir === 'up' ? 'delta-up' : (s.dir === 'down' ? 'delta-down' : '')" style="color:var(--text-2)">{{ s.delta }}</div>
        </div>
      </div>
    </div>

    <!-- 三栏卡片 -->
    <div class="cards">
      <div class="card">
        <h3><Icon name="shieldCheck" :size="18" />双预防体系 <span class="more">查看详情 ›</span></h3>
        <div class="kpi-row">
          <div class="kpi"><div class="n">12</div><div class="l">风险辨识(项)</div></div>
          <div class="kpi"><div class="n">8</div><div class="l">隐患排查(项)</div></div>
          <div class="kpi"><div class="n" style="color:var(--ok)">86%</div><div class="l">三检完成率</div></div>
        </div>
        <div style="margin-top:14px">
          <div class="list-item"><span class="dot" style="background:var(--danger)"></span>3 项隐患逾期未闭环<span class="val" style="color:var(--danger)">立即督办 ›</span></div>
          <div class="list-item"><span class="dot" style="background:var(--warn)"></span>今日三检完成 18/24 车<span class="val">75%</span></div>
          <div class="list-item"><span class="dot" style="background:var(--ok)"></span>风险管控措施全部落实<span class="val" style="color:var(--ok)"><Icon name="check" :size="14" /></span></div>
        </div>
      </div>

      <div class="card meet-card">
        <h3><Icon name="calendar" :size="18" />安全会议 <span class="more">会议管理 ›</span></h3>
        <div class="meet-title">冬季行车安全与风险预防</div>
        <div class="meet-meta">
          计划日期：<b>11月25日 14:00</b><br>
          应参与 <b>48 人</b> · 已确认 <b>0 人</b><br>
          本年度已完成 <b>10 场</b> · 平均出席率 <b>94%</b>
        </div>
        <div style="margin-top:14px"><button class="btn btn-primary btn-sm" @click="router.push('/archive')">立即安排会议</button></div>
      </div>

      <div class="card">
        <h3><Icon name="bookOpen" :size="18" />教育培训 <span class="more">培训管理 ›</span></h3>
        <div class="list-item">月度培训进度<span class="val">78%</span></div>
        <div class="progress"><div class="bar" style="width:78%"></div></div>
        <div class="list-item" style="font-size:13px;color:var(--text-2)">已完成 34 人 · 待完成 4 人</div>
        <div class="list-item"><span class="dot" style="background:var(--warn)"></span>违法违规教育待培训<span class="val">2 人</span></div>
        <div class="list-item"><span class="dot" style="background:var(--info)"></span>岗前培训待培训<span class="val">1 人</span></div>
      </div>
    </div>

    <!-- 安全态势走势 -->
    <div class="card">
      <h3 style="font-size:16px;margin-bottom:14px;display:flex;align-items:center;gap:8px;color:var(--text)">
        <Icon name="chart" :size="18" style="color:var(--blue-800)" />安全态势走势
        <span class="legend" style="font-weight:400;margin-left:auto">
          <span><i style="background:var(--blue-800)"></i>隐患排查</span>
          <span><i style="background:var(--ok)"></i>培训完成率</span>
          <span><i style="background:var(--warn)"></i>企业安全分</span>
        </span>
      </h3>
      <div class="chart">
        <div v-for="w in weeks" :key="w.label" class="col">
          <div class="bars">
            <div class="b" :style="{ height: w.h + 'px', background: 'var(--blue-800)' }"></div>
            <div class="b" :style="{ height: w.c + 'px', background: 'var(--ok)' }"></div>
            <div class="b" :style="{ height: w.s + 'px', background: 'var(--warn)' }"></div>
          </div>
          <div class="x">{{ w.label }}</div>
        </div>
      </div>
    </div>
  </div>
</template>
