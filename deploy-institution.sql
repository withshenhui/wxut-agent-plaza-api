-- ============================================================
-- 无锡职大智能体广场 - 生产环境增量 SQL
-- 内容：制度建设分类 + 9 个制度智能体（external_url 为 NULL）+ 标签 + 分类排序
-- 特性：幂等（可重复执行，不会产生重复数据）
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 < deploy-institution.sql
-- 日期：2026-08-31
-- ============================================================
USE agent_plaza;

-- 1. 分类：制度建设（不存在才插入）
INSERT INTO agent_category (category_key, label, description, sort_order, status)
SELECT 'institution', '制度建设', '学校制度查询与管理相关智能体', 0, 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM agent_category WHERE category_key='institution');

-- 2. 分类排序（幂等：直接赋固定值，制度建设排第一）
UPDATE agent_category SET sort_order=0 WHERE category_key='institution';
UPDATE agent_category SET sort_order=2 WHERE category_key='student';
UPDATE agent_category SET sort_order=3 WHERE category_key='teacher';
UPDATE agent_category SET sort_order=4 WHERE category_key='education';
UPDATE agent_category SET sort_order=5 WHERE category_key='research';
UPDATE agent_category SET sort_order=6 WHERE category_key='logistics';
UPDATE agent_category SET sort_order=7 WHERE category_key='other';

-- 3. 9 个制度智能体（id 40-48 固定防重复；external_url 留 NULL，管理后台编辑）
INSERT INTO agent (id, name, icon, description, detail, category_id, sort_order)
SELECT v.id, v.name, v.icon, v.descr, v.detail, c.id, v.so
FROM (
  SELECT 40 AS id,'党建与思政制度智能体' AS name,'🚩' AS icon,'提供党建与思政制度咨询和查询服务。' AS descr,'这是一个专业的党建与思政制度智能体，可以帮助你了解党建与思政相关制度文件。' AS detail,1 AS so
  UNION ALL SELECT 41,'教育教学管理制度智能体','📚','提供教育教学管理制度的查询和解读。','这是一个专业的教育教学管理制度智能体，可以帮助你查询教育教学管理相关制度。',2
  UNION ALL SELECT 42,'科技研发制度智能体','🔬','提供科技研发制度的查询和咨询服务。','这是一个专业的科技研发制度智能体，可以帮助你了解科研管理相关制度规定。',3
  UNION ALL SELECT 43,'社会服务制度智能体','🤝','提供社会服务制度的查询和咨询服务。','这是一个专业的社会服务制度智能体，可以帮助你了解社会服务相关制度。',4
  UNION ALL SELECT 44,'财务与资产管理制度智能体','💰','提供财务与资产管理制度的查询和解读。','这是一个专业的财务与资产管理制度智能体，可以帮助你了解财务资产相关制度。',5
  UNION ALL SELECT 45,'人事管理制度智能体','👥','提供人事管理制度的查询和解读。','这是一个专业的人事管理制度智能体，可以帮助你了解人事管理相关制度。',6
  UNION ALL SELECT 46,'服务保障制度智能体','🛠️','提供服务保障制度的查询和咨询。','这是一个专业的服务保障制度智能体，可以帮助你了解服务保障相关制度。',7
  UNION ALL SELECT 47,'民主监督制度智能体','⚖️','提供民主监督制度的查询和咨询。','这是一个专业的民主监督制度智能体，可以帮助你了解民主监督相关制度。',8
  UNION ALL SELECT 48,'其他制度智能体','📋','提供其他各类制度的查询和咨询。','这是一个专业的其他制度智能体，可以帮助你了解学校其他相关制度。',9
) v
LEFT JOIN agent_category c ON c.category_key='institution'
WHERE NOT EXISTS (SELECT 1 FROM agent WHERE id=v.id);

-- 4. 标签（agent_id + tag_name 不存在才插入）
INSERT INTO agent_tag (agent_id, tag_name)
SELECT v.aid, v.tag
FROM (
  SELECT 40 AS aid,'党建' AS tag UNION ALL SELECT 40,'思政' UNION ALL SELECT 40,'制度'
  UNION ALL SELECT 41,'教学' UNION ALL SELECT 41,'管理' UNION ALL SELECT 41,'制度'
  UNION ALL SELECT 42,'科研' UNION ALL SELECT 42,'研发' UNION ALL SELECT 42,'制度'
  UNION ALL SELECT 43,'社会' UNION ALL SELECT 43,'服务' UNION ALL SELECT 43,'制度'
  UNION ALL SELECT 44,'财务' UNION ALL SELECT 44,'资产' UNION ALL SELECT 44,'制度'
  UNION ALL SELECT 45,'人事' UNION ALL SELECT 45,'管理' UNION ALL SELECT 45,'制度'
  UNION ALL SELECT 46,'服务' UNION ALL SELECT 46,'保障' UNION ALL SELECT 46,'制度'
  UNION ALL SELECT 47,'民主' UNION ALL SELECT 47,'监督' UNION ALL SELECT 47,'制度'
  UNION ALL SELECT 48,'其他' UNION ALL SELECT 48,'规章' UNION ALL SELECT 48,'制度'
) v
WHERE NOT EXISTS (SELECT 1 FROM agent_tag WHERE agent_id=v.aid AND tag_name=v.tag);
