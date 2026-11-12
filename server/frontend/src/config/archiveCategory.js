// 电子档案柜「文件分类」唯一字典（A/B/C 三码，2026-11-12 定稿）。
//
// 权威定义见《详细设计/档案资料A-B-C分类规范.md》与《产品需求确认文档》§2.2：
//   A 静态资料（data_type=1）：范本生成 → 签字确认；模板因地区/企业规模选用不同范本套；年度更新。
//   B 动态资料（data_type=2）：企业执行动作产生记录（真实流程 / 服务商代录·先台账后执行）；
//      月度/季度/每日等周期更新。
//   C 证件资料（data_type=3）：手动上传/外部提供 + 系统只做上传留存，需到期预警的按有效期提醒
//      （动态监控台账等外部平台产出也归 C，可不设有效期——2026-11-12 口径扩展）。
//
// 两个维度互相独立：类别决定「怎么生产」（生成/记录/上传），是否按岗位决定
// 「能不能切换岗位」。证件类、动态记录一律与岗位无关，不显示岗位切换。
//
// 分类支持按营运类型分叉（cargo 分叉）：同一项在普货/危货可归不同类——
// 如「应急预案/风险评估和应急资源调查/应急预案评审或论证」普货由系统范本生成（A/B），
// 危货须第三方机构出具、企业只上传（C），见 ITEM_CATEGORY_DANGER 覆盖表。

export const CATEGORY = {
  A: {
    code: 'A', key: 'static', dataType: 1, label: 'A 静态资料', short: '静态', color: 'blue',
    desc: '制度、预案、责任书、计划等范本类，系统从范本生成后签字确认；模板因地区/企业规模选用不同范本套',
  },
  B: {
    code: 'B', key: 'dynamic', dataType: 2, label: 'B 动态资料', short: '动态', color: 'green',
    desc: '会议、培训、检查、隐患等执行产生的记录（服务商可代录·提取签字照片直接生成）',
  },
  C: {
    code: 'C', key: 'cert', dataType: 3, label: 'C 证件资料', short: '证件', color: 'red',
    desc: '营业执照、许可证、备案回执、产权证明、外部平台台账等手动上传/外部提供资料，系统只做上传留存并按有效期提醒',
  },
}

export const CATEGORY_LIST = Object.values(CATEGORY)
// ITEM_CATEGORY 里存的是小写 key（cert/static/dynamic），按 key 精确取类别对象，
// 不要写成 CATEGORY[ITEM_CATEGORY[n]]（那是大写常量名，会取到 undefined 并静默退化到关键词兜底）。
const CATEGORY_BY_KEY = Object.fromEntries(CATEGORY_LIST.map((c) => [c.key, c]))

// 显式分类表：政府检查目录逐项标注，避免关键词误伤（如「管理人员档案」不是单本证件）。
// 2026-11-12 修订：营收入证明/岗前三级培训 → A（范本生成）；动态监控台账（外部平台
// 产出上传留存）→ C；事故档案盒三项 → C（事故卷宗多为外部认定件，手动上传留存）。
export const ITEM_CATEGORY = {
  // 盒一 公司档案及管理层档案
  '营业执照': 'cert',
  '道路运输经营许可证': 'cert',
  '安全组织架构': 'static',
  '关于成立安全生产领导小组的通知': 'static',
  '二级维护计划': 'static',
  '安全生产责任险': 'cert',
  '管理人员档案': 'static',
  '安全生产责任书': 'static',
  '安全生产承诺书': 'static',
  '岗位危害告知书': 'static',
  '岗前三级培训': 'static',
  '两类人员考核合格证明': 'cert',
  '年度责任制考核记录': 'dynamic',
  '人员任命书': 'static',
  '各类方案': 'static',
  '复工复产方案': 'static', // 历史归档项兼容
  // 盒二 制度
  '适用安全生产法律法规标准目录清单': 'static',
  '制度汇编（含操作规程）': 'static',
  // 盒三 会议
  '安全生产领导小组会议（会议记录、会议PPT、签到、照片）': 'dynamic',
  '安全生产月度会议（会议记录、会议PPT、签到、照片）': 'dynamic',
  '安全生产会议记录': 'dynamic',
  // 盒四 培训（计划=范本生成→A；月度培训=执行记录→B）
  '安全生产教育和培训计划': 'static',
  '安全生产月度培训（培训内容、照片、签字）': 'dynamic',
  // 盒五 费用
  '年度营收入证明': 'static',
  '安全费用提取标准及计划': 'static',
  '安全生产费用使用台账（发票或收据）': 'dynamic',
  // 盒六 动态监控（外部平台产出→系统上传留存，归 C；不设有效期）
  '动态监控工作情况记录台账（每日）': 'cert',
  '动态监控工作情况记录台账': 'cert',
  '针对性违规教育（培训内容、照片、签字）': 'dynamic',
  '交通事故违法针对性教育（培训内容、照片、签字）': 'dynamic',
  // 盒七 应急管理（普货：预案/风险评估系统范本生成→A，评审论证→B；危货覆盖为 C）
  '应急预案': 'static',
  '风险评估和应急资源调查': 'static',
  '应急预案评审或论证': 'dynamic',
  '应急队伍架构': 'static',
  '应急培训（培训内容、考核、照片）': 'dynamic',
  '应急救援器材配备': 'static',
  '应急演练计划': 'static',
  '应急值班表': 'dynamic',
  '应急演练（方案、照片、签到）': 'dynamic',
  '应急预案备案回执': 'cert',
  '应急值班制度': 'static',
  // 盒八 风险与隐患
  '风险辨识手册': 'static',
  '风险辨识清单': 'static',
  '全面风险辨识清单': 'static',
  '安全风险辨识分级管控清单': 'static',
  '风险告知卡（每个岗位一份）': 'static',
  '无重大风险的说明': 'static',
  '隐患排查记录（排查内容、照片、签字）': 'dynamic',
  '隐患排查治理（整改通知书、验收单）': 'dynamic',
  '安全评估': 'cert',
  '安全评估（危货）': 'cert',
  '无重大事故隐患的声明': 'static',
  '无重大事故隐患的声明（危货）': 'static',
  // 盒九 一日三检
  '（日常车辆三检记录）': 'dynamic',
  '日常车辆三检记录': 'dynamic',
  '三检月报汇总表': 'dynamic',
  '一日三检记录': 'dynamic',
  // 盒十/十一 事故（卷宗多为外部认定件，手动上传留存 → C；年度汇总表为执行记录 → B）
  '年度事故汇总统计表': 'dynamic',
  '事故资料（按事故归档）': 'cert',
  '事故资料': 'cert',
  '事故快报表': 'cert',
  '事故四不放过资料': 'cert',
  // 危货盒七 停车场（手动上传凭证 → C）
  '产权证明': 'cert',
  '租赁合同（到期预警）': 'cert',
  '平面图及照片': 'cert',
}

