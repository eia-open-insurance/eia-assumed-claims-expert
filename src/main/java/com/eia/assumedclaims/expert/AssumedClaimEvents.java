package com.eia.assumedclaims.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event factory for the Assumed Claims vertical.
 *
 * <p>Event names are the canonical names from {@code eia-contracts/asyncapi/
 * event-catalog.yaml} (SPEC 11.5.9). The requested working names map as
 * follows:</p>
 *
 * <ul>
 *   <li>{@code assumed.claim.created} -&gt; {@code assumed.claim.registered}</li>
 *   <li>{@code assumed.claim.advice-accepted} -&gt;
 *       {@code assumed.claim.loss-advice-posted}</li>
 *   <li>{@code assumed.claim.cash-call-issued} -&gt;
 *       {@code assumed.claim.cash-call-requested}</li>
 *   <li>{@code assumed.claim.updated} for a plain status change; the catalog
 *       carries the lifecycle-specific {@code assumed.claim.closed} and
 *       {@code assumed.claim.reopened} verbs, which are used when the target
 *       status matches. The generic {@code assumed.claim.updated} name is not
 *       in the current catalog and is reported as a contract gap.</li>
 * </ul>
 *
 * <p>Assumed claims are an ASSUMED-book aggregate, so every event carries
 * {@code book=ASSUMED}. Row versions start at 0 for API compatibility, while
 * the canonical envelope requires {@code aggregateVersion >= 1}; the event
 * aggregate version is therefore the row version plus one (create -&gt; 1,
 * each subsequent material write -&gt; the next integer).</p>
 */
final class AssumedClaimEvents {
    static final String REGISTERED = "assumed.claim.registered";
    static final String UPDATED = "assumed.claim.updated";
    static final String EVENT_CLOSED = "assumed.claim.closed";
    static final String EVENT_REOPENED = "assumed.claim.reopened";
    static final String LOSS_ADVICE_POSTED = "assumed.claim.loss-advice-posted";
    static final String RESERVE_CHANGED = "assumed.claim.reserve-changed";
    static final String CASH_CALL_REQUESTED = "assumed.claim.cash-call-requested";
    static final String RECOVERY_RECORDED = "assumed.claim.recovery-recorded";
    static final String COMMUTED = "assumed.claim.commuted";

    private static final Book EVENT_BOOK = Book.ASSUMED;
    private static final String VERTICAL = "assumed";
    private static final String AGGREGATE_TYPE = "AssumedClaim";
    private static final String SUBJECT_TYPE = "assumed-claim";
    private static final String SOURCE_BUILD = "eia-assumed-claims-expert@0.1.0-SNAPSHOT";
    private static final String ACTOR = "eia-assumed-claims-expert";

    private AssumedClaimEvents() {
    }

    static EventEnvelope registered(AssumedClaim claim, Instant now) {
        Map<String, Object> data = identity(claim);
        data.put("lossDate", date(claim.lossDate()));
        data.put("reportDate", date(claim.reportDate()));
        data.put("reserveAmount", money(claim.reserveAmount()));
        data.put("reserveCurrency", claim.reserveCurrency());
        data.put("createdAt", instant(claim.createdAt()));

        return envelope(claim, REGISTERED, claim.version() + 1L, now, data);
    }

    static EventEnvelope statusChanged(AssumedClaim before, AssumedClaim after, Instant now) {
        Map<String, Object> data = identity(after);
        data.put("changedFields", List.of("status"));
        data.put("from", before.status().name());
        data.put("to", after.status().name());

        return envelope(after, statusEventType(after.status()),
                after.version() + 1L, now, data);
    }

    static EventEnvelope lossAdvicePosted(AssumedClaim claim, LossAdvice advice, Instant now) {
        Map<String, Object> data = nextVersionIdentity(claim);
        data.put("adviceId", advice.id());
        data.put("adviceNumber", advice.adviceNumber());
        data.put("adviceType", advice.adviceType().name());
        data.put("adviceDate", date(advice.adviceDate()));
        data.put("receivedDate", date(advice.receivedDate()));
        data.put("adviceStatus", advice.status().name());
        data.put("reportedAmount", money(advice.reportedAmount()));
        data.put("paidAmount", money(advice.paidAmount()));
        data.put("outstandingAmount", money(advice.outstandingAmount()));
        data.put("expenseAmount", money(advice.expenseAmount()));
        data.put("reserveAmount", money(advice.reserveAmount()));
        data.put("recoveryAmount", money(advice.recoveryAmount()));
        data.put("cessionPct", money(advice.cessionPct()));
        data.put("cededAmount", money(advice.cededAmount()));
        data.put("currency", advice.currency());
        data.put("layerAttachmentMet", advice.layerAttachmentMet());
        data.put("source", advice.source().name());
        data.put("postedAt", instant(advice.postedAt()));

        return envelope(claim, LOSS_ADVICE_POSTED, claim.version() + 2L, now, data);
    }

