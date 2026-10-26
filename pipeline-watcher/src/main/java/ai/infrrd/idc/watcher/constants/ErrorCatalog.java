package ai.infrrd.idc.watcher.constants;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Known ERROR log signatures across the platform, matched by {@link ai.infrrd.idc.watcher.check.LogErrorCheck}.
 *
 * Built 2026-10-21 from ~20k real ERROR lines (6-24h, 8 prods), debug-refs/failure-patterns.md and
 * the TITAN REPOS source. Counts in comments are what one prod logged in that sample, so
 * {@code minCount} sits above the normal background level and only real jumps reach the board.
 *
 * Levels:
 *  CRITICAL / WARNING : shown in "Firing now" once {@code minCount} matches in the 15-min window
 *  INFO               : never fires; listed in the "Known errors" panel with counts
 *  IGNORE             : excluded inside the Loki query itself (pure noise, never fetched)
 *
 * Adding an entry: put specific patterns before generic ones (first match wins).
 */
public final class ErrorCatalog {

    public enum Level { CRITICAL, WARNING, INFO, IGNORE }

    /**
     * @param service  Loki {@code instance} label to restrict to, or null for any service
     * @param match    regex searched in the raw line; for IGNORE it must be a plain literal (used in LogQL {@code !=})
     * @param minCount matches per 15-min window before CRITICAL/WARNING fires
     */
    public record KnownError(String id, String service, Pattern match, Level level, int minCount,
            String title, String meaning) {
    }

    private static KnownError e(String id, String service, String regex, Level level, int minCount,
            String title, String meaning) {
        return new KnownError(id, service, Pattern.compile(regex), level, minCount, title, meaning);
    }

    private static KnownError ignore(String id, String literal) {
        return new KnownError(id, null, Pattern.compile(Pattern.quote(literal)), Level.IGNORE, 0, id, "noise");
    }

