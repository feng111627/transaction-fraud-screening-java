package com.github.feng111627.transactionfraudscreeningjava.model;

import java.util.List;

/** 一次风险判定的完整结论（含规则版本，保证可复现） */
public record RiskEvaluation(
        String txnId,
        int totalScore,
        Disposition disposition,
        List<HitRule> hits,
        long configVersion,
        long evaluatedAtEpochMilli
) {
}
