package com.wxut.agentplaza.crawler.model;

import lombok.Data;

@Data
public class JobInfo {
    private String id;
    private String name;
    private String keyword;
    private String experience;
    private String education;
    private String description;
    private String salary;
    private String tags;
    private String jobHref;
    private String company;
    private String companyType;
    private String companySize;
    private String hrName;
    private String address;
    private String companyHref;
    private String eid;
    private String raw;
    private String city;
    private String jobSource;
}
