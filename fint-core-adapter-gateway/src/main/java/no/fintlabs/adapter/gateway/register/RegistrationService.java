package no.fintlabs.adapter.gateway.register;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import no.fintlabs.adapter.models.AdapterContract;
import no.novari.core.shared.model.OrgId;
import no.novari.core.shared.org.OrgStore;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class RegistrationService {

    private final AdapterContractProducer adapterContractProducer;
    private final ContractService contractService;
    private final OrgStore orgStore;
    private final EventCapabilityPublisher eventCapabilityPublisher;

    public void register(AdapterContract adapterContract) {
        adapterContractProducer.send(adapterContract);
        contractService.saveContract(adapterContract);
        orgStore.upsert(adapterContract.getOrgId());
        eventCapabilityPublisher.publish(OrgId.Companion.from(adapterContract.getOrgId()));
    }
}
