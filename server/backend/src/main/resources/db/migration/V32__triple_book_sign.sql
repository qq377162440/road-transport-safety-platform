-- ============================================================
-- V32 三书签订（承诺书 / 岗位危害告知书）
--
-- 依据（用户 2026-11-11 反馈）：
--  * 三书（责任书 / 承诺书 / 告知书）属于「动态记录」，不是静态范本——
--    通过签订动作产生，而非一次性范本生成；
--  * 企业视角：
--      - 责任书 = 上下级签订后生成（沿用 V31 responsibility_sign 双签）；
--      - 承诺书 = 与公司签订后生成（公司盖章 + 本人签字）；
--      - 告知书 = 本人签订后生成（本人签字即生效）；
--  * 服务商视角：可从范本直接生成成品（不走签订流程）。
--
-- 本表只承载承诺书 / 告知书两种（责任书双签已由 responsibility_sign 承担）。
-- 签订 / 生成后的 PDF 会同步落到 person_archive_item（P06 / P07）的 file_url。
-- ============================================================

-- ------------------------------------------------------------------
-- 1. 三书签订记录（承诺书 / 告知书）
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `triple_book_sign` (
  `id`                    BIGINT PRIMARY KEY,
  `tenant_id`             BIGINT NOT NULL DEFAULT 0,
  `sign_year`             SMALLINT NOT NULL COMMENT '签署年度（一年一签）',
  `book_type`             VARCHAR(20) NOT NULL COMMENT 'PROMISE=安全生产承诺书 / NOTICE=岗位危害告知书',
  `person_id`             BIGINT NOT NULL COMMENT '签署人 person.id',
  `person_name`           VARCHAR(50) COMMENT '签署人姓名（冗余快照）',
  `post_id`               BIGINT COMMENT '签署人岗位 org_post.id',
  `post_name`             VARCHAR(100) COMMENT '签署人岗位名（冗余快照）',
  `company_name`          VARCHAR(100) COMMENT '承诺书签订公司名（盖章方）',
  `sign_name`             VARCHAR(50) COMMENT '电子签署人姓名（本人 / 公司）',
  `sign_image`            VARCHAR(500) COMMENT '电子签名（文字签名）',
  `status`                VARCHAR(20) NOT NULL DEFAULT 'pending'
                          COMMENT 'pending待签 / effective已生效 / void已作废',
  `effective_at`          DATETIME COMMENT '生效时间',
  `pdf_id`                BIGINT COMMENT '生效后归档 PDF（archive_pdf.id）',
  `file_url`              VARCHAR(500) COMMENT 'MinIO 对象名（同步到 person_archive_item.file_url）',
  `created_at`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created_by`            BIGINT NOT NULL DEFAULT 0,
  `deleted`               TINYINT NOT NULL DEFAULT 0,
  KEY `idx_tbs_tenant_year_type` (`tenant_id`, `sign_year`, `book_type`),
  KEY `idx_tbs_person` (`tenant_id`, `person_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='三书签订状态机（承诺书 / 岗位危害告知书）';
