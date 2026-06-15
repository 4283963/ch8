-- ============================================================
-- 多联式中央空调冷凝水集水盘防溢流系统 - 数据库初始化脚本
-- Database: mall_ac
-- ============================================================

CREATE DATABASE IF NOT EXISTS mall_ac
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE mall_ac;

-- ============================================================
-- 空调设备表：存储每台内机的基本信息和实时状态
-- ============================================================
DROP TABLE IF EXISTS ac_device;
CREATE TABLE ac_device (
    id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id       VARCHAR(32)     NOT NULL COMMENT '设备唯一编号，如 AC-001-F1',
    location        VARCHAR(128)    DEFAULT NULL COMMENT '设备位置描述，如 1F-北区',
    gateway_id      VARCHAR(64)     DEFAULT NULL COMMENT '所属网关ID',
    current_level_mm DOUBLE         NOT NULL DEFAULT 0.0 COMMENT '当前液位（毫米）',
    current_pump_speed INT          NOT NULL DEFAULT 0 COMMENT '当前水泵转速百分比（0-100）',
    status          VARCHAR(32)     DEFAULT 'NORMAL' COMMENT '状态：NORMAL/ELEVATED/WARNING/DANGER/OVERFLOW/MANUAL/OFFLINE',
    last_update_time DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_device_id (device_id),
    KEY idx_gateway_id (gateway_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='空调内机设备表';

-- ============================================================
-- 液位历史表：记录每秒的液位采样数据和控制决策
-- ============================================================
DROP TABLE IF EXISTS level_history;
CREATE TABLE level_history (
    id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id       VARCHAR(32)     NOT NULL COMMENT '设备ID',
    liquid_level_mm DOUBLE          NOT NULL COMMENT '液位（毫米）',
    level_slope     DOUBLE          NOT NULL DEFAULT 0.0 COMMENT '液位上升斜率（mm/s）',
    pump_speed_percent INT          NOT NULL DEFAULT 0 COMMENT '控制的水泵转速百分比',
    control_reason  VARCHAR(256)    DEFAULT NULL COMMENT '控制决策说明',
    sensor_status   INT             NOT NULL DEFAULT 0 COMMENT '传感器状态码：0正常',
    record_time     DATETIME        NOT NULL COMMENT '采样时间',
    KEY idx_device_time (device_id, record_time),
    KEY idx_record_time (record_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='液位历史记录表';

-- ============================================================
-- 水泵控制日志表：记录每次下发控制指令及其执行结果
-- ============================================================
DROP TABLE IF EXISTS pump_control_log;
CREATE TABLE pump_control_log (
    id              BIGINT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id       VARCHAR(32)     NOT NULL COMMENT '设备ID',
    target_speed_percent INT        NOT NULL COMMENT '目标转速百分比',
    actual_speed_percent INT        NOT NULL COMMENT '实际执行转速百分比',
    reason          VARCHAR(256)    DEFAULT NULL COMMENT '控制原因',
    success         TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否执行成功',
    message         VARCHAR(512)    DEFAULT NULL COMMENT '执行结果信息',
    control_time    DATETIME        NOT NULL COMMENT '控制时间',
    KEY idx_pump_device_time (device_id, control_time),
    KEY idx_pump_success (success)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='水泵控制日志表';

-- ============================================================
-- 初始化测试数据
-- ============================================================
INSERT INTO ac_device (device_id, location, gateway_id, status) VALUES
('AC-001-F1', '1F-北区-化妆品区',  'GATEWAY-001', 'NORMAL'),
('AC-002-F1', '1F-南区-珠宝区',    'GATEWAY-001', 'NORMAL'),
('AC-003-F2', '2F-东区-服装区',    'GATEWAY-001', 'NORMAL'),
('AC-004-F2', '2F-西区-鞋包区',    'GATEWAY-001', 'NORMAL'),
('AC-005-F3', '3F-餐饮区-美食广场', 'GATEWAY-001', 'NORMAL'),
('AC-006-F3', '3F-餐饮区-火锅店',  'GATEWAY-001', 'NORMAL'),
('AC-007-F4', '4F-影院区',         'GATEWAY-001', 'NORMAL'),
('AC-008-B1', 'B1-超市区',         'GATEWAY-001', 'NORMAL');

-- ============================================================
-- 历史数据分区建议（生产环境使用）
-- ============================================================
-- ALTER TABLE level_history
-- PARTITION BY RANGE (TO_DAYS(record_time)) (
--     PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
--     PARTITION p202607 VALUES LESS THAN (TO_DAYS('2026-08-01')),
--     PARTITION p_future VALUES LESS THAN MAXVALUE
-- );
