package com.walletapi.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public final class OracleTestDatabase {

    public static final String IMAGE = "gvenzl/oracle-free:23.26.4-slim-faststart";

    public static final String OWNER = "wallet_owner";
    public static final String AUDIT = "wallet_audit";
    public static final String APP = "wallet_app";

    private static final String OWNER_PASSWORD = randomPassword();
    private static final String AUDIT_PASSWORD = randomPassword();
    private static final String APP_PASSWORD = randomPassword();

    private static final OracleContainer CONTAINER = new OracleContainer(DockerImageName.parse(IMAGE))
            .withEnv("WALLET_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("WALLET_AUDIT_PASSWORD", AUDIT_PASSWORD)
            .withEnv("WALLET_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("db/init/01-create-schemas.sh", 0755),
                    "/container-entrypoint-initdb.d/01-create-schemas.sh");

    private static boolean ready;

    private OracleTestDatabase() {
    }

    public static synchronized void start() {
        if (ready) {
            return;
        }
        CONTAINER.start();
        migrate(AUDIT, AUDIT_PASSWORD, "db/migration/audit");
        migrate(OWNER, OWNER_PASSWORD, "db/migration/owner");
        ready = true;
    }

    public static String jdbcUrl() {
        start();
        return CONTAINER.getJdbcUrl();
    }

    public static String appPassword() {
        return APP_PASSWORD;
    }

    public static Connection connectAsApp() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), APP, APP_PASSWORD);
    }

    public static Connection connectAsOwner() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), OWNER, OWNER_PASSWORD);
    }

    public static Connection connectAsAudit() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), AUDIT, AUDIT_PASSWORD);
    }

    private static void migrate(String user, String password, String location) {
        Flyway.configure()
                .dataSource(CONTAINER.getJdbcUrl(), user, password)
                .locations("filesystem:" + location)
                .load()
                .migrate();
    }

    private static String randomPassword() {
        return "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
