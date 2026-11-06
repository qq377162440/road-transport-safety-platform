#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
业务元数据生成器（A4/A5 及 B/C/D 阶段共用）
------------------------------------------------------------------
输入：database/schema.sql（权威 DDL）+ 下方模块配置 + EXTRA_DDL（工程补齐表）
输出：
  1) backend/src/main/resources/biz/modules.json   模块/表/字段元数据（驱动通用 CRUD 引擎与前端菜单/表单）
  2) backend/src/main/resources/db/migration/V3__phase1.sql  一期表 DDL（A/B）
  3) backend/src/main/resources/db/migration/V4__phase2.sql  二期表 DDL（C）
  4) backend/src/main/resources/db/migration/V5__archive_catalog.sql  10/11 盒目录种子（B7）
  5) backend/src/main/resources/db/migration/V8__phase3.sql  三期 D1/D2/D3 表 DDL
  6) backend/src/main/resources/db/migration/V9__phase3_ext.sql  D4/D5 新增表 + 字典种子 + 目录补充
  7) frontend/src/config/modules.generated.js      前端菜单配置（构建期生成，A5）

设计原则：DDL 逐字取自 schema.sql，保证「表结构与 schema.sql 一致」；
字段白名单由元数据固化，通用 CRUD 引擎只接受白名单字段，防 SQL 注入。

重要：V3/V4/V5 内容由 A/B/C 配置决定，D 阶段配置不得改动它们，
      否则已发布 Flyway 迁移的校验和会变，生产库将无法启动。
      V8/V9 只补「V1~V7 尚未建过」的表，已建表（如 monitor_violation、
      tenant_parking、training_plan）由专用业务闭环直接复用。
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "database" / "schema.sql"
BE_RES = ROOT / "server" / "backend" / "src" / "main" / "resources"
FE_CFG = ROOT / "server" / "frontend" / "src" / "config"

# V1 已建表（15 张），V3+ 不再重复创建
V1_TABLES = {
    "sys_tenant", "sys_user", "sys_role", "sys_user_role", "tenant_info",
    "org_dict", "org_post", "org_member", "tpl_set", "tpl_template",
    "ent_tpl_set", "tpl_revision_log", "archive_catalog", "archive_item", "archive_pdf",
}

