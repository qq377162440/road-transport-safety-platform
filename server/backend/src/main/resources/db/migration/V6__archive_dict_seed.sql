-- B1 / B2 归档项字典种子（一人一档 13 项 / 一车一档 13 类）
-- 依据：07 号《人员基础信息 + 一人一档》、10 号《车辆基础信息与一车一档》详细设计
-- person_archive_dict / vehicle_doc_dict 为平台字典表，无 tenant_id / created_by 列。

DELETE FROM `person_archive_dict` WHERE `id` >= 5000;
INSERT INTO `person_archive_dict`
  (`id`,`item_code`,`item_name`,`source_type`,`apply_biz`,`is_optional`,`sort_no`,`created_at`,`deleted`) VALUES
  (5001,'P01','身份证复印件','upload','all',0,1,NOW(),0),
  (5002,'P02','机动车驾驶证','upload','all',0,2,NOW(),0),
  (5003,'P03','道路运输从业人员从业资格证','upload','all',0,3,NOW(),0),
  (5004,'P04','劳动合同或聘用协议','upload','all',0,4,NOW(),0),
  (5005,'P05','安全生产责任书','auto','all',0,5,NOW(),0),
  (5006,'P06','安全生产承诺书','auto','all',0,6,NOW(),0),
  (5007,'P07','岗位危害告知书','auto','all',0,7,NOW(),0),
  (5008,'P08','岗前三级安全教育培训记录','auto','all',0,8,NOW(),0),
  (5009,'P09','两类人员考核合格证明','upload','all',0,9,NOW(),0),
  (5010,'P10','年度安全生产责任制考核记录','auto','all',0,10,NOW(),0),
  (5011,'P11','职业健康检查报告（体检）','upload','all',1,11,NOW(),0),
  (5012,'P12','人员任命书','auto','all',0,12,NOW(),0),
  (5013,'P13','继续教育及培训考核证明','upload','all',0,13,NOW(),0);

DELETE FROM `vehicle_doc_dict` WHERE `id` >= 5000;
INSERT INTO `vehicle_doc_dict`
  (`id`,`doc_code`,`doc_name`,`time_limit`,`apply_biz_type`,`apply_class`,`is_custom`,`created_at`,`deleted`) VALUES
  (5001,'V01','机动车行驶证',1,'all','',0,NOW(),0),
  (5002,'V02','道路运输证',1,'all','',0,NOW(),0),
  (5003,'V03','车辆登记证书',0,'all','',0,NOW(),0),
  (5004,'V04','购车发票',0,'all','',0,NOW(),0),
  (5005,'V05','车辆购置税完税证明',0,'all','',0,NOW(),0),
  (5006,'V06','机动车交通事故责任强制保险',1,'all','',0,NOW(),0),
  (5007,'V07','承运人责任险',1,'all','',0,NOW(),0),
  (5008,'V08','商业保险',1,'all','',0,NOW(),0),
  (5009,'V09','车辆技术等级评定',1,'all','',0,NOW(),0),
  (5010,'V10','二级维护竣工合格证',1,'all','',0,NOW(),0),
  (5011,'V11','车辆综合性能检测报告',1,'all','',0,NOW(),0),
  (5012,'V12','卫星定位装置安装证明',0,'all','',0,NOW(),0),
  (5013,'V13','车辆维修保养记录',0,'all','',0,NOW(),0);
