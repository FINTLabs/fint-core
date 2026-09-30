package no.fintlabs.client.security

import org.springframework.security.authorization.AuthorizationDecision

/**
 * Why a request was denied. The checks in [SecurityConfiguration] return one of these instead of
 * `false`, so adding a new check means adding a case here with its own [detail]. The 403 handler
 * only prints [detail], it does not try to work out the reason on its own.
 */
sealed interface Denial {
    val detail: String

    data object NotAClient : Denial {
        override val detail = "Principal is not a FINT client"
    }

    data object WrongType : Denial {
        override val detail = "Principal type must be CLIENT"
    }

    data object MissingScope : Denial {
        override val detail = "JWT is missing required 'fint-client' scope"
    }

    data object MissingComponentRole : Denial {
        override val detail = "Client is missing the required role for the requested component"
    }

    data class OrgNotInAssets(
        val orgId: String,
    ) : Denial {
        override val detail get() = "Client does not have access to organisation '$orgId'"
    }

    data object ResourceNotGranted : Denial {
        override val detail = "Client does not have access to the requested resource"
    }

    // Access control is an external service that decides which resources, fields and relations a client has access to.
    data object AccessControlUnavailable : Denial {
        override val detail = "Access could not be decided because access control is unavailable"
    }
}

/** A denied decision that carries its [Denial]; Spring puts it on the `AuthorizationDeniedException`. */
class Denied(
    val denial: Denial,
) : AuthorizationDecision(false)
