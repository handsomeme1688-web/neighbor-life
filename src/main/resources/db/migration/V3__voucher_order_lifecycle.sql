-- Add fields/indexes needed by payment and scheduled unpaid-order closing.
ALTER TABLE `tb_voucher_order`
    ADD COLUMN `cancel_time` timestamp NULL DEFAULT NULL COMMENT 'cancellation time' AFTER `refund_time`,
    ADD COLUMN `stock_compensated` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Redis stock compensation: 0 pending, 1 completed' AFTER `cancel_time`,
    ADD KEY `idx_status_create_time` (`status`, `create_time`);
