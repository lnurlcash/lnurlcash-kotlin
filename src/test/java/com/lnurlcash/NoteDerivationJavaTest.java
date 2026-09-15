package com.lnurlcash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NoteDerivationJavaTest {
    // Public conformance fixture from vectors/part2.json, first branch.
    private static final String NODE =
        "6e44409fddc788205f5b0eace8024bae91a2b57dc4e07a32bf9dbd732350dde9"
        + "d751c4963c99fb3d08eb2e580f66f1c1463e5249a9435677bd0951e06f7edc6c";

    static void assertDerivation(String publicKey, String privateKey, String chainCode,
                                 long index, String expectedPublicKey, String expectedSecretKey) {
        assertEquals(expectedPublicKey, LnurlcashKt.deriveNotePubkey(publicKey, chainCode, index));
        assertEquals(expectedSecretKey, LnurlcashKt.deriveNoteSecretKey(privateKey, chainCode, index));
    }

    @Test
    void acceptsAnOrdinaryJavaIntLiteral() {
        Cx1 branch = LnurlcashKt.cashNodeToCx1(NODE);
        assertEquals("b52e0b9dcd39edd137cf4b6794d0d2a55f13c9aa968078394d49159651b5a02f",
            LnurlcashKt.deriveNotePubkey(branch.getPubkeyXOnly(), branch.getChainCode(), 0));
        assertEquals("c809325604f901c494bebab0f02d74d43cb3d58c143b753c2b749d1733288f64",
            LnurlcashKt.deriveNoteSecretKey(NODE.substring(0, 64), NODE.substring(64), 0));
    }

    @Test
    void rejectsOutOfRangeIndicesBeforeDerivation() {
        for (long index : new long[] {-1L, Long.MIN_VALUE, 0x1_0000_0000L, Long.MAX_VALUE}) {
            // Invalid key material ensures index validation precedes the native call.
            IllegalArgumentException publicError = assertThrows(IllegalArgumentException.class,
                () -> LnurlcashKt.deriveNotePubkey("", "", index));
            IllegalArgumentException secretError = assertThrows(IllegalArgumentException.class,
                () -> LnurlcashKt.deriveNoteSecretKey("", "", index));
            assertEquals("index must be in 0..4294967295", publicError.getMessage());
            assertEquals("index must be in 0..4294967295", secretError.getMessage());
        }
    }
}
