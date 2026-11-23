package prism.api.event;

/**
 * A mouse button went down or up while playing (0 = left, 1 = right, 2 = middle).
 * Cancel to hide it from the game's keybinds (e.g. stop an attack or use).
 */
public final class MouseButtonEvent extends Cancellable {
    private final int button;
    private final boolean pressed;

    public MouseButtonEvent(int button, boolean pressed) {
        this.button = button;
        this.pressed = pressed;
    }

    public int button() { return button; }
    public boolean pressed() { return pressed; }
}