    static EventEnvelope reserveChanged(
            AssumedClaim claim,
            ReserveHistoryEntry entry,
            BigDecimal previousReserve,
            BigDecimal reserveAmount,
            Instant now) {
        Map<String, Object> data = nextVersionIdentity(claim);
        data.put("reserveId", entry.id());
        data.put("component", entry.component().name());
        data.put("sequence", entry.sequence());
        data.put("amount", money(entry.amount()));
        data.put("previousReserveAmount", money(previousReserve));
        data.put("reserveAmount", money(reserveAmount));
        data.put("currency", entry.currency());
        data.put("effectiveDate", date(entry.effectiveDate()));
        data.put("reasonCode", entry.reasonCode().name());
        data.put("reasonText", entry.reasonText());
        data.put("authority", entry.authority().name());
        data.put("authorityRequired", entry.authorityRequired());
        data.put("approvedBy", entry.approvedBy());
        data.put("approvedAt", instant(entry.approvedAt()));

        return envelope(claim, RESERVE_CHANGED, claim.version() + 2L, now, data);
    }

    static EventEnvelope cashCallRequested(AssumedClaim claim, CashCall call, Instant now) {
        Map<String, Object> data = nextVersionIdentity(claim);
        data.put("cashCallId", call.id());
        data.put("requestNumber", call.requestNumber());
        data.put("requestedAmount", money(call.requestedAmount()));
        data.put("currency", call.currency());
        data.put("requestedAt", date(call.requestedAt()));
        data.put("dueDate", date(call.dueDate()));
        data.put("cashCallStatus", call.status().name());
        data.put("reason", call.reason().name());
        data.put("reasonText", call.reasonText());
        data.put("approvalStatus", call.approvalStatus());
        data.put("approvedBy", call.approvedBy());
        data.put("approverRole", call.approverRole() == null ? null : call.approverRole().name());
        data.put("paymentId", call.paymentId());

        return envelope(claim, CASH_CALL_REQUESTED, claim.version() + 2L, now, data);
    }

    static EventEnvelope recoveryRecorded(AssumedClaim claim, Recovery recovery, Instant now) {
        Map<String, Object> data = nextVersionIdentity(claim);
        data.put("recoveryId", recovery.id());
        data.put("recoveryType", recovery.recoveryType().name());
        data.put("amount", money(recovery.amount()));
        data.put("currency", recovery.currency());
        data.put("receivedDate", date(recovery.receivedDate()));
        data.put("recoveryStatus", recovery.status().name());
        data.put("expectedAmount", money(recovery.expectedAmount()));
        data.put("sourcePartyId", recovery.sourcePartyId());
        data.put("sourceContractId", recovery.sourceContractId());
        data.put("paymentId", recovery.paymentId());

        return envelope(claim, RECOVERY_RECORDED, claim.version() + 2L, now, data);
    }

    static EventEnvelope commuted(
            AssumedClaim closed,
            Commutation commutation,
            BigDecimal releasedReserve,
            Instant now) {
        Map<String, Object> data = identity(closed);
        data.put("commutationId", commutation.id());
        data.put("commutationNumber", commutation.commutationNumber());
        data.put("commutationType", commutation.commutationType().name());
        data.put("offerAmount", money(commutation.offerAmount()));
        data.put("acceptedAmount", money(commutation.acceptedAmount()));
        data.put("savingsAmount", money(commutation.savingsAmount()));
        data.put("releasedReserveAmount", money(releasedReserve));
        data.put("reserveAmount", money(closed.reserveAmount()));
        data.put("currency", commutation.currency());
        data.put("effectiveDate", date(commutation.effectiveDate()));
        data.put("valuationDate", date(commutation.valuationDate()));
        data.put("basis", commutation.basis().name());
        data.put("releaseDocumentId", commutation.releaseDocumentId());
        data.put("commutationStatus", commutation.status().name());
        data.put("approvedBy", commutation.approvedBy());
        data.put("approverRole", commutation.approverRole().name());
        data.put("executedAt", instant(commutation.executedAt()));

        return envelope(closed, COMMUTED, closed.version() + 1L, now, data);
    }

    private static String statusEventType(AssumedClaimStatus status) {
        return switch (status) {
            case CLOSED -> EVENT_CLOSED;
            case REOPENED -> EVENT_REOPENED;
            default -> UPDATED;
        };
    }

    private static Map<String, Object> identity(AssumedClaim claim) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assumedClaimId", claim.id());
        data.put("assumedClaimNumber", claim.assumedClaimNumber());
        data.put("cedentClaimNumber", claim.cedentClaimNumber());
        data.put("contractId", claim.contractId());
        data.put("businessBook", claim.businessBook());
        data.put("status", claim.status().name());
        data.put("version", claim.version());
        return data;
    }

    private static Map<String, Object> nextVersionIdentity(AssumedClaim claim) {
        Map<String, Object> data = identity(claim);
        // The child write (touch/updateReserveAmount) increments the claim row
        // version in the same transaction, so the event reports the new value.
        data.put("version", claim.version() + 1);
        return data;
    }

    private static EventEnvelope envelope(
            AssumedClaim claim,
            String eventType,
            long aggregateVersion,
            Instant now,
            Map<String, Object> data) {
        UUID eventId = EventIds.deterministic(
                claim.tenantId(), AGGREGATE_TYPE, claim.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(claim.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(claim.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system(ACTOR))
                .subject(EventSubject.of(VERTICAL, SUBJECT_TYPE, claim.id(), claim.assumedClaimNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
