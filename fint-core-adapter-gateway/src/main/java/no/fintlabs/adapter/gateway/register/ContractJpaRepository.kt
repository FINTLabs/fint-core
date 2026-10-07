package no.fintlabs.adapter.gateway.register

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ContractJpaRepository : JpaRepository<ContractEntity, Long> {
    @Query("select distinct c.adapterId from ContractEntity c")
    fun getAdapterIds(): Set<String>

    @Query(
        """
        select distinct c
        from ContractEntity c
        left join fetch c.capabilityEntityset
        where c.userName = :userName and c.orgId = :orgId
        """,
    )
    fun findByUserNameAndOrgId(
        @Param("userName") userName: String,
        @Param("orgId") orgId: String,
    ): ContractEntity?

    @Query(
        """
        select distinct c
        from ContractEntity c
        left join fetch c.capabilityEntityset
        """,
    )
    fun findAllWithCapabilities(): List<ContractEntity>
}
