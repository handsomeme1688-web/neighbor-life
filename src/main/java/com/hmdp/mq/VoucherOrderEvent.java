package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderEvent {
    private Long orderId;
    private Long userId;
    private Long voucherId;

    public VoucherOrder toOrder() {
        return new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId);
    }
}
