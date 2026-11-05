-- ============================================================
-- 第一阶段垂直切片：子集建表
-- 从 database/schema.sql 摘录切片所需表，DDL 原样保留（对齐权威 schema）
-- ============================================================

-- 认证域
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

CREATE TABLE `sys_role` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `role_code` VARCHAR(50) NOT NULL COMMENT '角色编码：enterprise/provider/admin',
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

CREATE TABLE `sys_user_role` (
  `user_id` BIGINT NOT NULL,
  `role_id` BIGINT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户-角色关联';

-- 企业信息域
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

-- 组织架构域
CREATE TABLE `org_dict` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `dict_type` VARCHAR(20) NOT NULL COMMENT 'dept=部门 / post=岗位',
  `dict_code` VARCHAR(50) NOT NULL COMMENT '标准编码：gm/safe/mon...',
  `dict_name` VARCHAR(50) NOT NULL COMMENT '标准名称：总经理/安全员...',
  `sort` INT DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='职能字典（标准部门/岗位，平台预置）';

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

-- 范本域
CREATE TABLE `tpl_set` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID（服务商侧为 0 全局）',
  `code` VARCHAR(50) COMMENT '范本套编码',
  `name` VARCHAR(100) COMMENT '范本套名称',
  `biz_type` VARCHAR(20) COMMENT '运输类型：GENERAL（普货）/ DANGER（危货）/ PASSENGER（客运）',
  `region` VARCHAR(50) DEFAULT 'ALL' COMMENT '区域：ALL=全区域通用',
  `is_default` TINYINT DEFAULT 0 COMMENT '是否默认套',
  `desc` VARCHAR(500) COMMENT '套说明',
  `status` TINYINT DEFAULT 1 COMMENT '1启用 0停用',
  `sort_no` INT DEFAULT 0 COMMENT '排序',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_set_biz_region` (`biz_type`,`region`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本套/模板集（服务商全局范本，所有企业可调用）';

CREATE TABLE `tpl_template` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `group_id` BIGINT COMMENT '所属范本套 id（对齐 tpl_set）',
  `source_id` BIGINT COMMENT '企业范本的来源系统范本 id（继承关系）',
  `code` VARCHAR(50) COMMENT '稳定编码（如 RESPONSIBILITY）',
  `name` VARCHAR(100) COMMENT '范本名称',
  `category` VARCHAR(30) COMMENT '类型：责任书/承诺书/告知书/任命书/制度/规程...',
  `archive_code` VARCHAR(50) COMMENT '归属档案盒 code（对齐 archive_catalog）',
  `post_scope` VARCHAR(200) COMMENT '适用岗位（逗号分隔，空=不区分岗位）',
  `content` LONGTEXT COMMENT '范本内容（HTML + {{变量}}）',
  `need_seal` TINYINT DEFAULT 1 COMMENT '是否盖章',
  `version` INT COMMENT '版本号',
  `status` TINYINT COMMENT '草稿/启用/停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_template_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='范本模板（归属某个范本套）';

CREATE TABLE `ent_tpl_set` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL COMMENT '企业租户ID',
  `source_set_id` BIGINT COMMENT '来源服务商范本套 id（实例化关系）',
  `name` VARCHAR(100) COMMENT '企业当前应用范本 / 我的范本名称',
  `biz_type` VARCHAR(20) COMMENT '运输类型',
  `region` VARCHAR(50) DEFAULT 'ALL' COMMENT '区域',
  `is_current` TINYINT DEFAULT 0 COMMENT '是否企业当前应用范本（同一企业仅一个为 1）',
  `customized` TINYINT DEFAULT 0 COMMENT '是否已定制（0未改 1已改/已另存）',
  `files_snapshot` LONGTEXT COMMENT '本套文件清单快照（JSON）',
  `applied_at` DATE COMMENT '从服务商套实例化日期',
  `saved_at` DATE COMMENT '另存为我的范本日期',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_ent_tpl_set_tenant` (`tenant_id`,`is_current`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业范本（当前应用范本 + 我的范本变体）';

CREATE TABLE `tpl_revision_log` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL COMMENT '企业租户ID',
  `src_module` VARCHAR(30) COMMENT '触发联动的模块，如 org',
  `desc` VARCHAR(500) COMMENT '修订描述',
  `status` TINYINT DEFAULT 0 COMMENT '0待处理 1已保存到当前范本 2已另存为新范本 3稍后处理',
  `linked_set_id` BIGINT COMMENT '关联的 ent_tpl_set id（当前应用范本）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_tpl_revision_tenant` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='制度联动待修订记录';

-- 归档域
CREATE TABLE `archive_catalog` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `biz_type` VARCHAR(20) COMMENT '营运类型：GENERAL普货 / DANGER危货',
  `level` TINYINT COMMENT '1=一级档案盒，2=二级归档子项',
  `sort_no` INT COMMENT '排序号',
  `code` VARCHAR(50) COMMENT '稳定编码',
  `name` VARCHAR(100) COMMENT '档案盒/子项名称（逐字对齐政府目录）',
  `parent_id` BIGINT COMMENT '二级子项指向一级盒',
  `has_pdf` TINYINT DEFAULT 0 COMMENT '是否本身就是一份可打印资料',
  `renew_freq` VARCHAR(20) COMMENT '更新频率',
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
  `data_type` TINYINT COMMENT '资料类别：1静态 2动态 3证件',
  `gen_mode` TINYINT DEFAULT 0 COMMENT '生成方式：1真实流程 2先台账后执行 3上传留存',
  `service_id` BIGINT COMMENT '方式二专用：关联增值服务单',
  `biz_module` VARCHAR(50) COMMENT '来源模块',
  `biz_id` BIGINT COMMENT '业务数据主键',
  `title` VARCHAR(200) COMMENT '归档项标题',
  `occur_date` DATE COMMENT '归档项发生日期',
  `signed_year` SMALLINT COMMENT '签署年度',
  `archive_year` SMALLINT COMMENT '归档年度（自然年度）',
  `person_id` BIGINT COMMENT '一人一档专用',
  `vehicle_id` BIGINT COMMENT '一车一档专用',
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
  `archive_year` SMALLINT COMMENT '归档年度',
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
