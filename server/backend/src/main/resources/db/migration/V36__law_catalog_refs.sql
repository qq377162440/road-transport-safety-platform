-- 法规清单 V36：恢复「制度引用」关系
-- V34 以服务商 88 条完整清单替换 V33 的 14 条种子时，refs 被重置为 []，导致引用条目统计为 0。
-- 本迁移把 V33 中平台真实制度/台账/档案盒的引用关系按法规名补回，实现「制度—法规—原文」可追溯。

UPDATE `law_catalog` SET `refs`='[{"type":"module","name":"安全生产责任制（逐级签订）"},{"type":"tpl","name":"安全生产责任书（各级岗位）"},{"type":"tpl","name":"年度安全生产目标分解表"},{"type":"doc","name":"档案盒一·安全组织架构"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='中华人民共和国安全生产法';
UPDATE `law_catalog` SET `refs`='[{"type":"module","name":"一日三检（出车前/行车中/收车后）"},{"type":"doc","name":"一人一档·机动车驾驶证"},{"type":"doc","name":"一车一档·行驶证/检验记录"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='中华人民共和国道路交通安全法';
UPDATE `law_catalog` SET `refs`='[{"type":"module","name":"应急管理（消防部分）"},{"type":"doc","name":"档案盒七·应急器材台账"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='中华人民共和国消防法';
UPDATE `law_catalog` SET `refs`='[{"type":"doc","name":"一人一档·劳动合同或聘用协议（P04）"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='中华人民共和国劳动合同法';
UPDATE `law_catalog` SET `refs`='[{"type":"doc","name":"档案盒一·道路运输经营许可证"},{"type":"tpl","name":"车辆二级维护计划"},{"type":"module","name":"车辆基础信息·B2"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='中华人民共和国道路运输条例';
UPDATE `law_catalog` SET `refs`='[{"type":"module","name":"危货专项深化·D4"},{"type":"tpl","name":"危货车辆技术管理制度"},{"type":"tpl","name":"危险货物运输告示与应急处置卡"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='危险化学品安全管理条例';
UPDATE `law_catalog` SET `refs`='[{"type":"tpl","name":"事故快报表"},{"type":"tpl","name":"年度事故汇总统计表"},{"type":"module","name":"事故管理"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='生产安全事故报告和调查处理条例';
UPDATE `law_catalog` SET `refs`='[{"type":"doc","name":"一人一档·从业资格证（P03）"},{"type":"doc","name":"一人一档·两类人员考核合格证明（P09）"},{"type":"doc","name":"一人一档·继续教育及培训考核证明（P13）"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='道路运输从业人员管理规定';
UPDATE `law_catalog` SET `refs`='[{"type":"module","name":"动态监控台账·D1"},{"type":"module","name":"监控员岗位（组织架构）"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='道路运输车辆动态监督管理办法';
UPDATE `law_catalog` SET `refs`='[{"type":"module","name":"危货专项深化·D4"},{"type":"tpl","name":"安全生产责任书（押运员）"},{"type":"tpl","name":"安全生产责任书（装卸管理员）"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='危险货物道路运输安全管理办法';
UPDATE `law_catalog` SET `refs`='[{"type":"tpl","name":"生产安全事故应急预案"},{"type":"tpl","name":"年度应急演练计划"},{"type":"tpl","name":"应急值班表"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='生产安全事故应急预案管理办法';
UPDATE `law_catalog` SET `refs`='[{"type":"doc","name":"一人一档·岗前三级安全教育培训记录（P08）"},{"type":"module","name":"培训教育·B14"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='生产经营单位安全培训规定';
UPDATE `law_catalog` SET `refs`='[{"type":"tpl","name":"安全生产费用提取计划"},{"type":"tpl","name":"安全生产费用使用台账"},{"type":"module","name":"安全生产费用·B17"}]' WHERE `tenant_id`=0 AND `deleted`=0 AND `law_name`='企业安全生产费用提取和使用管理办法';
