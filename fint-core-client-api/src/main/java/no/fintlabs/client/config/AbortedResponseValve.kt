package no.fintlabs.client.config

import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.valves.ValveBase
import org.apache.coyote.ActionCode

/**
 * Drops the connection of a response that failed after its status was sent. The client then gets
 * a network error from its HTTP library and cannot take a cut-off body for a complete one.
 *
 * A handler asks for it by setting [ABORTED_ATTRIBUTE] on the request to the failure.
 */
class AbortedResponseValve : ValveBase(true) {
    override fun invoke(
        request: Request,
        response: Response,
    ) {
        next.invoke(request, response)
        val cause = request.getAttribute(ABORTED_ATTRIBUTE) as? Throwable ?: return
        // What was written before the failure still goes out
        runCatching { response.flushBuffer() }
        // Closes without the last chunk. Ending the response normally would make the body look complete
        response.coyoteResponse.action(ActionCode.CLOSE_NOW, cause)
    }

    companion object {
        const val ABORTED_ATTRIBUTE = "no.fintlabs.client.response.aborted"
    }
}
