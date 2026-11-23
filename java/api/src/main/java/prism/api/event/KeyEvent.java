package prism.api.event;

/**
 * A key went down or up while playing (not while a menu or chat is open).
 * Key codes are LWJGL codes (e.g. 30 = A). Cancel to hide it from the game's keybinds.
 */
public final class KeyEvent extends Cancellable {
    private final int key;
    private final boolean pressed;

    public KeyEvent(int key, boolean pressed) {
        this.key = key;
        this.pressed = pressed;
    }

    public int key() { return key; }
    public boolean pressed() { return pressed; }
}
