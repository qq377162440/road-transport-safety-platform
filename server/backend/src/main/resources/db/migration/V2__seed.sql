-- ============================================================
-- 第一阶段：种子数据
-- 角色 / 12 职能字典 / 档案盒目录 / 范本套 / 演示企业+账号+岗位
-- 固定 ID，便于测试与跨租户验证
-- ============================================================

-- ---------- 角色（3 个平台级角色） ----------
INSERT INTO `sys_role` (`id`, `tenant_id`, `role_code`, `role_name`, `is_builtin`, `data_scope`) VALUES
  (1001, 0, 'enterprise', '企业账号', 1, 1),
  (1002, 0, 'provider',   '服务商账号', 1, 1),
  (1003, 0, 'admin',      '平台超级管理员', 1, 1);

-- ---------- 12 系统职能字典（平台统一，企业侧不可改） ----------
INSERT INTO `org_dict` (`id`, `dict_type`, `dict_code`, `dict_name`, `sort`) VALUES
  (2001, 'post', 'gm',      '总经理',          1),
  (2002, 'post', 'safe',    '安全员',          2),
  (2003, 'post', 'disp',    '运营调度员',      3),
  (2004, 'post', 'mon',     '监控员',          4),
  (2005, 'post', 'clerk',   '资料员',          5),
  (2006, 'post', 'finance', '财务',            6),
  (2007, 'post', 'captain', '车队长',          7),
  (2008, 'post', 'vehtech', '车辆技术管理员',  8),
  (2009, 'post', 'parklot', '停车场管理员',    9),
  (2010, 'post', 'driver',  '驾驶员',         10),
  (2011, 'post', 'escort',  '押运员',         11),
  (2012, 'post', 'loader',  '装卸管理员',     12);

-- ---------- 档案盒目录（普货；只建盒二及子项，覆盖本切片） ----------
-- 盒二：安全生产管理制度及操作规程
INSERT INTO `archive_catalog` (`id`, `tenant_id`, `biz_type`, `level`, `sort_no`, `code`, `name`, `parent_id`, `has_pdf`, `renew_freq`) VALUES
  (3002, 0, 'GENERAL', 1, 2, 'BOX2', '盒二 · 安全生产管理制度及操作规程', 0, 0, 'LONG'),
  (3101, 0, 'GENERAL', 2, 1, 'RESPONSIBILITY', '安全生产责任制', 3002, 1, 'ONCHANGE');

-- ---------- 范本套（服务商全局） ----------
INSERT INTO `tpl_set` (`id`, `tenant_id`, `code`, `name`, `biz_type`, `region`, `is_default`, `desc`, `status`, `sort_no`) VALUES
  (4001, 0, 'PU_TPL_COMMON', '普货通用范本', 'GENERAL', 'ALL', 1, '普货通用制度范本套（第一阶段含安全生产责任制）', 1, 1),
  (4002, 0, 'PU_TPL_MICRO', '普货小微范本', 'GENERAL', 'ALL', 0, '占位骨架，后续补充', 1, 2);

-- 套内范本文件：安全生产责任制（动态生成占位模板）
INSERT INTO `tpl_template` (`id`, `tenant_id`, `group_id`, `code`, `name`, `category`, `archive_code`, `post_scope`, `content`, `need_seal`, `version`, `status`) VALUES
  (4101, 0, 4001, 'RESPONSIBILITY', '安全生产责任制', '制度', 'RESPONSIBILITY', '', '{{RESPONSIBILITY_BODY}}', 1, 1, 1);

-- ---------- 演示企业（租户） ----------
INSERT INTO `sys_tenant` (`id`, `name`, `credit_code`, `status`) VALUES
  (5001, '佛山市高明高洲燃气有限公司', '91440600TEST000001', 1);

INSERT INTO `tenant_info` (`id`, `tenant_id`, `credit_code`, `biz_type`, `biz_term_type`, `legal_person`, `safety_charge`, `charge_phone`, `region`, `address`, `industry`) VALUES
  (5101, 5001, '91440600TEST000001', 'freight', 1, '张伟', '张伟', '138****0001', '佛山市高明区', '佛山市高明区高洲路 1 号', '道路货物运输');

