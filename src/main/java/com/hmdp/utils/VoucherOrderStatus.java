package com.hmdp.utils;

/**
 * VoucherOrderStatus component.
 *
 * @author NeighborLife
 */
public final class VoucherOrderStatus {
    public static final int UNPAID = 1;
    public static final int PAID = 2;
    public static final int USED = 3;
    public static final int CANCELED = 4;
    public static final int REFUNDING = 5;
    public static final int REFUNDED = 6;
    public static final int CLOSING = 7;

    private VoucherOrderStatus() {
    }
}
