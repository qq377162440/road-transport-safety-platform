-- 法规原文库 V35（用户 2026-11-11 需求：上传法规原文到系统，以供查询和标记引用）
-- 存储服务商上传的法规原文（PDF/DOCX/DOC/TXT/RAR 等），文件本体存 MinIO，元数据与引用标记存本表。
-- 权限：服务商(provider)/超管(admin) 上传、标记引用、删除；企业(enterprise) 只读查询 + 在线预览/下载。
-- law_id 关联 law_catalog.id，ref_note 记录引用该原文的制度 / 条款 / 章节，实现「制度—法规—原文」三级可追溯。
CREATE TABLE IF NOT EXISTS `law_document` (
    `id`            BIGINT        NOT NULL,
    `tenant_id`     BIGINT        NOT NULL DEFAULT 0 COMMENT '0=平台内置原文库，所有企业可见',
    `file_name`     VARCHAR(300)  NOT NULL COMMENT '原始文件名',
    `object_name`   VARCHAR(400)  NOT NULL COMMENT 'MinIO 对象名 law_docs/...',
    `ext`           VARCHAR(20)   NOT NULL DEFAULT '' COMMENT 'pdf/docx/doc/txt/rar',
    `file_size`     BIGINT        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    `content_type`  VARCHAR(120)  NOT NULL DEFAULT 'application/octet-stream',
    `content_text`  LONGTEXT      COMMENT '提取的正文（供全文查询；扫描版 PDF 为空）',
    `text_len`      INT           NOT NULL DEFAULT 0 COMMENT '正文字符数，0 表示扫描件未提取到文字',
    `law_id`        BIGINT        NOT NULL DEFAULT 0 COMMENT '关联 law_catalog.id；0=未关联',
    `law_name`      VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '冗余关联法规名（标记引用时写入）',
    `ref_note`      VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '引用标记：引用该原文的制度/条款/章节',
    `doc_tag`       VARCHAR(40)   NOT NULL DEFAULT '' COMMENT '版本标记：现行 / 征求意见稿 / 送审稿 / 附件 / 提醒',
    `sort_no`       INT           NOT NULL DEFAULT 0,
    `created_at`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `created_by`    BIGINT        NOT NULL DEFAULT 0,
    `deleted`       TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_tenant` (`tenant_id`, `deleted`, `sort_no`),
    KEY `idx_law` (`law_id`, `deleted`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '法规原文库（原文文件 + 引用标记）';
