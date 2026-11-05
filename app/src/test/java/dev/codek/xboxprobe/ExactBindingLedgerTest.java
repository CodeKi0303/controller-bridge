package dev.codek.xboxprobe;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ExactBindingLedgerTest {
    @Test public void oldBindingDeathCannotSatisfyRedeliveredBinding(){
        ExactBindingLedger<Object,String> ledger=new ExactBindingLedger<>();Object old=new Object(),fresh=new Object();
        assertEquals(ExactBindingLedger.Kind.PRIMARY,ledger.admit(old,"old").kind);
        ledger.markDead(old);assertTrue(ledger.allDead());
        assertEquals(ExactBindingLedger.Kind.ADDITIONAL,ledger.admit(fresh,"fresh").kind);
        ledger.markDead(old);assertFalse(ledger.allDead());assertFalse(ledger.isDead(fresh));
        ledger.markDead(fresh);assertTrue(ledger.allDead());
    }
    @Test public void duplicateDeliveryKeepsTheOriginalOwnedEndpoint(){
        ExactBindingLedger<Object,String> ledger=new ExactBindingLedger<>();Object key=new Object();
        ledger.admit(key,"first");ExactBindingLedger.Admission<String> again=ledger.admit(key,"replacement");
        assertEquals(ExactBindingLedger.Kind.DUPLICATE,again.kind);assertEquals("first",again.value);assertEquals(1,ledger.snapshot().size());
    }
    @Test public void EqualDisplayValuesDoNotMergeDifferentBindingIdentities(){
        ExactBindingLedger<String,String> ledger=new ExactBindingLedger<>();String first=new String("same"),second=new String("same");
        ledger.admit(first,"first");assertEquals(ExactBindingLedger.Kind.ADDITIONAL,ledger.admit(second,"second").kind);
        ledger.markDead(first);assertFalse(ledger.allDead());assertEquals(2,ledger.snapshot().size());
    }
    @Test public void MissingBindingAndUnknownDeathRemainUnresolved(){
        ExactBindingLedger<Object,String> ledger=new ExactBindingLedger<>();assertFalse(ledger.allDead());
        assertFalse(ledger.markDead(new Object()));assertFalse(ledger.allDead());
    }
}
