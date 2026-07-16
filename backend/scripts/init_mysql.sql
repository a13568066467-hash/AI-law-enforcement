-- 巡查员档案 + 执法仪台账 + 扫码绑定 MySQL 初始化
-- 与 officer_db.init_db() 保持一致；已有库可仅靠启动时 migrate 补列/补表

CREATE DATABASE IF NOT EXISTS aifieldcam
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE aifieldcam;

-- ---------------------------------------------------------------------------
-- 人员权威档案
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS officers (
    employee_id VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '工号(6位数字)',
    name VARCHAR(64) NOT NULL COMMENT '姓名',
    gender VARCHAR(8) NOT NULL DEFAULT '未知' COMMENT '性别',
    id_card VARCHAR(32) NULL COMMENT '身份证',
    company VARCHAR(128) NULL COMMENT '公司',
    department VARCHAR(128) NOT NULL COMMENT '所属部门',
    position VARCHAR(64) NULL COMMENT '职位',
    phone VARCHAR(16) NULL COMMENT '电话',
    device_id VARCHAR(128) NOT NULL COMMENT '占位或历史绑定 DSJ；当前占用见 device_occupancy',
    face_vector MEDIUMTEXT NULL COMMENT '人脸特征 JSON',
    status TINYINT NOT NULL DEFAULT 2 COMMENT '0已离职 1在岗 2注册办理中',
    created_at VARCHAR(64) NOT NULL COMMENT '创建时间',
    updated_at VARCHAR(64) NOT NULL COMMENT '最近更新时间',
    last_device_id VARCHAR(128) NULL COMMENT '上次绑定设备ID',
    resigned_at VARCHAR(64) NULL COMMENT '注销时间',
    UNIQUE KEY uq_officers_phone (phone),
    UNIQUE KEY uq_officers_device (device_id),
    UNIQUE KEY uq_officers_id_card (id_card),
    KEY idx_officers_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 会话 token（手机登录 + 绑定后 session）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auth_tokens (
    token VARCHAR(128) NOT NULL PRIMARY KEY,
    employee_id VARCHAR(32) NOT NULL,
    phone VARCHAR(16) NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    KEY idx_auth_tokens_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 执法仪台账
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS recorders (
    device_id VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '设备编号 DSJ-xxx',
    device_name VARCHAR(128) NOT NULL COMMENT '设备名称/别名',
    model VARCHAR(64) NOT NULL DEFAULT 'DSJ-ZECN6A1' COMMENT '型号',
    company VARCHAR(128) NULL COMMENT '入库所属公司',
    in_use TINYINT(1) NOT NULL DEFAULT 0 COMMENT '展示：是否被占用',
    employee_id VARCHAR(32) NULL COMMENT '展示：当前工号',
    is_faulty TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否故障损坏',
    fault_note VARCHAR(512) NULL COMMENT '故障说明',
    binding_phase TINYINT NOT NULL DEFAULT 0 COMMENT '0空闲 1办理中 2在岗',
    bound_at VARCHAR(64) NULL COMMENT '最近绑定时间',
    unbound_at VARCHAR(64) NULL COMMENT '最近解绑时间',
    last_seen_at VARCHAR(64) NULL COMMENT '最后在线时间',
    remark VARCHAR(512) NULL COMMENT '备注',
    created_at VARCHAR(64) NOT NULL,
    updated_at VARCHAR(64) NOT NULL,
    KEY idx_recorders_in_use (in_use),
    KEY idx_recorders_employee (employee_id),
    KEY idx_recorders_faulty (is_faulty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 扫码绑定：短期 token
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_bind_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id VARCHAR(128) NOT NULL,
    token VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/consumed/expired',
    expires_at VARCHAR(64) NOT NULL,
    session_token VARCHAR(128) NULL,
    employee_id VARCHAR(32) NULL,
    reject_reason VARCHAR(512) NULL,
    created_at VARCHAR(64) NOT NULL,
    UNIQUE KEY uq_bind_token (token),
    KEY idx_bind_device (device_id),
    KEY idx_bind_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 扫码绑定：当前占用（执勤权威）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_occupancy (
    device_id VARCHAR(128) NOT NULL PRIMARY KEY,
    employee_id VARCHAR(32) NOT NULL,
    started_at VARCHAR(64) NOT NULL,
    session_token VARCHAR(128) NOT NULL,
    UNIQUE KEY uq_occupancy_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 扫码绑定：使用历史
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS device_usage_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id VARCHAR(128) NOT NULL,
    employee_id VARCHAR(32) NOT NULL,
    started_at VARCHAR(64) NOT NULL,
    ended_at VARCHAR(64) NOT NULL,
    end_reason VARCHAR(32) NOT NULL COMMENT 'unbind/shutdown/admin/migrate',
    KEY idx_usage_device (device_id),
    KEY idx_usage_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
