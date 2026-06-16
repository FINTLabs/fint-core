package no.fintlabs.config

import no.fintlabs.utils.EntityProducer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import

@TestConfiguration
@Import(EntityProducer::class)
class KafkaTestConfig
