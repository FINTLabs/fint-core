package no.fintlabs.client.resource.paging

import no.fintlabs.client.config.ConsumerConfiguration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Turns a [PageCursor] into the token clients get in `next` and `prev` links, and back again.
 *
 * The token is encrypted with AES-GCM, because the id inside a cursor can be a value the client
 * is not allowed to read, such as a fødselsnummer. A token that was changed, or that was made
 * with another key, fails to decrypt and is rejected like any other text that is not a cursor.
 *
 * The key comes from `fint.consumer.paging.cursor-key`, base64 of 16, 24 or 32 bytes. Every
 * replica needs the same key and it has to stay the same across restarts, otherwise the links
 * clients hold stop working. Without a configured key a random one is made at startup, which is
 * fine for one instance but makes every outstanding link invalid on restart.
 */
@Component
class PageCursorCodec(
    private val key: SecretKey,
) {
    @Autowired
    constructor(configuration: ConsumerConfiguration) : this(keyFrom(configuration.paging.cursorKey))

    private val random = SecureRandom()

    fun encode(cursor: PageCursor): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))

        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + cipher.doFinal(cursor.toBytes()))
    }

    fun decode(token: String): PageCursor =
        try {
            val bytes = Base64.getUrlDecoder().decode(token)
            require(bytes.size > NONCE_BYTES) { "Not a page cursor" }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))

            PageCursor.fromBytes(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES))
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("Not a page cursor", e)
        }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 96
        private val KEY_SIZES = setOf(16, 24, 32)
        private val log = LoggerFactory.getLogger(PageCursorCodec::class.java)

        fun withRandomKey(): PageCursorCodec = PageCursorCodec(randomKey())

        private fun keyFrom(configured: String?): SecretKey {
            if (configured.isNullOrBlank()) {
                log.warn(
                    "No fint.consumer.paging.cursor-key configured, page cursors will stop working when this instance restarts",
                )
                return randomKey()
            }

            val bytes = Base64.getDecoder().decode(configured)
            require(bytes.size in KEY_SIZES) { "fint.consumer.paging.cursor-key must be base64 of 16, 24 or 32 bytes" }
            return SecretKeySpec(bytes, "AES")
        }

        private fun randomKey(): SecretKey = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
    }
}
