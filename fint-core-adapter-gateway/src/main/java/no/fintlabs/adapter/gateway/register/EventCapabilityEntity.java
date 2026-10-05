package no.fintlabs.adapter.gateway.register;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import no.fintlabs.adapter.models.v2.event.EventOperation;

/**
 * One resource and one event operation the adapter answers for it. An event capability with
 * several operations is stored as one row per operation.
 */
@Getter
@Setter
@Entity
@Table(name = "event_capabilities")
public class EventCapabilityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false)
    private ContractEntity contractEntity;

    private String domainName;
    private String pkgName;
    private String resourceName;

    @Enumerated(EnumType.STRING)
    private EventOperation operation;

    public EventCapabilityEntity(String domainName, String pkgName, String resourceName, EventOperation operation) {
        this.domainName = domainName;
        this.pkgName = pkgName;
        this.resourceName = resourceName;
        this.operation = operation;
    }

    public EventCapabilityEntity() {
    }
}
