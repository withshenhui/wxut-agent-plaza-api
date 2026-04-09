CREATE DATABASE IF NOT EXISTS agent_plaza
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE agent_plaza;

CREATE TABLE IF NOT EXISTS sys_user (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    username     VARCHAR(50)  NOT NULL COMMENT '登录用户名',
    password     VARCHAR(255) NOT NULL COMMENT 'BCrypt加密密码',
    nickname     VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    avatar       VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    email        VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    phone        VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    role         VARCHAR(20)  NOT NULL DEFAULT 'user' COMMENT '角色: admin/user',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用, 0=禁用',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户';

CREATE TABLE IF NOT EXISTS agent_category (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    category_key VARCHAR(50)  NOT NULL COMMENT '分类唯一标识',
    label        VARCHAR(100) NOT NULL COMMENT '显示名称',
    description  VARCHAR(500) DEFAULT NULL COMMENT '分类描述',
    icon         VARCHAR(200) DEFAULT NULL COMMENT '分类图标',
    sort_order   INT          NOT NULL DEFAULT 0 COMMENT '排序',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用, 0=禁用',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_key (category_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体分类';

CREATE TABLE IF NOT EXISTS agent (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    name           VARCHAR(100) NOT NULL COMMENT '智能体名称',
    icon           VARCHAR(10)  DEFAULT NULL COMMENT 'Emoji图标',
    description    VARCHAR(500) DEFAULT NULL COMMENT '简短描述',
    detail         TEXT         DEFAULT NULL COMMENT '详细描述',
    category_id    BIGINT       DEFAULT NULL COMMENT 'FK -> agent_category.id',
    external_url   VARCHAR(500) DEFAULT NULL COMMENT '外部链接',
    is_recommended TINYINT      NOT NULL DEFAULT 0 COMMENT '1=推荐, 0=普通',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用, 0=禁用',
    sort_order     INT          NOT NULL DEFAULT 0 COMMENT '排序',
    visit_count    BIGINT       NOT NULL DEFAULT 0 COMMENT '访问次数',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_category_id (category_id),
    KEY idx_status (status),
    CONSTRAINT fk_agent_category FOREIGN KEY (category_id)
        REFERENCES agent_category(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体';

CREATE TABLE IF NOT EXISTS agent_tag (
    id        BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'PK',
    agent_id  BIGINT      NOT NULL COMMENT 'FK -> agent.id',
    tag_name  VARCHAR(50) NOT NULL COMMENT '标签名',
    PRIMARY KEY (id),
    KEY idx_agent_id (agent_id),
    CONSTRAINT fk_agent_tag_agent FOREIGN KEY (agent_id)
        REFERENCES agent(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体标签';

CREATE TABLE IF NOT EXISTS chat_session (
    id         VARCHAR(36)  NOT NULL COMMENT '会话UUID',
    agent_id   BIGINT       NOT NULL COMMENT 'FK -> agent.id',
    user_id    BIGINT       NOT NULL COMMENT 'FK -> sys_user.id',
    title      VARCHAR(200) DEFAULT NULL COMMENT '会话标题',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_id (agent_id),
    KEY idx_user_id (user_id),
    CONSTRAINT fk_session_agent FOREIGN KEY (agent_id)
        REFERENCES agent(id) ON DELETE CASCADE,
    CONSTRAINT fk_session_user FOREIGN KEY (user_id)
        REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天会话';

CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT 'PK',
    session_id VARCHAR(36) NOT NULL COMMENT 'FK -> chat_session.id',
    sender     VARCHAR(20) NOT NULL COMMENT '发送者: user/agent',
    content    TEXT        NOT NULL COMMENT '消息内容',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_session_id (session_id),
    CONSTRAINT fk_message_session FOREIGN KEY (session_id)
        REFERENCES chat_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息';

CREATE TABLE IF NOT EXISTS model (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    name          VARCHAR(100) NOT NULL COMMENT '模型名称',
    provider      VARCHAR(100) DEFAULT NULL COMMENT '提供商',
    type          VARCHAR(50)  DEFAULT NULL COMMENT '模型类型',
    description   TEXT         DEFAULT NULL COMMENT '模型描述',
    release_date  VARCHAR(20)  DEFAULT NULL COMMENT '发布日期',
    category      VARCHAR(20)  NOT NULL DEFAULT 'general' COMMENT 'general/vertical',
    api_docs_url  VARCHAR(500) DEFAULT NULL COMMENT 'API文档地址',
    tryout_url    VARCHAR(500) DEFAULT NULL COMMENT '试用地址',
    icon_url      VARCHAR(500) DEFAULT NULL COMMENT '图标URL',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用, 0=禁用',
    sort_order    INT          NOT NULL DEFAULT 0 COMMENT '排序',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_category (category),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI模型';

CREATE TABLE IF NOT EXISTS model_tag (
    id        BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'PK',
    model_id  BIGINT      NOT NULL COMMENT 'FK -> model.id',
    tag_name  VARCHAR(50) NOT NULL COMMENT '标签名',
    PRIMARY KEY (id),
    KEY idx_model_id (model_id),
    CONSTRAINT fk_model_tag_model FOREIGN KEY (model_id)
        REFERENCES model(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型标签';

CREATE TABLE IF NOT EXISTS sys_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    config_key   VARCHAR(100) NOT NULL COMMENT '配置键',
    config_value TEXT         DEFAULT NULL COMMENT '配置值',
    description  VARCHAR(500) DEFAULT NULL COMMENT '配置描述',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置';

CREATE TABLE IF NOT EXISTS sys_operation_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    user_id     BIGINT       DEFAULT NULL COMMENT '操作用户ID',
    username    VARCHAR(50)  DEFAULT NULL COMMENT '操作用户名',
    operation   VARCHAR(200) DEFAULT NULL COMMENT '操作描述',
    method      VARCHAR(200) DEFAULT NULL COMMENT '请求方法',
    params      TEXT         DEFAULT NULL COMMENT '请求参数',
    ip          VARCHAR(50)  DEFAULT NULL COMMENT '请求IP',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志';

CREATE TABLE IF NOT EXISTS sys_file (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'PK',
    original_name   VARCHAR(255) DEFAULT NULL COMMENT '原始文件名',
    stored_name     VARCHAR(255) DEFAULT NULL COMMENT '存储文件名',
    file_path       VARCHAR(500) DEFAULT NULL COMMENT '文件路径',
    file_size       BIGINT       DEFAULT NULL COMMENT '文件大小(字节)',
    mime_type       VARCHAR(100) DEFAULT NULL COMMENT 'MIME类型',
    upload_user_id  BIGINT       DEFAULT NULL COMMENT '上传用户ID',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件上传';