# ---------------------------------------------------------------------------
# 模块配置：code / 标题 / 阶段 / 分组 / 主表 / 归档盒 code / 表清单
#   catalog 为归档归属编码（铁律 6：产生记录的业务模块必须有归档归属）
# ---------------------------------------------------------------------------
MODULES = [
    # ===== 阶段 A：公共基建 =====
    dict(code="common", title="系统运维", phase="A2", group="系统管理", main="sys_oper_log", catalog="", tables=[
        "sys_oper_log", "sys_menu", "sys_dept", "sys_group",
        "warn_rule", "warn_record", "biz_file",
    ]),
    # ===== 阶段 B：一期核心 =====
    dict(code="person", title="人员基础信息", phase="B1", group="基础档案", main="person", catalog="PERSON_FILE", tables=[
        "person", "person_archive_dict", "person_archive_item", "person_doc",
        "org_leader", "org_leader_member", "org_compliance_check", "org_compliance_template",
        "onboarding", "onboarding_status",
    ]),
    dict(code="vehicle", title="车辆基础信息", phase="B2", group="基础档案", main="vehicle", catalog="VEHICLE_FILE", tables=[
        "vehicle", "vehicle_doc", "vehicle_doc_dict", "vehicle_driver", "vehicle_insurance",
        "vehicle_device", "vehicle_grade", "vehicle_link", "veh_type", "tenant_veh_config",
        "tenant_parking", "tenant_cert", "vehicle_repair",
    ]),
    dict(code="institution", title="制度档案引擎", phase="B3", group="制度范本", main="tpl_group", catalog="BOX2", tables=[
        "tpl_group", "tpl_spec_group", "tpl_field_dict", "tpl_seal", "tpl_signature",
        "tpl_gen_record", "flow_config", "flow_node", "flow_process",
    ]),
    dict(code="check", title="一日三检", phase="B4", group="业务工具", main="check_record", catalog="BOX9", tables=[
        "check_config", "check_item_dict", "check_task", "check_record", "check_item_result", "check_remind",
    ]),
    dict(code="danger", title="隐患排查治理", phase="B5", group="业务工具", main="danger_record", catalog="BOX8", tables=[
        "danger_class", "danger_check_template", "danger_record", "danger_rectify", "danger_accept",
    ]),
    dict(code="meeting", title="安全会议", phase="B6", group="业务工具", main="meeting", catalog="BOX3", tables=[
        "meeting", "meeting_attendee", "meeting_record",
    ]),
    dict(code="collect", title="服务商代录", phase="B8", group="服务商", main="collect_task", catalog="BOX1", tables=[
        "collect_task", "collect_result",
    ]),
    # ===== 阶段 C：二期 P1 =====
    dict(code="training", title="培训教育", phase="C1", group="二期业务", main="training_plan", catalog="BOX4", tables=[
        "training_plan", "training_plan_video", "training_video", "training_question",
        "training_exam", "training_assign",
    ]),
    dict(code="emergency", title="应急管理", phase="C2", group="二期业务", main="emergency_plan", catalog="BOX7", tables=[
        "emergency_plan", "emergency_plan_tpl", "emergency_drill_plan", "emergency_drill",
        "emergency_drill_item", "emergency_drill_sign", "emergency_drill_photo",
        "emergency_duty", "emergency_duty_rule", "emergency_equipment",
        "emergency_team", "emergency_team_member",
    ]),
    dict(code="accident", title="事故与违法", phase="C3", group="二期业务", main="accident_record", catalog="BOX10", tables=[
        "accident_record", "accident_quick_report", "accident_service",
        "accident_year_summary", "violation_dict", "monitor_violation",
    ]),
    dict(code="assess", title="考核奖惩", phase="C4", group="二期业务", main="credit_assess", catalog="BOX1", tables=[
        "credit_assess", "credit_assess_item", "credit_reward_notice",
    ]),
    dict(code="sign", title="自定义文件签署", phase="C6", group="二期业务", main="sign_task", catalog="BOX1", tables=[
        "sign_task",
    ]),
    # ===== 阶段 D：三期 P2/P3（v1.2）=====
    # 注意：这些表若已在 V1~V7 建过，V8 生成时会自动跳过，只补真正缺的表。
    dict(code="monitor", title="动态监控台账", phase="D1", group="三期业务", main="monitor_ledger", catalog="BOX6", tables=[
        "monitor_ledger", "org_outsource",
    ]),
    dict(code="budget", title="安全生产费用", phase="D2", group="三期业务", main="budget_plan", catalog="BOX5", tables=[
        "budget_plan", "budget_expense", "budget_category",
    ]),
    dict(code="risk", title="风险分级管控", phase="D3", group="三期业务", main="risk_list", catalog="BOX8", tables=[
        "risk_point_dict", "risk_list", "risk_card", "risk_manual", "org_post_content",
    ]),
    # D4 专项台账（电子运单/装卸查验/押运员在岗）按 21 号设计归「盒六·动态监控及交通违法管理」
    dict(code="dangerspecial", title="危货专项深化", phase="D4", group="三期业务", main="danger_special_ledger", catalog="BOX6", tables=[
        "danger_special_ledger", "danger_waybill",
    ]),
    dict(code="platform", title="平台侧能力", phase="D5", group="平台管理", main="sys_module_config", catalog="", tables=[
        "sys_module_config", "tenant_module", "sso_config", "service_billing",
    ]),
]

