-- B5 隐患排查治理完整闭环（详细设计 13）
-- 说明：danger_record / danger_rectify / danger_accept / danger_check_template 已在 V4 建表，
--       本迁移只做字段补齐 + 新增子表 + 系统级清单范本种子，不重建既有表，
--       保证 B4 三检异常已写入的隐患数据与演示数据不丢失。

-- 1. 主表补齐闭环冗余字段（超期判定、督办、闭环时间、排查批次关联）
ALTER TABLE `danger_record`
  ADD COLUMN `check_plan_id` BIGINT      NULL COMMENT '关联排查批次 danger_check_plan.id；三检/司机上报为空' AFTER `ref_id`,
  ADD COLUMN `deadline`      DATETIME    NULL COMMENT '整改期限（冗余自整改单，便于超期判定与列表展示）' AFTER `status`,
  ADD COLUMN `remind_count`  INT         NOT NULL DEFAULT 0 COMMENT '累计督办提醒次数' AFTER `deadline`,
  ADD COLUMN `closed_time`   DATETIME    NULL COMMENT '验收通过闭环时间' AFTER `remind_count`,
  ADD INDEX `idx_danger_plan` (`check_plan_id`),
  ADD INDEX `idx_danger_deadline` (`status`, `deadline`);

-- 2. 排查批次主表（一次定期专项排查 / 一组逐项判定）
CREATE TABLE IF NOT EXISTS `danger_check_plan` (
  `id`            BIGINT       NOT NULL,
  `tenant_id`     BIGINT       NOT NULL,
  `plan_no`       VARCHAR(50)  NULL COMMENT '排查编号 PC-YYYYMM-xxxx',
  `title`         VARCHAR(200) NOT NULL COMMENT '检查名称',
  `check_type`    VARCHAR(30)  NOT NULL COMMENT '排查类型编码',
  `template_id`   BIGINT       NULL COMMENT '采用的清单范本 id',
  `template_name` VARCHAR(100) NULL COMMENT '采用的清单范本名称（快照）',
  `checker_id`    BIGINT       NULL COMMENT '检查人/负责人',
  `check_date`    DATE         NULL COMMENT '排查日期',
  `item_total`    INT          NOT NULL DEFAULT 0 COMMENT '检查项目数',
  `problem_total` INT          NOT NULL DEFAULT 0 COMMENT '不合格（整改项）数',
  `danger_total`  INT          NOT NULL DEFAULT 0 COMMENT '联动生成的隐患单数',
  `status`        VARCHAR(20)  NOT NULL DEFAULT 'doing' COMMENT 'doing排查中 / done排查完成待闭环 / closed全部整改闭环',
  `remark`        VARCHAR(500) NULL,
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NULL,
  `created_by`    BIGINT       NULL,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_dcp_tenant` (`tenant_id`, `check_type`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隐患排查批次（定期专项排查主表）';

-- 3. 逐项判定结果子表（范本带出 → 逐项判定 → 不合格联动隐患）
CREATE TABLE IF NOT EXISTS `danger_check_item` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   BIGINT       NOT NULL,
  `plan_id`     BIGINT       NOT NULL,
  `category`    VARCHAR(40)  NULL COMMENT '项目类别：重大事故隐患排查项 / 基础管理',
  `item_name`   VARCHAR(200) NULL COMMENT '检查项目',
  `standard`    VARCHAR(1000) NULL COMMENT '检查内容与检查标准',
  `is_major`    TINYINT      NOT NULL DEFAULT 0 COMMENT '1=一票否决重大事故隐患项',
  `result`      VARCHAR(10)  NOT NULL DEFAULT 'pending' COMMENT 'pending未判定 / pass合格 / fail不合格',
  `remark`      VARCHAR(500) NULL COMMENT '备注/不合格说明',
  `danger_id`   BIGINT       NULL COMMENT '不合格联动生成的隐患单 id',
  `sort_no`     INT          NOT NULL DEFAULT 0,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_dci_plan` (`plan_id`, `sort_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隐患排查逐项判定结果';

-- 4. 隐患状态流转日志（H7 五要素 + 全流程留痕）
CREATE TABLE IF NOT EXISTS `danger_flow_log` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   BIGINT       NOT NULL,
  `danger_id`   BIGINT       NOT NULL,
  `action`      VARCHAR(40)  NOT NULL COMMENT 'create/rectify/feedback/accept/reject/remind/correct/overdue',
  `from_status` VARCHAR(20)  NULL,
  `to_status`   VARCHAR(20)  NULL,
  `detail`      VARCHAR(500) NULL,
  `operator`    VARCHAR(50)  NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by`  BIGINT       NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_dfl_danger` (`danger_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隐患闭环状态流转日志';

-- 5. 督办提醒记录（超期/临期/人工督办）
CREATE TABLE IF NOT EXISTS `danger_remind` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   BIGINT       NOT NULL,
  `danger_id`   BIGINT       NOT NULL,
  `remind_type` VARCHAR(20)  NOT NULL DEFAULT 'manual' COMMENT 'near临期 / overdue超期 / manual人工',
  `content`     VARCHAR(500) NULL,
  `operator`    VARCHAR(50)  NULL,
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by`  BIGINT       NULL,
  `deleted`     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_dr_danger` (`danger_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='隐患督办提醒记录';
