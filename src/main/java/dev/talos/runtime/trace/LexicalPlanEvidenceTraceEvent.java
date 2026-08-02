package dev.talos.runtime.trace;

import dev.talos.runtime.TurnPolicyTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Schema-v5-compatible event carrying bounded typed lexical-plan evidence. */
public final class LexicalPlanEvidenceTraceEvent {
    public static final String TYPE = "LEXICAL_PLAN_EVIDENCE_RECORDED";
    public static final int SCHEMA_VERSION = 1;

    private LexicalPlanEvidenceTraceEvent() {}

    public static TurnTraceEvent create(
            String timestamp,
            TurnPolicyTrace trace
    ) {
        TurnPolicyTrace safe = trace == null ? TurnPolicyTrace.empty() : trace;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", SCHEMA_VERSION);
        data.put("decisions", decisionRows(safe.lexicalDecisions()));
        data.put("regions", regionRows(safe.lexicalRegions()));
        return TurnTraceEvent.simple(TYPE, timestamp, data);
    }

    public static boolean hasEvidence(TurnPolicyTrace trace) {
        return trace != null && (!trace.lexicalDecisions().isEmpty()
                || !trace.lexicalRegions().isEmpty());
    }

    private static List<Map<String, Object>> decisionRows(
            List<TurnPolicyTrace.LexicalDecisionEvidence> decisions
    ) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (TurnPolicyTrace.LexicalDecisionEvidence decision : decisions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ordinal", decision.ordinal());
            row.put("owner", decision.owner());
            row.put("path", decision.path());
            row.put("pathSha256", decision.pathSha256());
            row.put("storedPathSha256", decision.storedPathSha256());
            row.put("authority", decision.authority());
            row.put("action", decision.action());
            row.put("actionFamily", decision.actionFamily());
            row.put("source", decision.source());
            row.put("evidenceState", decision.evidenceState());
            row.put("sourceRequestSha256", decision.sourceRequestSha256());
            row.put("sourceRequestChars", decision.sourceRequestChars());
            row.put("sourceRequestBytes", decision.sourceRequestBytes());
            row.put("actionStartOffset", decision.actionStartOffset());
            row.put("actionEndOffset", decision.actionEndOffset());
            row.put("targetStartOffset", decision.targetStartOffset());
            row.put("startOffset", decision.startOffset());
            row.put("authorityEndOffset", decision.authorityEndOffset());
            row.put("endOffset", decision.endOffset());
            row.put("selectedForPath", decision.selectedForPath());
            row.put("activeInResolution", decision.activeInResolution());
            row.put("sourceSpanSha256", decision.sourceSpanSha256());
            row.put("occurrenceSha256", decision.occurrenceSha256());
            rows.add(Map.copyOf(row));
        }
        return List.copyOf(rows);
    }

    private static List<Map<String, Object>> regionRows(
            List<TurnPolicyTrace.LexicalRegionEvidence> regions
    ) {
        ArrayList<Map<String, Object>> rows = new ArrayList<>();
        for (TurnPolicyTrace.LexicalRegionEvidence region : regions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ordinal", region.ordinal());
            row.put("owner", region.owner());
            row.put("kind", region.kind());
            row.put("source", region.source());
            row.put("evidenceState", region.evidenceState());
            row.put("sourceRequestSha256", region.sourceRequestSha256());
            row.put("sourceRequestChars", region.sourceRequestChars());
            row.put("sourceRequestBytes", region.sourceRequestBytes());
            row.put("action", region.action());
            row.put("pathSha256", region.pathSha256());
            row.put("startOffset", region.startOffset());
            row.put("endOffset", region.endOffset());
            row.put("sourceSpanSha256", region.sourceSpanSha256());
            row.put("regionSha256", region.regionSha256());
            rows.add(Map.copyOf(row));
        }
        return List.copyOf(rows);
    }
}