# ---------------------------------------------------------------------------
# schema.sql 中没有、由本次工程补齐的表（B2 车辆维修保养缺口 / A2 附件 / C6 签署）
# ---------------------------------------------------------------------------
EXTRA_DDL = {
    "biz_file": """CREATE TABLE `biz_file` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通用附件上传记录';""",
    "vehicle_repair": """CREATE TABLE `vehicle_repair` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='车辆维修保养（B2 补齐的既有设计缺口）';""",
    "sign_task": """CREATE TABLE `sign_task` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='自定义文件签署任务（C6）';""",

    # ===== 阶段 D4：危货专项深化（21 号设计 §5.1 六项台账 + 电子运单）=====
    "danger_special_ledger": """CREATE TABLE `danger_special_ledger` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='危货六项专项台账统一表（D4）';""",
    "danger_waybill": """CREATE TABLE `danger_waybill` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='危险货物电子运单（D4，法律法规强制）';""",

    # ===== 阶段 D5：平台侧能力（模块配置 / SSO / 增值计费）=====
    "sys_module_config": """CREATE TABLE `sys_module_config` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台功能模块字典（D5，平台级无租户）';""",
    "tenant_module": """CREATE TABLE `tenant_module` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业功能模块开通记录（D5）';""",
    "sso_config": """CREATE TABLE `sso_config` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='企业 SSO 配置登记（D5）';""",
    "service_billing": """CREATE TABLE `service_billing` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='增值服务计费账单（D5）';""",
}

# 普货 10 盒 / 危货 11 盒目录（逐字对齐 08 号设计文档 §2.1 / §2.2）
BOXES = {
    "GENERAL": [
        ("BOX1", "一、公司档案及管理层档案"), ("BOX2", "二、安全生产管理制度及操作规程"),
        ("BOX3", "三、安全生产会议"), ("BOX4", "四、安全生产教育和培训"),
        ("BOX5", "五、安全生产费用提取与使用"), ("BOX6", "六、动态监控及交通违法管理"),
        ("BOX7", "七、应急管理"), ("BOX8", "八、风险分级管控与隐患排查治理"),
        ("BOX9", "九、一日三检"), ("BOX10", "十、事故档案"),
    ],
    "DANGER": [
        ("BOX1", "一、公司档案及管理层档案"), ("BOX2", "二、安全生产管理制度及操作规程"),
        ("BOX3", "三、安全生产会议"), ("BOX4", "四、安全生产教育和培训"),
        ("BOX5", "五、安全生产费用提取与使用"), ("BOX6", "六、动态监控及交通违法管理"),
        ("BOX7", "七、停车场管理"), ("BOX8", "八、应急管理"),
        ("BOX9", "九、风险分级管控与隐患排查治理"), ("BOX10", "十、一日三检"),
        ("BOX11", "十一、事故档案"),
    ],
}
# 每盒下的二级归档子项（B7 缺项提示依据）
SUBITEMS = {
    "BOX1": ["营业执照", "道路运输经营许可证", "安全组织架构", "关于成立安全生产领导小组的通知",
             "二级维护计划", "安全生产责任险", "管理人员档案", "安全生产责任书", "安全生产承诺书",
             "岗位危害告知书", "岗前三级培训", "两类人员考核合格证明", "年度责任制考核记录",
             "人员任命书", "复工复产方案"],
    "BOX2": ["适用安全生产法律法规标准目录清单", "制度汇编（含操作规程）", "安全生产责任制"],
    "BOX3": ["安全生产领导小组会议", "安全生产月度会议"],
    "BOX4": ["安全生产教育和培训计划", "安全生产月度培训"],
    "BOX5": ["年度营收入证明", "安全费用提取标准及计划", "安全生产费用使用台账"],
    "BOX6": ["动态监控工作情况记录台账", "设备故障异常台账", "在线率台账", "月报台账", "针对性违规教育"],
    "BOX8": ["隐患排查记录", "隐患排查治理（整改通知书、验收单）"],
    "BOX9": ["日常车辆三检记录", "三检月报汇总表"],
    "BOX10": ["年度事故汇总统计表", "事故资料", "事故快报表", "事故四不放过资料"],
    "BOX11": ["年度事故汇总统计表", "事故资料", "事故快报表", "事故四不放过资料"],
}

SYSTEM_FIELDS = {"id", "tenant_id", "created_at", "updated_at", "created_by", "deleted"}

# ---------------------------------------------------------------------------
# 阶段 D（三期 v1.2）：字典种子与档案盒二级目录补充
# ---------------------------------------------------------------------------
# 财资〔2022〕136 号 11 类标准支出范围（17 号设计 §3.2），tenant_id=0 为系统预置
BUDGET_CATEGORIES = [
    ("FACILITY", "安全防护设施设备支出"),
    ("LOCATION", "车辆定位监控装置支出"),
    ("RESCUE", "应急救援器材及演练支出"),
    ("HAZARD", "危险源监控与隐患整改支出"),
    ("CHECK", "安全检查评价咨询支出"),
    ("PPE", "安全防护用品支出"),
    ("TRAIN", "安全宣传教育培训支出"),
    ("TECH", "新技术新装备推广支出"),
    ("INSPECT", "设施检测检验支出"),
    ("INSUR", "安全责任保险支出"),
    ("OTHER", "其他安全生产支出"),
]

