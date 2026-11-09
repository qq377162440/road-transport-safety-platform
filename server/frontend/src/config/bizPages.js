// 业务模块专用页展示配置（与 tools/gen_biz_meta.py 的表字段保持一致）
// 每项：title/desc 页头；table 主表；cols 列表列 [字段, 中文名, 宽度]；
//       filters 筛选；box 归档盒；status 状态字典；feature 特色区块类型。
// 设计依据：详细设计/00~21 + prototype/pc/*.html，数据全部来自后端真实表。
export const BIZ_PAGES = {
  common: {
    title: '系统运维', desc: '操作留痕 / 菜单权限 / 预警规则 · 系统级基础设施',
    table: 'sys_oper_log', feature: 'none',
    cols: [['module', '模块', 130], ['action', '操作', 140], ['target_id', '目标ID', 160], ['ip', 'IP', 140], ['user_id', '操作人ID', 140]],
    filters: [['module', '模块', 'input']],
  },
  person: {
    title: '人员基础信息', desc: '从业人员基础档案管理 · 一人一档（以档案盒目录为骨架）',
    table: 'person', box: '盒一', feature: 'person',
    cols: [['name', '人员姓名', 110], ['politic', '政治面貌', 110], ['gender', '性别', 70], ['person_type', '人员类型', 120], ['telephone', '手机号', 140], ['id_card', '身份证号', 170]],
    filters: [['name', '姓名', 'input'], ['person_type', '人员类型', 'select:管理人员/从业人员']],
    status: {},
  },
  vehicle: {
    title: '车辆基础信息', desc: '车辆基础信息（一车一档核心）· 车/挂分离、证照与设备',
    table: 'vehicle', box: '盒二', feature: 'vehicle',
    cols: [['plate_no', '车牌号', 130], ['biz_type', '营运类型', 110], ['vehicle_type', '车辆类型', 110], ['is_trailer', '车/挂', 80], ['vehicle_no', '车辆编号', 130], ['brand_model', '厂牌型号', 150], ['vin', '车架号', 160]],
    filters: [['plate_no', '车牌号', 'input'], ['biz_type', '营运类型', 'select:货运/客运/危险品']],
  },
  institution: {
    title: '制度档案引擎', desc: '制度 / 操作规程 / 台账范本的分级配置与版本管理',
    table: 'tpl_group', box: '盒二', feature: 'doc',
    cols: [['code', '编码', 130], ['name', '范本组名称', 200], ['biz_type', '业务类型', 120], ['region', '适用范围', 130], ['status', '状态', 90], ['remark', '备注', 180]],
    filters: [['name', '名称', 'input'], ['biz_type', '业务类型', 'input']],
  },
  check: {
    title: '一日三检', desc: '行车日志 + 出车/行车/收车三检 · 字段与台账「一日三检·行车日志」对应',
    table: 'check_record', box: '盒九', feature: 'check',
    cols: [['task_id', '任务ID', 160], ['phase', '时段', 100], ['check_day', '日期', 120], ['result', '结果', 100], ['check_time', '检查时间', 160], ['abnormal_note', '异常说明', 200]],
    filters: [['check_day', '日检日期', 'input'], ['phase', '时段', 'select:出车前/行车中/收车后']],
    status: {},
  },
  danger: {
    title: '隐患排查治理', desc: '隐患排查、登记、整改、闭环 · 预置重大事故隐患专项排查项',
    table: 'danger_record', box: '盒八', feature: 'danger',
    cols: [['title', '隐患/检查名称', 220], ['check_type', '排查类型', 140], ['danger_level', '隐患级别', 110], ['location', '位置', 130], ['find_time', '发现时间', 160], ['status', '状态', 110]],
    filters: [['title', '名称', 'input'], ['status', '状态', 'select:待整改/整改中/待验收/已闭环']],
  },
  meeting: {
    title: '安全会议管理', desc: '会议通知、签到、纪要、照片四段式 · 两类会议（领导小组/月度）',
    table: 'meeting', box: '盒三', feature: 'meeting',
    cols: [['title', '会议名称', 240], ['meeting_type', '会议类型', 140], ['start_time', '召开时间', 170], ['location', '地点', 130], ['host_id', '主持人ID', 120], ['status', '状态', 100]],
    filters: [['title', '会议名称', 'input'], ['meeting_type', '会议类型', 'input']],
  },
  collect: {
    title: '服务商代录', desc: '服务商代企业录入资料与签署文件 · 代录工作台',
    table: 'collect_task', box: '盒一', feature: 'none',
    cols: [['file_type', '资料类型', 140], ['target_type', '归属对象', 130], ['status', '状态', 100], ['file_url', '文件', 240]],
    filters: [['status', '状态', 'input']],
  },
  training: {
    title: '培训教育', desc: '培训计划 / 课程视频 / 在线考试 / 学习留痕（PC 下发端）',
    table: 'training_plan', box: '盒四', feature: 'training',
    cols: [['plan_name', '计划名称', 220], ['plan_type', '培训类型', 130], ['year', '年度', 80], ['month', '月份', 80], ['learn_hours', '学时', 90], ['pass_score', '合格分', 90], ['status', '状态', 100]],
    filters: [['plan_name', '计划名称', 'input'], ['year', '年度', 'input']],
  },
  emergency: {
    title: '应急管理', desc: '应急预案 / 演练计划 / 演练记录 / 应急物资',
    table: 'emergency_plan', box: '盒七', feature: 'none',
    cols: [['plan_name', '预案名称', 220], ['cargo_type', '业务类型', 120], ['version', '版本', 90], ['status', '状态', 100], ['record_no', '备案号', 140], ['effective_date', '生效日期', 130]],
    filters: [['plan_name', '预案名称', 'input'], ['status', '状态', 'input']],
  },
  accident: {
    title: '事故与违法', desc: '事故快报 / 事故台账 / 违法记录 / 年度汇总',
    table: 'accident_record', box: '盒十', feature: 'none',
    cols: [['accident_no', '事故编号', 150], ['accident_time', '事故时间', 170], ['location', '地点', 150], ['vehicle_plate', '车牌号', 120], ['accident_type', '事故类型', 120], ['severity', '等级', 90], ['status', '状态', 100]],
    filters: [['vehicle_plate', '车牌号', 'input'], ['status', '状态', 'input']],
  },
  assess: {
    title: '考核奖惩', desc: '安全考核 / 信用评价 / 奖惩通知',
    table: 'credit_assess', box: '盒一', feature: 'none',
    cols: [['assess_name', '考核名称', 220], ['year', '年度', 90], ['status', '状态', 100]],
    filters: [['year', '年度', 'input']],
  },
  sign: {
    title: '自定义文件签署', desc: '自定义 PDF 文件在线签署 / 签署留痕归档',
    table: 'sign_task', box: '盒一', feature: 'none',
    cols: [['title', '文件名称', 220], ['status', '状态', 100], ['signer', '签署人', 130], ['signed_at', '签署时间', 170]],
    filters: [['title', '文件名称', 'input'], ['status', '状态', 'input']],
  },
  monitor: {
    title: '动态监控台账', desc: '台账留存 → 违规识别统计 → 靶向教育 → 落实跟踪（归档盒六）',
    table: 'monitor_ledger', box: '盒六', feature: 'none',
    cols: [['ledger_name', '台账名称', 200], ['ledger_type', '台账类型', 130], ['source', '来源', 140], ['period', '期间', 120], ['violation_count', '违规数', 90]],
    filters: [['ledger_name', '台账名称', 'input'], ['period', '期间', 'input']],
  },
  budget: {
    title: '安全生产费用', desc: '年度提取计划 / 使用明细 / 余额与凭证（归档盒五）',
    table: 'budget_plan', box: '盒五', feature: 'none',
    cols: [['year', '年度', 90], ['cargo_type', '业务类型', 120], ['revenue', '营业收入', 140], ['rate', '提取比例', 110], ['plan_amount', '计划金额', 130], ['used_amount', '已使用', 130], ['balance', '余额', 130], ['status', '状态', 100]],
    filters: [['year', '年度', 'input']],
  },
  risk: {
    title: '风险分级管控', desc: '风险点清单 / 辨识评估 / 分级管控措施 / 风险告知卡',
    table: 'risk_list', box: '盒八', feature: 'none',
    cols: [['year', '年度', 90], ['risk_source', '风险源', 160], ['risk_desc', '风险描述', 220], ['consequence', '后果', 160], ['risk_level', '风险等级', 110], ['responsible_id', '责任人ID', 120]],
    filters: [['risk_level', '风险等级', 'input'], ['year', '年度', 'input']],
  },
  dangerspecial: {
    title: '危货专项深化', desc: '危货专项台账 / 电子运单 / 一车一档专项留痕',
    table: 'danger_special_ledger', box: '盒六', feature: 'none',
    cols: [['ledger_type', '台账类型', 130], ['biz_date', '业务日期', 130], ['waybill_no', '运单号', 150], ['plate_no', '车牌号', 120], ['cargo_name', '货物品名', 140], ['status', '状态', 100]],
    filters: [['plate_no', '车牌号', 'input'], ['biz_date', '业务日期', 'input']],
  },
  platform: {
    title: '平台侧能力', desc: '模块计费配置 / 租户模块授权 / SSO 配置 / 服务账单',
    table: 'sys_module_config', box: '', feature: 'none',
    cols: [['module_code', '模块编码', 140], ['module_name', '模块名称', 160], ['phase', '阶段', 90], ['group_name', '分组', 120], ['is_base', '基础模块', 100], ['monthly_fee', '月费', 100], ['enabled', '启用', 90]],
    filters: [['module_name', '模块名称', 'input'], ['phase', '阶段', 'input']],
  },
}

// 状态值 → 中文 / 标签色
export const STATUS_MAP = {
  '待整改': 'warning', '整改中': 'primary', '待验收': 'primary', '已闭环': 'success', '超期': 'danger',
  '未开始': 'info', '进行中': 'primary', '已完成': 'success', '已结束': 'info', '已归档': 'success',
  '待整改中': 'warning', '合格': 'success', '不合格': 'danger', '正常': 'success', '异常': 'danger',
  '生效': 'success', '草稿': 'info', '停用': 'info', '启用': 'success', '已签署': 'success', '待签署': 'warning',
}
