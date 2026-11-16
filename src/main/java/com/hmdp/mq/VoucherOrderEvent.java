package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * VoucherOrderEvent component.
 *
 * @author NeighborLife
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderEvent {
    @javax.validation.constraints.NotNull
    @javax.validation.constraints.Positive
    private Long orderId;
    @javax.validation.constraints.NotNull
    @javax.validation.constraints.Positive
    private Long userId;
    @javax.validation.constraints.NotNull
    @javax.validation.constraints.Positive
    private Long voucherId;

    public VoucherOrder toOrder() {
        if (orderId == null || userId == null || voucherId == null || orderId < 1 || userId < 1 || voucherId < 1) {
            throw new IllegalArgumentException("订单消息身份字段无效");
        }
        return new VoucherOrder()
                .setId(orderId)
                .setUserId(userId)
                .setVoucherId(voucherId);
    }
}
