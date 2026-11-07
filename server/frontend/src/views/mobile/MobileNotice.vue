<script setup>
/** 公告 / 通知：读 PC 端动态监控预警与通知（warn_record），已读回执写 mini_notice_read */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini } from '@/store/mini'

const route = useRoute()
const rows = ref([])
const loading = ref(true)
const err = ref('')
const openId = ref(null)

async function load() {
  loading.value = true
  try {
    const res = await request.get('/mini/notice/list', { params: { personId: mini.personId } })
    rows.value = res.data || []
  } catch (e) { err.value = e.message || '加载失败' }
  finally { loading.value = false }
}
onMounted(async () => { await ensureMini(route); await load() })

const unread = computed(() => rows.value.filter(x => Number(x.read) === 0).length)
function levelText(l) { return Number(l) >= 2 ? '提醒' : '通知' }
function fmt(t) { return String(t || '').replace('T', ' ').slice(0, 16) }
async function open(n) {
  openId.value = openId.value === String(n.id) ? null : String(n.id)
  if (openId.value && Number(n.read) === 0) {
    try {
      await request.post(`/mini/notice/${n.id}/read`, { personId: mini.personId })
      n.read = 1
    } catch (e) { /* 静默，下次进入仍可重试 */ }
  }
}
</script>

<template>
  <MobileShell title="消息通知" tab="home" back>
    <div class="m-banner" :class="unread ? 'blue' : 'amber'">
      <span style="font-size:22px">!</span><span>{{ unread ? '还有 ' + unread + ' 条未读' : '全部已读' }}</span>
    </div>
    <div v-if="err" class="m-err">{{ err }}</div>
    <div v-if="loading" class="m-loading">正在加载通知…</div>
    <template v-else>
      <div v-for="n in rows" :key="n.id" class="m-card" style="cursor:pointer" @click="open(n)">
        <div style="display:flex;align-items:flex-start;gap:10px">
          <div style="flex:1;min-width:0">
            <div style="font-size:calc(17px * var(--m-scale));font-weight:700;line-height:1.6">{{ n.title }}</div>
            <div style="font-size:calc(13px * var(--m-scale));color:#64748B;margin-top:6px">
              {{ fmt(n.created_at) }} · {{ levelText(n.level) }}
              <span v-if="n.expire_date"> · 有效期至 {{ String(n.expire_date).slice(0, 10) }}</span>
            </div>
          </div>
          <span class="m-tag" :class="Number(n.read) ? 'm-tag-gray' : 'm-tag-red'">{{ Number(n.read) ? '已读' : '未读' }}</span>
        </div>
        <div v-if="openId === String(n.id)" class="m-tip" style="margin-top:10px">
          {{ n.title }}<br />请按通知要求及时办理；如有疑问请联系安全员。
        </div>
      </div>
      <div v-if="!rows.length" class="m-empty">暂无通知</div>
    </template>
    <div class="m-foot">通知与 PC 端动态监控预警同源，已读状态按人记录</div>
  </MobileShell>
</template>
