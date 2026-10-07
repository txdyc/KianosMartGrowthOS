package com.kiano.imaging;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Builds the deterministic input canvas for the SCENE workflow: the cutout is
 * scaled, horizontally centred and anchored above the bottom margin on a grey
 * canvas, together with the matching binary product mask (white = product).
 */
public final class SceneCanvasComposer {

    private SceneCanvasComposer() {
    }

    /** Grey scene input canvas plus its binary product mask. */
    public record SceneCanvas(BufferedImage image, BufferedImage productMask) {
    }

    public static SceneCanvas compose(BufferedImage cutout, int canvas, double occupancy, double bottomMargin) {
        CutoutGeometry.requireUsable(cutout);
        BufferedImage scaled = WhiteComposer.scaleToLongSide(cutout, canvas, occupancy);
        int bottom = Math.round((float) (canvas * bottomMargin));
        int x = (canvas - scaled.getWidth()) / 2;
        int y = canvas - bottom - scaled.getHeight();

        BufferedImage image = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(128, 128, 128));
        g.fillRect(0, 0, canvas, canvas);
        g.drawImage(scaled, x, y, null);
        g.dispose();

        BufferedImage mask = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_RGB);
        Graphics2D gm = mask.createGraphics();
        gm.setColor(Color.BLACK);
        gm.fillRect(0, 0, canvas, canvas);
        gm.dispose();
        int[] row = new int[scaled.getWidth()];
        for (int sy = 0; sy < scaled.getHeight(); sy++) {
            scaled.getRGB(0, sy, scaled.getWidth(), 1, row, 0, scaled.getWidth());
            for (int sx = 0; sx < scaled.getWidth(); sx++) {
                if ((row[sx] >>> 24) > 127) {
                    mask.setRGB(x + sx, y + sy, 0xFFFFFFFF);
                }
            }
        }
        return new SceneCanvas(image, mask);
    }
}
