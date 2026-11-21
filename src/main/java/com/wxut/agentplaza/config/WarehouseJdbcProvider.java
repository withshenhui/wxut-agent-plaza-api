package com.wxut.agentplaza.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数仓（210.28.145.81:5431/datav）共享只读连接池。
 * 2026-11-21 RBAC 教训：此前资助/场景/学院同步各自建池，单个 war 最多占 10 条
 * usr_gxai 连接，把数仓角色配额（too many connections for role "usr_gxai"）挤爆。
 * 全后端共用此一个池（max 4，低水位 1，空闲回收）。
 */
@Slf4j
@Component
public class WarehouseJdbcProvider {

    @Value("${warehouse.url:jdbc:postgresql://210.28.145.81:5431/datav}")
    private String url;

    @Value("${warehouse.username:usr_gxai}")
    private String username;

    @Value("${warehouse.password:}")
    private String password;

    private volatile JdbcTemplate jt;

    public JdbcTemplate get() {
        if (jt == null) {
            synchronized (this) {
                if (jt == null) {
                    try { Class.forName("org.postgresql.Driver"); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
                    HikariDataSource ds = new HikariDataSource();
                    ds.setDriverClassName("org.postgresql.Driver");
                    ds.setJdbcUrl(url);
                    ds.setUsername(username);
                    ds.setPassword(password);
                    ds.setMaximumPoolSize(4);
                    ds.setMinimumIdle(1);
                    ds.setIdleTimeout(120000);
                    ds.setReadOnly(true);
                    ds.setConnectionInitSql("SET default_transaction_read_only = on; SET statement_timeout = '60s'");
                    ds.setPoolName("warehouse-readonly-pool");
                    jt = new JdbcTemplate(ds);
                    log.info("warehouse shared pool initialized (max=4, minIdle=1)");
                }
            }
        }
        return jt;
    }
}