# 通用风险点库（18 号设计 §3.2）：驾驶员/车辆/货物/环境/管理 × 四色等级
# 字段：名称, 分类, 等级（红/橙/黄/蓝）, 默认管控措施, 适用（general/danger/all）
RISK_POINTS = [
    ("疲劳驾驶", "driver", "红", "连续驾驶不超过4小时、24小时累计不超过8小时，动态监控平台报警后必须停车休息并登记违规教育", "all"),
    ("超速行驶", "driver", "红", "按道路限速与车辆核定速度行驶，接入动态监控超速报警并按月汇总违规台账", "all"),
    ("酒后驾驶", "driver", "红", "出车前酒精检测，一经发现立即停岗并按制度处理", "all"),
    ("驾驶员从业资格失效", "driver", "橙", "建立证照到期预警，从业资格证过期前完成换证，过期不得派车", "all"),
    ("危货押运员脱岗", "driver", "红", "每趟电子运单绑定押运员，全程在岗并与运单/轨迹匹配，脱岗不得发车", "danger"),
    ("制动系统失效", "vehicle", "红", "执行一日三检与二级维护，制动异常立即停运送修", "all"),
    ("轮胎磨损/爆胎", "vehicle", "橙", "出车前检查胎压与花纹深度，按里程周期更换并留存维修记录", "all"),
    ("卫星定位装置离线", "vehicle", "橙", "离线车辆不得派车，恢复上线后补录设备故障异常台账", "all"),
    ("罐体/专用标识不合格", "vehicle", "红", "罐体定期检测、专用标识喷涂齐全，证照归档一车一档", "danger"),
    ("货物超载/装载不牢", "cargo", "橙", "按核定载质量装载，出车前核验捆扎与封口，超载属重大隐患", "all"),
    ("危险货物泄漏", "cargo", "红", "配随车安全卡（一书一签）与应急器材，装卸前后查验并留存记录", "danger"),
    ("危化品夹带/匿报", "cargo", "红", "托运清单与电子运单逐项核对，发现夹带立即停运并纳入隐患排查", "danger"),
    ("恶劣天气（雨雾冰雪）", "env", "橙", "发布预警、调整班次或限速，必要时停运并记录调度指令", "all"),
    ("长下坡/临崖等危险路段", "env", "橙", "建立风险路段清单与路书，800公里以上线路执行双押双驾", "all"),
    ("限行区域误入", "env", "黄", "出车前核对通行证与限行区域，剧毒品按地方要求报备", "danger"),
    ("安全制度缺失或未更新", "manage", "黄", "年度评审制度汇编并按法规更新，范本套版本留痕", "all"),
    ("培训教育不到位", "manage", "黄", "年度培训计划 + 月度培训 + 违规靶向教育，学时与考核留痕", "all"),
    ("隐患排查未闭环", "manage", "橙", "隐患整改通知→整改→验收闭环，超期自动升级提醒", "all"),
    ("随车工具与灭火器摆放不规范", "vehicle", "蓝", "纳入一日三检随车检查项，定置摆放并月度抽查", "all"),
    ("安全会议记录签字遗漏", "manage", "蓝", "会议纪要归档前校验签到表完整性，缺签自动提示补签", "all"),
    ("停车场标识/照明磨损", "env", "蓝", "纳入停车场季度巡检，及时更换标识与照明", "all"),
]

# 档案盒二级目录补充：D 阶段成果物对齐政府检查项（18/20/21 号设计）
# key = V5 生成的一级目录 code，value = 新增子项名
EXTRA_SUBITEMS = {
    "GENERAL_BOX8": ["风险辨识手册", "风险辨识清单", "岗位风险告知卡", "无重大风险的说明"],
    "DANGER_BOX9": ["风险辨识手册", "风险辨识清单", "岗位风险告知卡", "无重大风险的说明",
                     "安全评估（危货）", "无重大事故隐患的声明（危货）"],
    "DANGER_BOX6": ["危险货物电子运单", "装卸查验记录", "危险品运输台账",
                    "押运员在岗记录", "800公里以上线路台账", "安全卡（一书一签）"],
}

