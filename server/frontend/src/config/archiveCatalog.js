// 电子档案柜目录：逐字摘自政府检查目录（详细设计/08-台账归档与PDF生成引擎.md 2.1/2.2），
// 是唯一权威来源。前端目录树据此渲染；后端按营运类型动态加载对应一套。
// freq: yearly=一年一签 / once=入职一次 / monthly=每月 / daily=每日 / warn=到期预警
// real: 已接入后端生成服务（调用 /archive/responsibility 系列）

const F = { yearly: 'yearly', once: 'once', monthly: 'monthly', daily: 'daily' }

const BOX_ONE = {
  no: '一',
  name: '公司档案及管理层档案',
  items: [
    { name: '营业执照' },
    { name: '道路运输经营许可证' },
    { name: '安全组织架构' },
    { name: '关于成立安全生产领导小组的通知' },
    { name: '二级维护计划' },
    { name: '安全生产责任险' },
    { name: '管理人员档案' },
    { name: '安全生产责任书', freq: F.yearly, real: 'responsibility' },
    { name: '安全生产承诺书', freq: F.yearly, real: 'responsibility' },
    { name: '岗位危害告知书' },
    { name: '岗前三级培训', freq: F.once },
    { name: '两类人员考核合格证明' },
    { name: '年度责任制考核记录' },
    { name: '人员任命书' },
    { name: '复工复产方案' },
  ],
}

const BOX_TWO = {
  no: '二',
  name: '安全生产管理制度及操作规程',
  items: [
    { name: '适用安全生产法律法规标准目录清单' },
    { name: '制度汇编（含操作规程）' },
  ],
}

const BOX_THREE = {
  no: '三',
  name: '安全生产会议',
  items: [
    { name: '安全生产领导小组会议（会议记录、会议PPT、签到、照片）' },
    { name: '安全生产月度会议（会议记录、会议PPT、签到、照片）' },
  ],
}

const BOX_FOUR = {
  no: '四',
  name: '安全生产教育和培训',
  items: [
    { name: '安全生产教育和培训计划' },
    { name: '安全生产月度培训（培训内容、照片、签字）', freq: F.monthly },
  ],
}

const BOX_FIVE = {
  no: '五',
  name: '安全生产费用提取与使用',
  items: [
    { name: '年度营收入证明' },
    { name: '安全费用提取标准及计划' },
    { name: '安全生产费用使用台账（发票或收据）' },
  ],
}

const BOX_SIX = {
  no: '六',
  name: '动态监控及交通违法管理',
  items: [
    { name: '动态监控工作情况记录台账（每日）', freq: F.daily },
    { name: '设备故障异常台账' },
    { name: '在线率台账' },
    { name: '月报台账' },
    { name: '针对性违规教育（培训内容、照片、签字）' },
    { name: '交通事故违法针对性教育（培训内容、照片、签字）' },
  ],
}

const BOX_SEVEN_GENERAL = {
  no: '七',
  name: '应急管理',
  items: [
    { name: '应急预案' },
    { name: '风险评估和应急资源调查' },
    { name: '应急预案评审或论证' },
    { name: '应急队伍架构' },
    { name: '应急培训（培训内容、考核、照片）' },
    { name: '应急救援器材配备' },
    { name: '应急演练计划' },
    { name: '应急演练（方案、照片、签到）' },
  ],
}

const BOX_EIGHT_GENERAL = {
  no: '八',
  name: '风险分级管控与隐患排查治理',
  items: [
    { name: '风险辨识手册' },
    { name: '风险辨识清单' },
    { name: '风险告知卡（每个岗位一份）' },
    { name: '无重大风险的说明' },
    { name: '隐患排查记录（排查内容、照片、签字）' },
    { name: '隐患排查治理（整改通知书、验收单）' },
  ],
}

const BOX_NINE_GENERAL = {
  no: '九',
  name: '一日三检',
  items: [{ name: '（日常车辆三检记录）' }],
}

const BOX_TEN = {
  no: '十',
  name: '事故档案',
  items: [
    { name: '年度事故汇总统计表' },
    { name: '事故资料（按事故归档）' },
    { name: '事故快报表' },
    { name: '事故四不放过资料' },
  ],
}

// 危货专属：盒七 停车场管理 / 盒八 应急管理（带备案回执等）/ 盒九 风险（带安全评估等）/ 盒十一 事故档案
const BOX_SEVEN_DANGER = {
  no: '七',
  name: '停车场管理',
  items: [
    { name: '产权证明' },
    { name: '租赁合同（到期预警）', freq: F.warn },
    { name: '平面图及照片' },
  ],
}

const BOX_EIGHT_DANGER = {
  no: '八',
  name: '应急管理',
  items: [
    { name: '应急预案备案回执' },
    { name: '应急预案' },
    { name: '风险评估和应急资源调查' },
    { name: '应急预案评审或论证' },
    { name: '应急值班制度' },
    { name: '应急队伍架构' },
    { name: '应急值班表' },
    { name: '应急培训（培训内容、考核、照片）' },
    { name: '应急救援器材配备' },
    { name: '应急演练计划' },
    { name: '应急演练（方案、照片、签到）' },
  ],
}

const BOX_NINE_DANGER = {
  no: '九',
  name: '风险分级管控与隐患排查治理',
  items: [
    { name: '安全评估' },
    { name: '风险辨识手册' },
    { name: '风险辨识清单' },
    { name: '风险告知卡（每个岗位一份）' },
    { name: '无重大风险的说明' },
    { name: '无重大事故隐患的声明' },
    { name: '隐患排查记录（排查内容、照片、签字）' },
    { name: '隐患排查治理（整改通知书、验收单）' },
  ],
}

const BOX_ELEVEN = {
  no: '十一',
  name: '事故档案',
  items: [
    { name: '年度事故汇总统计表' },
    { name: '事故资料（按事故归档）' },
    { name: '事故快报表' },
    { name: '事故四不放过资料' },
  ],
}

export const ARCHIVE_CATALOG = {
  普货: [
    BOX_ONE, BOX_TWO, BOX_THREE, BOX_FOUR, BOX_FIVE, BOX_SIX,
    BOX_SEVEN_GENERAL, BOX_EIGHT_GENERAL, BOX_NINE_GENERAL, BOX_TEN,
  ],
  危货: [
    BOX_ONE, BOX_TWO, BOX_THREE, BOX_FOUR, BOX_FIVE, BOX_SIX,
    BOX_SEVEN_DANGER, BOX_EIGHT_DANGER, BOX_NINE_DANGER, BOX_NINE_GENERAL, BOX_ELEVEN,
  ],
}

export const FREQ_LABEL = {
  yearly: '一年一签',
  once: '入职一次',
  monthly: '每月',
  daily: '每日',
  warn: '到期预警',
}

// 状态：0 缺失(无数据) / 1 待生成 / 2 已生成 / 3 已打印归档
export const STATUS = {
  MISS: 0,
  WAIT: 1,
  DONE: 2,
  PRINT: 3,
}