// 危货覆盖表（cargo 分叉）：危货的应急预案/风险评估/评审论证须第三方机构出具、
// 企业只上传留存 → C（普货由系统范本生成：预案/风险评估=A、评审论证=B）。
export const ITEM_CATEGORY_DANGER = {
  '应急预案': 'cert',
  '风险评估和应急资源调查': 'cert',
  '应急预案评审或论证': 'cert',
}

// 关键词兜底：仅用于后端动态追加的目录项（如范本驱动的盒二条目）。
const CERT_KW = /营业执照|许可证|行驶证|驾驶证|从业资格|资格证|保险|保单|责任险|通行证|运输证|登记证|合格证|检测报告|备案回执|复[印件]|产权|营收入证明|考核合格证明|证明$/
const DYNAMIC_KW = /记录|会议|培训|检查|演练|照片|签到|汇总|统计表|三检|隐患|事故|考核|费用使用|值班表|快报|评估表|整改|验收|发票|收据|教育|台账|月报|在线率|抄报|外部平台/
const STATIC_KW = /制度|预案|方案|通知|架构|手册|清单|说明|声明|计划|标准|配备|责任书|承诺书|告知书|任命书|目录/

/** 按归档项名称推断类别（A/B/C）；cargo='危货' 时先查危货覆盖表 */
export function categoryOf(name, cargo) {
  const n = String(name || '').trim()
  if (cargo === '危货' && ITEM_CATEGORY_DANGER[n]) return CATEGORY_BY_KEY[ITEM_CATEGORY_DANGER[n]] || CATEGORY.A
  if (ITEM_CATEGORY[n]) return CATEGORY_BY_KEY[ITEM_CATEGORY[n]] || CATEGORY.A
  if (CERT_KW.test(n)) return CATEGORY.C
  if (DYNAMIC_KW.test(n)) return CATEGORY.B
  if (STATIC_KW.test(n)) return CATEGORY.A
  return CATEGORY.A // 未标注的目录项保守按范本类处理
}

// 按岗位拆分的文件（09 §2.1 的 4 类 + 18 风险告知卡）。
const POST_SET = new Set([
  '安全生产责任书', '安全生产承诺书', '岗位危害告知书', '人员任命书',
  '风险告知卡（每个岗位一份）', '岗位职业病危害告知书', '禁毒承诺书',
  '安全责任书', '承诺书', '任命书', '告知书',
])
const POST_KW = /责任书|承诺书|告知书|任命书|风险告知卡/
// 例外：复工复产套装里的诚信承诺书、声明类文本不按岗位拆分
const POST_EXCLUDE = /诚信承诺书|复工复产|无重大|声明/

/** 是否与岗位相关（决定是否显示岗位切换入口） */
export function isPostRelated(name) {
  const n = String(name || '').trim()
  if (POST_EXCLUDE.test(n)) return false
  return POST_SET.has(n) || POST_KW.test(n)
}

/** 供渲染行使用：类别（A/B/C）+ 岗位相关性 + 生产方式提示 */
export function classifyItem(name, cargo) {
  const c = categoryOf(name, cargo)
  return {
    code: c.code,
    dataType: c.dataType,
    categoryKey: c.key,
    categoryLabel: c.label,
    categoryShort: c.short,
    categoryColor: c.color,
    categoryDesc: c.desc,
    postRelated: isPostRelated(name),
  }
}
