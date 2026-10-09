package no.fintlabs.adapter.gateway.register;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import no.fintlabs.adapter.models.EventCapability;
import no.fintlabs.adapter.operation.OperationType;

/**
 * One operation the adapter answers for one resource. A contract that lists a resource with two
 * operations has two rows for it.
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
    private OperationType operation;

    public EventCapabilityEntity(EventCapability capability, OperationType operation) {
        this.domainName = capability.getDomainName();
        this.pkgName = capability.getPackageName();
        this.resourceName = capability.getResourceName();
        this.operation = operation;
    }

    public EventCapabilityEntity() {
    }
}
