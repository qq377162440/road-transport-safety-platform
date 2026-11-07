<script setup>
/** 会议签到：读 PC 端安全会议管理下发的会议，签到写回 meeting_attendee（PC 签到表同源） */
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/api/request'
import MobileShell from '@/components/MobileShell.vue'
import { mini, ensureMini } from '@/store/mini'

const route = useRoute()
const rows = ref([])
const loading = ref(true)
const err = ref('')
const signing = ref(null)

async function load() {
  loading.value = true
  try {
    const res = await request.get('/mini/meeting/list', { params: { personId: mini.personId } })
    rows.value = res.data || []
  } catch (e) { err.value = e.message || '加载失败' }
  finally { loading.value = false }
}
onMounted(async () => { await ensureMini(route); await load() })

function fmt(t) { return String(t || '').replace('T', ' ').slice(0, 16) }
async function sign(m) {
  if (m.sign_status === 'signed') return
  signing.value = m.id
  try {
    await request.post(`/mini/meeting/${m.id}/sign`, {
      personId: mini.personId, signImage: 'data:image/png;base64,MINI_SIGN',
    })
    await load()
  } catch (e) { err.value = '签到失败：' + (e.message || '请重试') }
  finally { signing.value = null }
}
</script>

<template>
  <MobileShell title="会议签到" tab="home" back>
    <div class="m-tip" style="margin-top:0">会议由 PC 端「安全会议管理」下发，签到结果实时回写 PC 端签到表。</div>
    <div v-if="err" class="m-err">{{ err }}</div>
    <div v-if="loading" class="m-loading">正在加载会议…</div>
    <template v-else>
      <div v-for="m in rows" :key="m.id" class="m-card">
        <div style="display:flex;align-items:flex-start;gap:10px">
          <div style="flex:1;min-width:0">
            <div style="font-size:calc(19px * var(--m-scale));font-weight:800">{{ m.title }}</div>
            <div style="font-size:calc(14px * var(--m-scale));color:#64748B;margin-top:4px">
              {{ fmt(m.start_time) }} · {{ m.location || '地点待定' }}
            </div>
          </div>
          <span class="m-tag" :class="m.sign_status === 'signed' ? 'm-tag-green' : 'm-tag-yellow'">
            {{ m.sign_status === 'signed' ? '已签到' : '待签到' }}
          </span>
        </div>
        <div v-if="m.topic" style="font-size:calc(15px * var(--m-scale));color:#475569;margin-top:10px">议题：{{ m.topic }}</div>
        <div v-if="m.notice_content" style="font-size:calc(15px * var(--m-scale));color:#475569;margin-top:6px;line-height:1.7">{{ m.notice_content }}</div>
        <div style="font-size:calc(13px * var(--m-scale));color:#64748B;margin-top:10px">
          应到 {{ m.attendee_count }} 人 · 已签 {{ m.signed_count }} 人
        </div>
        <button v-if="m.sign_status !== 'signed'" class="m-btn m-btn-primary" :disabled="signing === m.id" @click="sign(m)">
          {{ signing === m.id ? '正在签到…' : '✓ 一键签到' }}
        </button>
        <div v-else class="m-tip" style="background:#F0FDF4;color:#15803D">已于 {{ fmt(m.sign_time) }} 完成签到</div>
      </div>
      <div v-if="!rows.length" class="m-empty">暂无会议<br /><span style="font-size:14px">安全员在 PC 端发布会议后会显示在这里</span></div>
    </template>
  </MobileShell>
</template>
