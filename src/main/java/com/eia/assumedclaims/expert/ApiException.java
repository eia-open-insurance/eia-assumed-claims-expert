package com.eia.assumedclaims.expert;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

/**
 * Domain error that the API layer renders as a structured {@link ApiError}.
 *
 * <p>Codes are stable. The 7000 range covers the original claim slice; 7100-7700 cover loss
 * advice/bordereau, reserves, cash, recoveries, commutation, disputes, and closure.</p>
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String category;
    private final String title;
    private final boolean retryable;
    private final transient List<FieldIssue> errors;

    private ApiException(
            HttpStatus status,
            String code,
            String category,
            String title,
            String detail,
            boolean retryable,
            List<FieldIssue> errors
    ) {
        super(detail);
        this.status = status;
        this.code = code;
        this.category = category;
        this.title = title;
        this.retryable = retryable;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String category() {
        return category;
    }

    public String title() {
        return title;
    }

    public boolean retryable() {
        return retryable;
    }

    public List<FieldIssue> errors() {
        return errors;
    }

    public static ApiException assumedClaimNotFound(UUID id) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EIA-RNS-7001",
                "NOT_FOUND",
                "Assumed claim not found",
                "Assumed claim " + id + " was not found.",
                false,
                List.of()
        );
    }

    public static ApiException validation(String detail, List<FieldIssue> errors) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "EIA-RNS-7004",
                "VALIDATION",
                "Request validation failed",
                detail,
                false,
                errors
        );
    }

    public static ApiException invalidTransition(AssumedClaimStatus from, AssumedClaimStatus to) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7003",
                "CONFLICT",
                "Invalid status transition",
                "Assumed claim status cannot move from " + from + " to " + to + ".",
                false,
                List.of(new FieldIssue("targetStatus", "INVALID_STATUS_TRANSITION",
                        from + " cannot transition to " + to))
        );
    }

    public static ApiException versionConflict(int expectedVersion) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7006",
                "CONFLICT",
                "Version conflict",
                "Assumed claim was modified by another request; expected version " + expectedVersion + ".",
                true,
                List.of()
        );
    }

    /**
     * Book-wall rejection used by the public {@code /api/v1} routes. The same
     * code and body are produced by {@link BookScopeFilter} for
     * {@code /internal/v1/**}, so a request outside the ASSUMED book cannot
     * distinguish a missing resource from a forbidden one (SPEC §3.6.2).
     */
    public static ApiException bookScope() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EIA-RNS-7005",
                "NOT_FOUND",
                "Not found",
                "No resource is available in the requested book scope.",
                false,
                List.of()
        );
    }

    public static ApiException taskNotFound(UUID id) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EIA-RNS-7801",
                "NOT_FOUND",
                "Claim task not found",
                "Claim task " + id + " was not found for this assumed claim.",
                false,
                List.of()
        );
    }

    public static ApiException taskVersionConflict(long expectedVersion) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7802",
                "CONFLICT",
                "Task version conflict",
                "Claim task was modified by another request; expected version "
                        + expectedVersion + ".",
                true,
                List.of(new FieldIssue("version", "STALE_TASK_VERSION",
                        "version must match the current task version"))
        );
    }

    /**
     * Same {@code Idempotency-Key} reused with a different canonical body. The
     * resource is unchanged and the caller must use a new key for a new command.
     */
    public static ApiException idempotencyKeyReused() {
        String message = "Idempotency-Key was already used with a different request.";
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-4091",
                "CONFLICT",
                "Idempotency key reused",
                message,
                false,
                List.of(new FieldIssue("Idempotency-Key", "IDEMPOTENCY_KEY_REUSED", message))
        );
    }

    /**
     * The stored command reservation is unreadable or its response body is
     * missing. This is an internal invariant failure, never a client error.
     */
    public static ApiException idempotencyRecordInconsistent() {
        return new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "EIA-RNS-7099",
                "INTERNAL",
                "Internal error",
                "The idempotency record is inconsistent; retry the command.",
                true,
                List.of()
        );
    }

    public static ApiException adviceNotFound(UUID id) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EIA-RNS-7007",
                "NOT_FOUND",
                "Loss advice not found",
                "Loss advice " + id + " was not found for this assumed claim.",
                false,
                List.of()
        );
    }

    public static ApiException duplicateAdvice(String adviceNumber) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7101",
                "CONFLICT",
                "Duplicate loss advice",
                "Loss advice " + adviceNumber + " has already been recorded for this claim (RNS-02).",
                false,
                List.of(new FieldIssue("adviceNumber", "DUPLICATE_ADVICE", "adviceNumber must be unique per claim"))
        );
    }

    public static ApiException adviceRequiredToOpen() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "EIA-RNS-7102",
                "BUSINESS_RULE",
                "Accepted loss advice required",
                "An assumed claim cannot be opened until at least one loss advice has been accepted.",
                false,
                List.of(new FieldIssue("status", "ADVICE_REQUIRED", "Accept a loss advice before opening the claim"))
        );
    }

    public static ApiException adviceNotAccepted(AdviceStatus status) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7103",
                "CONFLICT",
                "Loss advice cannot be accepted",
                "A loss advice in status " + status + " cannot be accepted.",
                false,
                List.of(new FieldIssue("status", "ADVICE_NOT_ACCEPTABLE", "Only a RECEIVED advice can be accepted"))
        );
    }

    public static ApiException bordereauDuplicate(String reference) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7104",
                "CONFLICT",
                "Bordereau reference conflict",
                "Bordereau " + reference + " already exists with different content.",
                false,
                List.of(new FieldIssue("bordereauReference", "BORDEREAU_CONFLICT",
                        "The same reference cannot be used for a different batch"))
        );
    }

    public static ApiException reserveInvalid(String field, String issueCode, String detail) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "EIA-RNS-7201",
                "VALIDATION",
                "Reserve change rejected",
                detail,
                false,
                List.of(new FieldIssue(field, issueCode, detail))
        );
    }

    public static ApiException reserveStale(ReserveComponent component, java.time.LocalDate latest, java.time.LocalDate requested) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7202",
                "CONFLICT",
                "Stale reserve effective date",
                "Reserve " + component + " has a current movement effective " + latest
                        + "; the new effective date " + requested + " must be later.",
                false,
                List.of(new FieldIssue("effectiveDate", "STALE_RESERVE_DATE",
                        "effectiveDate must be later than the latest movement for the component"))
        );
    }

    public static ApiException reserveAuthorityRequired(String detail) {
        return new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "EIA-RNS-7203",
                "APPROVAL_REQUIRED",
                "Reserve approval required",
                detail,
                false,
                List.of(new FieldIssue("approvedBy", "RESERVE_APPROVAL_REQUIRED", detail))
        );
    }

    public static ApiException reserveRequired() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "EIA-RNS-7204",
                "BUSINESS_RULE",
                "Initial reserve required",
                "An assumed claim cannot move to RESERVED without a positive current reserve.",
                false,
                List.of(new FieldIssue("status", "RESERVE_REQUIRED", "Record a positive reserve first"))
        );
    }

    public static ApiException financialChangeBlocked(AssumedClaimStatus status) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7205",
                "CONFLICT",
                "Claim is not open for financial change",
                "A financial movement cannot be recorded while the claim is " + status + ".",
                false,
                List.of()
        );
    }

    public static ApiException cashCallExceedsReserve(String detail) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "EIA-RNS-7301",
                "BUSINESS_RULE",
                "Cash call exceeds reserve",
                detail,
                false,
                List.of(new FieldIssue("requestedAmount", "CASH_CALL_EXCEEDS_RESERVE", detail))
        );
    }

    public static ApiException cashCallApprovalRequired(String detail) {
        return new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "EIA-RNS-7302",
                "APPROVAL_REQUIRED",
                "Cash call approval required",
                detail,
                false,
                List.of(new FieldIssue("approvedBy", "CASH_CALL_APPROVAL_REQUIRED", detail))
        );
    }

    public static ApiException recoveryExceedsAdvisedLoss(String detail) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "EIA-RNS-7401",
                "BUSINESS_RULE",
                "Recovery exceeds advised loss",
                detail,
                false,
                List.of(new FieldIssue("amount", "RECOVERY_EXCEEDS_ADVISED_LOSS", detail))
        );
    }

    public static ApiException commutationApprovalRequired() {
        return new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "EIA-RNS-7501",
                "APPROVAL_REQUIRED",
                "Commutation approval required",
                "A commutation requires a named underwriter or claims approver.",
                false,
                List.of(new FieldIssue("approvedBy", "COMMUTATION_APPROVAL_REQUIRED",
                        "approvedBy and an UNDERWRITER or CLAIMS approverRole are required"))
        );
    }

    public static ApiException commutationBlocked(String detail) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "EIA-RNS-7502",
                "BUSINESS_RULE",
                "Commutation blocked",
                detail,
                false,
                List.of(new FieldIssue("commutation", "COMMUTATION_BLOCKED", detail))
        );
    }

    public static ApiException releaseDocumentRequired() {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "EIA-RNS-7503",
                "VALIDATION",
                "Release document required",
                "A commutation requires a signed release document.",
                false,
                List.of(new FieldIssue("releaseDocumentId", "RELEASE_DOCUMENT_REQUIRED",
                        "releaseDocumentId is required"))
        );
    }

    public static ApiException commutationNotFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EIA-RNS-7506",
                "NOT_FOUND",
                "Commutation not found",
                "No commutation has been recorded for this assumed claim.",
                false,
                List.of()
        );
    }

    public static ApiException commutationRequiresCommand() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7505",
                "CONFLICT",
                "Commutation requires the commute command",
                "The COMMUTED status cannot be set through the generic status endpoint; use POST /commute.",
                false,
                List.of(new FieldIssue("targetStatus", "COMMUTATION_REQUIRES_COMMAND",
                        "Use the commute endpoint"))
        );
    }

    public static ApiException disputeNotFound(UUID id) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "EIA-RNS-7601",
                "NOT_FOUND",
                "Dispute not found",
                "Dispute " + id + " was not found for this assumed claim.",
                false,
                List.of()
        );
    }

    public static ApiException disputeInvalid(DisputeStatus status) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7602",
                "CONFLICT",
                "Dispute cannot be resolved",
                "A dispute in status " + status + " cannot be resolved.",
                false,
                List.of(new FieldIssue("status", "DISPUTE_NOT_RESOLVABLE", "Dispute is already resolved or closed"))
        );
    }

    public static ApiException claimCloseBlocked(String detail) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "EIA-RNS-7701",
                "BUSINESS_RULE",
                "Claim cannot be closed",
                detail,
                false,
                List.of(new FieldIssue("status", "CLAIM_CLOSE_BLOCKED", detail))
        );
    }

    public static ApiException claimAlreadyClosed() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "EIA-RNS-7702",
                "CONFLICT",
                "Claim is already closed",
                "The assumed claim is already closed.",
                false,
                List.of()
        );
    }

    public static ApiException currencyMismatch(String field, String expected, String actual) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "EIA-RNS-7703",
                "VALIDATION",
                "Currency mismatch",
                "The request currency " + actual + " does not match the claim currency " + expected + ".",
                false,
                List.of(new FieldIssue(field, "CURRENCY_MISMATCH", "must equal " + expected))
        );
    }

    public static ApiException businessRule(String code, String title, String detail) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                code,
                "BUSINESS_RULE",
                title,
                detail,
                false,
                List.of()
        );
    }
}
