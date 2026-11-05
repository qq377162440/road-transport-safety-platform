<script setup>
import { computed } from 'vue'
import FileUpload from './FileUpload.vue'

/**
 * A5 通用表单：由 biz/modules.json 的字段元数据自动生成，新增页面只需配置模块。
 */
const props = defineProps({
  modelValue: { type: Object, default: () => ({}) },
  fields: { type: Array, default: () => [] },
  bizModule: { type: String, default: '' },
})
const model = computed(() => props.modelValue || {})
const visibleFields = computed(() =>
  (props.fields || []).filter((f) => f.control !== 'hidden' && f.name !== 'id'),
)
</script>

<template>
  <el-form label-width="140px" class="biz-form">
    <el-form-item
      v-for="f in visibleFields"
      :key="f.name"
      :label="f.label"
      :required="f.required"
    >
      <FileUpload
        v-if="f.control === 'upload'"
        v-model="model[f.name]"
        :biz-module="bizModule"
        multiple
      />
      <el-input
        v-else-if="f.control === 'textarea'"
        v-model="model[f.name]"
        type="textarea"
        :rows="3"
        :placeholder="f.label"
      />
      <el-date-picker
        v-else-if="f.control === 'date'"
        v-model="model[f.name]"
        type="date"
        value-format="YYYY-MM-DD"
        :placeholder="f.label"
        style="width: 100%"
      />
      <el-date-picker
        v-else-if="f.control === 'datetime'"
        v-model="model[f.name]"
        type="datetime"
        value-format="YYYY-MM-DD HH:mm:ss"
        :placeholder="f.label"
        style="width: 100%"
      />
      <el-input-number
        v-else-if="f.control === 'number'"
        v-model="model[f.name]"
        :controls="false"
        :placeholder="f.label"
        style="width: 100%"
      />
      <el-switch
        v-else-if="f.control === 'switch'"
        v-model="model[f.name]"
        :active-value="1"
        :inactive-value="0"
      />
      <el-input v-else v-model="model[f.name]" :placeholder="f.label" />
    </el-form-item>
  </el-form>
</template>

<style scoped>
.biz-form { max-height: 60vh; overflow: auto; padding-right: 8px; }
</style>