    public static final List<KnownError> ALL = List.of(

            // ---------------------------------------------------------------- CRITICAL: client impact
            e("gemini-default-segment", "cidc-classification-wrapper",
                    "Gemini response is None due to repeated API errors", Level.CRITICAL, 3,
                    "Classification silently defaulting (Gemini failing)",
                    "Docs get the default segment but audit says success: wrong doc types downstream. Check Gemini/Vertex quota + status."),
            e("pagination-lambda-missing", null,
                    "Lambda function '[^']+' does not exist", Level.CRITICAL, 5,
                    "Pagination Lambda missing",
                    "The model's pagination Lambda isn't deployed; docs can't be split. (swbcprod ~20/15 min on 2026-10-21)"),
            e("lambda-iam-denied", null,
                    "AccessDenied\\) when calling the AssumeRoleWithWebIdentity", Level.CRITICAL, 5,
                    "AWS role can't be assumed (AccessDenied)",
                    "Service account IAM role / trust policy broken; every Lambda call from this pod fails."),
            e("llm-provider-500", "llm-predictor",
                    "server_error", Level.CRITICAL, 10,
                    "LLM provider returning 500s",
                    "Provider outage; extractions fail across unrelated docs. Check provider status and llmConfig fallback model."),
            e("crop-util-no-value", null,
                    "PageCropUtilHandler.*No value present|No value present.*PageCropUtilHandler", Level.CRITICAL, 3,
                    "Page crop failing (NoSuchElementException)",
                    "Deterministic bug: every page fails crop and replays re-fail; docs stay IN_PROGRESS (IDCST-18435)."),
            e("crop-s3-timeout", null,
                    "not read from or written to within timeout", Level.CRITICAL, 3,
                    "Crop failing: S3 socket timeout",
                    "Stale S3 pool connections; grandchildren left docType=\"\" IN_PROGRESS. Re-trigger once S3 healthy."),
            e("amqp-message-dropped", null,
                    "Fatal message conversion error", Level.CRITICAL, 1,
                    "Queue message dropped (conversion error)",
                    "Message rejected and dropped: a page never OCRs and the doc freezes IN_PROGRESS with no failed audit."),
            e("pika-channel-closed", null,
                    "ChannelWrongStateError|StreamLostError", Level.CRITICAL, 1,
                    "Pika connection broken (channel closed)",
                    "Publisher on a non-thread-safe connection lost its channel; results are silently not published."),
            e("objectid-not-serializable", null,
                    "Object of type ObjectId is not JSON serializable", Level.CRITICAL, 1,
                    "Config with ObjectId breaks finalization",
                    "Hand-inserted config (ntp_rules etc.) has an ObjectId; docs end PROCESSING_FAILED at finalization."),

            // ---------------------------------------------------------------- WARNING: degraded, above background
            e("llm-rate-limit", "llm-predictor",
                    "exceeded token rate limit|rate_limit_exceeded", Level.WARNING, 150,
                    "LLM rate limited (429) heavily",
                    "Usually self-heals on retry (usprod ~45/15 min is normal). This many means retries may exhaust."),
            e("prediction-consumers-down", null,
                    "One or more consumers are down", Level.WARNING, 15,
                    "Prediction wrapper consumers dying",
                    "Consumer threads died; healthcheck fails and the pod restarts. Extraction slows/stalls meanwhile."),
            e("scheduler-page-classification-down", "scheduler",
                    "Connection refused: page-classification", Level.WARNING, 20,
                    "page-classification service unreachable",
                    "Scheduler can't reach page-classification (euprod/auprod ~40/15 min on 2026-10-21); pagination training checks fail."),
            e("groupid-null", "orchestrator",
                    "Document groupId is null", Level.WARNING, 20,
                    "Predicted value mapping failing (groupId null)",
                    "PredictedValueMappingHandler throws for docs without groupId (rkt2prod ~40/15 min on 2026-10-21)."),
            e("azure-ocr-v3-failed", "pika-azure-ocr",
                    "Azure OCR V3 failed", Level.WARNING, 15,
                    "Azure OCR failing",
                    "Azure OCR V3 returns no result ('cannot unpack NoneType'); pages fall back or never OCR."),
            e("azure-ocr-redelivered", "pika-azure-ocr",
                    "Redelivered message for id", Level.WARNING, 20,
                    "OCR messages being redelivered",
                    "Consumer died holding messages; one redelivery after a stall is the IDCST-18908 fingerprint."),
            e("qpdf-split-failed", null,
                    "(?i)error occurred while splitting using QPDF", Level.WARNING, 20,
                    "PDF split (QPDF) failing",
                    "Splitter can't split the PDF with QPDF (rkt2prod ~12/15 min background on 2026-10-21)."),
            e("ocr-artifacts-missing", "ml-predictor",
                    "(tokens|blocks)_file_path is empty or the path is not valid", Level.WARNING, 40,
                    "OCR files missing on EFS",
                    "NER can't find page tokens/blocks on EFS (retention or OCR gap); those pages extract nothing."),
            e("kvp-lambda-unhandled", "titan-key-value-pair",
                    "Function error occurred for pageId", Level.WARNING, 30,
                    "KVP Lambda failing",
                    "KVP inference Lambda returns 'Unhandled' per page; KVP fields empty for those pages."),
            e("de-initiator-500", "de-initiator",
                    "DEException: 5\\d\\d", Level.WARNING, 20,
                    "Data-extractor initiator getting 5xx",
                    "Extraction routing call failing with 5xx; fields don't get routed to extractors."),
            e("module-extraction-failed", "orchestrator",
                    "Unable to complete \\w+ module extraction", Level.WARNING, 30,
                    "Extraction modules failing",
                    "Orchestrator got failed results from an extractor (NER/CHKB/...). See the extractor's own errors."),
            e("field-wrapper-failed", "orchestrator",
                    "Wrapper Field Failed Exception|Exception found in Wrapper Fields Handler", Level.WARNING, 10,
                    "Customer wrapper failing",
                    "Customer wrapper endpoint errors (often 500); wrapper fields come back empty."),
            e("custom-postprocessing-failed", "custom-wrapper",
                    "Exception in post-processing for customer", Level.WARNING, 10,
                    "Customer post-processing failing",
                    "custom-wrapper post-processing throws for a customer; delivery for those docs is affected."),
            e("table-llm-json-unparseable", "pika-table-extractor",
                    "Unable to parse dictionary JSON", Level.WARNING, 20,
                    "Table LLM output unparseable",
                    "LLM table response isn't valid JSON; tables come back empty."),
            e("ocr-proximity-analysis", "sampoorn-ocr",
                    "Error in Proximity analysis", Level.WARNING, 20,
                    "OCR proximity analysis failing",
                    "StringIndexOutOfBounds in proximity analysis; affects OCR post-processing for those pages."),
            e("efs-file-missing", null,
                    "Specified file/file_path doesn't exist", Level.WARNING, 5,
                    "Source file missing on EFS",
                    "Usually EFS retention (doc older than ~3 weeks being replayed); preprocessing fails."),

            // ---------------------------------------------------------------- INFO: chronic, known, tracked not paged
            e("ner-token-type", "ml-predictor",
                    "missing 1 required positional argument: 'token_type'", Level.INFO, 0,
                    "NER code bug (token_type)", "usprod NER failing 360-825×/day since at least 2026-10-14."),
            e("ner-lambda-errors", "ml-predictor",
                    "Error responses of models", Level.INFO, 0,
                    "NER Lambda error responses", "NER model Lambda returning errors."),
            e("extractor-code-error", null,
                    "Error calling extract on <class|could not convert string to float|list index out of range", Level.INFO, 0,
                    "Field extractor code errors", "Per-docType extractor bugs (EOI, paystubs, deed of trust...)."),
            e("date-parse-tz", null,
                    "There is no valid format for this date|Unable to parse file uploaded timestamp", Level.INFO, 0,
                    "Date with timezone offset not parsed", "Timestamps with offsets fail to parse (orchestrator DateUtil, swbc wrapper)."),
            e("scheduler-mysql-disabled", "scheduler",
                    "mysql.enabled|user-team lookup collection not ready", Level.INFO, 0,
                    "Scheduler user-team sync misconfigured", "mysql.enabled=false so user-team report sync can't run (most prods)."),
            e("dc-model-management-errors", "data-correction",
                    "ModelManagementService#getDocument|Error while saving corrected document as draft", Level.INFO, 0,
                    "Correction UI save/load errors", "data-correction calls to model-management failing."),
            e("page-data-none", null,
                    "Page data is None", Level.INFO, 0,
                    "Post-FF page data missing", "Post-FF extraction can't find page data."),
            e("cui-document-empty", "data-correction",
                    "CUIDocument is empty", Level.INFO, 0,
                    "Business rules on empty CUI document", "Business rules run on a doc with no CUI data."),

            // ---------------------------------------------------------------- IGNORE: noise, filtered in Loki
            ignore("gateway-session-expired", "Session expired"),                 // ~66k/h on auprod: clients with expired tokens
            ignore("dc-user-profile-name", "Error while updating user profile name"),
            ignore("rcs-stratification-skip", "Stratification fields are not valid"),
            ignore("pika-connection-already-lost", "Connection already lost. Skipping closure"),
            ignore("tf-cuinit", "failed call to cuInit"),
            ignore("orchestrator-failed-audit-echo", "Failed Audit is being inserted")   // duplicate of audit; module check covers it
    );

    private ErrorCatalog() {
    }
}
