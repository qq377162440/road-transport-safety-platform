// 电子档案柜「文件分类 / 岗位相关性」规则回归。
// 依据《08-台账归档与PDF生成引擎》三大类资料 + ②′ 上传留存，
// 《09-静态资料范本引擎》§2.1 按岗位拆分表，《18-风险辨识》风险告知卡每岗位一份。
//
// 运行：node server/deploy/verify_archive_category.mjs
// 无外部依赖，直接 import 前端唯一分类字典，保证「规则只有一份」。

import { classifyItem, CATEGORY_LIST, categoryOf, isPostRelated, ITEM_CATEGORY } from '../frontend/src/config/archiveCategory.js'

let pass = 0
let fail = 0
const ok = (m) => { pass++; console.log(`  PASS  ${m}`) }
const bad = (m) => { fail++; console.log(`  FAIL  ${m}`) }
const eq = (actual, expected, label) =>
  actual === expected ? ok(`${label} = ${actual}`) : bad(`${label} 期望 ${expected}，实际 ${actual}`)

console.log('\n=== ① 分类字典完整性 ===')
eq(CATEGORY_LIST.length, 4, '类别数量（静态/动态/上传留存/证件）')
eq(new Set(CATEGORY_LIST.map((c) => c.key)).size, 4, '类别 key 唯一')
for (const c of CATEGORY_LIST) {
  if (c.key && c.label && c.short && c.color && c.desc && c.dataType) ok(`类别 ${c.key} 字段齐全`)
  else bad(`类别 ${c.key} 字段缺失`)
}

console.log('\n=== ①-b 显式标注表逐项生效（防止 key 大小写不一致静默退化到兜底）===')
const explicit = Object.entries(ITEM_CATEGORY)
eq(explicit.length > 50, true, `显式标注项数量 = ${explicit.length}`)
let explicitBad = 0
for (const [name, key] of explicit) {
  const got = categoryOf(name).key
  if (got !== key) { explicitBad++; bad(`「${name}」显式标注 ${key}，实际解析为 ${got}`) }
}
if (!explicitBad) ok(`全部 ${explicit.length} 项显式标注均精确命中`)

console.log('\n=== ② 证件类：必须与岗位无关（用户反馈的核心问题）===')
const CERTS = [
  '营业执照', '道路运输经营许可证', '安全生产责任险', '两类人员考核合格证明',
  '年度营收入证明', '产权证明', '租赁合同（到期预警）', '车辆行驶证', '从业资格证', '保单',
]
for (const n of CERTS) {
  const c = classifyItem(n)
  eq(c.categoryKey, 'cert', `「${n}」类别`)
  eq(c.dataType, 3, `「${n}」data_type`)
  eq(c.postRelated, false, `「${n}」不按岗位`)
}

console.log('\n=== ③ 上传留存 / 动态记录：同样与岗位无关 ===')
const NO_POST = [
  '动态监控工作情况记录台账（每日）', '设备故障异常台账', '在线率台账', '月报台账', '应急预案备案回执',
  '安全生产会议记录', '安全生产月度培训（培训内容、照片、签字）', '隐患排查记录（排查内容、照片、签字）',
  '应急演练（方案、照片、签到）', '一日三检记录', '事故快报表', '安全生产费用使用台账（发票或收据）',
  '年度事故汇总统计表', '安全生产领导小组会议（会议记录、会议PPT、签到、照片）',
]
for (const n of NO_POST) {
  const c = classifyItem(n)
  if (c.categoryKey === 'upload' || c.categoryKey === 'dynamic') ok(`「${n}」→ ${c.categoryShort}`)
  else bad(`「${n}」类别错误：${c.categoryKey}`)
  eq(c.postRelated, false, `「${n}」不按岗位`)
}

console.log('\n=== ④ 按岗位拆分的文件：必须保留岗位切换 ===')
const POST_FILES = [
  '安全生产责任书', '安全生产承诺书', '岗位危害告知书', '人员任命书',
  '风险告知卡（每个岗位一份）', '岗位职业病危害告知书', '禁毒承诺书',
]
for (const n of POST_FILES) {
  const c = classifyItem(n)
  eq(c.postRelated, true, `「${n}」按岗位`)
  eq(c.categoryKey, 'static', `「${n}」属于静态范本`)
}

console.log('\n=== ⑤ 例外：不按岗位的静态文本 ===')
for (const n of ['无重大事故隐患的声明', '无重大风险的说明', '复工复产方案', '诚信承诺书']) {
  eq(isPostRelated(n), false, `「${n}」不按岗位`)
}

console.log('\n=== ⑥ 静态范本兜底 ===')
for (const n of ['应急预案', '制度汇编（含操作规程）', '风险辨识手册', '安全组织架构', '应急值班制度']) {
  eq(categoryOf(n).key, 'static', `「${n}」→ 静态范本`)
}

console.log(`\n结果：${pass} 通过 / ${fail} 失败\n`)
process.exit(fail ? 1 : 0)
