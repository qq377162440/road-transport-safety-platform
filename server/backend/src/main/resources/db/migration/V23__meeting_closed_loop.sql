-- V23 B6 安全会议完整流程闭环补充字段 + 流转日志
-- 既有 meeting / meeting_attendee / meeting_record 在 V3 已建，本迁移仅补发布/归档时间字段，
-- 并新增 meeting_flow_log 承载状态机流转留痕。

ALTER TABLE `meeting`
  ADD COLUMN `publish_time` DATETIME NULL COMMENT '发布通知时间（draft -> published）' AFTER `status`,
  ADD COLUMN `archive_time` DATETIME NULL COMMENT '一键归档时间（finished -> archived）' AFTER `publish_time`,
  ADD COLUMN `cancelled_at` DATETIME NULL COMMENT '取消会议时间' AFTER `archive_time`,
  ADD INDEX `idx_meeting_status` (`tenant_id`, `status`);

CREATE TABLE IF NOT EXISTS `meeting_flow_log` (
  `id`           BIGINT       NOT NULL COMMENT '雪花ID',
  `tenant_id`    BIGINT       NOT NULL DEFAULT 0 COMMENT '租户ID',
  `meeting_id`   BIGINT       NOT NULL COMMENT '会议ID',
  `action`       VARCHAR(30)  NOT NULL COMMENT '动作：create/publish/sign/replenish/leave/record/archive/cancel/correct',
  `from_status`  VARCHAR(20)  NULL COMMENT '动作前状态',
  `to_status`    VARCHAR(20)  NULL COMMENT '动作后状态',
  `detail`       VARCHAR(500) NULL COMMENT '说明',
  `operator`     VARCHAR(50)  NULL COMMENT '操作人',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `created_by`   BIGINT       NOT NULL DEFAULT 0 COMMENT '创建人ID',
  `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否1是',
  PRIMARY KEY (`id`),
  KEY `idx_meeting_flow_tenant` (`tenant_id`, `meeting_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='安全会议状态流转日志';
