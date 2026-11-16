ALTER TABLE tb_voucher_order
    ADD COLUMN pay_value bigint unsigned NULL COMMENT 'immutable price in cents',
    ADD COLUMN expire_time timestamp NULL COMMENT 'payment deadline',
    ADD KEY idx_status_expire (status, expire_time);
UPDATE tb_voucher_order o JOIN tb_voucher v ON o.voucher_id = v.id SET o.pay_value = v.pay_value WHERE o.pay_value IS NULL;
UPDATE tb_voucher_order SET expire_time = DATE_ADD(create_time, INTERVAL 15 MINUTE) WHERE expire_time IS NULL;

CREATE TABLE tb_user_role (
    user_id bigint unsigned NOT NULL,
    role varchar(32) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='database-backed roles';
CREATE TABLE tb_shop_member (
    shop_id bigint unsigned NOT NULL,
    user_id bigint unsigned NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (shop_id, user_id), KEY idx_member_user (user_id, shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='merchant shop access';
CREATE TABLE tb_audit_log (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    actor_id bigint unsigned NOT NULL,
    action varchar(64) NOT NULL,
    resource_id varchar(64) NOT NULL,
    before_data text NULL,
    after_data text NULL,
    trace_id varchar(64) NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), KEY idx_actor_time (actor_id, create_time), KEY idx_action_time (action, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='business administration audit';
CREATE TABLE tb_payment (
    order_id bigint unsigned NOT NULL,
    amount bigint unsigned NOT NULL,
    currency char(3) NOT NULL DEFAULT 'CNY',
    state varchar(24) NOT NULL DEFAULT 'CREATED',
    transaction_id varchar(64) NULL,
    code_url varchar(1024) NULL,
    last_error varchar(256) NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id), UNIQUE KEY uk_payment_transaction (transaction_id), KEY idx_payment_state_time (state, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='payment ledger';
CREATE TABLE tb_payment_notification (
    notification_id varchar(128) NOT NULL,
    order_id bigint unsigned NOT NULL,
    transaction_id varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (notification_id), KEY idx_notification_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='verified notification identities';
CREATE TABLE tb_refund (
    refund_no varchar(64) NOT NULL,
    order_id bigint unsigned NOT NULL,
    amount bigint unsigned NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'PENDING',
    reason varchar(128) NOT NULL,
    provider_refund_id varchar(64) NULL,
    attempts int unsigned NOT NULL DEFAULT 0,
    next_attempt_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error varchar(256) NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (refund_no), UNIQUE KEY uk_refund_order (order_id), KEY idx_refund_due (state, next_attempt_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='durable full-refund outbox';
CREATE TABLE tb_order_close_task (
    order_id bigint unsigned NOT NULL,
    due_time timestamp NOT NULL,
    next_attempt_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    attempts int unsigned NOT NULL DEFAULT 0,
    last_error varchar(256) NULL,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id), KEY idx_close_due (state, due_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='persistent deadline scheduler';
CREATE TABLE tb_blog_like (
    blog_id bigint unsigned NOT NULL,
    user_id bigint unsigned NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (blog_id, user_id), KEY idx_like_time (blog_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='idempotent blog likes';
CREATE TABLE tb_user_follow (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    user_id bigint unsigned NOT NULL,
    follow_user_id bigint unsigned NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id), UNIQUE KEY uk_follow_pair (user_id, follow_user_id), KEY idx_follow_fans (follow_user_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='idempotent follow relationships';
INSERT INTO tb_user_follow(user_id, follow_user_id, create_time)
SELECT user_id, follow_user_id, MIN(create_time) FROM tb_follow GROUP BY user_id, follow_user_id;
CREATE TABLE tb_feed_delivery (
    blog_id bigint unsigned NOT NULL,
    author_id bigint unsigned NOT NULL,
    last_user_id bigint unsigned NOT NULL DEFAULT 0,
    published_ms bigint NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (blog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='resumable follower fanout';
CREATE TABLE tb_sms_delivery (
    id varchar(64) NOT NULL,
    phone varchar(20) NOT NULL,
    biz_id varchar(128) NOT NULL,
    send_date char(8) NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'PENDING',
    last_error varchar(256) NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id), KEY idx_sms_pending (state, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SMS delivery receipt polling';
ALTER TABLE tb_cache_invalidation ADD COLUMN resource_type varchar(16) NOT NULL DEFAULT 'SHOP';
ALTER TABLE tb_order_event ADD COLUMN failure_reason varchar(128) NULL;
ALTER TABLE tb_voucher_order ADD KEY idx_voucher_status (voucher_id, status);
CREATE TABLE tb_message_quarantine (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    payload_hash char(64) NOT NULL,
    payload text NOT NULL,
    reason varchar(128) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id), UNIQUE KEY uk_quarantine_hash (payload_hash), KEY idx_quarantine_state (state, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='durable poison-message quarantine';
