package fr.enimaloc.w2d.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.enimaloc.w2d.config.AppConfig;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;

import javax.sql.DataSource;

public final class Database {
    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    public Database(AppConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.databaseUrl());
        hc.setUsername(config.dbUser());
        hc.setPassword(config.dbPassword());
        this.dataSource = new HikariDataSource(hc);
        try {
            Flyway.configure().dataSource(dataSource).load().migrate();
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        this.jdbi = Jdbi.create(dataSource).installPlugin(new PostgresPlugin());
    }

    public Jdbi jdbi() {
        return jdbi;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public void close() {
        dataSource.close();
    }
}
