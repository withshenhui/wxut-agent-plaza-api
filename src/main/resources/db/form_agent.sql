-- 智能填报（form-agent）建表脚本
-- 库：agent_plaza（与 sys_config 同库）

CREATE TABLE IF NOT EXISTS form_template (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL COMMENT '模板名称',
  description VARCHAR(512) DEFAULT '' COMMENT '模板说明',
  source VARCHAR(16) DEFAULT 'preset' COMMENT 'preset=预置 / custom=上传',
  file_type VARCHAR(8) DEFAULT '' COMMENT 'docx/xlsx/pdf',
  file_name VARCHAR(255) DEFAULT '' COMMENT '上传原文件名',
  file_path VARCHAR(255) DEFAULT '' COMMENT '模板原文件路径（classpath:=war内预置），有值即可导出Word',
  fields_json MEDIUMTEXT COMMENT '字段定义数组 JSON',
  field_count INT DEFAULT 0 COMMENT '字段数',
  status TINYINT DEFAULT 1 COMMENT '1=启用 0=停用',
  created_by VARCHAR(64) DEFAULT '',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_ft_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能填报-模板';

-- 存量表升级：补 file_path 列（幂等，重复执行报 Duplicate column 可忽略）
ALTER TABLE form_template ADD COLUMN IF NOT EXISTS file_path VARCHAR(255) DEFAULT '' COMMENT '模板原文件路径（classpath:=war内预置），有值即可导出Word';

CREATE TABLE IF NOT EXISTS form_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(192) NOT NULL COMMENT '任务名称',
  template_id BIGINT NOT NULL,
  template_name VARCHAR(128) DEFAULT '',
  teacher_name VARCHAR(64) DEFAULT '' COMMENT '填报人姓名',
  teacher_id VARCHAR(32) DEFAULT '' COMMENT '填报人工号',
  fill_count INT DEFAULT 0 COMMENT '已填字段数',
  total_count INT DEFAULT 0 COMMENT '总字段数',
  status VARCHAR(16) DEFAULT 'draft' COMMENT 'draft=进行中 todo=待补充 done=已完成',
  export_formats VARCHAR(64) DEFAULT '' COMMENT '已导出格式',
  fields_json MEDIUMTEXT COMMENT '填报结果快照 JSON',
  created_by VARCHAR(64) DEFAULT '',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_fr_created (created_at),
  INDEX idx_fr_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能填报-记录';
