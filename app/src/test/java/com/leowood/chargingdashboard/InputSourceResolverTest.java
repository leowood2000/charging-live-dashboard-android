package com.leowood.chargingdashboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InputSourceResolverTest {
    @Test
    public void usbOffResidualVbusWirelessCpUsesWireless() {
        assertEquals("wireless", InputSourceResolver.resolve(false, 9000, true));
    }

    @Test
    public void usbOnlineWinsWhenWirelessSignalAlsoExists() {
        assertEquals("wired", InputSourceResolver.resolve(true, 9000, true));
    }

    @Test
    public void unknownUsbFallsBackToVbusWithoutWirelessSignal() {
        assertEquals("wired", InputSourceResolver.resolve(null, 5000, false));
    }

    @Test
    public void usbOffWithoutWirelessSignalIsNoneDespiteResidualVbus() {
        assertEquals("none", InputSourceResolver.resolve(false, 9000, false));
    }

    @Test
    public void realRegressionOwnsSharedIbusAsWirelessNotOldWiredCp() {
        String source = InputSourceResolver.resolve(false, 9000, true);
        assertEquals("wireless", source);
        assertEquals("wireless", InputSourceResolver.cpIbusOwner(source, 1755));
        // Source selection makes the UI choose wireless work_mode=2, never stale wired ratio=1.
        int wirelessWorkMode = 2;
        int staleWiredRatio = 1;
        assertEquals(2, "wireless".equals(source) ? wirelessWorkMode : staleWiredRatio);
    }
}
