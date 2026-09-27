package com.mitchej123.supernova.light;

/** Every factory publishes to the visible side. */
public final class NibbleStates {

    private NibbleStates() {}

    public static SWMRNibbleArray uninit() {
        return new SWMRNibbleArray();
    }

    public static SWMRNibbleArray zeroInit() {
        final SWMRNibbleArray nib = new SWMRNibbleArray();
        nib.setZero();
        nib.updateVisible();
        return nib;
    }

    public static SWMRNibbleArray nullNibble() {
        return new SWMRNibbleArray(null, true);
    }

    public static SWMRNibbleArray lit(final int index, final int value) {
        final SWMRNibbleArray nib = new SWMRNibbleArray();
        nib.set(index, value);
        nib.updateVisible();
        return nib;
    }

    public static SWMRNibbleArray lit(final int x, final int y, final int z, final int value) {
        final SWMRNibbleArray nib = new SWMRNibbleArray();
        nib.set(x, y, z, value);
        nib.updateVisible();
        return nib;
    }

    public static SWMRNibbleArray full() {
        final SWMRNibbleArray nib = new SWMRNibbleArray();
        nib.setFull();
        nib.updateVisible();
        return nib;
    }
}
