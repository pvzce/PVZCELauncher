package com.tungsten.fcl.generic;

import org.junit.After;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class GameOperationGateTest {
    private final Object first = new Object();
    private final Object recreated = new Object();

    @After public void release() {
        GameOperationGate.end(first);
        GameOperationGate.end(recreated);
    }

    @Test public void recreatedActivityCannotAcquireOrReleaseAnExistingOperation() {
        assertTrue(GameOperationGate.begin(first));
        assertTrue(GameOperationGate.isBusy());
        assertFalse(GameOperationGate.begin(recreated));
        GameOperationGate.end(recreated);
        assertTrue(GameOperationGate.isOwnedBy(first));
        GameOperationGate.end(first);
        assertFalse(GameOperationGate.isBusy());
        assertTrue(GameOperationGate.begin(recreated));
    }

    @Test public void lifecycleListenersAreNotRetainedAfterRemoval() {
        AtomicInteger events = new AtomicInteger();
        Runnable listener = events::incrementAndGet;
        GameOperationGate.addListener(listener);
        try {
            assertTrue(GameOperationGate.begin(first));
            assertEquals(1, events.get());
            GameOperationGate.removeListener(listener);
            GameOperationGate.end(first);
            assertEquals(1, events.get());
        } finally {
            GameOperationGate.removeListener(listener);
        }
    }
}