# D5 增值模块月费（元/月）；基础模块 is_base=1 不计费
D5_FEES = {"D1": 199, "D2": 99, "D3": 199, "D4": 299, "D5": 0}


def built_tables():
    """读取 V1~V7 迁移脚本，返回已建表集合。V8/V9 只补真正缺的表，
    避免重复建表导致 Flyway 在生产库上失败（绝不改已发布迁移的校验和）。"""
    mig = BE_RES / "db" / "migration"
    names = set(V1_TABLES)
    for f in sorted(mig.glob("V[1-7]__*.sql")):
        names.update(re.findall(r"CREATE TABLE `(\w+)`", f.read_text(encoding="utf-8")))
    return names


def catalog_rows():
    """V5 目录树（普货 10 盒 / 危货 11 盒 + 二级子项）。V9 复用它取父目录 id。"""
    seq = [4000]
    rows = []

    def nid():
        seq[0] += 1
        return seq[0]

    # 固定 id：V2 种子中责任制子项（3101）将迁移到 V5 的这一条，ArchiveService 按此常量引用
    RESPONSIBILITY_ID = 40099
    for biz, boxes in BOXES.items():
        for idx, (code, name) in enumerate(boxes, start=1):
            box_id = nid()
            rows.append((box_id, 0, biz, 1, idx, f"{biz}_{code}", name, 0, 0, "LONG"))
            sub_code = code
            for sidx, sub in enumerate(SUBITEMS.get(sub_code, []), start=1):
                rid = RESPONSIBILITY_ID if (biz == "GENERAL" and code == "BOX2" and sub == "安全生产责任制") else nid()
                rows.append((rid, 0, biz, 2, sidx, f"{biz}_{code}_S{sidx:02d}", sub, box_id, 0, "YEAR"))
    return rows


def phase3_seed(cat_rows):
    """V9 尾部：字典种子 + 档案盒二级目录补充（全部幂等，可读性好、便于人工核对）。"""
    out = ["", "-- ===== D2：财资〔2022〕136 号 11 类安全费用支出（系统预置 tenant_id=0）====="]
    out.append("DELETE FROM `budget_category` WHERE `id` BETWEEN 6001 AND 6011;")
    out.append("INSERT INTO `budget_category` "
               "(`id`,`tenant_id`,`category_name`,`category_code`,`sort_order`,`created_at`,`deleted`) VALUES")
    out.append(",\n".join(
        f"  ({6001 + i}, 0, '{name}', '{code}', {i + 1}, NOW(), 0)"
        for i, (code, name) in enumerate(BUDGET_CATEGORIES)) + ";")

    out.append("")
    out.append("-- ===== D3：通用风险点库（系统预置 tenant_id=0，企业可增补自定义）=====")
    out.append("DELETE FROM `risk_point_dict` WHERE `id` BETWEEN 6201 AND 6299;")
    out.append("INSERT INTO `risk_point_dict` "
               "(`id`,`tenant_id`,`risk_name`,`risk_category`,`risk_level`,`control_measure`,`cargo_type`,`created_at`,`deleted`) VALUES")
    out.append(",\n".join(
        "  ({}, 0, '{}', '{}', '{}', '{}', '{}', NOW(), 0)".format(
            6201 + i, *[str(x).replace("'", "''") for x in rp])
        for i, rp in enumerate(RISK_POINTS)) + ";")

    out.append("")
    out.append("-- ===== D5：平台功能模块字典（与 biz/modules.json 逐项对齐）=====")
    out.append("DELETE FROM `sys_module_config` WHERE `id` BETWEEN 6301 AND 6399;")
    out.append("INSERT INTO `sys_module_config` "
               "(`id`,`module_code`,`module_name`,`phase`,`group_name`,`is_base`,`monthly_fee`,`sort_no`,`enabled`,`created_at`,`deleted`) VALUES")
    vals = []
    for i, m in enumerate(MODULES, start=1):
        is_base = 0 if m["phase"].startswith("D") else 1
        fee = 0 if is_base else D5_FEES.get(m["phase"], 99)
        vals.append(f"  ({6300 + i}, '{m['code']}', '{m['title']}', '{m['phase']}', "
                    f"'{m['group']}', {is_base}, {fee}, {i}, 1, NOW(), 0)")
    out.append(",\n".join(vals) + ";")

    out.append("")
    out.append("-- ===== D1/D3/D4：档案盒二级目录补充（父目录 id 取自 V5 目录树）=====")
    parent = {r[5]: r[0] for r in cat_rows if r[3] == 1}
    seq_id = [50000]
    inserts = []
    for box_code, subs in EXTRA_SUBITEMS.items():
        pid = parent.get(box_code)
        if pid is None:
            continue
        biz = box_code.split("_")[0]
        for sidx, sub in enumerate(subs, start=20):
            seq_id[0] += 1
            code = f"{box_code}_D{sidx:02d}"
            name = sub.replace("'", "''")
            inserts.append(f"  ({seq_id[0]}, 0, '{biz}', 2, {sidx}, '{code}', '{name}', {pid}, 0, 'YEAR')")
    out.append("DELETE FROM `archive_catalog` WHERE `id` BETWEEN 50001 AND 50999;")
    out.append("INSERT INTO `archive_catalog` "
               "(`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`renew_freq`) VALUES")
    out.append(",\n".join(inserts) + ";")
    out.append("")
    return "\n".join(out)


