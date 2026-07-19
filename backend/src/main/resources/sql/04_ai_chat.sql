-- ============================================================
-- 跨境金融业财核算与智能决策平台 - AI 顾问会话持久化建表脚本
-- 数据库：MySQL 8.0+  字符集：utf8mb4
-- 创建日期：2026-07-15
-- 说明：支撑 DeepSeek 式多轮对话改造（会话持久化 + 上下文记忆）
-- ============================================================

SET NAMES utf8mb4;
USE `cross_finance`;

-- 删除顺序：先删有外键依赖的消息表，再删会话表
DROP TABLE IF EXISTS `ai_message`;
DROP TABLE IF EXISTS `ai_session`;

-- ------------------------------------------------------------
-- 1. AI 会话表
-- ------------------------------------------------------------
CREATE TABLE `ai_session` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `user_id`     BIGINT       NOT NULL COMMENT '所属用户 ID（关联 sys_user.id）',
    `title`       VARCHAR(128) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 顾问会话表';

-- ------------------------------------------------------------
-- 2. AI 消息表
-- ------------------------------------------------------------
CREATE TABLE `ai_message` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `session_id`  BIGINT       NOT NULL COMMENT '所属会话 ID（关联 ai_session.id）',
    `role`        VARCHAR(16)  NOT NULL COMMENT '消息角色：USER / ASSISTANT',
    `content`     TEXT         NOT NULL COMMENT '消息内容',
    `seq_no`      INT          NOT NULL COMMENT '消息序号（会话内自增）',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_by`   BIGINT       DEFAULT NULL COMMENT '创建人 ID',
    `update_by`   BIGINT       DEFAULT NULL COMMENT '更新人 ID',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删除，1 已删除',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_seq_no` (`seq_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 顾问消息表';
