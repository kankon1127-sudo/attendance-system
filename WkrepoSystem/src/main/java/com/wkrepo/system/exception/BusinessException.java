package com.wkrepo.system.exception;

/**
 * 画面に表示する業務エラー。メッセージは messages*.properties のキーと引数で持ち、
 * 表示時に {@link com.wkrepo.system.config.Messages#of(Throwable)} で画面の言語に変換する。
 */
public class BusinessException extends RuntimeException {

    private final String messageKey;
    private final transient Object[] args;

    public BusinessException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = args;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public Object[] getArgs() {
        return args;
    }
}
