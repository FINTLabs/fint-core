package no.fintlabs.adapter.gateway.register

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Migrates a database the way the running gateways have it: the old tables that Hibernate made,
 * with contracts in them and no Flyway history. Flyway has to take over that database at
 * version 1 and then move the contracts to one per org without losing them.
 *
 * A new, empty database is covered by every other integration test, since they all start from
 * the migrations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractSchemaMigrationIT {
    private val postgres = PostgreSQLContainer("postgres:18.3")
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun migrateAnExistingDatabase() {
        postgres.start()
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = JdbcTemplate(dataSource)

        ResourceDatabasePopulator(ClassPathResource("db/migration/V1__initial_schema.sql")).execute(dataSource)

        insertContract(userName = ADAPTER, orgId = "fintlabs-no")
        insertOldCapability(userName = ADAPTER, resourceName = "elev")
        insertOldCapability(userName = ADAPTER, resourceName = "elevforhold")

        insertContract(userName = ADAPTER_WITHOUT_ORG, orgId = null)
        insertOldCapability(userName = ADAPTER_WITHOUT_ORG, resourceName = "elev")

        insertContract(userName = ADAPTER_GETTING_A_SECOND_ORG, orgId = "fintlabs.no")

        Flyway
            .configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .load()
            .migrate()
    }

    @AfterAll
    fun stopDatabase() {
        postgres.stop()
    }

    @Test
    fun `the existing tables are taken over at version 1 and the later migrations run`() {
        val history =
            jdbc.query("select version, type from flyway_schema_history where success order by installed_rank") { row, _ ->
                row.getString("version") to row.getString("type")
            }

        assertThat(history).containsExactly("1" to "BASELINE", "2" to "SQL", "3" to "SQL")
    }

    @Test
    fun `a contract keeps its capabilities, now linked by contract id`() {
        val contractId = jdbc.queryForObject("select id from contract where user_name = ?", Long::class.java, ADAPTER)

        val resources =
            jdbc.queryForList(
                "select resource_name from capabilities where contract_id = ? order by resource_name",
                String::class.java,
                contractId,
            )

        assertThat(resources).containsExactly("elev", "elevforhold")
    }

    @Test
    fun `the org id is rewritten with dots`() {
        val orgId = jdbc.queryForObject("select org_id from contract where user_name = ?", String::class.java, ADAPTER)

        assertThat(orgId).isEqualTo("fintlabs.no")
    }

    @Test
    fun `a contract without an org is removed together with its capabilities`() {
        val contracts =
            jdbc.queryForObject("select count(*) from contract where user_name = ?", Int::class.java, ADAPTER_WITHOUT_ORG)
        val capabilities = jdbc.queryForObject("select count(*) from capabilities", Int::class.java)

        assertThat(contracts).isZero()
        assertThat(capabilities).isEqualTo(2)
    }

    @Test
    fun `the same adapter can hold a contract for a second org`() {
        insertContract(userName = ADAPTER_GETTING_A_SECOND_ORG, orgId = "test.fintlabs.no")

        val orgIds =
            jdbc.queryForList(
                "select org_id from contract where user_name = ? order by org_id",
                String::class.java,
                ADAPTER_GETTING_A_SECOND_ORG,
            )

        assertThat(orgIds).containsExactly("fintlabs.no", "test.fintlabs.no")
    }

    @Test
    fun `the same adapter cannot hold two contracts for one org`() {
        assertThatThrownBy { insertContract(userName = ADAPTER, orgId = "fintlabs.no") }
            .isInstanceOf(DuplicateKeyException::class.java)
    }

    private fun insertContract(
        userName: String,
        orgId: String?,
    ) {
        jdbc.update(
            "insert into contract (user_name, adapter_id, org_id, heartbeat_interval_in_minutes) values (?, ?, ?, 5)",
            userName,
            "https://vendor.example/$userName",
            orgId,
        )
    }

    private fun insertOldCapability(
        userName: String,
        resourceName: String,
    ) {
        jdbc.update(
            """
            insert into capabilities (user_name, domain_name, pkg_name, resource_name, full_sync_interval_in_days, delta_sync_interval)
            values (?, 'utdanning', 'elev', ?, 1, 'IMMEDIATE')
            """.trimIndent(),
            userName,
            resourceName,
        )
    }

    private companion object {
        const val ADAPTER = "vis@adapter.fintlabs.no"
        const val ADAPTER_WITHOUT_ORG = "old@adapter.fintlabs.no"
        const val ADAPTER_GETTING_A_SECOND_ORG = "multi@adapter.fintlabs.no"
    }
}
