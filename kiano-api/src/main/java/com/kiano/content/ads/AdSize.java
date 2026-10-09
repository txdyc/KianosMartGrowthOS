package com.kiano.content.ads;

/**
 * The three ad static sizes (§7.3): 1:1, 4:5 and 9:16. {@code label()} is the
 * {@code w}x{h} string used in asset variants and file names, {@code sizeClass()}
 * the CSS hook the AD_* templates switch layout on.
 */
public enum AdSize {
    S1X1(1080, 1080),
    S4X5(1080, 1350),
    S9X16(1080, 1920);

    private final int width;
    private final int height;

    AdSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** {@code 1080x1080} - the variant suffix and file-name dimension segment. */
    public String label() {
        return width + "x" + height;
    }

    /** {@code s1x1} / {@code s4x5} / {@code s9x16} - the template CSS class. */
    public String sizeClass() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
