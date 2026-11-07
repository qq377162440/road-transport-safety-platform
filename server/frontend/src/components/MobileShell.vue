<script setup>
/**
 * 小程序端统一外壳：手机框 + 状态栏 + 导航 + 底部 4 Tab + 身份/角色切换 + 字号。
 * 对齐原型 mini/*.html 的 [首页 | 三检 | 学习 | 我的] 四 Tab 与适老化规范。
 * 所有 /m 页面用它包裹，保证视觉与导航一致。
 */
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { mini, ensureMini, goMini, switchRole, switchPerson, learnerName } from '@/store/mini'

const props = defineProps({
  title: { type: String, default: '运管管' },
  tab: { type: String, default: 'home' },   // home | check | study | mine
  back: { type: Boolean, default: false },
  right: { type: String, default: 'picker' }, // picker | none
})

const route = useRoute()
const router = useRouter()
onMounted(() => { ensureMini(route) })

const tabs = [
  { key: 'home', path: '/m/home', ico: '⌂', label: '首页' },
  { key: 'check', path: '/m/check', ico: '▤', label: '三检' },
  { key: 'study', path: '/m/study', ico: '▦', label: '学习' },
  { key: 'mine', path: '/m/mine', ico: '◉', label: '我的' },
]
const scaleClass = computed(() => mini.scale >= 1.28 ? 'scale-3' : (mini.scale >= 1.1 ? 'scale-2' : ''))
const persons = computed(() => mini.bootstrap?.persons || [])
const roleLabel = computed(() => mini.role === 'manager' ? '管理视角' : '司机视角')

function go(path) { goMini(router, path) }
function backHome() { goMini(router, '/m/home') }
</script>

<template>
  <div class="m-shell">
    <div class="m-phone" :class="scaleClass">
      <div class="m-status"><span>9:41</span><span>运管管 · 安全助手</span><span>100%</span></div>

      <div class="m-nav">
        <span v-if="back" class="m-back" @click="backHome">‹</span>
        <span>{{ title }}</span>
        <span v-if="right === 'picker'" class="m-nav-right" @click="mini.pop = mini.pop ? '' : 'picker'">
          {{ learnerName() }} · {{ roleLabel }} ▾
        </span>
      </div>

      <!-- 身份 / 角色切换（测试期走查用，对齐原型 role-switch） -->
      <div v-if="mini.pop === 'picker'" class="m-pop">
        <div class="m-pop-title">切换视角</div>
        <div class="m-pop-item" :class="{ on: mini.role === 'worker' }" @click="switchRole(router, 'worker')">司机视角</div>
        <div class="m-pop-item" :class="{ on: mini.role === 'manager' }" @click="switchRole(router, 'manager')">管理视角</div>
        <div class="m-pop-title" style="margin-top:6px">切换从业人员</div>
        <div v-for="p in persons" :key="p.id" class="m-pop-item"
             :class="{ on: String(p.id) === String(mini.personId) }"
             @click="switchPerson(router, p.id)">{{ p.name }}</div>
      </div>

      <div class="m-content">
        <div v-if="!mini.ready" class="m-loading">正在加载…</div>
        <slot v-else />
      </div>

      <div class="m-tabbar">
        <div v-for="t in tabs" :key="t.key" class="m-tab" :class="{ active: tab === t.key }" @click="go(t.path)">
          <span class="m-tab-ico">{{ t.ico }}</span>{{ t.label }}
        </div>
      </div>
    </div>
  </div>
</template>
