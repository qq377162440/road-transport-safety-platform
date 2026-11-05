-- 二期（C 阶段）业务表
-- 由 tools/gen_biz_meta.py 从 database/schema.sql 逐字提取，请勿手工编辑。

-- ===== C1 培训教育 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='培训计划';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='培训计划-视频关联';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='培训视频';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='题库';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='考核记录';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='培训指派';;

-- ===== C2 应急管理 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急预案';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急范本模板（预置）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急演练计划';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急演练记录';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演练项目明细';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演练签到';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演练照片';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急值守';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急值守规则';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急物资';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急队伍';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应急队伍成员';;

-- ===== C3 事故与违法 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='事故记录';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='事故快报';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='事故增值服务';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='事故年度汇总';;

CREATE TABLE `violation_dict` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `violation_name` VARCHAR(100) NOT NULL COMMENT '违规类型名称（企业自定义，如"疲劳驾驶"）',
  `violation_code` VARCHAR(50) COMMENT '编码（可选）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='违法字典';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='动态监控违规记录';;

-- ===== C4 考核奖惩 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='诚信考核';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='诚信考核项目';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='考核奖惩公示';;

-- ===== C6 自定义文件签署 =====
CREATE TABLE `sign_task` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `title` VARCHAR(200) NOT NULL COMMENT '签署任务名称',
  `source_pdf` VARCHAR(500) COMMENT '待签署 PDF（MinIO 对象键）',
  `result_pdf` VARCHAR(500) COMMENT '签署后合成 PDF',
  `anchors` LONGTEXT COMMENT '盖章/签名锚点 JSON：[{type:seal|sign,page,x,y,scale}]',
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft草稿/pending待签/signed已签署',
  `signer` VARCHAR(50) COMMENT '签署人',
  `signed_at` DATETIME COMMENT '签署时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_sign_task_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='自定义文件签署任务（C6）';

