package com.wkrepo.system.service;

import com.wkrepo.system.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 交通費・諸経費の金額チェック（1 件あたり）。
 * - 0 より大きいこと（小数は 2 桁まで：DB の列定義に合わせる）
 * - 上限は app.expense.max-amount（既定 50,000 円）以下であること。0 以下を設定すると上限なし
 */
@Component
public class ExpenseAmountPolicy {

    private final BigDecimal maxAmount;

    public ExpenseAmountPolicy(@Value("${app.expense.max-amount:50000}") BigDecimal maxAmount) {
        this.maxAmount = maxAmount;
    }

    /** 上限（なしの場合は null）。画面の入力欄の max 属性にも使う */
    public BigDecimal getMaxAmount() {
        return maxAmount == null || maxAmount.signum() <= 0 ? null : maxAmount;
    }

    public void validate(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("expense.error.amountNotPositive");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("application.error.invalidRequest");
        }
        BigDecimal max = getMaxAmount();
        if (max != null && amount.compareTo(max) > 0) {
            throw new BusinessException("expense.error.amountTooLarge", NumberFormat.getNumberInstance(Locale.JAPAN).format(max));
        }
    }
}
