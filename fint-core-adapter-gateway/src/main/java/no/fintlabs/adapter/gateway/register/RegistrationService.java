package no.fintlabs.adapter.gateway.register;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.adapter.models.AdapterCapability;
import no.fintlabs.adapter.models.AdapterContract;
import no.novari.core.shared.org.OrgStore;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
public class RegistrationService {

    private final AdapterContractProducer adapterContractProducer;
    private final ContractService contractService;
    private final OrgStore orgStore;

    /**
     * Fills in what provider-gateway let adapters leave out, before the contract is published
     * and saved, so the contract topic and the database hold the same contract:
     * <ul>
     *     <li>No heartbeat interval becomes 0, which is what provider-gateway published. The
     *     database column is not null, and status-service cannot read a null interval.</li>
     *     <li>No adapter id becomes the username. status-service cannot read a null adapter id.</li>
     *     <li>A resource listed more than once in the capabilities is kept once.</li>
     * </ul>
     */
    public void register(AdapterContract adapterContract) {
        if (adapterContract.getHeartbeatIntervalInMinutes() == null) {
            adapterContract.setHeartbeatIntervalInMinutes(0);
        }
        if (adapterContract.getAdapterId() == null || adapterContract.getAdapterId().isBlank()) {
            adapterContract.setAdapterId(adapterContract.getUsername());
        }
        adapterContract.setCapabilities(onePerResource(adapterContract));

        adapterContractProducer.send(adapterContract);
        contractService.saveContract(adapterContract);
        orgStore.upsert(adapterContract.getOrgId());
    }

    /**
     * Keeps one capability per resource, ignoring case. When a resource is listed more than once,
     * the entry with the shortest full sync interval wins, since that is the strictest promise
     * the adapter made.
     */
    private Set<AdapterCapability> onePerResource(AdapterContract adapterContract) {
        Map<String, List<AdapterCapability>> byResource = adapterContract.getCapabilities().stream()
                .collect(Collectors.groupingBy(capability -> capability.getEntityUri().toLowerCase(Locale.ROOT)));

        byResource.forEach((resource, capabilities) -> {
            if (capabilities.size() > 1) {
                log.warn("{} for {} lists {} {} times, keeping the shortest full sync interval",
                        adapterContract.getUsername(), adapterContract.getOrgId(), resource, capabilities.size());
            }
        });

        return byResource.values().stream()
                .map(capabilities -> Collections.min(capabilities, Comparator.comparingInt(AdapterCapability::getFullSyncIntervalInDays)))
                .collect(Collectors.toSet());
    }
}
