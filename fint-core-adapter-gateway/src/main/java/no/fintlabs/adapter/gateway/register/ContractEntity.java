package no.fintlabs.adapter.gateway.register;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import no.fintlabs.adapter.models.AdapterContract;
import no.fintlabs.adapter.models.EventCapability;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@Setter
@Entity
@Table(
        name = "contract",
        uniqueConstraints = @UniqueConstraint(name = "uk_contract_user_name_org_id", columnNames = {"user_name", "org_id"})
)
public class ContractEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    private String adapterId;
    private int heartbeatIntervalInMinutes;

    @OneToMany(mappedBy = "contractEntity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<CapabilityEntity> capabilityEntityset = new HashSet<>();

    @OneToMany(mappedBy = "contractEntity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<EventCapabilityEntity> eventCapabilityEntitySet = new HashSet<>();

    public ContractEntity(AdapterContract adapterContract) {
        this.userName = adapterContract.getUsername();
        this.orgId = adapterContract.getOrgId();
        applyContract(adapterContract);
    }

    public ContractEntity() {

    }

    /**
     * Copies everything except the identity of the contract, which is the username and orgId
     * pair. Re-registering the same pair replaces both capability lists rather than adding to
     * them, so a capability the adapter dropped disappears from the contract.
     */
    public void applyContract(AdapterContract adapterContract) {
        this.adapterId = adapterContract.getAdapterId();
        this.heartbeatIntervalInMinutes = adapterContract.getHeartbeatIntervalInMinutes();

        Set<CapabilityEntity> replacements = adapterContract.getCapabilities().stream().map(capability -> {
            CapabilityEntity entity = new CapabilityEntity(capability);
            entity.setContractEntity(this);
            return entity;
        }).collect(Collectors.toSet());

        this.capabilityEntityset.clear();
        this.capabilityEntityset.addAll(replacements);

        Set<EventCapabilityEntity> eventReplacements = Optional.ofNullable(adapterContract.getEventCapabilities())
                .orElseGet(Set::of)
                .stream()
                .flatMap(this::toEntities)
                .collect(Collectors.toSet());

        this.eventCapabilityEntitySet.clear();
        this.eventCapabilityEntitySet.addAll(eventReplacements);
    }

    private Stream<EventCapabilityEntity> toEntities(EventCapability capability) {
        return capability.getOperations().stream().map(operation -> {
            EventCapabilityEntity entity = new EventCapabilityEntity(
                    capability.getDomainName(),
                    capability.getPackageName(),
                    capability.getResourceName(),
                    operation
            );
            entity.setContractEntity(this);
            return entity;
        });
    }
}
