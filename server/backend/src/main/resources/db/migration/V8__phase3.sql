-- D 阶段（三期 v1.2）D1 动态监控台账 / D2 安全生产费用 / D3 风险分级管控
-- 由 tools/gen_biz_meta.py 从 database/schema.sql 逐字提取（EXTRA_DDL 补齐表同规则）。
-- 只创建 V1~V7 尚未建过的表；已发布迁移 V1~V7 不受影响，校验和保持不变。

-- ===== D1 动态监控台账 =====
CREATE TABLE `monitor_ledger` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `ledger_name` VARCHAR(200) NOT NULL COMMENT '台账名称',
  `ledger_type` VARCHAR(30) COMMENT '类型：工作记录/设备故障/在线率/月报/交警抄报',
  `source` VARCHAR(30) COMMENT '来源：动态监控平台/交警抄报/人工录入',
  `period` VARCHAR(20) COMMENT '周期（如 2026-10）',
  `file_url` VARCHAR(500) COMMENT '附件/文件留存地址',
  `uploader_id` BIGINT COMMENT '上传人',
  `uploaded_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `violation_count` INT DEFAULT 0 COMMENT '识别出的违规车辆数（冗余统计）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_monitor_ledger_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='动态监控台账留存';;

CREATE TABLE `org_outsource` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `outsource_type` VARCHAR(30) NOT NULL COMMENT '委托类型：monitor=监控',
  `outsource_org` VARCHAR(100) COMMENT '第三方机构名称',
  `enabled` TINYINT DEFAULT 1 COMMENT '是否已委托',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_org_outsource_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='委托第三方监控标记';;

-- ===== D2 安全生产费用 =====
CREATE TABLE `budget_plan` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `year` INT NOT NULL COMMENT '计提年度',
  `cargo_type` VARCHAR(10) COMMENT 'general普货/danger危货',
  `revenue` DECIMAL(14,2) COMMENT '上年度营业收入（元）',
  `revenue_file` VARCHAR(500) COMMENT '营收证明文件（B7）',
  `rate` DECIMAL(5,4) COMMENT '计提比例（普货0.01/危货0.015，B2）',
  `plan_amount` DECIMAL(14,2) COMMENT '应提金额 = revenue × rate（B3）',
  `used_amount` DECIMAL(14,2) DEFAULT 0 COMMENT '已用金额（支出台账汇总）',
  `balance` DECIMAL(14,2) COMMENT '结余 = plan_amount - used_amount',
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft/confirmed',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_budget_plan_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全费用计提计划';;

CREATE TABLE `budget_expense` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `plan_id` BIGINT NOT NULL COMMENT '关联年度计提',
  `category_id` BIGINT COMMENT '费用类别（B5）',
  `expense_date` DATE NOT NULL COMMENT '支出日期',
  `expense_desc` VARCHAR(500) COMMENT '事由/摘要',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '金额（元）',
  `receipt_file` VARCHAR(500) COMMENT '发票/收据（B6）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_budget_expense_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全费用支出';;

CREATE TABLE `budget_category` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `category_name` VARCHAR(100) NOT NULL COMMENT '费用类别名（财资136号11类全称）',
  `category_code` VARCHAR(50) COMMENT '编码（FACILITY/LOCATION/RESCUE/HAZARD/CHECK/PPE/TRAIN/TECH/INSPECT/INSUR/OTHER）',
  `sort_order` INT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_budget_category_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全费用支出类别';;

-- ===== D3 风险分级管控 =====
CREATE TABLE `risk_point_dict` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `risk_name` VARCHAR(200) NOT NULL COMMENT '风险点名称（如"疲劳驾驶"）',
  `risk_category` VARCHAR(50) COMMENT '分类：driver驾驶员/vehicle车辆/cargo货物/env环境/manage管理',
  `risk_level` VARCHAR(20) COMMENT '风险等级：红/橙/黄/蓝（重大/较大/一般/低）',
  `control_measure` VARCHAR(1000) COMMENT '管控措施（模板默认）',
  `cargo_type` VARCHAR(10) COMMENT 'general普货/danger危货/all通用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_risk_point_dict_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='风险点字典';;

CREATE TABLE `risk_list` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `year` INT COMMENT '年度',
  `risk_point_id` BIGINT COMMENT '风险点（引用字典）',
  `risk_source` VARCHAR(200) COMMENT '风险源（如"恶劣天气/道路条件"，两级清单第二级）',
  `risk_desc` VARCHAR(500) COMMENT '风险描述（企业可补充）',
  `consequence` VARCHAR(500) COMMENT '事故后果（如"人员伤亡、车辆损坏、财产损失"）',
  `risk_level` VARCHAR(20) COMMENT '风险等级',
  `control_engineering` VARCHAR(1000) COMMENT '风险控制措施·工程技术',
  `control_manage` VARCHAR(1000) COMMENT '风险控制措施·管理措施',
  `responsible_id` BIGINT COMMENT '责任人（组织架构）',
  `remark` VARCHAR(500),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_risk_list_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='风险清单';;

CREATE TABLE `risk_card` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `year` INT,
  `post_id` BIGINT NOT NULL COMMENT '岗位（组织架构）',
  `post_name` VARCHAR(50) COMMENT '岗位名（快照）',
  `risk_points` TEXT COMMENT '该岗位风险点（JSON，引用风险点）',
  `content_html` LONGTEXT COMMENT '告知卡正文（模板生成）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_risk_card_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='风险卡';;

CREATE TABLE `risk_manual` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `year` INT,
  `doc_type` VARCHAR(30) COMMENT 'manual手册/no_risk说明/safety_eval安全评估/no_hidden声明',
  `doc_name` VARCHAR(200) NOT NULL,
  `content_html` LONGTEXT COMMENT '正文（模板生成）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_risk_manual_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='风险手册';;

CREATE TABLE `org_post_content` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `post_code` VARCHAR(50) COMMENT '岗位编码（对齐组织架构岗位）',
  `content_code` VARCHAR(30) COMMENT '内容块：duty/target/hazard/prevent/commitment/post_desc',
  `content_val` LONGTEXT COMMENT '内容（JSON，列表型存数组）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_org_post_content_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='org_post_content';;

