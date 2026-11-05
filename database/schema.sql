-- ============================================================
-- 道路运输安全生产管理平台 · 数据库建表脚本
-- 数据库：safe_transport
-- 引擎：MySQL 8.0 · utf8mb4 · InnoDB
-- 生成日期：2026-10-31
-- 规模：102 张表 · 1215 个字段
-- 说明：
--   1. 主键为雪花 ID（BIGINT），由应用层 MyBatis-Plus ASSIGN_ID 生成，非自增
--   2. 多租户字段级隔离：业务表统一含 tenant_id
--   3. 逻辑删除：deleted 标志位（0否 1是）
--   4. 不建物理外键，实体关系见《数据库设计文档》第五节 ER 图
-- ============================================================

CREATE DATABASE IF NOT EXISTS `safe_transport` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `safe_transport`;

CREATE TABLE `accident_quick_report` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `accident_id` BIGINT NOT NULL COMMENT '关联事故记录',
  `report_content` LONGTEXT COMMENT '快报正文（自动生成）',
  `report_no` VARCHAR(50) COMMENT '快报编号',
  `report_time` DATETIME COMMENT '快报时间',
  `pdf_id` BIGINT COMMENT '生成的 PDF（08 引擎）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_accident_quick_report_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='事故快报';

CREATE TABLE `accident_record` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `accident_no` VARCHAR(50) COMMENT '事故编号（自动生成）',
  `accident_time` DATETIME NOT NULL COMMENT '事故发生时间',
  `location` VARCHAR(200) COMMENT '事故地点',
  `vehicle_id` BIGINT COMMENT '事故车辆（10 车辆档案）',
  `vehicle_plate` VARCHAR(20) COMMENT '车牌号（冗余，快照）',
  `accident_type` VARCHAR(30) COMMENT '事故类型：碰撞/追尾/刮擦/侧翻/起火/其他',
  `involve_person` TEXT COMMENT '涉及人员（JSON：姓名/岗位/伤亡情况）',
  `description` TEXT COMMENT '事故经过（简述）',
  `loss_amount` DECIMAL(12,2) COMMENT '直接经济损失（元）',
  `responsibility` VARCHAR(200) COMMENT '责任初判',
  `severity` VARCHAR(20) COMMENT '事故等级：轻微/一般/较大/重大/特别重大',
  `status` VARCHAR(20) DEFAULT 'reported' COMMENT 'reported已登记/processing处理中/closed已结案',
  `photos` VARCHAR(2000) COMMENT '现场照片（逗号分隔）',
  `reported_by` BIGINT COMMENT '上报人',
  `reported_at` DATETIME DEFAULT NOW() COMMENT '上报时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_accident_record_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='事故记录';

CREATE TABLE `accident_service` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `accident_id` BIGINT COMMENT '关联事故（可空，通用咨询）',
  `service_type` VARCHAR(50) COMMENT '服务项目：资料整理/四不放过/处理指导/责任分析/其他',
  `service_desc` VARCHAR(1000) COMMENT '需求描述',
  `contact_name` VARCHAR(50) COMMENT '联系人',
  `contact_phone` VARCHAR(20) COMMENT '联系电话',
  `status` VARCHAR(20) DEFAULT 'pending' COMMENT 'pending待受理/processing服务中/completed已完成',
  `fee` DECIMAL(12,2) COMMENT '服务费用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_accident_service_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='事故增值服务';

CREATE TABLE `accident_year_summary` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `year` INT NOT NULL COMMENT '年度',
  `total_count` INT DEFAULT 0 COMMENT '事故总数',
  `total_loss` DECIMAL(14,2) DEFAULT 0 COMMENT '总损失',
  `minor_count` INT DEFAULT 0 COMMENT '轻微事故数',
  `general_count` INT DEFAULT 0 COMMENT '一般事故数',
  `major_count` INT DEFAULT 0 COMMENT '较大事故数',
  `pdf_id` BIGINT COMMENT '生成的 PDF',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_accident_year_summary_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='事故年度汇总';

CREATE TABLE `archive_catalog` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `biz_type` VARCHAR(20) COMMENT '营运类型：GENERAL普货 / DANGER危货 / PASSENGER客运',
  `level` TINYINT COMMENT '1=一级档案盒，2=二级归档子项',
  `sort_no` INT COMMENT '排序号（盒号一/二/三...）',
  `code` VARCHAR(50) COMMENT '稳定编码（如 MONITOR_DAILY），业务模块引用用',
  `name` VARCHAR(100) COMMENT '档案盒/子项名称（逐字对齐政府目录）',
  `parent_id` BIGINT COMMENT '二级子项指向一级盒',
  `has_pdf` TINYINT DEFAULT 0 COMMENT '是否"本身就是一份可打印资料"',
  `renew_freq` VARCHAR(20) COMMENT '更新频率：YEARLY一年一签/MONTHLY每月/QUARTERLY每季/ONCE入职或一次性/ONCHANGE有变动时/DAILY每日/TRIP每趟/长期LONG/证照CERT',
  `remark` VARCHAR(255),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_archive_catalog_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='档案盒目录（11盒）';

CREATE TABLE `archive_item` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `catalog_id` BIGINT COMMENT '归属的档案盒/子项（二级）',
  `data_type` TINYINT COMMENT '资料类别：1静态 2动态 3证件（见第二章）',
  `gen_mode` TINYINT DEFAULT 0 COMMENT '生成方式：1真实流程 2先台账后执行（仅动态资料有效） 3上传留存',
  `service_id` BIGINT COMMENT '方式二专用：关联增值服务单（计费/结算用）',
  `biz_module` VARCHAR(50) COMMENT '来源模块：monitor/meeting/training/check...',
  `biz_id` BIGINT COMMENT '业务数据主键（如 meeting.id）',
  `title` VARCHAR(200) COMMENT '归档项标题（如"2026年10月安全生产月度会议"）',
  `occur_date` DATE COMMENT '归档项发生日期',
  `signed_year` SMALLINT COMMENT '签署年度（一年一签类用于自然年度到期判断：signed_year < 当前年即需重签）',
  `archive_year` SMALLINT COMMENT '归档年度（自然年度，如2026）；年度归档核心维度。周期性资料每年一条实例，ONCE类仅一条',
  `person_id` BIGINT COMMENT '一人一档专用：归属人员',
  `vehicle_id` BIGINT COMMENT '一车一档专用：归属车辆',
  `status` TINYINT COMMENT '0待生成 1已生成 2已打印 3已归档',
  `pdf_id` BIGINT COMMENT '关联成品PDF',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_archive_item_tenant_id` (`tenant_id`),
  KEY `idx_archive_item_year` (`tenant_id`, `catalog_id`, `archive_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='归档子项';

