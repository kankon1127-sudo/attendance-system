package com.wkrepo.system.config;

import com.wkrepo.system.exception.BusinessException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 画面に表示する文言を、現在の画面の言語（?lang= で切り替え）で取得する。
 */
@Component
public class Messages {

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String get(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /**
     * 例外を表示用の文言に変換する。
     * BusinessException はキーと引数から、それ以外はメッセージをキーとして解決し、
     * キーでなければ（旧来の文言や英語のメッセージ）そのまま返す。
     */
    public String of(Throwable e) {
        if (e instanceof BusinessException be) {
            return get(be.getMessageKey(), be.getArgs());
        }
        String message = e.getMessage();
        if (message == null) {
            return get("common.error.unexpected");
        }
        return messageSource.getMessage(message, null, message, LocaleContextHolder.getLocale());
    }
}
