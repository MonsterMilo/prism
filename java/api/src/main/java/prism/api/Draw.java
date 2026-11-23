package prism.api;

/** Simple 2D drawing. Only call it inside a RenderOverlayEvent handler. Colors are 0xAARRGGBB. */
public interface Draw {
    /** Draws text with a shadow; supports § colour codes. Returns the text width. */
    int text(String text, float x, float y, int color);

    int textWidth(String text);

    /** Height of one line of text. */
    int fontHeight();

    /** Filled rectangle from (x1, y1) to (x2, y2). */
    void rect(int x1, int y1, int x2, int y2, int color);
}
