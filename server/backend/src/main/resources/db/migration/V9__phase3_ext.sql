-- D 阶段（三期 v1.2）D4 危货专项深化 / D5 平台侧能力
-- 由 tools/gen_biz_meta.py 从 database/schema.sql 逐字提取（EXTRA_DDL 补齐表同规则）。
-- 只创建 V1~V7 尚未建过的表；已发布迁移 V1~V7 不受影响，校验和保持不变。

-- ===== D4 危货专项深化 =====
CREATE TABLE `danger_special_ledger` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `ledger_type` VARCHAR(30) NOT NULL COMMENT '台账类型：waybill电子运单/load_check装卸查验/transport危险品运输台账/escort押运员在岗/long_line800公里线路/safety_card安全卡一书一签',
  `biz_date` DATE NOT NULL COMMENT '台账日期',
  `waybill_no` VARCHAR(50) COMMENT '关联电子运单号',
  `plate_no` VARCHAR(20) COMMENT '车牌号',
  `cargo_name` VARCHAR(100) COMMENT '货物品名',
  `vehicle_id` BIGINT COMMENT '承运车辆',
  `driver_id` BIGINT COMMENT '驾驶员',
  `escort_id` BIGINT COMMENT '押运员',
  `content` TEXT COMMENT '台账内容/查验项（JSON 或文本）',
  `file_url` VARCHAR(500) COMMENT '附件凭证（MinIO 对象键）',
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft草稿/confirmed已确认/archived已归档',
  `remark` VARCHAR(500),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_danger_special_tenant` (`tenant_id`),
  KEY `idx_danger_special_type` (`tenant_id`, `ledger_type`, `biz_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='危货六项专项台账统一表（D4）';

