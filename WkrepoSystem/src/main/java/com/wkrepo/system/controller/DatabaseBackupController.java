package com.wkrepo.system.controller;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.DatabaseBackupService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDate;

@Controller
public class DatabaseBackupController {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseBackupController.class);
    private static final long MAX_BACKUP_SIZE = 50L * 1024 * 1024;
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

    @PostMapping("/admin/database-restore")
    public String restoreBackup(
            @RequestParam("backupFile") MultipartFile backupFile,
            @RequestParam(name = "confirmReplace", defaultValue = "false") boolean confirmReplace,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"ADMIN".equals(user.getRole())) {
            return "redirect:/home?error=permission";
        }
        if (!confirmReplace) {
            redirectAttributes.addFlashAttribute("databaseRestoreErrorKey", "database.restore.confirmRequired");
            return "redirect:/user-management";
        }
        if (backupFile.isEmpty() || backupFile.getSize() > MAX_BACKUP_SIZE
                || backupFile.getOriginalFilename() == null
                || !backupFile.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).endsWith(".sql")) {
            redirectAttributes.addFlashAttribute("databaseRestoreErrorKey", "database.restore.invalidFile");
            return "redirect:/user-management";
        }

        try (var inputStream = backupFile.getInputStream()) {
            databaseBackupService.restoreSqlBackup(inputStream);
            redirectAttributes.addFlashAttribute("databaseRestoreSuccess", true);
            logger.warn("Database restored from uploaded SQL backup by administrator {}", user.getId());
        } catch (IOException | SQLException | DataAccessException exception) {
            logger.error("Database restore failed for administrator {}", user.getId(), exception);
            redirectAttributes.addFlashAttribute("databaseRestoreErrorKey", "database.restore.failed");
        }
        return "redirect:/user-management";
    }
}
