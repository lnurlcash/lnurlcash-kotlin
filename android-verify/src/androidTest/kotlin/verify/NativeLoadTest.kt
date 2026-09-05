package io.github.thecryptodonkey.lnurlcash.verify

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thecryptodonkey.lnurlcash.deriveCashRoot
import io.github.thecryptodonkey.lnurlcash.deriveCashSecret
import io.github.thecryptodonkey.lnurlcash.hashK1
import io.github.thecryptodonkey.lnurlcash.isAllowedServiceUrl
import io.github.thecryptodonkey.lnurlcash.verifyNoteSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android runtime. Every assertion crosses the FFI boundary
 * into the Rust core, so the suite passing at all is the proof that JNA found
 * `liblnurlcash_core.so` in the APK's native library directory - which is the
 * one thing no amount of inspecting the aar can establish.
 *
 * The values are the shared conformance vectors, not a self-consistency check.
 * An Android build that loads the core but computes different money from every
 * other implementation would pass the first and fail these.
 */
@RunWith(AndroidJUnit4::class)
class NativeLoadTest {

    // cash-derivation.json, "standard mnemonic, index 0".
    private val seedHex =
        "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
            "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
    private val cashRoot =
        "c7a2496e9b453a67c5d2a1f04936ec1259440d45454c795a99a66269e4cd3005" +
            "111e1cc966fca2fe32f054f14caceab90449e536d94cf6935ea12a087e414f60"
    private val k1 = "de5b81405a12e1297b350d80e2ad85043ed5b9436a0c5592d3302778de330499"
    private val noteId = "7db9da2845cd45c1c3c2e302d6135da46823e245f756b830ef59ac324b769e02"

    @Test
    fun urlAdmissionCrossesTheFfi() {
        assertTrue(isAllowedServiceUrl("https://example.com/lnurlp/x"))
        assertFalse(isAllowedServiceUrl("http://10.0.0.1/lnurlp/x"))
    }

    @Test
    fun signatureVerificationCrossesTheFfi() {
        // Not a valid signature for these values. What is being established is
        // that the core answers at all rather than throwing at load time.
        assertFalse(
            verifyNoteSignature(
                k1 = "00".repeat(32),
                amountMsat = 1000L,
                signatureHex = "00".repeat(64),
                mintPubkeyHex = "02" + "00".repeat(32),
            )
        )
    }

    @Test
    fun derivationMatchesTheConformanceVectors() {
        assertEquals(cashRoot, deriveCashRoot(seedHex))
        assertEquals(k1, deriveCashSecret(cashRoot, "mint.example", 0u))
        assertEquals(noteId, hashK1(k1))
    }
}
