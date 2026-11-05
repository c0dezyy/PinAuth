package com.kodeshi.pinauth;

/** What the PIN window is currently asking the player for. */
public enum Flow {
    NONE("login"),
    LOGIN("login"),
    REGISTER("register"),
    REGISTER_CONFIRM("register-confirm"),
    CHANGE_OLD("change-old"),
    CHANGE_NEW("change-new"),
    CHANGE_CONFIRM("change-confirm");

    public final String key;

    Flow(String key) {
        this.key = key;
    }

    public boolean isChange() {
        return this == CHANGE_OLD || this == CHANGE_NEW || this == CHANGE_CONFIRM;
    }
}
