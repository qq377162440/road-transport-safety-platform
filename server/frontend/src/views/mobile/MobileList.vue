<script setup>
/**
 * 配置化只读台账列表：checks / dangers / repairs / risk / budget / credit
 * 统一由 /api/mini/view/{type} 返回列模型，底层分别是 PC 端一日三检、
 * 隐患排查、维修保养、风险辨识、安全投入、考核奖惩台账。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini, goMini } from '@/store/mini'

const route = useRoute()
const router = useRouter()
const rows = ref([])
const loading = ref(true)
const err = ref('')

const META = {
  checks: { title: '我的三检记录', desc: '与 PC 端「一日三检」台账同源', report: null },
  dangers: { title: '隐患台账', desc: '与 PC 端「隐患排查治理」同源', report: '/m/report', q: { type: 'danger' } },
  repairs: { title: '维修记录', desc: '与 PC 端「维修保养管理」同源', report: '/m/report', q: { type: 'repair' } },
  risk: { title: '风险辨识', desc: '与 PC 端「风险分级管控」同源' },
  budget: { title: '安全投入', desc: '与 PC 端「安全生产费用」同源' },
  credit: { title: '考核记录', desc: '与 PC 端「考核奖惩 / 诚信考核」同源' },
}
const type = computed(() => String(route.query.type || 'checks'))
const meta = computed(() => META[type.value] || META.checks)

async function load() {
  loading.value = true
  try {
    const res = await request.get(`/mini/view/${type.value}`, { params: { personId: mini.personId } })
    rows.value = res.data || []
  } catch (e) { err.value = e.message || '加载失败' }
  finally { loading.value = false }
}
onMounted(async () => { await ensureMini(route); await load() })

function tagClass(t) {
  return t === '绿' ? 'm-tag-green' : (t === '红' ? 'm-tag-red' : (t === '黄' ? 'm-tag-yellow' : 'm-tag-blue'))
}
</script>

<template>
  <MobileShell :title="meta.title" tab="home" back>
    <div class="m-tip" style="margin-top:0">{{ meta.desc }}，PC 端新增/修改后这里立即更新。</div>
    <div v-if="err" class="m-err">{{ err }}</div>
    <div v-if="loading" class="m-loading">正在加载台账…</div>
    <template v-else>
      <div v-for="r in rows" :key="r.id" class="m-task" style="cursor:default">
        <div class="m-t-info">
          <div class="m-t-title">{{ r.title }}</div>
          <div v-if="r.subtitle" class="m-t-desc">{{ r.subtitle }}</div>
          <div class="m-t-sub">
            {{ r.date || '' }}<span v-if="r.amount"> · ¥{{ r.amount }}</span>
          </div>
        </div>
        <span v-if="r.status" class="m-tag" :class="tagClass(r.tag)">{{ r.status }}</span>
      </div>
      <div v-if="!rows.length" class="m-empty">暂无记录<br /><span style="font-size:14px">PC 端录入或小程序上报后会显示在这里</span></div>
    </template>

    <button v-if="meta.report" class="m-btn m-btn-primary" @click="goMini(router, meta.report, meta.q)">
      + {{ type === 'dangers' ? '上报隐患' : '上报维修' }}
    </button>
    <button class="m-btn m-btn-outline" @click="goMini(router, '/m/home')">返回首页</button>
  </MobileShell>
</template>
