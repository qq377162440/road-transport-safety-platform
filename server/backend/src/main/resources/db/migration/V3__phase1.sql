-- 一期（A/B 阶段）业务表
-- 由 tools/gen_biz_meta.py 从 database/schema.sql 逐字提取，请勿手工编辑。

-- ===== A2 系统运维 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='全量操作日志（履职留痕）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='菜单/权限（菜单树+按钮权限）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='部门（系统内置）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业分组/车组';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预警规则';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='预警记录';;

CREATE TABLE `biz_file` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `biz_module` VARCHAR(50) COMMENT '来源模块',
  `biz_id` BIGINT COMMENT '关联业务记录ID',
  `file_name` VARCHAR(200) NOT NULL COMMENT '原始文件名',
  `object_key` VARCHAR(500) NOT NULL COMMENT 'MinIO 对象键',
  `file_size` BIGINT COMMENT '字节数',
  `content_type` VARCHAR(100) COMMENT 'MIME 类型',
  `uploader` BIGINT COMMENT '上传人',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_biz_file_tenant_id` (`tenant_id`),
  KEY `idx_biz_file_biz` (`biz_module`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通用附件上传记录';

-- ===== B1 人员基础信息 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='人员主表（一人一档）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='一人一档归档项字典';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='一人一档归档子项';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='人员证照/文件（子表）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='领导机构';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='领导机构成员';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='合规校验结果';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='法规最低架构模板（内置）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='接入初始化状态';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全管理现状（选择题答案）';;

-- ===== B2 车辆基础信息 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆主表（一车一档）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆证照/文件（子表）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='一车一档证照字典';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆-驾驶员绑定';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆保险';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆设备（卫星定位等）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆技术等级评定';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆挂靠关系';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车型字典（平台预置）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业车辆配置';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='停车场档案（独立页，一企业一条）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业证照（子表）';;

CREATE TABLE `vehicle_repair` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `vehicle_id` BIGINT NOT NULL COMMENT '关联车辆',
  `plate_no` VARCHAR(20) COMMENT '车牌号（冗余快照）',
  `repair_type` VARCHAR(30) COMMENT '维修类型：日常维护/一级维护/二级维护/故障维修/年检维修',
  `repair_date` DATE NOT NULL COMMENT '维修日期',
  `mileage` INT COMMENT '送修里程（km）',
  `fault_desc` TEXT COMMENT '故障描述',
  `repair_content` TEXT COMMENT '维修项目及内容',
  `repair_org` VARCHAR(200) COMMENT '承修单位',
  `cost_amount` DECIMAL(12,2) COMMENT '维修费用（元）',
  `invoice_url` VARCHAR(500) COMMENT '发票/结算单附件',
  `next_maintain_date` DATE COMMENT '下次二级维护日期',
  `handler` VARCHAR(50) COMMENT '经办人',
  `status` VARCHAR(20) DEFAULT 'done' COMMENT 'draft草稿/done已完成',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_repair_tenant_id` (`tenant_id`),
  KEY `idx_vehicle_repair_vehicle` (`vehicle_id`, `repair_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆维修保养（B2 补齐的既有设计缺口）';

-- ===== B3 制度档案引擎 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本分组';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本规格组字典';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本字段字典';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='印章库';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='签字库（历史签字数据）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本生成记录';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='审批配置（业务类型审批模板）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='审批节点';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='审批流程实例';;

-- ===== B4 一日三检 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检配置';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检项目字典（平台预置）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检任务（出车周期）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检记录（出/行/收）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检项目结果（明细）';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='三检提醒';;

-- ===== B5 隐患排查治理 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患分类字典';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患排查模板';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患排查记录';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患整改';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='隐患验收';;

-- ===== B6 安全会议 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全会议';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='会议签到/参会人';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='会议纪要';;

-- ===== B8 服务商代录 =====
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='智能资料收集任务';;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='智能资料收集解析结果';;

-- ===== 仅建表：无单一主键的关联表（不暴露通用 CRUD）=====
CREATE TABLE `sys_role_menu` (
  `role_id` BIGINT NOT NULL,
  `menu_id` BIGINT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`role_id`, `menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='角色-菜单关联';;

