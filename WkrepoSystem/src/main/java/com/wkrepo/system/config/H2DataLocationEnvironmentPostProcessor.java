package com.wkrepo.system.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * H2 の保存先を APP_DATA_DIR で指定可能にする。未指定時はプロジェクトの data/ を使う。
 * AUTO_SERVER は使わない。起動前に古い lock ファイルを削除して接続失敗を防ぐ。
 */
public class H2DataLocationEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path projectRoot = findProjectRoot();
        String configuredDataDir = environment.getProperty("APP_DATA_DIR");
        Path dataDir = configuredDataDir == null || configuredDataDir.isBlank()
                ? projectRoot.resolve("data")
                : Path.of(configuredDataDir);
        dataDir = dataDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("データベースディレクトリを作成できません: " + dataDir, e);
        }

        Path destMv = dataDir.resolve("wkreposystem.mv.db");
        if (!Files.exists(destMv)) {
            copyIfExists(projectRoot.resolve("db").resolve("wkreposystem.mv.db"), destMv);
        }
        if (!Files.exists(destMv)) {
            copyIfExists(projectRoot.resolve("target").resolve("db").resolve("wkreposystem.mv.db"), destMv);
        }

        deleteQuietly(dataDir.resolve("wkreposystem.lock.db"));
        deleteQuietly(projectRoot.resolve("db").resolve("wkreposystem.lock.db"));

        String dbFile = dataDir.resolve("wkreposystem").toAbsolutePath().toString().replace('\\', '/');
        String url = "jdbc:h2:file:" + dbFile + ";DB_CLOSE_DELAY=-1;AUTO_RECONNECT=TRUE";
        environment.getPropertySources().addFirst(new MapPropertySource("wkrepoH2Path", Map.of(
                "spring.datasource.url", url
        )));
        System.out.println("【DB】データベースファイル: " + dbFile + ".mv.db");
    }

    private Path findProjectRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path current = dir;
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return dir;
    }

    private void copyIfExists(Path source, Path dest) {
        if (source == null || !Files.exists(source)) {
            return;
        }
        try {
            Files.copy(source, dest, StandardCopyOption.COPY_ATTRIBUTES);
            System.out.println("【DB】既存データを移行: " + source + " -> " + dest);
        } catch (IOException e) {
            System.err.println("【DB】移行に失敗しました: " + source + " / " + e.getMessage());
        }
    }

    private void deleteQuietly(Path lockFile) {
        try {
            if (Files.exists(lockFile)) {
                Files.delete(lockFile);
                System.out.println("【DB】古いロックを削除: " + lockFile);
            }
        } catch (IOException e) {
            System.err.println("【DB】ロック削除に失敗（他プロセスが起動中の可能性）: " + lockFile);
        }
    }
}
