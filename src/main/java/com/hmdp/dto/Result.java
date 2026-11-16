package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result component.
 *
 * @author NeighborLife
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private Boolean success;
    private String errorMsg;
    private Object data;
    private Long total;
    private String code;

    public static Result ok(){
        return new Result(true, null, null, null, "OK");
    }
    public static Result ok(Object data){
        return new Result(true, null, data, null, "OK");
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total, "OK");
    }
    public static Result fail(String errorMsg){
        return fail("BUSINESS_ERROR", errorMsg);
    }

    public static Result fail(String code, String message) {
        return new Result(false, message, null, null, code);
    }
}
