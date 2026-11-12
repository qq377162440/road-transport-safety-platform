-- 电子档案柜归档项来源追溯（用户 2026-11-12 需求三）
-- 盒二「制度档案」的单个制度须与 B3 制度档案引擎一一对应，且归档来源有三种：
--   1 = 档案柜手动上传（企业/管理员在档案柜内上传扫描件）
--   2 = B3 制度档案引擎流程产出（起草→审核→发布，发布时套企业印章归档）
--   3 = 服务商强制生成（服务商视角调用企业印章 + 主要负责人签字信息，代企业生成并归档）
-- 新增 source 列，供前端来源徽标与「按来源统计」展示。

ALTER TABLE `archive_item`
    ADD COLUMN `source` TINYINT NOT NULL DEFAULT 0 COMMENT '归档来源：0未知 1档案柜手动上传 2B3流程产出 3服务商强制生成' AFTER `gen_mode`;

-- 历史数据：盒二既有归档项（由 B3 发布产生）统一标记为「B3 流程产出」
UPDATE `archive_item` SET `source` = 2 WHERE `biz_module` LIKE 'institution_%' AND `source` = 0 AND `deleted` = 0;
