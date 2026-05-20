package com.test.credit.score.scoring.application;

import com.test.credit.score.scoring.domain.FeatureVector;
import com.test.credit.score.scoring.domain.InferenceResult;
import com.test.credit.score.scoring.domain.ReasonCodeItem;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Maps feature contributions (partial logit values) to regulatory reason codes.
 * Positive contribution → raises PD → NEGATIVE direction (hurts score).
 * Top 4 by absolute contribution magnitude are returned.
 */
@Service
public class ReasonCodeService {

    // featureName → (code, NEGATIVE description, POSITIVE description)
    private static final Map<String, String[]> CODE_MAP = Map.of(
        "bureau.cibil_score",              new String[]{"R01", "Insufficient credit history or low bureau score",   "Strong credit history"},
        "bureau.dpd_last_6m",              new String[]{"R02", "Delinquency on accounts in the past 6 months",     "No recent delinquencies"},
        "bureau.credit_utilization",       new String[]{"R03", "High utilization of revolving credit accounts",    "Low credit utilization"},
        "bureau.inquiry_count_last_90d",   new String[]{"R04", "Too many recent credit inquiries",                 "Low recent inquiry activity"},
        "behavior.upi_txn_count_last_30d", new String[]{"R05", "Limited digital payment activity",                 "Active digital payment behaviour"},
        "behavior.avg_monthly_credit",     new String[]{"R06", "Low or inconsistent monthly income inflows",       "Consistent income inflows"}
    );

    public List<ReasonCodeItem> compute(FeatureVector features, InferenceResult result) {
        Map<String, Double> contributions = result.featureContributions();

        // Sort by absolute contribution descending, take top 4
        List<Map.Entry<String, Double>> sorted = contributions.entrySet().stream()
                .filter(e -> CODE_MAP.containsKey(e.getKey()))
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
                .limit(4)
                .toList();

        List<ReasonCodeItem> items = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Double> entry : sorted) {
            String[] meta = CODE_MAP.get(entry.getKey());
            // Positive contribution to logit = raises PD = NEGATIVE for borrower
            boolean isNegative = entry.getValue() > 0;
            items.add(new ReasonCodeItem(
                    meta[0],
                    isNegative ? meta[1] : meta[2],
                    isNegative ? "NEGATIVE" : "POSITIVE",
                    rank++
            ));
        }
        return items;
    }
}
