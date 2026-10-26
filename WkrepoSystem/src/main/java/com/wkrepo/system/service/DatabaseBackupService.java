package com.wkrepo.system.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

@Service
public class DatabaseBackupService {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public DatabaseBackupService(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    public void writeSqlBackup(OutputStream outputStream) throws IOException {
        try {
            jdbcTemplate.query("SCRIPT", resultSet -> {
                while (resultSet.next()) {
                    String statement = resultSet.getString(1);
                    if (statement != null) {
                        try {
                            outputStream.write(statement.getBytes(StandardCharsets.UTF_8));
                            outputStream.write('\n');
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    }
                }
                return null;
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    public synchronized void restoreSqlBackup(InputStream inputStream) throws IOException, SQLException {
        Path tempDirectory = Files.createTempDirectory("wkrepo-restore-");
        Path uploadedScript = tempDirectory.resolve("uploaded.sql");
        Path currentBackup = tempDirectory.resolve("current.sql");
        boolean currentBackupReady = false;
        boolean safeToDeleteCurrentBackup = false;
        try {
            Files.copy(inputStream, uploadedScript, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            validateScript(uploadedScript);
            try (OutputStream outputStream = Files.newOutputStream(currentBackup)) {
                writeSqlBackup(outputStream);
            }
            currentBackupReady = true;

            try {
                executeScriptOnCurrentDatabase(uploadedScript);
                safeToDeleteCurrentBackup = true;
            } catch (RuntimeException | IOException | SQLException restoreFailure) {
                try {
                    executeScriptOnCurrentDatabase(currentBackup);
                    safeToDeleteCurrentBackup = true;
                } catch (RuntimeException | IOException | SQLException rollbackFailure) {
                    restoreFailure.addSuppressed(rollbackFailure);
                }
                throw restoreFailure;
            }
        } finally {
            Files.deleteIfExists(uploadedScript);
            if (safeToDeleteCurrentBackup || !currentBackupReady) {
                Files.deleteIfExists(currentBackup);
                Files.deleteIfExists(tempDirectory);
            } else {
                System.err.println("Database restore rollback failed; previous database backup retained at "
                        + currentBackup);
            }
        }
    }

    private void validateScript(Path script) throws IOException, SQLException {
        String validationUrl = "jdbc:h2:mem:wkrepo-restore-validation-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(validationUrl, "sa", "")) {
            runScript(connection, script);
        }
    }

    private void executeScriptOnCurrentDatabase(Path script) throws IOException, SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("DROP ALL OBJECTS");
            }
            runScript(connection, script);
        }
    }

    private void runScript(Connection connection, Path script) throws SQLException {
        String scriptPath = script.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        try (var statement = connection.createStatement()) {
            statement.execute("RUNSCRIPT FROM '" + scriptPath + "' CHARSET 'UTF-8'");
        }
    }
}
