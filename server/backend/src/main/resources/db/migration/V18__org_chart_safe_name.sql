-- 安全管理架构交付态统一：岗位显示名统一为「安全员」
-- 早期 e2e 改名验收脚本把演示企业 safe 岗位改成「安环主管」且未回滚，
-- 导致架构图 PDF / 屏幕端 / 责任制联动显示不一致。此处统一回标准名「安全员」，
-- 与 V2 种子、org_dict(2002)、OrgChartService.DEMO_NAMES 重置初始态保持一致。
-- 仅修正该历史遗留名，不动企业自行改的其他岗位名。
UPDATE `org_post` SET `post_name` = '安全员'
WHERE `func_key` = 'safe' AND `post_name` = '安环主管';

UPDATE `org_dict` SET `dict_name` = '安全员'
WHERE `id` = 2002 AND `dict_name` = '安环主管';
