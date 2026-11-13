package no.fintlabs.client.config

import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.valves.ValveBase
import org.apache.coyote.ActionCode

/**
 * Drops the connection of a response that failed after its status was sent. The client then gets
 * a network error from its HTTP library and cannot take a cut-off body for a complete one.
 *
 * What a dropped connection means to the client is defined in
 * [RFC 9112 section 8](https://www.rfc-editor.org/rfc/rfc9112.html#section-8).
 *
 * > A message body that uses the chunked transfer coding is incomplete if the zero-sized chunk
 * > that terminates the encoding has not been received.
 *
 * > A client that receives an incomplete response message, which can occur when a connection is
 * > closed prematurely or when decoding a supposedly chunked transfer coding fails, MUST record
 * > the message as incomplete.
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
        // Closes without the zero-sized chunk, so the body is incomplete by the RFC. Ending the response
        // normally would send that chunk and make a cut-off body look complete. Tomcat makes the same
        // call in its ErrorReportValve when a request fails after the response was committed
        response.coyoteResponse.action(ActionCode.CLOSE_NOW, cause)
    }

    companion object {
        const val ABORTED_ATTRIBUTE = "no.fintlabs.client.response.aborted"
    }
}
