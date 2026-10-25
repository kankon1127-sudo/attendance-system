package com.wkrepo.system.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@Service
public class DatabaseBackupService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseBackupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
}
