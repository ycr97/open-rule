package io.openrule.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 共享 MySQL 容器基类；Docker 缺席自动跳过（disabledWithoutDocker）。 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractMySqlIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withInitScript("schema.sql");

    protected JdbcTemplate jdbc;
    protected DataSourceTransactionManager txm;

    @BeforeEach
    void baseSetUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        ds.setDriverClassName(MYSQL.getDriverClassName());
        jdbc = new JdbcTemplate(ds);
        txm = new DataSourceTransactionManager(ds);
        jdbc.execute("DELETE FROM or_execute_log");
        jdbc.execute("DELETE FROM or_flow");
    }
}
