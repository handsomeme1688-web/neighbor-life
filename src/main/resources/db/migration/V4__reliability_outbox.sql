-- Apply before deploying the reliability changes. Retain event fences while Kafka can replay events.
CREATE TABLE IF NOT EXISTS `tb_order_event` (
    `order_id` bigint unsigned NOT NULL COMMENT 'reservation/order identity',
    `user_id` bigint unsigned NOT NULL COMMENT 'reservation owner',
    `voucher_id` bigint unsigned NOT NULL COMMENT 'reserved voucher',
    `outcome` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCEEDED/REJECTED',
    `compensated` tinyint unsigned NOT NULL DEFAULT 0 COMMENT 'rejected reservation released',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`order_id`),
    KEY `idx_outcome_compensated_time` (`outcome`, `compensated`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='order event processing fence';

CREATE TABLE IF NOT EXISTS `tb_cache_invalidation` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `shop_id` bigint unsigned NOT NULL COMMENT 'cache subject',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='transactional cache invalidation outbox';

ALTER TABLE `tb_voucher_order`
    ADD KEY `idx_status_compensated_cancel` (`status`, `stock_compensated`, `cancel_time`);

CREATE TABLE IF NOT EXISTS `tb_seckill_initialization` (
    `voucher_id` bigint unsigned NOT NULL COMMENT 'activity identity',
    `stock` int unsigned NOT NULL COMMENT 'initial stock',
    `begin_second` bigint NOT NULL COMMENT 'start epoch second',
    `end_second` bigint NOT NULL COMMENT 'end epoch second',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`voucher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='activity publication outbox';
