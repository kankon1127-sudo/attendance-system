package com.wkrepo.system.controller;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.DatabaseBackupService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
public class DatabaseBackupController {

    private final DatabaseBackupService databaseBackupService;

    public DatabaseBackupController(DatabaseBackupService databaseBackupService) {
        this.databaseBackupService = databaseBackupService;
    }

    @GetMapping("/admin/database-backup")
    public ResponseEntity<StreamingResponseBody> downloadBackup(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!"ADMIN".equals(user.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String filename = "wkrepo-database-" + LocalDate.now() + ".sql";
        StreamingResponseBody responseBody = databaseBackupService::writeSqlBackup;
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "sql", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8)
                        .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(responseBody);
    }
}