def parse_schema():
    """解析 schema.sql → {table: {'ddl': str, 'comment': str, 'fields': [...]}}"""
    text = SCHEMA.read_text(encoding="utf-8")
    tables = {}
    for m in re.finditer(r"CREATE TABLE `(\w+)` \((.*?)\n\) ENGINE=[^;]+;", text, re.S):
        name, body = m.group(1), m.group(2)
        cm = re.search(r"COMMENT='([^']*)'", m.group(0))
        fields = []
        for line in body.splitlines():
            line = line.strip().rstrip(",")
            fm = re.match(r"`(\w+)`\s+([A-Za-z]+(?:\([^)]*\))?)\s*(.*)$", line)
            if not fm:
                continue
            col, coltype, rest = fm.group(1), fm.group(2).upper(), fm.group(3)
            if col.upper() in ("PRIMARY", "KEY", "UNIQUE", "INDEX", "CONSTRAINT"):
                continue
            cmt = re.search(r"COMMENT\s+'([^']*)'", rest)
            comment = cmt.group(1) if cmt else ""
            required = "NOT NULL" in rest and "DEFAULT" not in rest and col != "id"
            fields.append(dict(name=col, type=coltype, comment=comment, required=required))
        tables[name] = dict(ddl=m.group(0) + ";", comment=cm.group(1) if cm else name, fields=fields)
    return tables


def control_of(f):
    """字段 → 前端控件类型"""
    n, t = f["name"], f["type"]
    if n in SYSTEM_FIELDS:
        return "hidden"
    if any(k in n for k in ("photo", "file", "url", "avatar", "attachment", "pdf")):
        return "upload"
    if t.startswith(("TEXT", "LONGTEXT")):
        return "textarea"
    if t.startswith("DATE") or n.endswith(("_at", "_date", "_time")):
        return "datetime" if "DATETIME" in t or n.endswith("_at") else "date"
    if t.startswith(("BIGINT", "INT", "SMALLINT", "DECIMAL")):
        return "number"
    if t.startswith("TINYINT"):
        return "switch"
    if any(k in n for k in ("status", "type", "level", "grade", "class", "gender")):
        return "select"
    return "input"


def java_type(t):
    if t.startswith("BIGINT"):
        return "Long"
    if t.startswith(("INT", "SMALLINT", "TINYINT")):
        return "Integer"
    if t.startswith("DECIMAL"):
        return "BigDecimal"
    if t.startswith("DATETIME") or t.startswith("TIMESTAMP"):
        return "LocalDateTime"
    if t.startswith("DATE"):
        return "LocalDate"
    return "String"


