<script setup>
/**
 * 统一签到入口（B15 决策 · 小程序）。
 * 司机不需要知道是「会议签到 / 演练签到 / 什么功能签到」——所有涉及签到的
 * 在小程序端都是同一个入口，聚合 PC 端下发的会议 + 应急演练待签任务，
 * 列表点按即签（适老化大字 + 一键签到），写回各自 PC 端同源签到表。
 */
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini } from '@/store/mini'

const route = useRoute()
const tasks = ref([])
const loading = ref(true)
const err = ref('')
const signing = ref(null)   // 当前正在签的 bizId

async function load() {
  loading.value = true
  try {
    const res = await request.get('/mini/checkin/tasks', { params: { personId: mini.personId } })
    tasks.value = res.data || []
  } catch (e) { err.value = e.message || '加载失败' }
  finally { loading.value = false }
}
onMounted(async () => {
  await ensureMini(route)
  // 若小程序端有「统一签到」聚合 badge，等待 home 接口刷新；这里直接拉待签
  await load()
})

function fmt(t) { return String(t || '').replace('T', ' ').slice(0, 16) }
function moduleLabel(m) { return m === 'drill' ? '演练' : '会议' }

async function sign(t) {
  if (signing.value === t.bizId) return
  signing.value = t.bizId
  try {
    await request.post('/mini/checkin/sign', {
      bizModule: t.bizModule,
      bizId: String(t.bizId),
      personId: mini.personId,
      signImage: 'data:image/png;base64,MINI_SIGN',
    })
    await load()
  } catch (e) { err.value = '签到失败：' + (e.message || '请重试') }
  finally { signing.value = null }
}

const pending = computed(() => tasks.value.filter((t) => t.status !== 'signed'))
const done = computed(() => tasks.value.filter((t) => t.status === 'signed'))
</script>

<template>
  <MobileShell title="统一签到" tab="home" back>
    <div class="m-tip" style="margin-top:0">
      司机点这里就行——会议、演练都在这一个入口签到，不需要区分是什么类型的签到。
    </div>
    <div v-if="err" class="m-err">{{ err }}</div>
    <div v-if="loading" class="m-loading">正在加载待签任务…</div>
    <template v-else>
      <div v-if="tasks.length" class="m-card" style="padding:12px 14px;margin-bottom:12px">
        <span style="font-size:calc(16px * var(--m-scale));font-weight:800;color:#1D4ED8">待签到 {{ pending.length }} 个</span>
        <span style="float:right;font-size:calc(14px * var(--m-scale));color:#64748B">已完成 {{ done.length }} 个</span>
      </div>

      <div v-for="t in tasks" :key="t.bizModule + '_' + t.bizId" class="m-card">
        <div style="display:flex;align-items:flex-start;gap:10px">
          <div style="flex:1;min-width:0">
            <div style="font-size:calc(19px * var(--m-scale));font-weight:800">{{ t.title }}</div>
            <div style="font-size:calc(14px * var(--m-scale));color:#64748B;margin-top:4px">
              {{ t.subTitle || moduleLabel(t.bizModule) }}
            </div>
            <div v-if="t.deadline" style="font-size:calc(13px * var(--m-scale));color:#94A3B8;margin-top:4px">
              时间：{{ fmt(t.deadline) }}
            </div>
          </div>
          <span class="m-tag" :class="t.status === 'signed' ? 'm-tag-green' : 'm-tag-yellow'">
            {{ t.status === 'signed' ? '已签到' : '待签到' }}
          </span>
        </div>
        <button v-if="t.status !== 'signed'" class="m-btn m-btn-primary" :disabled="signing === t.bizId" @click="sign(t)">
          {{ signing === t.bizId ? '正在签到…' : '✓ 一键签到' }}
        </button>
        <div v-else class="m-tip" style="background:#F0FDF4;color:#15803D">已完成签到</div>
      </div>

      <div v-if="!tasks.length" class="m-empty">
        暂无待签任务<br /><span style="font-size:14px">安全员在 PC 端下发会议或开展演练后会显示在这里</span>
      </div>
    </template>
  </MobileShell>
</template>
