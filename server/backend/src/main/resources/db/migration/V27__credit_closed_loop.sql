-- ============================================================
-- V27 考核奖惩 + 诚信考核 完整闭环（设计 19）
-- 年度责任制考核：按岗位自动生成考核明细（默认 100 分），
-- 驾驶员联动靶向教育违规减分（人工确认），一键提交/签名/生成台账，
-- 归档盒一（年度责任制考核记录）+ 沉淀一人一档（考核/诚信/奖惩记录）。
-- ============================================================

-- 1. 年度考核主表
CREATE TABLE IF NOT EXISTS `credit_assess` (
  `id`           BIGINT PRIMARY KEY,
  `tenant_id`    BIGINT NOT NULL,
  `year`         INT NOT NULL,
  `assess_name`  VARCHAR(200) NOT NULL,
  `status`       VARCHAR(20) DEFAULT 'draft',   -- draft / confirming / completed
  `ledger_pdf`   VARCHAR(500),
  `created_by`   BIGINT,
  `created_at`   DATETIME DEFAULT NULL,
  `updated_at`   DATETIME DEFAULT NULL,
  `deleted`      TINYINT DEFAULT 0,
  UNIQUE KEY `uk_credit_tenant_year` (`tenant_id`, `year`)
);

-- 2. 考核明细（按岗位一条，默认 100 分）
CREATE TABLE IF NOT EXISTS `credit_assess_item` (
  `id`              BIGINT PRIMARY KEY,
  `tenant_id`       BIGINT NOT NULL,
  `assess_id`       BIGINT NOT NULL,
  `user_id`         BIGINT DEFAULT 0,
  `post_id`         BIGINT DEFAULT 0,
  `post_name`       VARCHAR(50),                 -- 岗位名（快照）
  `targets`         TEXT,                         -- 责任目标快照 JSON（来自 org_post goals/duties/assess_std）
  `score`           INT DEFAULT 100,             -- 应得/满分
  `deduction`       INT DEFAULT 0,               -- 减分（违规联动，人工确认）
  `deduction_note`  VARCHAR(500),                -- 减分说明
  `level`           VARCHAR(20),                 -- excellent/pass/fail
  `sign_image`      VARCHAR(500),                -- 被考核人签字（快照）
  `sign_time`       DATETIME,
  `created_at`      DATETIME DEFAULT NULL,
  `updated_at`      DATETIME DEFAULT NULL,
  `deleted`         TINYINT DEFAULT 0
);

-- 3. 操作留痕
CREATE TABLE IF NOT EXISTS `credit_flow_log` (
  `id`           BIGINT PRIMARY KEY,
  `tenant_id`    BIGINT NOT NULL,
  `biz_type`     VARCHAR(30),
  `biz_id`       BIGINT,
  `action`       VARCHAR(50),
  `from_status`  VARCHAR(20),
  `to_status`    VARCHAR(20),
  `detail`       VARCHAR(500),
  `operator`     VARCHAR(50),
  `created_at`   DATETIME DEFAULT NULL,
  `created_by`   BIGINT,
  `deleted`      TINYINT DEFAULT 0
);

-- 4. 一人一档归档项字典（考核/诚信/违章/奖惩），幂等播种
INSERT IGNORE INTO `person_archive_dict` (`item_code`,`item_name`,`source_type`,`apply_biz`,`is_optional`,`sort_no`,`created_at`,`updated_at`,`deleted`) VALUES
  ('CREDIT_ASSESS',    '年度责任制考核记录', 'credit', 'credit', 0, 30, NOW(), NOW(), 0),
  ('CREDIT_HONEST',    '诚信考核记录',       'credit', 'credit', 1, 31, NOW(), NOW(), 0),
  ('CREDIT_VIOLATION', '违章记录',           'credit', 'credit', 1, 32, NOW(), NOW(), 0),
  ('CREDIT_REWARD',    '奖惩记录',           'credit', 'credit', 1, 33, NOW(), NOW(), 0);
