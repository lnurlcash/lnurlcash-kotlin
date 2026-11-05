package com.lnurlcash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The LUD-26 derivation as plain Java sees it: Int levels, a sealed path, a Long note index. */
class NoteDerivationJavaTest {
    // Public conformance fixture from vectors/part2.json, first branch (mint.example).
    private static final String SERVICE_ROOT =
        "72056e5cde21458b13689c3950904dfd327415a506d064290e5e5f4296a40543"
        + "dd5e9504ddb6eefbafa4ad3b00ad421858fafc0ed9ea4abd9cb68793f845cfc1";

    @Test
    void derivesAWalletNoteFromOrdinaryJavaInts() {
        NoteKey viaPath = LnurlcashKt.deriveNoteKey(SERVICE_ROOT, new NotePath.Wallet(0, 0));
        NoteKey viaHelper = LnurlcashKt.deriveWalletNoteKey(SERVICE_ROOT, 0, 0);
        assertEquals(viaPath, viaHelper);
        assertEquals("867fbf93ceb491d696a53c9f4c0f4a4a05bbfb2ab2c92b2ae052335071f1e195", viaPath.getSecretKey());
        assertEquals("9820be6eb2fd00e6dd739f029e58f61f4ae19a8ded723fc670db052285be2f80", viaPath.getOutputKey());
    }

    @Test
    void aWatcherAndTheHolderAgreeOnAShareNote() {
        String node = LnurlcashKt.deriveShareNode(SERVICE_ROOT, 0, 0);
        String cx1 = LnurlcashKt.cashNodeToCx1(node);
        for (int index : new int[] {0, 3, Integer.MAX_VALUE}) {
            assertEquals(
                LnurlcashKt.deriveShareNoteKey(SERVICE_ROOT, 0, 0, index).getOutputKey(),
                LnurlcashKt.deriveNotePubkey(cx1, (long) index));
        }
    }

    @Test
    void rejectsOutOfRangeLevelsBeforeDerivation() {
        assertThrows(IllegalArgumentException.class, () -> new NotePath.Wallet(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new NotePath.Change(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new NotePath.Share(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> LnurlcashKt.deriveShareNode(SERVICE_ROOT, -1, 0));
    }

    @Test
    void rejectsOutOfRangeNoteIndicesBeforeDerivation() {
        for (long index : new long[] {-1L, Long.MIN_VALUE, 0x1_0000_0000L, Long.MAX_VALUE}) {
            // Invalid key material ensures index validation precedes the native call.
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LnurlcashKt.deriveNotePubkey("", index));
            assertEquals("index must be in 0..4294967295", error.getMessage());
        }
    }
}