-- ---------- 演示账号（账号前缀识别身份：aa=企业 / bb=服务商 / cc=超管） ----------
-- 密码均为 BCrypt("123456")
INSERT INTO `sys_user` (`id`, `tenant_id`, `username`, `password`, `real_name`, `phone`, `user_type`, `status`) VALUES
  (6001, 5001, 'aa_gaozhou',  '$2a$10$JDZ/JmmNddNg29KgY9vGV.g3Q5iW4ak5KGqZJ1cviOTqmBr3SZLZa', '高洲企业管理员', '138****0001', 'enterprise', 1),
  (6002, 0,    'bb_provider', '$2a$10$JDZ/JmmNddNg29KgY9vGV.g3Q5iW4ak5KGqZJ1cviOTqmBr3SZLZa', '服务商账号',     '138****0002', 'provider',   1),
  (6003, 0,    'cc_admin',    '$2a$10$JDZ/JmmNddNg29KgY9vGV.g3Q5iW4ak5KGqZJ1cviOTqmBr3SZLZa', '平台超管',       '138****0003', 'admin',      1);

INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES
  (6001, 1001),
  (6002, 1002),
  (6003, 1003);

-- ---------- 演示企业岗位（普货默认模板：gm/safe/mon/driver 启用，其余禁用） ----------
-- 默认上级关系：gm 根；safe/mon 挂 gm；driver 挂 captain（captain 禁用，故 driver 上提到 gm）
INSERT INTO `org_post` (`id`, `tenant_id`, `parent_id`, `dict_id`, `func_key`, `post_name`, `required`, `enabled`, `goals`, `duties`, `assess_std`) VALUES
  (7001, 5001, 0,    2001, 'gm',     '总经理',      1, 1, '全面领导公司安全生产工作，年度零重特大事故、死亡事故为零', '建立健全并落实全员安全生产责任制；组织制定安全生产规章制度和操作规程；保证安全生产投入有效实施；组织建立并落实安全风险分级管控和隐患排查治理双重预防机制', '年度安全生产目标完成情况、事故控制指标、责任制考核得分'),
  (7002, 5001, 7001, 2002, 'safe',   '安全员',      1, 1, '组织或参与拟定安全生产规章制度、操作规程和应急救援预案，隐患整改闭环率 100%', '组织安全生产教育培训；组织隐患排查并督促整改闭环；制止和纠正违章指挥、强令冒险作业行为；组织应急演练', '制度修订完成率、隐患整改闭环率、培训计划完成率'),
  (7003, 5001, 7001, 2003, 'disp',   '运营调度员',  0, 0, '合理调度运力，杜绝超载超速疲劳驾驶派单', '编制运输计划与车辆调度；核查驾驶员资格与车辆技术状况；动态跟踪运输任务执行', '调度任务及时率、违规派单次数'),
  (7004, 5001, 7002, 2004, 'mon',    '监控员',      1, 1, '动态监控报警处置及时率 100%，监控台账完整率 100%', '24 小时实时监控车辆动态；对超速、疲劳驾驶等报警及时提醒纠正并留档；填记动态监控台账', '报警处置及时率、监控台账完整率'),
  (7005, 5001, 7001, 2005, 'clerk',  '资料员',      0, 0, '行政后勤保障到位，文件档案归档及时', '公文收发与档案管理；会议组织与后勤保障；安全台账归档', '档案归档及时率、会议组织完成率'),
  (7006, 5001, 7001, 2006, 'finance','财务',        0, 0, '安全生产费用足额提取、专款专用', '安全生产费用提取与使用核算；保险、赔偿等资金保障', '安全费用提取率、使用台账合规率'),
  (7007, 5001, 7003, 2007, 'captain','车队长',      0, 0, '车队安全行车零重大责任事故', '组织车队日常安全例会与出车前叮嘱；管理驾驶员行车日志与三检执行', '车队违章率、事故率、三检执行率'),
  (7008, 5001, 7001, 2008, 'vehtech','车辆技术管理员', 0, 0, '车辆技术状况完好率 ≥98%', '车辆维护保养计划制定与执行；车辆技术档案管理；车辆证照年审跟踪', '车辆完好率、维保计划完成率'),
  (7009, 5001, 7001, 2009, 'parklot','停车场管理员', 0, 0, '停车场秩序与消防设施完好', '车辆进出登记与停放管理；停车场消防设施巡查', '停车场巡查台账完整率'),
  (7010, 5001, 7007, 2010, 'driver', '驾驶员',      0, 1, '安全行车零责任事故，三检执行率 100%', '严格执行一日三检；填记行车日志；遵章驾驶不超速不疲劳驾驶', '三检执行率、行车日志完整率、违章次数'),
  (7011, 5001, 7007, 2011, 'escort', '押运员',      0, 0, '押运货物零丢失零泄漏', '装卸货物监装监卸；运输途中押运监护；紧急情况先期处置', '押运任务完成率、监护记录完整率'),
  (7012, 5001, 7007, 2012, 'loader', '装卸管理员',  0, 0, '装卸作业零事故', '组织装卸作业；核查装卸区安全条件；装卸设备状态确认', '装卸作业合规率、装卸区隐患整改率');
