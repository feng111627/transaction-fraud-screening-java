package com.github.feng111627.transactionfraudscreeningjava.web;

import com.github.feng111627.transactionfraudscreeningjava.model.HitRule;
import com.github.feng111627.transactionfraudscreeningjava.model.ReviewRecord;
import com.github.feng111627.transactionfraudscreeningjava.model.RiskEvaluation;
import com.github.feng111627.transactionfraudscreeningjava.model.TxnRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 领域对象 -> 只读 JSON 视图，保证结论中的命中规则与原因完整对外可解释。 */
final class Views {

    private Views() {}

    static Map<String, Object> txn(TxnRecord t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", t.getRequestId());
        m.put("txnId", t.getTxnId());
        m.put("accountId", t.getAccountId());
        m.put("targetId", t.getTargetId());
        m.put("amount", t.getAmount());
        m.put("currency", t.getCurrency());
        m.put("txnTimeEpochMilli", t.getTxnTimeEpochMilli());
        m.put("source", t.getSource());
        m.put("status", t.getStatus());
        m.put("rejectReason", t.getRejectReason());
        if (t.getEvaluation() != null) {
            m.put("evaluation", evaluation(t.getEvaluation()));
        }
        return m;
    }

    static Map<String, Object> evaluation(RiskEvaluation e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("txnId", e.txnId());
        m.put("totalScore", e.totalScore());
        m.put("disposition", e.disposition());
        m.put("configVersion", e.configVersion());
        m.put("evaluatedAtEpochMilli", e.evaluatedAtEpochMilli());
        m.put("hits", e.hits().stream().map(Views::hit).toList());
        return m;
    }

    static Map<String, Object> hit(HitRule h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", h.rule());
        m.put("score", h.score());
        m.put("reason", h.reason());
        m.put("detail", h.detail());
        return m;
    }

    static Map<String, Object> review(ReviewRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reviewId", r.getReviewId());
        m.put("txnId", r.getTxnId());
        m.put("status", r.getStatus());
        m.put("systemDisposition", r.getSystemDisposition());
        m.put("finalDisposition", r.getFinalDisposition());
        m.put("overridden", r.isOverridden());
        m.put("claimedBy", r.getClaimedBy());
        m.put("claimedAtEpochMilli", r.getClaimedAtEpochMilli());
        m.put("resolvedBy", r.getResolvedBy());
        m.put("resolvedAtEpochMilli", r.getResolvedAtEpochMilli());
        m.put("comment", r.getComment());
        return m;
    }

    static List<Map<String, Object>> txns(List<TxnRecord> list) {
        return list.stream().map(Views::txn).toList();
    }
}
