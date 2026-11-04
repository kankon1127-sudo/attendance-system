package com.wkrepo.system.config;

import com.wkrepo.system.TestData;
import com.wkrepo.system.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesTest {

    private final Messages messages = TestData.messages();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void resolvesBusinessExceptionWithArguments() {
        String text = messages.of(new BusinessException("user.error.usernameExists", "taro"));

        assertThat(text).isEqualTo("ユーザー名は既に存在します: taro");
    }

    @Test
    void resolvesExceptionWhoseMessageIsAKey() {
        assertThat(messages.of(new IllegalStateException("monthly.error.notFound"))).isEqualTo("月報が見つかりません。");
    }

    @Test
    void returnsMessageAsIsWhenItIsNotAKey() {
        assertThat(messages.of(new RuntimeException("free text"))).isEqualTo("free text");
        assertThat(messages.of(new RuntimeException())).isEqualTo("処理中にエラーが発生しました。");
    }

    @Test
    void followsScreenLanguage() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);

        assertThat(messages.get("monthly.error.notFound")).isEqualTo("找不到该月报。");
        assertThat(messages.of(new BusinessException("user.error.usernameExists", "taro"))).isEqualTo("用户名已存在：taro");
    }

    @Test
    void japaneseAndChineseFilesHaveTheSameKeys() throws IOException {
        Properties japanese = load("/messages.properties");
        Properties chinese = load("/messages_zh_CN.properties");

        assertThat(chinese.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(japanese.stringPropertyNames());
    }

    private Properties load(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream(path)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
