-- 巡查员档案 MySQL 初始化（
CREATE DATABASE IF NOT EXISTS aifieldcam
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE aifieldcam;

CREATE TABLE IF NOT EXISTS officers (
    employee_id VARCHAR(32) NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    Identity card VARCHAR(18) NOT NULL,
    department VARCHAR(128) NOT NULL,
    phone VARCHAR(16) NULL,
    device_id VARCHAR(128) NOT NULL,
    face_vector MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    updated_at VARCHAR(64) NOT NULL,
    last_device_id VARCHAR(128) NULL,
    resigned_at VARCHAR(64) NULL,
    UNIQUE KEY uq_officers_phone (phone),
    UNIQUE KEY uq_officers_device (device_id),
    KEY idx_officers_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS auth_tokens (
    token VARCHAR(128) NOT NULL PRIMARY KEY,
    employee_id VARCHAR(32) NOT NULL,
    phone VARCHAR(16) NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    KEY idx_auth_tokens_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
