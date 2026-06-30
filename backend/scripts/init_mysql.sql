-- 巡查员档案 MySQL 初始化
CREATE DATABASE IF NOT EXISTS aifieldcam
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE aifieldcam;

CREATE TABLE IF NOT EXISTS officers (
    employee_id VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '工号(6位数字)',
    name VARCHAR(64) NOT NULL COMMENT '姓名',
    id_card VARCHAR(18) NULL COMMENT '身份证',
    company VARCHAR(128) NULL COMMENT '公司',
    department VARCHAR(128) NOT NULL COMMENT '所属部门',
    position VARCHAR(64) NULL COMMENT '职位',
    phone VARCHAR(16) NULL COMMENT '电话',
    device_id VARCHAR(128) NOT NULL COMMENT '绑定的执法仪ID',
    face_vector MEDIUMTEXT NULL COMMENT '人脸特征',
    status VARCHAR(32) NOT NULL COMMENT '绑定状态',
    created_at VARCHAR(64) NOT NULL COMMENT '创建时间',
    updated_at VARCHAR(64) NOT NULL COMMENT '最近更新时间',
    last_device_id VARCHAR(128) NULL COMMENT '上次绑定设备ID',
    resigned_at VARCHAR(64) NULL COMMENT '注销时间',
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
