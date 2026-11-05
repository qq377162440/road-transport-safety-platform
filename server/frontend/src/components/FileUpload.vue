<script setup>
import { ref } from 'vue'
import request from '@/api/request'
import { ElMessage } from 'element-plus'

/**
 * A2 通用附件上传组件（A5 封装）。
 * v-model 绑定文件 URL / 对象键；支持多文件逗号分隔。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  bizModule: { type: String, default: '' },
  bizId: { type: [Number, String], default: '' },
  multiple: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

const files = ref([])
const busy = ref(false)

async function onChange(e) {
  const list = Array.from(e.target.files || [])
  if (!list.length) return
  busy.value = true
  try {
    const urls = []
    for (const f of list) {
      const fd = new FormData()
      fd.append('file', f)
      if (props.bizModule) fd.append('bizModule', props.bizModule)
      if (props.bizId !== '' && props.bizId != null) fd.append('bizId', String(props.bizId))
      const res = await request.post('/file/upload', fd)
      urls.push(res.data.url)
      files.value.push({ name: f.name, url: res.data.url })
    }
    const merged = props.multiple
      ? [props.modelValue, ...urls].filter(Boolean).join(',')
      : urls[urls.length - 1]
    emit('update:modelValue', merged)
    ElMessage.success('上传成功')
  } catch (err) {
    ElMessage.error(err.message || '上传失败')
  } finally {
    busy.value = false
    e.target.value = ''
  }
}
</script>

<template>
  <div class="file-upload">
    <label class="btn">
      <input type="file" :multiple="multiple" :disabled="busy" style="display: none" @change="onChange" />
      <span>{{ busy ? '上传中…' : '选择文件' }}</span>
    </label>
    <span v-if="modelValue" class="hint">已上传 {{ modelValue.split(',').filter(Boolean).length }} 个文件</span>
    <div v-if="files.length" class="list">
      <a v-for="f in files" :key="f.url" :href="f.url" target="_blank">{{ f.name }}</a>
    </div>
  </div>
</template>

<style scoped>
.file-upload { display: flex; flex-direction: column; gap: 6px; }
.btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: fit-content; padding: 6px 14px; border: 1px solid var(--el-border-color);
  border-radius: 4px; cursor: pointer; font-size: 13px; color: var(--el-text-color-regular);
}
.btn:hover { border-color: var(--el-color-primary); color: var(--el-color-primary); }
.hint { font-size: 12px; color: var(--el-text-color-secondary); }
.list { display: flex; flex-direction: column; gap: 2px; }
.list a { font-size: 12px; color: var(--el-color-primary); }
</style>
