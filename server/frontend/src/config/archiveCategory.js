// 电子档案柜「文件分类」唯一字典。
//
// 依据：
//  * 《08-台账归档与PDF生成引擎》第二章「三大类资料（核心分类逻辑，统领全系统）」
//    ① 静态资料（范本生成，data_type=1）/ ② 动态资料（工作记录，data_type=2）
//    / ②′ 上传留存资料（外部平台产出，只上传留存）/ ③ 证件资料（手动上传，data_type=3）
//  * 《09-静态资料范本引擎》§2.1「按岗位拆分？」表：仅责任书/承诺书/告知书/任命书
//    4 类按岗位拆分；《18-风险辨识》风险告知卡「每岗位一份」同样按岗位。
//
// 两个维度互相独立：类别决定「怎么生产」（生成/记录/上传），是否按岗位决定
// 「能不能切换岗位」。证件类、动态记录、上传留存一律与岗位无关，不显示岗位切换。

export const CATEGORY = {
  STATIC: {
    key: 'static', dataType: 1, label: '① 静态范本', short: '静态', color: 'blue',
    desc: '制度、预案、责任书等范本类资料，系统从范本生成后签字确认',
  },
  DYNAMIC: {
    key: 'dynamic', dataType: 2, label: '② 动态记录', short: '动态', color: 'green',
    desc: '会议、培训、检查、隐患、事故等执行工作产生的记录',
  },
  UPLOAD: {
    key: 'upload', dataType: 2, label: '②′ 上传留存', short: '上传', color: 'amber',
    desc: '动态监控台账、官方回执等外部产出，本系统只上传留存',
  },
  CERT: {
    key: 'cert', dataType: 3, label: '③ 证件证照', short: '证件', color: 'red',
    desc: '营业执照、经营许可证、保单、资格证等，手动上传并做到期预警',
  },
}

export const CATEGORY_LIST = Object.values(CATEGORY)
// ITEM_CATEGORY 里存的是小写 key（cert/static/...），按 key 精确取类别对象，
// 不要写成 CATEGORY[ITEM_CATEGORY[n]]（那是大写常量名，会取到 undefined 并静默退化到关键词兜底）。
const CATEGORY_BY_KEY = Object.fromEntries(CATEGORY_LIST.map((c) => [c.key, c]))

// 显式分类表：政府检查目录逐项标注，避免关键词误伤（如「管理人员档案」不是单本证件）。
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
  '岗前三级培训': 'dynamic',
  '两类人员考核合格证明': 'cert',
  '年度责任制考核记录': 'dynamic',
  '人员任命书': 'static',
  '复工复产方案': 'static',
  // 盒二 制度
  '适用安全生产法律法规标准目录清单': 'static',
  '制度汇编（含操作规程）': 'static',
  // 盒三 会议
  '安全生产领导小组会议（会议记录、会议PPT、签到、照片）': 'dynamic',
  '安全生产月度会议（会议记录、会议PPT、签到、照片）': 'dynamic',
  '安全生产会议记录': 'dynamic',
  // 盒四 培训
  '安全生产教育和培训计划': 'dynamic',
  '安全生产月度培训（培训内容、照片、签字）': 'dynamic',
  // 盒五 费用
  '年度营收入证明': 'cert',
  '安全费用提取标准及计划': 'static',
  '安全生产费用使用台账（发票或收据）': 'dynamic',
  // 盒六 动态监控（②′ 外部平台产出，只上传留存）
  '动态监控工作情况记录台账（每日）': 'upload',
  '设备故障异常台账': 'upload',
  '在线率台账': 'upload',
  '月报台账': 'upload',
  '针对性违规教育（培训内容、照片、签字）': 'dynamic',
  '交通事故违法针对性教育（培训内容、照片、签字）': 'dynamic',
  // 盒七 应急管理（普货）
  '应急预案': 'static',
  '风险评估和应急资源调查': 'static',
  '应急预案评审或论证': 'dynamic',
  '应急队伍架构': 'static',
  '应急培训（培训内容、考核、照片）': 'dynamic',
  '应急救援器材配备': 'static',
  '应急演练计划': 'dynamic',
  '应急值班表': 'dynamic',
  '应急演练（方案、照片、签到）': 'dynamic',
  '应急预案备案回执': 'upload',
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
  '安全评估': 'static',
  '无重大事故隐患的声明': 'static',
  // 盒九 一日三检
  '（日常车辆三检记录）': 'dynamic',
  '一日三检记录': 'dynamic',
  // 盒十/十一 事故
  '年度事故汇总统计表': 'dynamic',
  '事故资料（按事故归档）': 'dynamic',
  '事故快报表': 'dynamic',
  '事故四不放过资料': 'dynamic',
  // 危货盒七 停车场
  '产权证明': 'cert',
  '租赁合同（到期预警）': 'cert',
  '平面图及照片': 'upload',
}

// 关键词兜底：仅用于后端动态追加的目录项（如范本驱动的盒二条目）。
const CERT_KW = /营业执照|许可证|行驶证|驾驶证|从业资格|资格证|保险|保单|责任险|通行证|运输证|登记证|合格证|检测报告|备案回执|复[印件]|产权|营收入证明|考核合格证明|证明$/
const UPLOAD_KW = /台账|月报|在线率|抄报|外部平台/
const DYNAMIC_KW = /记录|会议|培训|检查|演练|照片|签到|汇总|统计表|三检|隐患|事故|考核|费用使用|值班表|快报|评估表|整改|验收|发票|收据|教育/
const STATIC_KW = /制度|预案|方案|通知|架构|手册|清单|说明|声明|计划|标准|配备|责任书|承诺书|告知书|任命书|目录/

/** 按归档项名称推断类别 */
export function categoryOf(name) {
  const n = String(name || '').trim()
  if (ITEM_CATEGORY[n]) return CATEGORY_BY_KEY[ITEM_CATEGORY[n]] || CATEGORY.STATIC
  if (CERT_KW.test(n)) return CATEGORY.CERT
  if (UPLOAD_KW.test(n)) return CATEGORY.UPLOAD
  if (DYNAMIC_KW.test(n)) return CATEGORY.DYNAMIC
  if (STATIC_KW.test(n)) return CATEGORY.STATIC
  return CATEGORY.STATIC // 未标注的目录项保守按范本类处理
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

/** 供渲染行使用：类别 + 岗位相关性 + 生产方式提示 */
export function classifyItem(name) {
  const c = categoryOf(name)
  return {
    dataType: c.dataType,
    categoryKey: c.key,
    categoryLabel: c.label,
    categoryShort: c.short,
    categoryColor: c.color,
    categoryDesc: c.desc,
    postRelated: isPostRelated(name),
  }
}
