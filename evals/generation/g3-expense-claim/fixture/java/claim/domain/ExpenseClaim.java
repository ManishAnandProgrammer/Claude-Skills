package @ROOT_PACKAGE@.claim.domain;

import @ROOT_PACKAGE@.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "expense_claims")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExpenseClaim extends AuditableEntity {

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Setter
    @Column(name = "approver_id", nullable = false)
    private UUID approverId;

    @Setter
    @Column(nullable = false)
    private BigDecimal amount;

    @Setter
    @Column(nullable = false)
    private String currency;

    @Setter
    @Column(name = "spent_on", nullable = false)
    private LocalDate spentOn;

    @Setter
    @Column(nullable = false)
    private String description;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseClaimStatus status = ExpenseClaimStatus.DRAFT;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;
}
