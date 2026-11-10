-- ============================================================
-- V28 自定义文件签署 完整闭环（设计 21 §7.12）
-- 服务商上传地方模板 + 自定义名称 + 归属档案盒 + 拖拽放置盖章/签名锚点，
-- 生成时复用印章引擎（企业章/自动红章）与签字库（历史签字/线上签/打印手签），
-- 合成带盖章+签名的自定义文件并归档到指定档案盒。
-- 注：开发期 dev 库 sign_task 已由早期会话预建，此处 IF NOT EXISTS 仅对全新库生效，
--     不改动已有表的列结构（其列已与设计一致）。
-- ============================================================

CREATE TABLE IF NOT EXISTS `sign_task` (
  `id`           BIGINT PRIMARY KEY,
  `tenant_id`    BIGINT NOT NULL DEFAULT '0',
  `title`        VARCHAR(200) NOT NULL COMMENT '自定义文件名称',
  `source_pdf`   VARCHAR(500) DEFAULT NULL COMMENT '原始模板 PDF（MinIO 对象名）',
  `result_pdf`   VARCHAR(500) DEFAULT NULL COMMENT '合成后结果 PDF',
  `anchors`      LONGTEXT COMMENT '盖章/签名锚点 JSON：[{type:seal|sign,page,x,y,scale,sealType,post,signType,required}]',
  `archive_box`  VARCHAR(30) DEFAULT NULL COMMENT '归属档案盒 code（如 GENERAL_BOX6）',
  `status`       VARCHAR(20) DEFAULT 'draft' COMMENT 'draft/pending/signed',
  `signer`       VARCHAR(50) DEFAULT NULL,
  `signed_at`    DATETIME DEFAULT NULL,
  `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`   BIGINT NOT NULL DEFAULT '0',
  `deleted`      TINYINT NOT NULL DEFAULT '0',
  KEY `idx_sign_task_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自定义文件签署（服务商上传模板）C6';

CREATE TABLE IF NOT EXISTS `sign_flow_log` (
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