CREATE TABLE `danger_waybill` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `waybill_no` VARCHAR(50) NOT NULL COMMENT '电子运单号（对接监管平台）',
  `cargo_name` VARCHAR(100) NOT NULL COMMENT '危险货物品名',
  `danger_class` VARCHAR(50) COMMENT '危险类别：易燃/腐蚀/毒害/爆炸/放射性',
  `un_no` VARCHAR(20) COMMENT 'UN 编号',
  `quantity` DECIMAL(12,2) COMMENT '数量',
  `unit` VARCHAR(10) DEFAULT '吨' COMMENT '计量单位',
  `origin` VARCHAR(200) COMMENT '起运地',
  `destination` VARCHAR(200) COMMENT '送达地',
  `vehicle_id` BIGINT COMMENT '承运车辆',
  `plate_no` VARCHAR(20) COMMENT '车牌号（快照）',
  `driver_id` BIGINT COMMENT '驾驶员',
  `escort_id` BIGINT COMMENT '押运员（危货强制全程在岗）',
  `depart_at` DATETIME COMMENT '发车时间',
  `arrive_at` DATETIME COMMENT '送达时间',
  `distance_km` INT COMMENT '线路里程（km）',
  `double_driver` TINYINT DEFAULT 0 COMMENT '800公里以上是否双押双驾',
  `escort_on_duty` TINYINT DEFAULT 0 COMMENT '押运员在岗校验 1通过',
  `vehicle_checked` TINYINT DEFAULT 0 COMMENT '车辆资质/罐体/标识查验 1通过',
  `status` VARCHAR(20) DEFAULT 'draft' COMMENT 'draft待发/transit在途/completed完成/archived已归档',
  `file_url` VARCHAR(500) COMMENT '运单/安全卡附件',
  `remark` VARCHAR(500),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_waybill_no` (`tenant_id`, `waybill_no`),
  KEY `idx_danger_waybill_tenant` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='危险货物电子运单（D4，法律法规强制）';

-- ===== D5 平台侧能力 =====
CREATE TABLE `sys_module_config` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `module_code` VARCHAR(50) NOT NULL COMMENT '模块编码（对齐 biz/modules.json）',
  `module_name` VARCHAR(100) NOT NULL COMMENT '模块名称',
  `phase` VARCHAR(10) COMMENT '所属阶段 A2/B1/C1/D1',
  `group_name` VARCHAR(50) COMMENT '菜单分组',
  `is_base` TINYINT DEFAULT 1 COMMENT '1基础版包含 0增值模块',
  `monthly_fee` DECIMAL(10,2) DEFAULT 0 COMMENT '增值模块月费（元）',
  `sort_no` INT DEFAULT 0 COMMENT '排序',
  `enabled` TINYINT DEFAULT 1 COMMENT '平台是否上架',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_module_code` (`module_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台功能模块字典（D5，平台级无租户）';

CREATE TABLE `tenant_module` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `module_code` VARCHAR(50) NOT NULL COMMENT '模块编码',
  `enabled` TINYINT DEFAULT 1 COMMENT '1已开通 0已关闭',
  `expire_at` DATE COMMENT '授权到期日（空=长期）',
  `opened_by` BIGINT COMMENT '开动人',
  `opened_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开通时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_module` (`tenant_id`, `module_code`),
  KEY `idx_tenant_module_code` (`module_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业功能模块开通记录（D5）';

CREATE TABLE `sso_config` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `protocol` VARCHAR(20) DEFAULT 'OIDC' COMMENT '协议：OIDC/SAML2/CAS',
  `idp_url` VARCHAR(300) COMMENT '身份提供方地址',
  `client_id` VARCHAR(200) COMMENT '客户端 ID',
  `client_secret` VARCHAR(500) COMMENT '客户端密钥（接口脱敏，不回显明文）',
  `enabled` TINYINT DEFAULT 0 COMMENT '1启用 0停用',
  `remark` VARCHAR(500) COMMENT '备注（对接范围说明）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sso_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业 SSO 配置登记（D5）';

CREATE TABLE `service_billing` (
  `id` BIGINT NOT NULL COMMENT '雪花ID',
  `tenant_id` BIGINT NOT NULL DEFAULT 0 COMMENT '租户ID',
  `module_code` VARCHAR(50) NOT NULL COMMENT '计费模块',
  `period` VARCHAR(20) NOT NULL COMMENT '账期 YYYY-MM',
  `amount` DECIMAL(12,2) NOT NULL COMMENT '账单金额（元）',
  `status` VARCHAR(20) DEFAULT 'unpaid' COMMENT 'unpaid待付/paid已结/waived已减免',
  `bill_no` VARCHAR(50) COMMENT '账单号',
  `paid_at` DATETIME COMMENT '结清时间',
  `remark` VARCHAR(500),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` BIGINT NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_billing` (`tenant_id`, `module_code`, `period`),
  KEY `idx_billing_period` (`period`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='增值服务计费账单（D5）';


-- ===== D2：财资〔2022〕136 号 11 类安全费用支出（系统预置 tenant_id=0）=====
DELETE FROM `budget_category` WHERE `id` BETWEEN 6001 AND 6011;
INSERT INTO `budget_category` (`id`,`tenant_id`,`category_name`,`category_code`,`sort_order`,`created_at`,`deleted`) VALUES
  (6001, 0, '安全防护设施设备支出', 'FACILITY', 1, NOW(), 0),
  (6002, 0, '车辆定位监控装置支出', 'LOCATION', 2, NOW(), 0),
  (6003, 0, '应急救援器材及演练支出', 'RESCUE', 3, NOW(), 0),
  (6004, 0, '危险源监控与隐患整改支出', 'HAZARD', 4, NOW(), 0),
  (6005, 0, '安全检查评价咨询支出', 'CHECK', 5, NOW(), 0),
  (6006, 0, '安全防护用品支出', 'PPE', 6, NOW(), 0),
  (6007, 0, '安全宣传教育培训支出', 'TRAIN', 7, NOW(), 0),
  (6008, 0, '新技术新装备推广支出', 'TECH', 8, NOW(), 0),
  (6009, 0, '设施检测检验支出', 'INSPECT', 9, NOW(), 0),
  (6010, 0, '安全责任保险支出', 'INSUR', 10, NOW(), 0),
  (6011, 0, '其他安全生产支出', 'OTHER', 11, NOW(), 0);

-- ===== D3：通用风险点库（系统预置 tenant_id=0，企业可增补自定义）=====
DELETE FROM `risk_point_dict` WHERE `id` BETWEEN 6201 AND 6299;
INSERT INTO `risk_point_dict` (`id`,`tenant_id`,`risk_name`,`risk_category`,`risk_level`,`control_measure`,`cargo_type`,`created_at`,`deleted`) VALUES
  (6201, 0, '疲劳驾驶', 'driver', '红', '连续驾驶不超过4小时、24小时累计不超过8小时，动态监控平台报警后必须停车休息并登记违规教育', 'all', NOW(), 0),
  (6202, 0, '超速行驶', 'driver', '红', '按道路限速与车辆核定速度行驶，接入动态监控超速报警并按月汇总违规台账', 'all', NOW(), 0),
  (6203, 0, '酒后驾驶', 'driver', '红', '出车前酒精检测，一经发现立即停岗并按制度处理', 'all', NOW(), 0),
  (6204, 0, '驾驶员从业资格失效', 'driver', '橙', '建立证照到期预警，从业资格证过期前完成换证，过期不得派车', 'all', NOW(), 0),
  (6205, 0, '危货押运员脱岗', 'driver', '红', '每趟电子运单绑定押运员，全程在岗并与运单/轨迹匹配，脱岗不得发车', 'danger', NOW(), 0),
  (6206, 0, '制动系统失效', 'vehicle', '红', '执行一日三检与二级维护，制动异常立即停运送修', 'all', NOW(), 0),
  (6207, 0, '轮胎磨损/爆胎', 'vehicle', '橙', '出车前检查胎压与花纹深度，按里程周期更换并留存维修记录', 'all', NOW(), 0),
  (6208, 0, '卫星定位装置离线', 'vehicle', '橙', '离线车辆不得派车，恢复上线后补录设备故障异常台账', 'all', NOW(), 0),
  (6209, 0, '罐体/专用标识不合格', 'vehicle', '红', '罐体定期检测、专用标识喷涂齐全，证照归档一车一档', 'danger', NOW(), 0),
  (6210, 0, '货物超载/装载不牢', 'cargo', '橙', '按核定载质量装载，出车前核验捆扎与封口，超载属重大隐患', 'all', NOW(), 0),
  (6211, 0, '危险货物泄漏', 'cargo', '红', '配随车安全卡（一书一签）与应急器材，装卸前后查验并留存记录', 'danger', NOW(), 0),
  (6212, 0, '危化品夹带/匿报', 'cargo', '红', '托运清单与电子运单逐项核对，发现夹带立即停运并纳入隐患排查', 'danger', NOW(), 0),
  (6213, 0, '恶劣天气（雨雾冰雪）', 'env', '橙', '发布预警、调整班次或限速，必要时停运并记录调度指令', 'all', NOW(), 0),
  (6214, 0, '长下坡/临崖等危险路段', 'env', '橙', '建立风险路段清单与路书，800公里以上线路执行双押双驾', 'all', NOW(), 0),
  (6215, 0, '限行区域误入', 'env', '黄', '出车前核对通行证与限行区域，剧毒品按地方要求报备', 'danger', NOW(), 0),
  (6216, 0, '安全制度缺失或未更新', 'manage', '黄', '年度评审制度汇编并按法规更新，范本套版本留痕', 'all', NOW(), 0),
  (6217, 0, '培训教育不到位', 'manage', '黄', '年度培训计划 + 月度培训 + 违规靶向教育，学时与考核留痕', 'all', NOW(), 0),
  (6218, 0, '隐患排查未闭环', 'manage', '橙', '隐患整改通知→整改→验收闭环，超期自动升级提醒', 'all', NOW(), 0),
  (6219, 0, '随车工具与灭火器摆放不规范', 'vehicle', '蓝', '纳入一日三检随车检查项，定置摆放并月度抽查', 'all', NOW(), 0),
  (6220, 0, '安全会议记录签字遗漏', 'manage', '蓝', '会议纪要归档前校验签到表完整性，缺签自动提示补签', 'all', NOW(), 0),
  (6221, 0, '停车场标识/照明磨损', 'env', '蓝', '纳入停车场季度巡检，及时更换标识与照明', 'all', NOW(), 0);

-- ===== D5：平台功能模块字典（与 biz/modules.json 逐项对齐）=====
DELETE FROM `sys_module_config` WHERE `id` BETWEEN 6301 AND 6399;
INSERT INTO `sys_module_config` (`id`,`module_code`,`module_name`,`phase`,`group_name`,`is_base`,`monthly_fee`,`sort_no`,`enabled`,`created_at`,`deleted`) VALUES
  (6301, 'common', '系统运维', 'A2', '系统管理', 1, 0, 1, 1, NOW(), 0),
  (6302, 'person', '人员基础信息', 'B1', '基础档案', 1, 0, 2, 1, NOW(), 0),
  (6303, 'vehicle', '车辆基础信息', 'B2', '基础档案', 1, 0, 3, 1, NOW(), 0),
  (6304, 'institution', '制度档案引擎', 'B3', '制度范本', 1, 0, 4, 1, NOW(), 0),
  (6305, 'check', '一日三检', 'B4', '业务工具', 1, 0, 5, 1, NOW(), 0),
  (6306, 'danger', '隐患排查治理', 'B5', '业务工具', 1, 0, 6, 1, NOW(), 0),
  (6307, 'meeting', '安全会议', 'B6', '业务工具', 1, 0, 7, 1, NOW(), 0),
  (6308, 'collect', '服务商代录', 'B8', '服务商', 1, 0, 8, 1, NOW(), 0),
  (6309, 'training', '培训教育', 'C1', '二期业务', 1, 0, 9, 1, NOW(), 0),
  (6310, 'emergency', '应急管理', 'C2', '二期业务', 1, 0, 10, 1, NOW(), 0),
  (6311, 'accident', '事故与违法', 'C3', '二期业务', 1, 0, 11, 1, NOW(), 0),
  (6312, 'assess', '考核奖惩', 'C4', '二期业务', 1, 0, 12, 1, NOW(), 0),
  (6313, 'sign', '自定义文件签署', 'C6', '二期业务', 1, 0, 13, 1, NOW(), 0),
  (6314, 'monitor', '动态监控台账', 'D1', '三期业务', 0, 199, 14, 1, NOW(), 0),
  (6315, 'budget', '安全生产费用', 'D2', '三期业务', 0, 99, 15, 1, NOW(), 0),
  (6316, 'risk', '风险分级管控', 'D3', '三期业务', 0, 199, 16, 1, NOW(), 0),
  (6317, 'dangerspecial', '危货专项深化', 'D4', '三期业务', 0, 299, 17, 1, NOW(), 0),
  (6318, 'platform', '平台侧能力', 'D5', '平台管理', 0, 0, 18, 1, NOW(), 0);

-- ===== D1/D3/D4：档案盒二级目录补充（父目录 id 取自 V5 目录树）=====
DELETE FROM `archive_catalog` WHERE `id` BETWEEN 50001 AND 50999;
INSERT INTO `archive_catalog` (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`renew_freq`) VALUES
  (50001, 0, 'GENERAL', 2, 20, 'GENERAL_BOX8_D20', '风险辨识手册', 4037, 0, 'YEAR'),
  (50002, 0, 'GENERAL', 2, 21, 'GENERAL_BOX8_D21', '风险辨识清单', 4037, 0, 'YEAR'),
  (50003, 0, 'GENERAL', 2, 22, 'GENERAL_BOX8_D22', '岗位风险告知卡', 4037, 0, 'YEAR'),
  (50004, 0, 'GENERAL', 2, 23, 'GENERAL_BOX8_D23', '无重大风险的说明', 4037, 0, 'YEAR'),
  (50005, 0, 'DANGER', 2, 20, 'DANGER_BOX9_D20', '风险辨识手册', 4088, 0, 'YEAR'),
  (50006, 0, 'DANGER', 2, 21, 'DANGER_BOX9_D21', '风险辨识清单', 4088, 0, 'YEAR'),
  (50007, 0, 'DANGER', 2, 22, 'DANGER_BOX9_D22', '岗位风险告知卡', 4088, 0, 'YEAR'),
  (50008, 0, 'DANGER', 2, 23, 'DANGER_BOX9_D23', '无重大风险的说明', 4088, 0, 'YEAR'),
  (50009, 0, 'DANGER', 2, 24, 'DANGER_BOX9_D24', '安全评估（危货）', 4088, 0, 'YEAR'),
  (50010, 0, 'DANGER', 2, 25, 'DANGER_BOX9_D25', '无重大事故隐患的声明（危货）', 4088, 0, 'YEAR'),
  (50011, 0, 'DANGER', 2, 20, 'DANGER_BOX6_D20', '危险货物电子运单', 4078, 0, 'YEAR'),
  (50012, 0, 'DANGER', 2, 21, 'DANGER_BOX6_D21', '装卸查验记录', 4078, 0, 'YEAR'),
  (50013, 0, 'DANGER', 2, 22, 'DANGER_BOX6_D22', '危险品运输台账', 4078, 0, 'YEAR'),
  (50014, 0, 'DANGER', 2, 23, 'DANGER_BOX6_D23', '押运员在岗记录', 4078, 0, 'YEAR'),
  (50015, 0, 'DANGER', 2, 24, 'DANGER_BOX6_D24', '800公里以上线路台账', 4078, 0, 'YEAR'),
  (50016, 0, 'DANGER', 2, 25, 'DANGER_BOX6_D25', '安全卡（一书一签）', 4078, 0, 'YEAR');