CREATE TABLE `archive_pdf` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `item_id` BIGINT COMMENT '关联归档项',
  `archive_year` SMALLINT COMMENT '归档年度（落款用，对齐对应归档项的archive_year；按年归档时PDF也按年隔离）',
  `file_name` VARCHAR(200) COMMENT '文件名',
  `file_path` VARCHAR(500) COMMENT 'MinIO/OSS 存储路径',
  `file_size` BIGINT,
  `page_count` INT,
  `template_code` VARCHAR(50) COMMENT '使用的PDF模板',
  `qr_content` VARCHAR(500) COMMENT '二维码内容（追溯）',
  `gen_time` DATETIME COMMENT '生成时间',
  `hash` VARCHAR(64) COMMENT '文件哈希（防篡改）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_archive_pdf_tenant_id` (`tenant_id`),
  KEY `idx_archive_pdf_year` (`tenant_id`, `archive_year`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='归档 PDF 产物';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全费用支出类别';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全费用支出';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全费用计提计划';

CREATE TABLE `check_config` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `default_policy` VARCHAR(20) DEFAULT 'not_out' COMMENT '默认策略：not_out默认未出车 / not_check默认未检查（C12）',
  `force_camera` TINYINT DEFAULT 0 COMMENT '强制实时摄像头：1开启（C15）',
  `allow_declare` TINYINT DEFAULT 0 COMMENT '允许司机声明未出车：1开启（C16）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_check_config_tenant_id` (`tenant_id`),
  UNIQUE KEY uk_config (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检配置';

CREATE TABLE `check_item_dict` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `item_code` VARCHAR(30) NOT NULL COMMENT '检查项编码',
  `item_name` VARCHAR(100) NOT NULL COMMENT '检查项名称（司机看，说人话）',
  `phase` VARCHAR(10) NOT NULL COMMENT '时段：out出车前/mid行车中/in收车后',
  `vehicle_class` VARCHAR(50) NOT NULL COMMENT '车型标签（普货7类/危货两层，见10模块3.7）',
  `biz_type` VARCHAR(20) COMMENT '营运类型 freight/danger/passenger',
  `sort_no` INT DEFAULT 0 COMMENT '排序',
  `is_default` TINYINT DEFAULT 0 COMMENT '是否通用项（所有车型都查）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检项目字典（平台预置）';

CREATE TABLE `check_item_result` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `record_id` BIGINT NOT NULL COMMENT '关联 check_record',
  `item_id` BIGINT NOT NULL COMMENT '关联 check_item_dict',
  `checked` TINYINT DEFAULT 0 COMMENT '1已勾选（正常）',
  `abnormal` TINYINT DEFAULT 0 COMMENT '1异常',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_check_item_result_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检项目结果（明细）';

CREATE TABLE `check_record` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `task_id` BIGINT NOT NULL COMMENT '关联 check_task（出车周期）',
  `phase` VARCHAR(10) NOT NULL COMMENT 'out/mid/in',
  `check_day` DATE COMMENT '实际检查日（跨日任务中区分第几天）',
  `result` VARCHAR(10) COMMENT 'pass合格/abnormal异常',
  `check_time` DATETIME COMMENT '检查完成时间（自动，超管可改）',
  `abnormal_note` VARCHAR(500) COMMENT '异常文字说明（语音转文字结果）',
  `abnormal_voice` VARCHAR(500) COMMENT '异常语音文件URL（语音+文字双保存）',
  `abnormal_photos` VARCHAR(1000) COMMENT '异常照片（可多张，JSON）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_check_record_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检记录（出/行/收）';

CREATE TABLE `check_remind` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `task_id` BIGINT NOT NULL,
  `remind_date` DATETIME COMMENT '提醒日期时间',
  `remind_way` VARCHAR(20) COMMENT '短信/小程序推送/电话',
  `remind_content` VARCHAR(500) COMMENT '提醒内容',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_check_remind_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检提醒';

CREATE TABLE `check_task` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `vehicle_id` BIGINT NOT NULL COMMENT '关联 vehicle',
  `driver_id` BIGINT COMMENT '实际驾驶员（默认取静态绑定，可切换，见 5.1）',
  `escort_id` BIGINT COMMENT '押运员（危货）',
  `is_trailer` TINYINT DEFAULT 0 COMMENT '0车头 1挂车（车头挂分离）',
  `start_date` DATE COMMENT '出车日期（出车周期起点）',
  `end_date` DATE COMMENT '收车日期（跨日任务时 > start_date）',
  `status` VARCHAR(20) DEFAULT 'not_out' COMMENT 'not_out未出车/doing出车中/done已完成',
  `remind_count` INT DEFAULT 0 COMMENT '提醒次数',
  `mileage` INT COMMENT '里程（本出车周期）',
  `mileage_start` INT COMMENT '出车公里数（行车日志）',
  `mileage_end` INT COMMENT '收车公里数（行车日志）',
  `cargo_name` VARCHAR(100) COMMENT '货物名称（行车日志）',
  `cargo_qty` VARCHAR(50) COMMENT '货物数量（行车日志）',
  `origin` VARCHAR(200) COMMENT '始发地（行车日志）',
  `stopover` VARCHAR(200) COMMENT '中途停靠地（行车日志）',
  `destination` VARCHAR(200) COMMENT '目的地（行车日志）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_check_task_tenant_id` (`tenant_id`),
  UNIQUE KEY uk_task (tenant_id, vehicle_id, is_trailer, start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检任务（出车周期）';

CREATE TABLE `collect_result` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `task_id` BIGINT NOT NULL,
  `row_data` JSON COMMENT '解析出的一行数据（字段名对齐系统字段）',
  `confidence` DECIMAL(5,2) COMMENT '置信度（用于高亮提示人工核对）',
  `status` TINYINT DEFAULT 0 COMMENT '0待确认 1已入库 2已忽略 3已修改',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_collect_result_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='智能资料收集解析结果';

CREATE TABLE `collect_task` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `file_type` VARCHAR(10) NOT NULL COMMENT 'excel/image/pdf',
  `target_type` VARCHAR(20) NOT NULL COMMENT '解析目标：person/vehicle/cert',
  `file_url` VARCHAR(200) COMMENT '上传文件（对象存储URL）',
  `status` TINYINT DEFAULT 0 COMMENT '0上传待解析 1解析中 2待确认 3已入库 4解析失败',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_collect_task_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='智能资料收集任务';

CREATE TABLE `credit_assess` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `year` INT NOT NULL COMMENT '考核年度',
  `assess_name` VARCHAR(200) NOT NULL COMMENT '如"2026年度安全生产责任制考核"',
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft/confirming/completed',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_credit_assess_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='诚信考核';

CREATE TABLE `credit_assess_item` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `assess_id` BIGINT NOT NULL COMMENT '关联年度考核',
  `user_id` BIGINT NOT NULL COMMENT '被考核人',
  `post_id` BIGINT COMMENT '岗位（组织架构）',
  `post_name` VARCHAR(50) COMMENT '岗位名（快照）',
  `targets` TEXT COMMENT '责任目标快照（JSON，来自岗位内容库 target）',
  `score` INT DEFAULT 100 COMMENT '得分（默认100，C4）',
  `deduction` INT DEFAULT 0 COMMENT '减分（违规联动，C6）',
  `deduction_note` VARCHAR(500) COMMENT '减分说明（违规类型/情况）',
  `level` VARCHAR(20) COMMENT '等级：excellent优秀/pass合格/fail不合格（C8）',
  `sign_image` VARCHAR(500) COMMENT '被考核人签名',
  `sign_time` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_credit_assess_item_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='诚信考核项目';

CREATE TABLE `credit_reward_notice` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `notice_no` VARCHAR(50) COMMENT '公告编号（如 XTTA 公告【2026】xxx 号）',
  `assess_type` VARCHAR(50) COMMENT '考核类型（安全生产责任制考核）',
  `period_start` DATE COMMENT '考核周期起',
  `period_end` DATE COMMENT '考核周期止',
  `result` VARCHAR(20) COMMENT '考核结果：合格/优秀/不合格',
  `assessee_post` VARCHAR(50) COMMENT '被考核对象（岗位）',
  `assessee_id` BIGINT COMMENT '被考核人 user_id',
  `assessee_name` VARCHAR(50) COMMENT '被考核人姓名（快照）',
  `assess_standard` VARCHAR(500) COMMENT '考核标准',
  `evaluate_condition` VARCHAR(500) COMMENT '评定条件',
  `reward_detail` VARCHAR(500) COMMENT '奖惩详情（记功·公开表扬/通报/减分等）',
  `sign_examiner` VARCHAR(500) COMMENT '考核人签字',
  `sign_assessee` VARCHAR(500) COMMENT '被考核人签字',
  `remark` VARCHAR(500) COMMENT '备注',
  `publish_time` DATETIME COMMENT '公示时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_credit_reward_notice_tenant_id` (`tenant_id`),
  KEY `idx_credit_reward_notice_assessee` (`tenant_id`, `assessee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='考核奖惩公示';

CREATE TABLE `danger_accept` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `danger_id` BIGINT NOT NULL,
  `acceptor_id` BIGINT COMMENT '验收人（安全员）',
  `accept_result` VARCHAR(10) COMMENT 'pass通过/reject退回',
  `accept_note` VARCHAR(500) COMMENT '验收意见',
  `accept_time` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_danger_accept_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患验收';

CREATE TABLE `danger_check_template` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `template_name` VARCHAR(100) NOT NULL COMMENT '清单名称（如"月度全面隐患排查清单"）',
  `check_type` VARCHAR(30) NOT NULL COMMENT '适用排查类型（见 3.2 字典）',
  `item_list` TEXT COMMENT '排查项目清单（JSON：项目名称+说明+判定方式）',
  `is_default` TINYINT DEFAULT 0 COMMENT '是否该类型默认清单',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_danger_check_template_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患排查模板';

CREATE TABLE `danger_class` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `code` VARCHAR(20) COMMENT '如 D_FLAM_LIQ',
  `name` VARCHAR(60) COMMENT '易燃液体 / 腐蚀性物质 / 毒害品 / 爆炸品 / 放射性物品 ...',
  `risk_note` VARCHAR(500) COMMENT '特殊风险说明',
  `add_clause` LONGTEXT COMMENT '条件性附加条款（附加到相关操作规程/制度上）',
  `remark` VARCHAR(200),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患分类字典';

CREATE TABLE `danger_record` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `check_type` VARCHAR(30) NOT NULL COMMENT '排查类型（见 3.2 字典：check_daily/monthly_full/monthly_major/holiday_pre/emergency_supply/season_risk/other_plan/driver_report）',
  `ref_id` BIGINT COMMENT '来源ID（如 check_record.id，三检异常时）',
  `title` VARCHAR(200) COMMENT '隐患名称/描述（隐患情况）',
  `danger_level` VARCHAR(10) COMMENT '隐患等级：一般/较大/重大',
  `location` VARCHAR(200) COMMENT '隐患位置（车辆/场地/设备）',
  `vehicle_id` BIGINT COMMENT '关联车辆（若为车辆隐患）',
  `finder_id` BIGINT COMMENT '检查人/发现人',
  `find_time` DATETIME COMMENT '发现时间',
  `photos` VARCHAR(2000) COMMENT '照片（JSON，多张）',
  `voice_url` VARCHAR(500) COMMENT '语音（若来自三检报修）',
  `status` VARCHAR(20) DEFAULT 'pending' COMMENT 'pending待整改/rectifying整改中/verify待验收/closed已闭环/overdue超期',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_danger_record_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患排查记录';

CREATE TABLE `danger_rectify` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `danger_id` BIGINT NOT NULL COMMENT '关联 danger_record',
  `rectify_no` VARCHAR(50) COMMENT '整改单编号（联动生成）',
  `rectify_require` VARCHAR(500) COMMENT '整改要求',
  `responsible_id` BIGINT COMMENT '整改人（整改责任人）',
  `deadline` DATETIME COMMENT '整改期限',
  `rectify_note` VARCHAR(500) COMMENT '整改情况说明',
  `rectify_photos` VARCHAR(2000) COMMENT '整改凭证照片',
  `submit_time` DATETIME COMMENT '整改反馈提交时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_danger_rectify_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患整改';

CREATE TABLE `emergency_drill` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `plan_item_id` BIGINT COMMENT '关联计划项（可空，独立演练）',
  `drill_name` VARCHAR(200) NOT NULL,
  `drill_type` VARCHAR(20),
  `drill_date` DATETIME COMMENT '实际演练时间',
  `eval_result` TEXT COMMENT '手填的演练评估表结果（E5）',
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft/executing/completed',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_drill_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急演练记录';

CREATE TABLE `emergency_drill_item` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `plan_id` BIGINT NOT NULL,
  `drill_name` VARCHAR(200) NOT NULL COMMENT '演练项目名',
  `scene_code` VARCHAR(50) COMMENT '对应场景范本（road_accident等）',
  `drill_type` VARCHAR(20) COMMENT 'tabletop桌面/field实战/comprehensive综合',
  `plan_time` DATETIME COMMENT '计划时间',
  `scheme` TEXT COMMENT '演练方案（系统自动生成，E4）',
  `eval_template` TEXT COMMENT '演练评估表模板（系统自动生成，E5）',
  `responsible_id` BIGINT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_drill_item_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演练项目明细';

CREATE TABLE `emergency_drill_photo` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `drill_id` BIGINT NOT NULL,
  `photo_url` VARCHAR(500) NOT NULL,
  `caption` VARCHAR(200),
  `sort_order` INT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_drill_photo_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演练照片';

CREATE TABLE `emergency_drill_plan` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `plan_name` VARCHAR(200) NOT NULL COMMENT '如"2026年度应急演练计划"',
  `year` INT NOT NULL,
  `cargo_type` VARCHAR(10),
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft/published/finished',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_drill_plan_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急演练计划';

CREATE TABLE `emergency_drill_sign` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `drill_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `user_type` VARCHAR(20),
  `sign_time` DATETIME,
  `sign_image` VARCHAR(500),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_drill_sign_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演练签到';

CREATE TABLE `emergency_duty` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `duty_date` DATE NOT NULL,
  `user_id` BIGINT NOT NULL,
  `phone` VARCHAR(20),
  `shift` VARCHAR(20),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_duty_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急值守';

CREATE TABLE `emergency_duty_rule` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `rule_name` VARCHAR(200) NOT NULL,
  `content_html` LONGTEXT,
  `file_url` VARCHAR(500),
  `effective_date` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_duty_rule_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急值守规则';

CREATE TABLE `emergency_equipment` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `equip_name` VARCHAR(100) NOT NULL,
  `equip_type` VARCHAR(50),
  `quantity` INT DEFAULT 0,
  `unit` VARCHAR(20),
  `valid_until` DATETIME COMMENT '有效期（到期预警）',
  `location` VARCHAR(200),
  `keeper_id` BIGINT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_equipment_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急物资';

CREATE TABLE `emergency_plan` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `cargo_type` VARCHAR(10) NOT NULL COMMENT 'general普货/danger危货',
  `plan_name` VARCHAR(200) NOT NULL COMMENT '预案名称',
  `version` VARCHAR(20) NOT NULL COMMENT '版本号 V1/V2...',
  `scene_codes` VARCHAR(500) COMMENT '勾选的场景编码（逗号分隔，E1）',
  `content_html` LONGTEXT COMMENT '预案正文（组合渲染后）',
  `variables` TEXT COMMENT '变量值快照（JSON）',
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft/published/archived',
  `record_no` VARCHAR(100) COMMENT '备案回执编号（危货）',
  `record_file` VARCHAR(500) COMMENT '备案回执文件（危货，客户上传）',
  `effective_date` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_plan_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急预案';

CREATE TABLE `emergency_plan_tpl` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tpl_code` VARCHAR(50) NOT NULL COMMENT '场景编码 road_accident/vehicle_fire/...',
  `tpl_name` VARCHAR(100) NOT NULL COMMENT '范本名称',
  `tpl_type` VARCHAR(20) COMMENT 'comprehensive综合/special专项/site现场处置',
  `cargo_type` VARCHAR(10) COMMENT 'general普货/danger危货/all通用',
  `content_html` LONGTEXT COMMENT '范本正文（含变量占位）',
  `variables` TEXT COMMENT '变量定义（JSON）',
  `is_default` TINYINT DEFAULT 0 COMMENT '是否默认勾选',
  `sort_order` INT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急范本模板（预置）';

CREATE TABLE `emergency_team` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `team_name` VARCHAR(100) NOT NULL,
  `team_role` VARCHAR(50) COMMENT 'command指挥部/office办公室/group工作组',
  `leader_id` BIGINT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_team_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急队伍';

CREATE TABLE `emergency_team_member` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `team_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `duty` VARCHAR(100),
  `phone` VARCHAR(20),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_emergency_team_member_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急队伍成员';

CREATE TABLE `flow_config` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `biz_type` VARCHAR(30) NOT NULL COMMENT '业务类型',
  `biz_name` VARCHAR(50) COMMENT '显示名',
  `node_order` INT NOT NULL COMMENT '第几级',
  `node_name` VARCHAR(50) COMMENT '节点名',
  `role_id` BIGINT COMMENT '该级由哪个角色审批（如"安全科负责人"角色）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_flow_config_tenant_id` (`tenant_id`),
  UNIQUE KEY (tenant_id, biz_type, node_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='审批配置（业务类型审批模板）';

CREATE TABLE `flow_node` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `process_id` BIGINT NOT NULL,
  `node_order` INT NOT NULL COMMENT '第几级（1,2,3...）',
  `node_name` VARCHAR(50) COMMENT '节点名，如"安全科负责人审批"',
  `approver_id` BIGINT COMMENT '审批人（用户ID）',
  `status` TINYINT DEFAULT 0 COMMENT '0待审 1通过 2驳回',
  `comment` VARCHAR(500) COMMENT '审批意见',
  `approved_at` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_flow_node_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='审批节点';

CREATE TABLE `flow_process` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `biz_type` VARCHAR(30) NOT NULL COMMENT '业务类型：doc_review / budget / accident',
  `biz_id` BIGINT NOT NULL COMMENT '关联业务数据ID',
  `title` VARCHAR(200) COMMENT '标题，如"《安全责任制》2026修订"',
  `status` TINYINT DEFAULT 1 COMMENT '1待提交 2审批中 3已通过 4已驳回 5已撤回',
  `current_node` INT DEFAULT 1 COMMENT '当前审批到第几级',
  `total_node` INT NOT NULL COMMENT '总共几级',
  `submitter_id` BIGINT COMMENT '发起人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_flow_process_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='审批流程实例';

CREATE TABLE `meeting` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `meeting_type` VARCHAR(20) NOT NULL COMMENT 'leader领导小组会议 / monthly月度会议（见 3.1）',
  `title` VARCHAR(200) NOT NULL COMMENT '会议名称（如"2026年10月安全生产月度会议"）',
  `topic` VARCHAR(1000) COMMENT '会议议题',
  `mode` VARCHAR(10) DEFAULT 'offline' COMMENT 'online线上 / offline线下',
  `meeting_no` VARCHAR(50) COMMENT '腾讯会议号（mode=online，如"123 456 789"）',
  `link` VARCHAR(500) COMMENT '会议链接（mode=online，腾讯会议/企微等，通用字段不锁厂商）',
  `location` VARCHAR(200) COMMENT '会议地点（mode=offline）',
  `start_time` DATETIME NOT NULL COMMENT '开始时间',
  `end_time` DATETIME NOT NULL COMMENT '结束时间',
  `host_id` BIGINT NOT NULL COMMENT '主持人',
  `recorder_id` BIGINT NOT NULL COMMENT '记录人',
  `attach_files` VARCHAR(2000) COMMENT '会议附件（JSON）',
  `notice_title` VARCHAR(200) COMMENT '通知标题（模板占位）',
  `notice_content` TEXT COMMENT '通知内容',
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft/published/ongoing/finished/archived',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_meeting_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全会议';

CREATE TABLE `meeting_attendee` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `meeting_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL COMMENT '参会人（从业人员）',
  `sign_image` VARCHAR(500) COMMENT '签名图片（关键留痕）',
  `sign_time` DATETIME COMMENT '签到时间戳（自动）',
  `sign_status` VARCHAR(10) DEFAULT 'absent' COMMENT 'signed已签到/absent缺席/leave请假',
  `absent_reason` VARCHAR(200) COMMENT '缺席原因',
  `sign_source` VARCHAR(10) DEFAULT 'self' COMMENT 'self现场签到/replenish补签（M9）',
  `replenished_by` BIGINT COMMENT '补签操作人（安全员/超管）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_meeting_attendee_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='会议签到/参会人';

CREATE TABLE `meeting_record` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `meeting_id` BIGINT NOT NULL,
  `content` TEXT COMMENT '会议记录（富文本）',
  `venue_photos` VARCHAR(2000) COMMENT '会场照片（JSON）',
  `ppt_files` VARCHAR(2000) COMMENT '会议PPT（JSON，对应归档"会议PPT"）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_meeting_record_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='会议纪要';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='动态监控台账留存';

CREATE TABLE `monitor_violation` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `ledger_id` BIGINT COMMENT '来源台账（monitor_ledger）',
  `vehicle_id` BIGINT COMMENT '车辆（10 车辆档案）',
  `vehicle_plate` VARCHAR(20) COMMENT '车牌（快照）',
  `driver_id` BIGINT COMMENT '驾驶员（07 人员档案）',
  `violation_type` VARCHAR(50) COMMENT '违规类型（引用 violation_dict，14）',
  `violation_time` DATETIME NOT NULL COMMENT '违规时间',
  `description` VARCHAR(500) COMMENT '违规说明',
  `source` VARCHAR(30) COMMENT '来源：动态监控平台/交警抄报/人工录入',
  `violation_count` INT DEFAULT 1 COMMENT '该车辆累计违规次数（统计用）',
  `status` VARCHAR(20) DEFAULT 'recorded' COMMENT 'recorded已登记/educated已教育',
  `education_plan_id` BIGINT COMMENT '关联靶向教育计划（14 training_plan）',
  `education_status` VARCHAR(20) DEFAULT 'none' COMMENT 'none未生成/pushed已推送/learning学习中/passed已考核/archived已归档',
  `education_completed_at` DATETIME COMMENT '教育落实完成时间（考核通过）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_monitor_violation_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='动态监控违规记录';

CREATE TABLE `onboarding` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `step` TINYINT DEFAULT 1 COMMENT '当前进行到第几步（1/2/3）',
  `status` TINYINT DEFAULT 0 COMMENT '0进行中 1已完成',
  `completed_at` DATETIME,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_onboarding_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='接入初始化状态';

CREATE TABLE `onboarding_status` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `dimension` VARCHAR(50) NOT NULL COMMENT '维度：system/staff/ledger/check/train/danger',
  `answer` VARCHAR(50) COMMENT '选择答案',
  `remark` VARCHAR(500) COMMENT '开放补充（全局一个即可）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_onboarding_status_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全管理现状（选择题答案）';

CREATE TABLE `org_compliance_check` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `post_code` VARCHAR(50) NOT NULL COMMENT '岗位编码',
  `satisfied` TINYINT DEFAULT 0 COMMENT '是否满足（0缺失 1满足）',
  `missing_reason` VARCHAR(200) COMMENT '缺失原因说明',
  `checked_at` DATETIME DEFAULT NOW(),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_org_compliance_check_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='合规校验结果';

CREATE TABLE `org_compliance_template` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `biz_type` VARCHAR(20) NOT NULL COMMENT '营运类型：freight/danger/passenger',
  `post_code` VARCHAR(50) NOT NULL COMMENT '岗位标准编码',
  `post_name` VARCHAR(50) NOT NULL COMMENT '岗位名称',
  `required` TINYINT DEFAULT 1 COMMENT '是否强制',
  `full_time` TINYINT DEFAULT 1 COMMENT '是否专职（1专职 0可兼职）',
  `allow_outsource` TINYINT DEFAULT 0 COMMENT '是否可委托第三方（如监控员）',
  `need_cert` TINYINT DEFAULT 0 COMMENT '是否需持证（如装卸管理员）',
  `sort` INT DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='法规最低架构模板（内置）';

CREATE TABLE `org_dict` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `dict_type` VARCHAR(20) NOT NULL COMMENT 'dept=部门 / post=岗位',
  `dict_code` VARCHAR(50) NOT NULL COMMENT '标准编码：drive_dept / driver_post',
  `dict_name` VARCHAR(50) NOT NULL COMMENT '标准名称：驾驶部 / 驾驶员',
  `sort` INT DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='职能字典（标准部门/岗位，平台预置）';

CREATE TABLE `org_leader` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `org_name` VARCHAR(100) NOT NULL COMMENT '领导机构名称',
  `leader_id` BIGINT NOT NULL COMMENT '组长',
  `duty` VARCHAR(500) COMMENT '职责',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_org_leader_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='领导机构';

CREATE TABLE `org_leader_member` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `leader_org_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `role_type` VARCHAR(20) NOT NULL COMMENT 'deputy=副组长 / member=组员',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_org_leader_member_tenant_id` (`tenant_id`),
  UNIQUE KEY uk_org_leader_member (leader_org_id, user_id, role_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='领导机构成员';

CREATE TABLE `org_member` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `user_id` BIGINT NOT NULL COMMENT '关联 person 或 sys_user（人员分配仅从系统已录入人员选取）',
  `post_id` BIGINT NOT NULL,
  `biz_field` VARCHAR(50) COMMENT '业务领域',
  `gender` VARCHAR(10) COMMENT '性别',
  `photo` VARCHAR(200) COMMENT '个人照片',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_org_member_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='人员-岗位分配';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='委托第三方监控标记';

CREATE TABLE `org_post` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `parent_id` BIGINT DEFAULT 0 COMMENT '上级岗位ID（0=直属公司顶层；调整上级即调整岗位层级关系）',
  `dict_id` BIGINT COMMENT '关联系统职能',
  `func_key` VARCHAR(30) COMMENT '系统职能编码（平台12职能：gm/safe/disp/mon/clerk/finance/captain/vehtech/parklot/driver/escort/loader；企业侧不可更改）',
  `merged_func_ids` VARCHAR(200) COMMENT '兼并的其他岗位职能编码（逗号分隔；总经理/安全员不可参与兼并）',
  `post_name` VARCHAR(50) NOT NULL COMMENT '显示名（企业可自定义）',
  `is_leader` TINYINT DEFAULT 0 COMMENT '是否负责人岗',
  `required` TINYINT DEFAULT 0 COMMENT '是否强制岗位（仅总经理/安全员/监控员=1，不可禁用）',
  `enabled` TINYINT DEFAULT 1 COMMENT '是否启用（0禁用：架构图置灰、不参与业务派发，且从安全生产责任制等制度内容中剔除）',
  `permissions` VARCHAR(500) COMMENT '岗位权限（职能关联）',
  `function` VARCHAR(500) COMMENT '岗位职能说明',
  `other_func` VARCHAR(500) COMMENT '其他职能',
  `goals` VARCHAR(500) COMMENT '岗位目标',
  `duties` VARCHAR(1000) COMMENT '岗位职责（联动安全生产责任制）',
  `assess_std` VARCHAR(500) COMMENT '考核标准（联动考核奖惩/诚信考核）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_org_post_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='岗位';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='org_post_content';

CREATE TABLE `person` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `person_type` VARCHAR(20) NOT NULL COMMENT 'manager=管理人员 / employee=从业人员',
  `name` VARCHAR(50) NOT NULL,
  `telephone` VARCHAR(20) NOT NULL,
  `gender` TINYINT COMMENT '1男 0女',
  `politic` VARCHAR(20) COMMENT '政治面貌（13类枚举）',
  `id_card` VARCHAR(18) COMMENT '身份证号',
  `avatar` VARCHAR(200) COMMENT '头像',
  `dept_id` BIGINT COMMENT '部门（文本化统计用；架构层级不依赖部门，岗位经 parent_id 组织）',
  `post_ids` VARCHAR(500) COMMENT '岗位（多选，org_post.id 逗号分隔）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_person_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='人员主表（一人一档）';

CREATE TABLE `person_archive_dict` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `item_code` VARCHAR(30) NOT NULL UNIQUE,
  `item_name` VARCHAR(100) NOT NULL COMMENT '归档项名称（逐字对齐档案盒目录）',
  `source_type` VARCHAR(20) NOT NULL COMMENT 'upload上传/auto系统生成/print打印手写',
  `apply_biz` VARCHAR(50) COMMENT '适用营运类型：freight/danger/passenger',
  `is_optional` TINYINT DEFAULT 0 COMMENT '是否可选（体检报告=普货可选/危货强制）',
  `sort_no` INT DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='一人一档归档项字典';

CREATE TABLE `person_archive_item` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `person_id` BIGINT NOT NULL,
  `item_code` VARCHAR(30) NOT NULL COMMENT '归档项编码（见 4.4，13项目录）',
  `source_type` VARCHAR(20) COMMENT 'upload上传/auto系统生成/print打印手写',
  `ref_id` BIGINT COMMENT '关联源数据（如范本生成PDF id / 培训记录id）',
  `file_url` VARCHAR(500) COMMENT '成品PDF或扫描件',
  `expire_date` DATE,
  `status` TINYINT DEFAULT 0 COMMENT '0缺 1齐 2已过期',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_person_archive_item_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='一人一档归档子项';

CREATE TABLE `person_doc` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `person_id` BIGINT NOT NULL COMMENT '关联 person',
  `doc_code` VARCHAR(30) NOT NULL COMMENT '文档编码（见 4.4，逐一对齐目录）',
  `doc_name` VARCHAR(100) COMMENT '文档名称（冗余）',
  `file_url` VARCHAR(500) COMMENT '扫描件（对象存储）',
  `issue_date` DATE COMMENT '发证/签订日期',
  `expire_date` DATE COMMENT '有效期至（→预警）',
  `status` TINYINT DEFAULT 0 COMMENT '0未上传 1已上传 2已过期',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_person_doc_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='人员证照/文件（子表）';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='风险卡';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='风险清单';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='风险手册';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='风险点字典';

CREATE TABLE `sys_dept` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `parent_id` BIGINT DEFAULT 0,
  `dept_name` VARCHAR(50) NOT NULL,
  `sort` INT DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_sys_dept_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='部门（系统内置）';

CREATE TABLE `sys_group` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `group_name` VARCHAR(50) NOT NULL,
  `group_type` VARCHAR(20) COMMENT '车队/线路/其它',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_sys_group_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业分组/车组';

CREATE TABLE `sys_menu` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `parent_id` BIGINT DEFAULT 0 COMMENT '父级，0=根',
  `menu_name` VARCHAR(50) NOT NULL COMMENT '显示名',
  `menu_type` TINYINT NOT NULL COMMENT '1目录 2菜单 3按钮',
  `perms` VARCHAR(100) COMMENT '权限标识：danger:delete',
  `path` VARCHAR(100) COMMENT '前端路由',
  `sort` INT DEFAULT 0,
  `visible` TINYINT DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='菜单/权限（菜单树+按钮权限）';

CREATE TABLE `sys_oper_log` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `user_id` BIGINT NOT NULL COMMENT '操作人ID',
  `module` VARCHAR(50) NOT NULL COMMENT '模块',
  `action` VARCHAR(50) NOT NULL COMMENT '动作',
  `target_id` BIGINT COMMENT '目标数据ID',
  `before_data` JSON COMMENT '变更前数据',
  `after_data` JSON COMMENT '变更后数据',
  `ip` VARCHAR(50) COMMENT 'IP地址',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_sys_oper_log_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='全量操作日志（履职留痕）';

CREATE TABLE `sys_role` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `role_code` VARCHAR(50) NOT NULL COMMENT '角色编码：ADMIN/SAFETY/DRIVER...',
  `role_name` VARCHAR(50) NOT NULL,
  `is_builtin` TINYINT DEFAULT 0 COMMENT '是否内置角色（不可删）',
  `data_scope` TINYINT DEFAULT 1 COMMENT '数据范围：1全部 2部门 3车组 4本人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_sys_role_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色';

CREATE TABLE `sys_role_menu` (
  `role_id` BIGINT NOT NULL,
  `menu_id` BIGINT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色-菜单关联';

CREATE TABLE `sys_tenant` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `name` VARCHAR(100) NOT NULL COMMENT '企业名称',
  `credit_code` VARCHAR(50) COMMENT '统一社会信用代码',
  `status` TINYINT DEFAULT 1 COMMENT '1启用 0禁用',
  `expire_time` DATETIME COMMENT '服务到期时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业（租户）主表';

CREATE TABLE `sys_user` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `username` VARCHAR(50) NOT NULL UNIQUE COMMENT '登录账号',
  `password` VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密',
  `real_name` VARCHAR(50) COMMENT '真实姓名',
  `phone` VARCHAR(20) COMMENT '手机号',
  `user_type` VARCHAR(20) COMMENT '用户类型：驾驶员/安全员/法人...',
  `status` TINYINT DEFAULT 1 COMMENT '1启用 0禁用',
  `dept_id` BIGINT COMMENT '所属部门',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_sys_user_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户账号';

CREATE TABLE `sys_user_role` (
  `user_id` BIGINT NOT NULL,
  `role_id` BIGINT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户-角色关联';

CREATE TABLE `tenant_cert` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `cert_type` VARCHAR(30) NOT NULL COMMENT '营业执照/经营许可证/停车场产权证明/停车场租赁合同/停车场平面图及照片/其它',
  `cert_no` VARCHAR(50) COMMENT '证照编号',
  `issue_date` DATE COMMENT '发证日期',
  `expire_date` DATE COMMENT '有效期至（预警依据）',
  `cert_image` VARCHAR(200) COMMENT '证照图片（对象存储URL）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_cert_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业证照（子表）';

CREATE TABLE `tenant_info` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `credit_code` VARCHAR(18) COMMENT '统一社会信用代码',
  `biz_type` VARCHAR(20) NOT NULL COMMENT '营运类型：freight/passenger/danger',
  `biz_term_type` TINYINT NOT NULL COMMENT '经营期限：1长期 2日期',
  `biz_term_end` DATE COMMENT '经营期限截止（仅当 biz_term_type=2）',
  `legal_person` VARCHAR(50) COMMENT '法定代表人',
  `safety_charge` VARCHAR(50) COMMENT '安全主要负责人',
  `charge_phone` VARCHAR(20) COMMENT '负责人电话',
  `region` VARCHAR(100) COMMENT '所在地',
  `address` VARCHAR(200) COMMENT '实际经营地址',
  `industry` VARCHAR(50) COMMENT '所属行业',
  `investment` DECIMAL(18,2) COMMENT '投资总额',
  `reg_capital` DECIMAL(18,2) COMMENT '注册资本',
  `biz_scope` VARCHAR(500) COMMENT '经营范围',
  `vehicle_scale` INT COMMENT '车辆规模',
  `main_route` VARCHAR(200) COMMENT '主要运输路线（替代原主营行程）',
  `main_cargo` VARCHAR(200) COMMENT '主要运输货物',
  `park_location` VARCHAR(200) COMMENT '停车场地位置（归入 tenant_parking）',
  `park_area` DECIMAL(10,2) COMMENT '停车场地面积（归入 tenant_parking）',
  `safety_staff` INT COMMENT '专职安全员数量',
  `employee_count` INT COMMENT '从业人员数量',
  `website` VARCHAR(100) COMMENT '官网',
  `zipcode` VARCHAR(10) COMMENT '邮编',
  `monitor_outsource` TINYINT COMMENT '监控外包 1是 0否',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_info_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业主体信息（租户扩展）';

CREATE TABLE `tenant_parking` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `address` VARCHAR(200) COMMENT '停车场地址',
  `ownership` TINYINT NOT NULL DEFAULT 1 COMMENT '所有权：1自有 2租赁',
  `area` DECIMAL(10,2) COMMENT '面积（平方米）',
  `lease_start` DATE COMMENT '租赁期限起',
  `lease_end` DATE COMMENT '租赁期限止（预警依据）',
  `land_proof` VARCHAR(200) COMMENT '土地使用证明（图片URL）',
  `layout_image` VARCHAR(200) COMMENT '平面布置图（图片URL）',
  `lease_contract` VARCHAR(200) COMMENT '租赁协议（文件URL）',
  `photos` VARCHAR(500) COMMENT '停车场图片（多图，逗号分隔URL）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_parking_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='停车场档案（独立页，一企业一条）';

CREATE TABLE `tenant_veh_config` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `veh_type_id` BIGINT COMMENT '车辆类型',
  `danger_class` VARCHAR(20) COMMENT '危货专用：危险货物类别（危货第二层，可多值）',
  `remark` VARCHAR(200),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_veh_config_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业车辆配置';

CREATE TABLE `tpl_field_dict` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `field_key` VARCHAR(50) COMMENT '变量 key（如 company_name）',
  `field_name` VARCHAR(50) COMMENT '中文名',
  `field_level` VARCHAR(20) COMMENT '企业级/人员级/上下文',
  `source` VARCHAR(50) COMMENT '数据来源模块/表',
  `remark` VARCHAR(200),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本字段字典';

CREATE TABLE `tpl_gen_record` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `template_id` BIGINT COMMENT '用的范本',
  `person_id` BIGINT COMMENT '关联人员（人员级范本）',
  `archive_item_id` BIGINT COMMENT '关联归档项',
  `fill_data` JSON COMMENT '实际填充的字段值快照',
  `pdf_id` BIGINT COMMENT '生成的 PDF',
  `gen_mode` TINYINT COMMENT '生成方式（1真实流程 2先台账后执行 3上传留存，同归档项 gen_mode）',
  `seal_id` BIGINT COMMENT '使用的印章',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_gen_record_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本生成记录';

CREATE TABLE `tpl_group` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `code` VARCHAR(50) COMMENT '组编码，如 GENERAL_BOX（普货-厢车）',
  `name` VARCHAR(100) COMMENT '组名称，如"普货-厢车"',
  `biz_type` VARCHAR(20) COMMENT '大类型：GENERAL/DANGER/PASSENGER',
  `region` VARCHAR(50) DEFAULT 'ALL' COMMENT '区域：ALL=全区域通用；其他=区域特殊版（如"乐从"）',
  `remark` VARCHAR(200),
  `status` TINYINT DEFAULT 1 COMMENT '1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_group_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本分组';

CREATE TABLE `tpl_seal` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `seal_type` TINYINT COMMENT '1客户上传 2系统自动生成',
  `seal_image` VARCHAR(500) COMMENT '印章图片路径',
  `is_default` TINYINT DEFAULT 0 COMMENT '是否默认章',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_seal_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='印章库';

CREATE TABLE `tpl_signature` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `person_id` BIGINT COMMENT '签字人',
  `post` VARCHAR(30) COMMENT '签字时岗位（如"主要负责人"）',
  `sign_image` VARCHAR(500) COMMENT '签字图片存储路径',
  `sign_type` TINYINT COMMENT '1线上电子签 2线下签字识别上传',
  `is_default` TINYINT DEFAULT 0 COMMENT '是否默认签字（复用用）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_signature_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='签字库（历史签字数据）';

CREATE TABLE `tpl_spec_group` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `code` VARCHAR(50) COMMENT '分组编码：普货 G1~G7；危货第一层 D_TANK/D_BOX/D_CONT/D_CYL',
  `name` VARCHAR(60) COMMENT '如"厢式及封闭运输类"',
  `biz_type` VARCHAR(20),
  `layer` TINYINT DEFAULT 1 COMMENT '层级：1=普货大类/危货第一层(车辆结构) 2=危货第二层(作业环节/岗位)',
  `veh_type_codes` VARCHAR(500) COMMENT '覆盖的车辆类型编码（逗号分隔）',
  `remark` VARCHAR(200),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本规格组字典';

CREATE TABLE `tpl_template` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `group_id` BIGINT COMMENT '所属范本套 id（对齐 tpl_set；服务商套与企业套的文件均归属某一套）',
  `source_id` BIGINT COMMENT '企业范本的来源系统范本 id（继承关系）',
  `code` VARCHAR(50) COMMENT '稳定编码（如 RESP_LETTER）',
  `name` VARCHAR(100) COMMENT '范本名称',
  `category` VARCHAR(30) COMMENT '类型：责任书/承诺书/告知书/任命书/制度/规程/应急预案...',
  `archive_code` VARCHAR(50) COMMENT '归属档案盒 code（对齐 archive_catalog）',
  `post_scope` VARCHAR(200) COMMENT '适用岗位（逗号分隔，空=不区分岗位）',
  `content` LONGTEXT COMMENT '范本内容（HTML + {{变量}} + {%条件%} + 内容库引用）',
  `need_seal` TINYINT DEFAULT 1 COMMENT '是否盖章（默认1，所有台账都盖）',
  `version` INT COMMENT '版本号',
  `status` TINYINT COMMENT '草稿/启用/停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_template_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本模板（主表，归属某个范本套）';

CREATE TABLE `tpl_set` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID（服务商侧为 0 全局；企业侧一般为 0 表示服务商发布）',
  `code` VARCHAR(50) COMMENT '范本套编码，如 PU_TPL_COMMON / PU_TPL_MICRO / DANGER_TPL_COMMON',
  `name` VARCHAR(100) COMMENT '范本套名称，如"普货通用范本""普货小微范本""危货通用范本""普货狮山范本"',
  `biz_type` VARCHAR(20) COMMENT '运输类型：GENERAL（普货）/ DANGER（危货）/ PASSENGER（客运）',
  `region` VARCHAR(50) DEFAULT 'ALL' COMMENT '区域：ALL=全区域通用；其他=区域特殊版（如"狮山""乐从"）',
  `is_default` TINYINT DEFAULT 0 COMMENT '是否默认套（服务商侧默认给新企业实例化的套）',
  `desc` VARCHAR(500) COMMENT '套说明',
  `status` TINYINT DEFAULT 1 COMMENT '1启用 0停用',
  `sort_no` INT DEFAULT 0 COMMENT '排序',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_set_biz_region` (`biz_type`,`region`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本套/模板集（服务商全局范本，所有企业可调用；每套是一整套制度包）';

CREATE TABLE `ent_tpl_set` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL COMMENT '企业租户ID',
  `source_set_id` BIGINT COMMENT '来源服务商范本套 id（实例化关系）',
  `name` VARCHAR(100) COMMENT '企业当前应用范本 / 我的范本名称',
  `biz_type` VARCHAR(20) COMMENT '运输类型',
  `region` VARCHAR(50) DEFAULT 'ALL' COMMENT '区域',
  `is_current` TINYINT DEFAULT 0 COMMENT '是否企业当前应用范本（同一企业仅一个为 1）',
  `customized` TINYINT DEFAULT 0 COMMENT '是否已在服务商基础上定制（0未改 1已改/已另存）',
  `files_snapshot` LONGTEXT COMMENT '本套文件清单快照（JSON：含每份文件的 box/name/type/link/role/content 等），企业定制只改此副本，不污染服务商原版',
  `applied_at` DATE COMMENT '从服务商套实例化日期',
  `saved_at` DATE COMMENT '另存为我的范本日期',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_ent_tpl_set_tenant` (`tenant_id`,`is_current`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业范本（当前应用范本 + 另存为的我的范本变体）';

CREATE TABLE `tpl_revision_log` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL COMMENT '企业租户ID',
  `src_module` VARCHAR(30) COMMENT '触发联动的模块，如 org（组织架构岗位调整）/ company（企业信息变更）',
  `desc` VARCHAR(500) COMMENT '修订描述，如"禁用了车队长岗位，制度内容已剔除"',
  `status` TINYINT DEFAULT 0 COMMENT '0待处理 1已保存到当前范本 2已另存为新范本 3稍后处理',
  `linked_set_id` BIGINT COMMENT '关联的 ent_tpl_set id（当前应用范本）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_revision_tenant` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='制度联动待修订记录（企业改动涉及制度联动时登记，提示保存/另存为）';

CREATE TABLE `training_assign` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `plan_id` BIGINT NOT NULL COMMENT '关联培训计划',
  `user_id` BIGINT NOT NULL COMMENT '被培训人员（从业人员）',
  `user_type` VARCHAR(20) COMMENT '人员类型（驾驶员/押运员/安全管理人员/监控管理人员/主要负责人）',
  `status` VARCHAR(20) DEFAULT 'not_started' COMMENT 'not_started/learning/qualified/exam_pending/completed/failed',
  `watch_minutes` INT DEFAULT 0 COMMENT '累计观看时长（分钟，学习留痕，T10）',
  `watch_progress` VARCHAR(2000) COMMENT '观看进度（JSON：每个视频的观看进度）',
  `sign_image` VARCHAR(500) COMMENT '培训人签名图片（T10）',
  `sign_time` DATETIME COMMENT '培训人签名时间',
  `photo` VARCHAR(500) COMMENT '培训人培训照片（拍照留痕，T10）',
  `photo_time` DATETIME COMMENT '拍照时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_training_assign_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='培训指派';

CREATE TABLE `training_exam` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `assign_id` BIGINT NOT NULL COMMENT '关联 training_assign',
  `user_id` BIGINT NOT NULL,
  `questions` TEXT COMMENT '本次考核试题（JSON：题目+选项，快照留痕）',
  `answers` TEXT COMMENT '用户答案（JSON）',
  `score` INT COMMENT '得分',
  `result` VARCHAR(10) COMMENT 'pass通过/fail未通过',
  `submit_time` DATETIME COMMENT '交卷时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_training_exam_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='考核记录';

CREATE TABLE `training_plan` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `plan_name` VARCHAR(200) NOT NULL COMMENT '培训计划名称',
  `plan_type` VARCHAR(20) NOT NULL COMMENT 'pre_job岗前/on_job岗中/targeted靶向',
  `cargo_type` VARCHAR(10) COMMENT 'general普货/danger危货（岗中年度计划用）',
  `year` INT COMMENT '年度（岗中：如 2026）',
  `month` INT COMMENT '月份（岗中：1~12，对应 12 个月）',
  `learn_hours` INT COMMENT '学时（提示/强制达标标准，T9，随开关生效）',
  `force_learn` TINYINT DEFAULT 0 COMMENT '学时开关：0=关闭(仅提示) 1=开启(学满才解锁考核)，企业可配（T9）',
  `pass_score` INT DEFAULT 60 COMMENT '考核及格线（分）',
  `max_retry` INT DEFAULT 3 COMMENT '最大重考次数',
  `learn_deadline` DATETIME COMMENT '学习截止时间（提示/强制用）',
  `violation_type` VARCHAR(50) COMMENT '违规类型（targeted 靶向，用于匹配视频）',
  `attach_files` VARCHAR(2000) COMMENT '附件（岗前：运营方上传的线下培训资料，可下载）',
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft/published/ongoing/finished',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_training_plan_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='培训计划';

CREATE TABLE `training_plan_video` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `plan_id` BIGINT NOT NULL,
  `video_id` BIGINT NOT NULL,
  `month` INT COMMENT '月份（岗中：1~12，对应 12 个月）',
  `sort_order` INT COMMENT '排序',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_training_plan_video_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='培训计划-视频关联';

CREATE TABLE `training_question` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `video_id` BIGINT COMMENT '关联视频（题目针对该视频内容）',
  `question_type` VARCHAR(20) COMMENT 'single单选/multi多选/judge判断',
  `stem` TEXT NOT NULL COMMENT '题干',
  `options` TEXT COMMENT '选项（JSON，选择题）',
  `answer` VARCHAR(500) COMMENT '正确答案',
  `analysis` VARCHAR(1000) COMMENT '解析（可选）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_training_question_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='题库';

CREATE TABLE `training_video` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `video_name` VARCHAR(200) NOT NULL COMMENT '视频名称',
  `video_desc` VARCHAR(2000) COMMENT '视频内容文字描述（对应台账"培训内容"）',
  `video_url` VARCHAR(500) NOT NULL COMMENT '视频文件地址',
  `duration` INT NOT NULL COMMENT '视频时长（分钟，用于学时判定/展示）',
  `violation_type` VARCHAR(50) COMMENT '适用违规类型标签（引用违规类型字典，靶向匹配用）',
  `cargo_type` VARCHAR(10) COMMENT '适用运输类型：general普货/danger危货/all通用（岗中）',
  `is_platform` TINYINT DEFAULT 0 COMMENT '是否平台级视频（运营维护）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_training_video_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='培训视频';

CREATE TABLE `veh_type` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `code` VARCHAR(50) COMMENT '类型编码，如 GEN_BOX（普货-厢车）',
  `name` VARCHAR(60) COMMENT '名称，如"厢车"',
  `biz_type` VARCHAR(20) COMMENT 'GENERAL/DANGER/PASSENGER',
  `spec_group` VARCHAR(50) COMMENT '归属普货制度大类编码（G1厢式及封闭/G2栏板及仓栅/G3集装箱及牵引/G4自卸及工程/G5罐体及散装/G6平板及大件/G7专项功能）；危货则为第一层结构编码（D_TANK罐式/D_BOX厢式包装/D_CONT集装箱/D_CYL气瓶）',
  `status` TINYINT DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车型字典（平台预置）';

CREATE TABLE `vehicle` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `plate_no` VARCHAR(20) NOT NULL COMMENT '车牌号',
  `plate_color` VARCHAR(10) NOT NULL COMMENT '车牌颜色（蓝/黄/绿/白/黑）',
  `biz_type` VARCHAR(20) NOT NULL COMMENT '营运类型：freight/danger/passenger',
  `vehicle_type` VARCHAR(50) COMMENT '车辆类型：牵引车/挂车/货车/客车/罐车...',
  `vehicle_class` VARCHAR(50) COMMENT '车型标签（普货7类/危货两层，见 3.7）',
  `is_trailer` TINYINT DEFAULT 0 COMMENT '车/挂：0=车 1=挂',
  `vehicle_no` VARCHAR(50) COMMENT '车辆编号（VEH-0001）',
  `brand_model` VARCHAR(100) COMMENT '品牌型号',
  `vin` VARCHAR(30) COMMENT '车辆识别代码VIN号',
  `engine_no` VARCHAR(50) COMMENT '发动机号码',
  `owner` VARCHAR(100) COMMENT '所有人',
  `reg_date` DATE COMMENT '注册日期',
  `license_no` VARCHAR(50) COMMENT '行驶证号（档案号）',
  `use_nature` VARCHAR(20) COMMENT '使用性质',
  `load_persons` INT COMMENT '核定载人量',
  `outline_size` VARCHAR(50) COMMENT '外廓尺寸',
  `curb_weight` INT COMMENT '整备质量（KG）',
  `body_color` VARCHAR(20) COMMENT '车身颜色',
  `energy_type` VARCHAR(20) COMMENT '能源类型',
  `axle_count` INT COMMENT '车轴数量',
  `tech_level` VARCHAR(10) COMMENT '技术等级（一/二/三级）',
  `business_status` VARCHAR(20) DEFAULT 'normal' COMMENT '营运状态：normal/stop/scrap',
  `stop_start` DATE COMMENT '停运开始日期',
  `stop_end` DATE COMMENT '停运结束日期（NULL=无限期）',
  `group_id` BIGINT COMMENT '车辆分组',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆主表（一车一档）';

CREATE TABLE `vehicle_device` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `vehicle_id` BIGINT COMMENT 'NULL=通用清单（"可一份所有车用"）',
  `device_name` VARCHAR(100) NOT NULL COMMENT '设备名称（灭火器/三角警示牌...）',
  `quantity` INT DEFAULT 1 COMMENT '数量',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_device_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆设备（卫星定位等）';

CREATE TABLE `vehicle_doc` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `vehicle_id` BIGINT NOT NULL COMMENT '关联 vehicle',
  `doc_code` VARCHAR(30) NOT NULL COMMENT '文档编码（见 3.6，逐一对齐13项目录）',
  `doc_name` VARCHAR(100) COMMENT '文档名称（冗余）',
  `file_url` VARCHAR(500) COMMENT '扫描件（对象存储）',
  `issue_date` DATE COMMENT '发证日期',
  `expire_date` DATE COMMENT '有效期至（→预警）',
  `status` TINYINT DEFAULT 0 COMMENT '0未上传 1已上传 2已过期',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_doc_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆证照/文件（子表）';

CREATE TABLE `vehicle_doc_dict` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `doc_code` VARCHAR(30) NOT NULL UNIQUE,
  `doc_name` VARCHAR(100) NOT NULL,
  `time_limit` TINYINT DEFAULT 0 COMMENT '是否有有效期（→预警）',
  `apply_biz_type` VARCHAR(50) COMMENT '适用营运类型：freight/danger/passenger',
  `apply_class` VARCHAR(50) COMMENT '适用车型标签（罐式才需罐体检验）',
  `is_custom` TINYINT DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='一车一档证照字典';

CREATE TABLE `vehicle_driver` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `vehicle_id` BIGINT NOT NULL,
  `driver_id` BIGINT NOT NULL COMMENT '关联 person（岗位=驾驶员）',
  `escort_id` BIGINT COMMENT '押运员（危货才有）',
  `bind_date` DATE,
  `unbind_date` DATE COMMENT 'NULL=当前绑定',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_driver_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆-驾驶员绑定';

CREATE TABLE `vehicle_grade` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `vehicle_id` BIGINT NOT NULL,
  `grade` VARCHAR(10) COMMENT '评定等级（一/二/三级）',
  `grade_date` DATE COMMENT '评定日期',
  `valid_until` DATE COMMENT '有效期至（→预警）',
  `file_url` VARCHAR(500) COMMENT '评定报告扫描件',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_grade_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆技术等级评定';

CREATE TABLE `vehicle_insurance` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `vehicle_id` BIGINT NOT NULL,
  `insurance_type` VARCHAR(30) NOT NULL COMMENT '交强险/商业险/承运人责任险/其它',
  `policy_no` VARCHAR(50) COMMENT '保单号',
  `file_url` VARCHAR(500),
  `expire_date` DATE COMMENT '有效期至（→预警）',
  `status` TINYINT DEFAULT 1 COMMENT '1有效 0失效',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_insurance_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆保险';

CREATE TABLE `vehicle_link` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `tractor_id` BIGINT NOT NULL COMMENT '牵引车（is_trailer=0）',
  `trailer_id` BIGINT NOT NULL COMMENT '挂车（is_trailer=1）',
  `start_date` DATE,
  `end_date` DATE COMMENT 'NULL=当前',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_link_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆挂靠关系';

CREATE TABLE `violation_dict` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `violation_name` VARCHAR(100) NOT NULL COMMENT '违规类型名称（企业自定义，如"疲劳驾驶"）',
  `violation_code` VARCHAR(50) COMMENT '编码（可选）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='违法字典';

CREATE TABLE `warn_record` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `warn_type` VARCHAR(30) NOT NULL,
  `level` TINYINT NOT NULL COMMENT '1严重 2一般 3提示',
  `title` VARCHAR(200) COMMENT '如"张三 从业资格证 30天后到期"',
  `target_type` VARCHAR(30) COMMENT 'person_cert / vehicle_insurance...',
  `target_id` BIGINT COMMENT '关联数据ID',
  `status` TINYINT DEFAULT 0 COMMENT '0未处理 1已处理 2已忽略',
  `expire_date` DATETIME COMMENT '到期时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_warn_record_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预警记录';

CREATE TABLE `warn_rule` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `warn_type` VARCHAR(30) NOT NULL COMMENT '预警类型',
  `days_before` INT DEFAULT 30 COMMENT '提前多少天预警',
  `level` TINYINT DEFAULT 2 COMMENT '1严重 2一般 3提示',
  `enabled` TINYINT DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_warn_rule_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预警规则';
