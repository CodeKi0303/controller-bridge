package dev.codek.xboxprobe.core;

/** One finite diagnostic input. IDs 1..3 preserve the original Binder contract. */
public enum GamepadPulse {
    A(1, "A", button(GamepadState.A)),
    LEFT_STICK_RIGHT(2, "왼쪽 스틱 →", stick(0.6f, 0, 0, 0)),
    RIGHT_TRIGGER(3, "RT", trigger(false)),
    LEFT_STICK_LEFT(4, "왼쪽 스틱 ←", stick(-0.6f, 0, 0, 0)),
    LEFT_STICK_UP(5, "왼쪽 스틱 ↑", stick(0, -0.6f, 0, 0)),
    LEFT_STICK_DOWN(6, "왼쪽 스틱 ↓", stick(0, 0.6f, 0, 0)),
    B(7, "B", button(GamepadState.B)),
    X(8, "X", button(GamepadState.X)),
    Y(9, "Y", button(GamepadState.Y)),
    DPAD_UP(10, "십자키 ↑", hat(0)),
    DPAD_RIGHT(11, "십자키 →", hat(2)),
    DPAD_DOWN(12, "십자키 ↓", hat(4)),
    DPAD_LEFT(13, "십자키 ←", hat(6)),
    LEFT_SHOULDER(14, "LB", button(GamepadState.L1)),
    RIGHT_SHOULDER(15, "RB", button(GamepadState.R1)),
    LEFT_STICK_CLICK(16, "왼쪽 스틱 클릭", button(GamepadState.L3)),
    RIGHT_STICK_CLICK(17, "오른쪽 스틱 클릭", button(GamepadState.R3)),
    MENU(18, "Menu / Start", button(GamepadState.START)),
    VIEW(19, "View / Select", button(GamepadState.SELECT)),
    LEFT_TRIGGER(20, "LT", trigger(true)),
    RIGHT_STICK_RIGHT(21, "오른쪽 스틱 →", stick(0, 0, 0.6f, 0)),
    RIGHT_STICK_LEFT(22, "오른쪽 스틱 ←", stick(0, 0, -0.6f, 0)),
    RIGHT_STICK_UP(23, "오른쪽 스틱 ↑", stick(0, 0, 0, -0.6f)),
    RIGHT_STICK_DOWN(24, "오른쪽 스틱 ↓", stick(0, 0, 0, 0.6f));

    private final int id;
    private final String label;
    private final GamepadState state;

    GamepadPulse(int id, String label, GamepadState state) {
        this.id = id;
        this.label = label;
        this.state = state;
    }

    public int getId() { return id; }
    public String getLabel() { return label; }
    public int getHoldMs() {
        return state.leftX != 0 || state.leftY != 0 || state.rightX != 0 || state.rightY != 0 ? 1500 : 250;
    }
    public GamepadState getState() { return state; }
    public NativeGamepadState getNativeState() {
        return NativeGamepadState.fromGamepadState(state);
    }
    public static GamepadPulse forId(int id) {
        for (GamepadPulse pulse : values()) if (pulse.id == id) return pulse;
        throw new IllegalArgumentException("Unknown gamepad pulse ID: " + id);
    }

    private static GamepadState button(int buttons) { return new GamepadState(0, 0, 0, 0, 0, 0, -1, buttons); }
    private static GamepadState hat(int value) { return new GamepadState(0, 0, 0, 0, 0, 0, value, 0); }
    private static GamepadState trigger(boolean left) { return new GamepadState(0, 0, 0, 0, left ? 1 : 0, left ? 0 : 1, -1, 0); }
    private static GamepadState stick(float lx, float ly, float rx, float ry) { return new GamepadState(lx, ly, rx, ry, 0, 0, -1, 0); }
}
