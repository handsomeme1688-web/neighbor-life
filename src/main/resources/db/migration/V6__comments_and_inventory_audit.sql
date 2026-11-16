CREATE TABLE tb_comment_like (
    comment_id bigint unsigned NOT NULL,
    user_id bigint unsigned NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (comment_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='idempotent comment likes';
CREATE TABLE tb_comment_report (
    comment_id bigint unsigned NOT NULL,
    user_id bigint unsigned NOT NULL,
    reason varchar(128) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (comment_id,user_id), KEY idx_report_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='comment moderation reports';
ALTER TABLE tb_blog_comments ADD KEY idx_blog_comment_cursor (blog_id,id);
CREATE TABLE tb_inventory_log (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    order_id bigint unsigned NOT NULL,
    voucher_id bigint unsigned NOT NULL,
    reason varchar(32) NOT NULL,
    quantity_delta int NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), UNIQUE KEY uk_order_inventory_reason (order_id,reason), KEY idx_inventory_voucher (voucher_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='transactional stock movement ledger';
ALTER TABLE tb_refund ADD UNIQUE KEY uk_refund_provider (provider_refund_id);
