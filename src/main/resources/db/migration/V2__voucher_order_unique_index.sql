-- Execute this once for an existing hmdp database.
-- Clean duplicate historical rows first if the statement reports duplicates.
ALTER TABLE `tb_voucher_order`
    ADD UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`);
