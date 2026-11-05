#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
业务元数据生成器（A4/A5 及 B/C 阶段共用）
------------------------------------------------------------------
输入：database/schema.sql（102 张表的权威 DDL）+ 下方模块配置
输出：
  1) backend/src/main/resources/biz/modules.json   模块/表/字段元数据（驱动通用 CRUD 引擎与前端菜单/表单）
  2) backend/src/main/resources/db/migration/V3__phase1.sql  一期表 DDL
  3) backend/src/main/resources/db/migration/V4__phase2.sql  二期表 DDL
  4) backend/src/main/resources/db/migration/V5__archive_catalog.sql  10/11 盒目录种子（B7）
  5) frontend/src/config/modules.generated.js      前端菜单配置（构建期生成，A5）

设计原则：DDL 逐字取自 schema.sql，保证「表结构与 schema.sql 一致」；
字段白名单由元数据固化，通用 CRUD 引擎只接受白名单字段，防 SQL 注入。
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
