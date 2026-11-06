-- ============================================================================
-- E2 数据初始化与开户引导 / E3 试运行反馈闭环
--
-- 依据：《产品需求确认文档》§十一「企业签约后的完整交付与运营流程」阶段 0~5
--       《详细设计 04》首次接入初始化引导（三步填报状态机）
--
-- 四张表：
--   onboarding_checklist  平台级六阶段交付检查项字典（阶段 0~5，逐项可跳转）
--   sys_onboarding        每企业一条接入状态（阶段/步骤/状态/合同期/现状诊断）
--   onboarding_item       每企业每检查项的完成进度
--   sys_trial_feedback    E3 种子客户试运行反馈（提交→受理→处理→闭环）
-- ============================================================================

CREATE TABLE `onboarding_checklist` (
  `id` BIGINT NOT NULL COMMENT '雪花ID（固定种子便于测试）',
  `stage` TINYINT NOT NULL COMMENT '交付阶段 0~5（需求 §十一）',
  `item_code` VARCHAR(50) NOT NULL COMMENT '稳定编码',
  `item_name` VARCHAR(120) NOT NULL COMMENT '检查项名称',
  `target_route` VARCHAR(120) COMMENT '点击跳转的前端路由',
  `required` TINYINT NOT NULL DEFAULT 1 COMMENT '1必做 0可选',
  `sort_no` INT NOT NULL DEFAULT 0,
  `remark` VARCHAR(255),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item_code` (`item_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新企业接入六阶段检查项字典';

CREATE TABLE `sys_onboarding` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `stage` TINYINT NOT NULL DEFAULT 0 COMMENT '当前交付阶段 0~5',
  `step` TINYINT NOT NULL DEFAULT 0 COMMENT '阶段1三步填报进度 0~3',
  `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending待接入/in_progress进行中/completed已完成/operating运营中',
  `biz_type` VARCHAR(20) NOT NULL DEFAULT 'GENERAL' COMMENT 'GENERAL普货 / DANGER危货',
  `contract_start` DATE COMMENT '合同起始',
  `contract_end` DATE COMMENT '合同到期（续费依据）',
  `diagnosis` TEXT COMMENT '阶段1安全管理现状诊断（JSON）',
  `completed_at` DATETIME COMMENT '接入完成时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by` BIGINT NOT NULL DEFAULT 0,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业签约接入状态';

CREATE TABLE `onboarding_item` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `item_code` VARCHAR(50) NOT NULL COMMENT '检查项编码',
  `stage` TINYINT NOT NULL COMMENT '冗余阶段号，便于分组统计',
  `done` TINYINT NOT NULL DEFAULT 0 COMMENT '0未完成 1已完成',
  `done_at` DATETIME COMMENT '完成时间',
  `done_by` BIGINT COMMENT '操作人',
  `remark` VARCHAR(255),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_item` (`tenant_id`, `item_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业接入检查项完成进度';

CREATE TABLE `sys_trial_feedback` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `user_id` BIGINT NOT NULL DEFAULT 0,
  `user_type` VARCHAR(20) COMMENT 'enterprise/provider/admin',
  `module` VARCHAR(50) COMMENT '反馈所属模块（如 一日三检/电子档案柜）',
  `feedback_type` VARCHAR(20) NOT NULL DEFAULT 'suggestion' COMMENT 'bug缺陷/suggestion建议/praise表扬/question咨询',
  `severity` TINYINT NOT NULL DEFAULT 2 COMMENT '1致命 2一般 3轻微',
  `content` VARCHAR(1000) NOT NULL COMMENT '反馈内容',
  `contact` VARCHAR(100) COMMENT '联系方式',
  `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending待受理/processing处理中/resolved已解决/rejected已驳回',
  `handler_id` BIGINT COMMENT '处理人',
  `handle_note` VARCHAR(500) COMMENT '处理说明',
  `handled_at` DATETIME COMMENT '处理时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='种子客户试运行反馈';

-- ---------- 六阶段检查项字典（逐字对齐需求 §十一 阶段 0~5） ----------
INSERT INTO `onboarding_checklist`
  (`id`,`stage`,`item_code`,`item_name`,`target_route`,`required`,`sort_no`,`remark`) VALUES
  -- 阶段 0 签约与开户
  (8001,0,'S0_CONTRACT','商务签约（营运类型 / 服务套餐 / 合同期）','/onboarding',1,1,'确定 GENERAL 普货或 DANGER 危货'),
  (8002,0,'S0_TENANT','新增企业并自动开通主账号','/onboarding',1,2,'主账号用户名=企业全称，初始密码 888888'),
  (8003,0,'S0_LICENSE','营业执照上传与工商信息录入','/company',1,3,'盒一归档项'),
  (8004,0,'S0_CONFIG','合同期与功能模块开通配置','/onboarding',1,4,'到期时间写入 sys_tenant.expire_time'),
  -- 阶段 1 资料采集
  (8011,1,'S1_STEP1','第一步：企业基础资料（接入必填项）','/m/onboard',1,1,'精简必填字段，其余在 PC 端补齐'),
  (8012,1,'S1_STEP2','第二步：人档 + 车档资料批量录入','/m/onboard',1,2,'录入手机号即自动开通小程序账号'),
  (8013,1,'S1_STEP3','第三步：安全管理现状（选择 + 开放）','/m/onboard',1,3,'诊断结果驱动阶段2整改'),
  (8014,1,'S1_DIAGNOSIS','现状诊断结论与整改范围确认','/onboarding',1,4,'客户往往因资料不规范才签约'),
  (8015,1,'S1_COLLECT','服务商代录（不会用系统的客户）','/collect',0,5,'可选：跳过推送、复用历史签字'),
  -- 阶段 2 静态资料全面整改
  (8021,2,'S2_ORG','安全管理架构 / 领导小组 / 任命书','/org',1,1,'按企业实际架构，非模板套话'),
  (8022,2,'S2_INSTITUTION','24 项安全生产制度 + 目录','/template-manager',1,2,'YT-ZD01~24，责任书↔责任制联动'),
  (8023,2,'S2_EMERGENCY','应急预案体系（综合/专项/现场处置）','/biz/emergency',1,3,'含风险评估与应急资源调查'),
  (8024,2,'S2_PLAN','教育/费用/隐患/会议四类年度计划','/biz/training',1,4,''),
  (8025,2,'S2_THREE_BOOKS','人员三书（责任书/承诺书/告知书）','/responsibility',1,5,'按自然年度 1 月 1 日重签'),
  (8026,2,'S2_PREJOB','岗前三级安全教育与考核','/flow-tools',1,6,'合格方可上岗'),
  -- 阶段 3 小程序全员账号开通
  (8031,3,'S3_ACCOUNTS','全员小程序账号开通（录入即开通）','/biz/person',1,1,'账号=手机号，初始密码 123456'),
  (8032,3,'S3_ROLES','岗位差异化功能可见性校验','/post',1,2,'驾驶员/安全员/管理员'),
  (8033,3,'S3_STATUS','离职停用联动与账号恢复','/biz/person',0,3,'人档车档保留 ≥3 年'),
  -- 阶段 4 制度与台账归档就位
  (8041,4,'S4_PUBLISH','制度发布与企业按清单签署归档','/archive',1,1,''),
  (8042,4,'S4_CABINET','电子档案柜自检（10/11 盒 + 缺项红黄绿）','/archive-overview',1,2,'验收标准 2：5 分钟调出完整台账'),
  (8043,4,'S4_PDF','整套台账 PDF 一键下载','/archive-overview',1,3,''),
  -- 阶段 5 日常运营与年审续签
  (8051,5,'S5_DAILY','一日三检 / 安全会议日常留痕','/m/check',1,1,'验收标准 1：30 秒完成一次三检'),
  (8052,5,'S5_TRAINING','培训教育（课程下发 / 视频 / 考试）','/biz/training',1,2,''),
  (8053,5,'S5_DANGER','隐患排查治理闭环','/biz/danger',1,3,'登记→整改→验收'),
  (8054,5,'S5_MONITOR','动态监控台账上传留存与靶向教育','/biz/monitor',0,4,'不做实时监测，归外部平台'),
  (8055,5,'S5_RENEW','自然年度重签预警与年审续费','/onboarding',1,5,'按合同期计费');

-- ---------- 演示企业（5001）已处于日常运营阶段 ----------
INSERT INTO `sys_onboarding`
  (`id`,`tenant_id`,`stage`,`step`,`status`,`biz_type`,`completed_at`) VALUES
  (9001, 5001, 5, 3, 'operating', 'GENERAL', '2026-01-01 09:00:00');

INSERT INTO `onboarding_item`
  (`id`,`tenant_id`,`item_code`,`stage`,`done`,`done_at`,`done_by`)
SELECT 9000 + c.`id`, 5001, c.`item_code`, c.`stage`, 1, '2026-01-01 09:00:00', 0
FROM `onboarding_checklist` c WHERE c.`deleted` = 0 AND c.`required` = 1;
