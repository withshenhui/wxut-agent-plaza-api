package com.wxut.agentplaza.crawler.store;

import cn.hutool.core.util.IdUtil;
import com.wxut.agentplaza.crawler.model.JobInfo;

import java.sql.*;
import java.util.List;

public class MysqlStore {

    private static final String URL = "jdbc:mysql://localhost:3306/agent_plaza?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String USER = "root";
    private static final String PASS = "root";

    private static final String INSERT_SQL =
            "INSERT INTO wxit_job (id, name, keyword, experience, education, description, salary, tags, " +
            "job_href, company, company_type, company_size, hr_name, address, company_href, eid, raw, city, job_source) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE name=VALUES(name), salary=VALUES(salary), description=VALUES(description), " +
            "experience=VALUES(experience), education=VALUES(education), tags=VALUES(tags), raw=VALUES(raw)";

    public MysqlStore() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("MySQL Driver not found", e);
        }
    }

    public void saveBatch(List<JobInfo> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (JobInfo job : jobs) {
                String id = job.getId() != null ? job.getId() : IdUtil.nanoId(20);
                ps.setString(1, id);
                ps.setString(2, job.getName());
                ps.setString(3, job.getKeyword());
                ps.setString(4, job.getExperience());
                ps.setString(5, job.getEducation());
                ps.setString(6, job.getDescription());
                ps.setString(7, job.getSalary());
                ps.setString(8, job.getTags());
                ps.setString(9, job.getJobHref());
                ps.setString(10, job.getCompany());
                ps.setString(11, job.getCompanyType());
                ps.setString(12, job.getCompanySize());
                ps.setString(13, job.getHrName());
                ps.setString(14, job.getAddress());
                ps.setString(15, job.getCompanyHref());
                ps.setString(16, job.getEid());
                ps.setString(17, job.getRaw());
                ps.setString(18, job.getCity());
                ps.setString(19, job.getJobSource());
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            System.out.println("[MysqlStore] Saved " + results.length + " jobs");
        } catch (SQLException e) {
            System.err.println("[MysqlStore] Error saving batch: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
