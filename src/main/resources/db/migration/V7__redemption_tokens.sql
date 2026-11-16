CREATE TABLE tb_order_redemption (
    order_id bigint unsigned NOT NULL,
    code_hash char(64) NOT NULL,
    expire_time timestamp NOT NULL,
    PRIMARY KEY (order_id), KEY idx_redemption_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='hashed short-lived redemption credentials';
UPDATE tb_blog_comments SET status=0 WHERE status IS NULL;
UPDATE tb_blog_comments SET liked=0 WHERE liked IS NULL;
ALTER TABLE tb_blog_comments MODIFY COLUMN status tinyint unsigned NOT NULL DEFAULT 0 COMMENT '0 normal,1 reported,2 hidden,3 deleted';