def build():
    schema = parse_schema()
    print(f"解析 schema.sql：{len(schema)} 张表")

    for tbl, ddl in EXTRA_DDL.items():
        # 补齐表的字段元数据：复用同一解析逻辑
        body = ddl[ddl.index("(") + 1:ddl.rindex(") ENGINE")]
        fields = []
        for line in body.splitlines():
            line = line.strip().rstrip(",")
            fm = re.match(r"`(\w+)`\s+([A-Za-z]+(?:\([^)]*\))?)\s*(.*)$", line)
            if not fm:
                continue
            col, coltype, rest = fm.group(1), fm.group(2).upper(), fm.group(3)
            cmt = re.search(r"COMMENT\s+'([^']*)'", rest)
            fields.append(dict(name=col, type=coltype, comment=cmt.group(1) if cmt else "",
                               required="NOT NULL" in rest and "DEFAULT" not in rest and col != "id"))
        cm = re.search(r"COMMENT='([^']*)'", ddl)
        schema[tbl] = dict(ddl=ddl, comment=cm.group(1) if cm else tbl, fields=fields)

    missing = []
    out_modules = []
    for mod in MODULES:
        out_tables = []
        for t in mod["tables"]:
            if t not in schema:
                missing.append(f"{mod['code']}.{t}")
                continue
            info = schema[t]
            fields = []
            for f in info["fields"]:
                fields.append(dict(
                    name=f["name"], type=f["type"], label=f["comment"] or f["name"],
                    required=f["required"], control=control_of(f), javaType=java_type(f["type"]),
                ))
            out_tables.append(dict(name=t, title=info["comment"], fields=fields))
        out_modules.append(dict(
            code=mod["code"], title=mod["title"], phase=mod["phase"], group=mod["group"],
            main=mod["main"], catalog=mod["catalog"], tables=out_tables,
        ))

    if missing:
        print("⚠️  schema.sql 中缺失的表（已跳过）：" + ", ".join(missing))

    # ---- modules.json ----
    (BE_RES / "biz").mkdir(parents=True, exist_ok=True)
    (BE_RES / "biz" / "modules.json").write_text(
        json.dumps(out_modules, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"生成 modules.json：{len(out_modules)} 个模块 / {sum(len(m['tables']) for m in out_modules)} 张表")

    # ---- Flyway V3 一期 / V4 二期 ----
    phase1 = [m for m in MODULES if m["phase"].startswith(("A", "B"))]
    phase2 = [m for m in MODULES if m["phase"].startswith("C")]

    # 纯关联表：建表但不暴露通用 CRUD（无单一 id 主键，由权限模块内部维护）
    DDL_ONLY = {"sys_role_menu": "A"}

    def migration(version, desc, mods, include_ddl_only=False):
        lines = [f"-- {desc}", "-- 由 tools/gen_biz_meta.py 从 database/schema.sql 逐字提取，请勿手工编辑。", ""]
        seen = set()
        for mod in mods:
            lines.append(f"-- ===== {mod['phase']} {mod['title']} =====")
            for t in mod["tables"]:
                if t in V1_TABLES or t in seen or t not in schema:
                    continue
                seen.add(t)
                lines.append(schema[t]["ddl"])
                lines.append("")
        if include_ddl_only:
            lines.append("-- ===== 仅建表：无单一主键的关联表（不暴露通用 CRUD）=====")
            for t in DDL_ONLY:
                if t not in V1_TABLES and t not in seen and t in schema:
                    seen.add(t)
                    lines.append(schema[t]["ddl"])
                    lines.append("")
        return "\n".join(lines) + "\n"

    (BE_RES / "db" / "migration" / "V3__phase1.sql").write_text(
        migration("V3", "一期（A/B 阶段）业务表", phase1, True), encoding="utf-8")
    (BE_RES / "db" / "migration" / "V4__phase2.sql").write_text(
        migration("V4", "二期（C 阶段）业务表", phase2), encoding="utf-8")
    p1 = sum(1 for m in phase1 for t in m["tables"] if t not in V1_TABLES and t in schema) + len(DDL_ONLY)
    p2 = sum(1 for m in phase2 for t in m["tables"] if t not in V1_TABLES and t in schema)
    print(f"生成 V3__phase1.sql：{p1} 张表；V4__phase2.sql：{p2} 张表")

    # ---- V5 档案盒目录种子（B7）----
    rows = catalog_rows()
    values = ",\n".join(
        f"  ({r[0]}, {r[1]}, '{r[2]}', {r[3]}, {r[4]}, '{r[5]}', '{r[6]}', {r[7]}, {r[8]}, '{r[9]}')"
        for r in rows)
    v5 = ("-- B7 电子档案柜：普货 10 盒 / 危货 11 盒目录树种子（逐字对齐 08 号设计文档）\n"
          "-- 统一目录：把 V2 的旧 BOX2/RESPONSIBILITY 迁移到模板目录，删除旧目录，避免全景页出现重复盒。\n"
          "DELETE FROM `archive_catalog` WHERE `id` >= 4000;\n"
          "INSERT INTO `archive_catalog`\n"
          "  (`id`,`tenant_id`,`biz_type`,`level`,`sort_no`,`code`,`name`,`parent_id`,`has_pdf`,`renew_freq`) VALUES\n"
          f"{values};\n"
          "UPDATE `archive_item` SET `catalog_id` = 40099 WHERE `catalog_id` = 3101;\n"
          "DELETE FROM `archive_catalog` WHERE `id` IN (3002, 3101);\n")
    (BE_RES / "db" / "migration" / "V5__archive_catalog.sql").write_text(v5, encoding="utf-8")
    print(f"生成 V5__archive_catalog.sql：{len(rows)} 条目录（含二级子项）")

    # ---- Flyway V8 / V9：三期 D1~D5（v1.2）----
    # 只读 V1~V7 判断「已建表」，因此重复运行生成器不会把 V8/V9 的表误判为已建。
    built = built_tables()
    d123 = [m for m in MODULES if m["phase"] in ("D1", "D2", "D3")]
    d45 = [m for m in MODULES if m["phase"] in ("D4", "D5")]

    def ddl_only(mods, header):
        lines = [header,
                 "-- 由 tools/gen_biz_meta.py 从 database/schema.sql 逐字提取（EXTRA_DDL 补齐表同规则）。",
                 "-- 只创建 V1~V7 尚未建过的表；已发布迁移 V1~V7 不受影响，校验和保持不变。", ""]
        seen, n = set(built), 0
        for mod in mods:
            lines.append(f"-- ===== {mod['phase']} {mod['title']} =====")
            for t in mod["tables"]:
                if t in seen or t not in schema:
                    continue
                seen.add(t)
                lines.append(schema[t]["ddl"])
                lines.append("")
                n += 1
        return "\n".join(lines) + "\n", n

    v8, n8 = ddl_only(d123, "-- D 阶段（三期 v1.2）D1 动态监控台账 / D2 安全生产费用 / D3 风险分级管控")
    (BE_RES / "db" / "migration" / "V8__phase3.sql").write_text(v8, encoding="utf-8")

    v9, n9 = ddl_only(d45, "-- D 阶段（三期 v1.2）D4 危货专项深化 / D5 平台侧能力")
    v9 += phase3_seed(rows)
    (BE_RES / "db" / "migration" / "V9__phase3_ext.sql").write_text(v9, encoding="utf-8")
    print(f"生成 V8__phase3.sql：{n8} 张表；V9__phase3_ext.sql：{n9} 张表 + 字典/目录种子")

    # ---- 前端菜单配置（A5：菜单由配置驱动）----
    FE_CFG.mkdir(parents=True, exist_ok=True)
    menus = [dict(code=m["code"], title=m["title"], phase=m["phase"], group=m["group"],
                  catalog=m["catalog"],
                  tables=[dict(name=t["name"], title=t["title"]) for t in m["tables"]])
             for m in out_modules]
    (FE_CFG / "modules.generated.js").write_text(
        "// 由 tools/gen_biz_meta.py 自动生成，请勿手工编辑\n"
        "export const bizModules = " + json.dumps(menus, ensure_ascii=False, indent=2) + "\n\n"
        "export const menuGroups = [...new Set(bizModules.map(m => m.group))]\n",
        encoding="utf-8")
    print("生成 frontend/src/config/modules.generated.js")


if __name__ == "__main__":
    build()
