package com.wxut.agentplaza.vo;

import lombok.Data;

@Data
public class UsernameVO {
    private String username;
    /** 显示名（CAS cn 姓名），供 Dify 等新接入方开户显示；锡锡等旧调用方可忽略 */
    private String name;
}
