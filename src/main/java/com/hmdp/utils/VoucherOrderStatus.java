package com.hmdp.utils;

public final class VoucherOrderStatus {
    public static final int UNPAID = 1;
    public static final int PAID = 2;
    public static final int USED = 3;
    public static final int CANCELED = 4;
    public static final int REFUNDING = 5;
    public static final int REFUNDED = 6;

    private VoucherOrderStatus() {
    }
}
