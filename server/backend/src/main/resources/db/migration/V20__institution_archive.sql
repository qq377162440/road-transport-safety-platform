-- B3 制度档案引擎 · 制度成品档案状态表（详细设计 09 §7.3 / §7.10 / §7.11）
-- 与 B1 person_archive_item、B2 vehicle_archive_item 对称，但语义不同：
--   台账范本管理管「空白范本模板」，本套表管「企业实际生效的制度成品文件」——
--   有文件编号 / 实施日期 / 签发人 / 盖章 / 版本 / 审批状态，最终归档电子档案柜盒二。
-- 24 项制度字典 ZD01~ZD24 以 tpl_template 中同名多套范本为同源数据源，不另建字典表。
-- 状态机：0未起草 → 1起草中 → 2待审核 → 3已审核待发布 → 4已发布 → 5已作废；
--         2 可驳回为 6审核驳回（回到起草）；4 可修订生成新版本回到 1起草中。
CREATE TABLE IF NOT EXISTS `institution_item` (
  `id`             bigint NOT NULL COMMENT '雪花ID',
  `tenant_id`      bigint NOT NULL DEFAULT '0' COMMENT '租户ID',
  `item_code`      varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '制度编码 ZD01~ZD24',
  `item_name`      varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '制度名称',
  `doc_no`         varchar(60) DEFAULT NULL COMMENT '文件编号 YT-ZDxx',
  `biz_scope`      varchar(30) DEFAULT 'ALL' COMMENT '适用范围 ALL/GENERAL/DANGER/PASSENGER',
  `status`         tinyint NOT NULL DEFAULT '0' COMMENT '0未起草 1起草中 2待审核 3已审核待发布 4已发布 5已作废 6审核驳回',
  `current_version` varchar(30) DEFAULT NULL COMMENT '当前版本号 v1 / v2',
  `version_seq`    int NOT NULL DEFAULT '0' COMMENT '版本序号',
  `tpl_id`         bigint DEFAULT NULL COMMENT '当前采用的 tpl_template 范本版本ID',
  `tpl_label`      varchar(60) DEFAULT NULL COMMENT '范本版本名（内置/小微/危货/规上）',
  `issuer_id`      bigint DEFAULT NULL COMMENT '签发人 person.id（B1 同源）',
  `issuer_name`    varchar(50) DEFAULT NULL COMMENT '签发人姓名',
  `effective_date` date DEFAULT NULL COMMENT '实施日期',
  `review_date`    date DEFAULT NULL COMMENT '复审到期日（→预警）',
  `file_url`       varchar(500) DEFAULT NULL COMMENT '成品PDF对象存储路径',
  `created_at`     datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`     bigint NOT NULL DEFAULT '0',
  `deleted`        tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_institution_item_tenant` (`tenant_id`),
  KEY `idx_institution_item_code` (`item_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='制度档案主状态（24项）';

-- 版本历史：每次起草 / 修订都留一条，旧版不覆盖，保证设计文档 §7.10.3 的可回溯
CREATE TABLE IF NOT EXISTS `institution_version` (
  `id`             bigint NOT NULL COMMENT '雪花ID',
  `tenant_id`      bigint NOT NULL DEFAULT '0',
  `item_code`      varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `version`        varchar(30) DEFAULT NULL COMMENT 'v1 / v2',
  `version_seq`    int NOT NULL DEFAULT '1',
  `tpl_id`         bigint DEFAULT NULL COMMENT '来源范本ID',
  `tpl_label`      varchar(60) DEFAULT NULL COMMENT '来源范本版本名',
  `doc_no`         varchar(60) DEFAULT NULL,
  `issuer_id`      bigint DEFAULT NULL,
  `issuer_name`    varchar(50) DEFAULT NULL,
  `effective_date` date DEFAULT NULL,
  `review_date`    date DEFAULT NULL,
  `reason`         varchar(300) DEFAULT NULL COMMENT '起草 / 修订原因',
  `status`         tinyint NOT NULL DEFAULT '1' COMMENT '1草稿 2待审核 3待发布 4已发布 5已作废 6驳回',
  `file_url`       varchar(500) DEFAULT NULL,
  `created_at`     datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by`     bigint NOT NULL DEFAULT '0',
  `deleted`        tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_institution_version_tenant` (`tenant_id`),
  KEY `idx_institution_version_code` (`item_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='制度版本历史';

-- 签署 / 盖章进度子表（原型 doc_publish.html：需签署 / 待签字 / 待盖章 / 已完成）
CREATE TABLE IF NOT EXISTS `institution_sign` (
  `id`          bigint NOT NULL COMMENT '雪花ID',
  `tenant_id`   bigint NOT NULL DEFAULT '0',
  `item_code`   varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `version`     varchar(30) DEFAULT NULL,
  `signer_id`   bigint DEFAULT NULL COMMENT '签署人 person.id（B1 同源）',
  `signer_name` varchar(50) DEFAULT NULL,
  `sign_type`   varchar(20) DEFAULT 'sign' COMMENT 'sign法人签字 / seal公司盖章',
  `status`      tinyint NOT NULL DEFAULT '0' COMMENT '0待办 1已完成',
  `signed_at`   datetime DEFAULT NULL,
  `created_at`  datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted`     tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_institution_sign_tenant` (`tenant_id`),
  KEY `idx_institution_sign_code` (`item_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='制度签署盖章进度';

-- 流转日志：每次状态变迁留痕（审批意见、操作人、时间）
CREATE TABLE IF NOT EXISTS `institution_flow_log` (
  `id`          bigint NOT NULL COMMENT '雪花ID',
  `tenant_id`   bigint NOT NULL DEFAULT '0',
  `item_code`   varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `version`     varchar(30) DEFAULT NULL,
  `from_status` tinyint DEFAULT NULL,
  `to_status`   tinyint DEFAULT NULL,
  `action`      varchar(30) DEFAULT NULL COMMENT 'draft/submit/approve/reject/publish/abolish/revise/sign/toc',
  `operator`    varchar(50) DEFAULT NULL,
  `remark`      varchar(300) DEFAULT NULL,
  `created_at`  datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted`     tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_institution_log_tenant` (`tenant_id`),
  KEY `idx_institution_log_code` (`item_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='制度流转日志';
