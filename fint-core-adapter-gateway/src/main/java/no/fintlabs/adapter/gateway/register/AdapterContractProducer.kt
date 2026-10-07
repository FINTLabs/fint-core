package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.gateway.kafka.EventPublisher
import no.fintlabs.adapter.gateway.kafka.topic.TopicNamesConstants.ADAPTER_CONTRACT
import no.fintlabs.adapter.models.AdapterContract
import no.novari.core.shared.model.OrgId
import org.springframework.stereotype.Service

/**
 * Publishes contracts to a compacted topic, keyed on the contract's identity: the username
 * and the orgId. The topic then keeps the latest registration per contract, and a tombstone
 * (a null value for the key) removes the contract.
 */
@Service
class AdapterContractProducer(
    private val eventPublisher: EventPublisher,
) {
    fun send(adapterContract: AdapterContract) {
        eventPublisher.publish(ADAPTER_CONTRACT, keyOf(adapterContract.username, adapterContract.orgId), adapterContract)
    }

    fun sendTombstone(
        username: String,
        orgId: String,
    ) {
        eventPublisher.publish(ADAPTER_CONTRACT, keyOf(username, orgId), null)
    }

    companion object {
        private const val KEY_SEPARATOR = '\u001F'

        fun keyOf(
            username: String,
            orgId: String,
        ): String = "$username$KEY_SEPARATOR${OrgId.from(orgId).value}"
    }
}
