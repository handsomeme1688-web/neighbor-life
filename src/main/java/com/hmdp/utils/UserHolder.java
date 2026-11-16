package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

/**
 * UserHolder component.
 *
 * @author NeighborLife
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> USER_CONTEXT = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        USER_CONTEXT.set(user);
    }

    public static UserDTO getUser(){
        return USER_CONTEXT.get();
    }

    public static void removeUser(){
        USER_CONTEXT.remove();
    }
}
