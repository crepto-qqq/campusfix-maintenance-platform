package com.campusfix.user;

public enum Role {
    GENERAL_USER,
    MAINTENANCE_STAFF,
    ADMINISTRATOR,
    MANAGER;

    /** MANAGER remains readable for historic assignments but is no longer selectable. */
    public boolean isSelectable() {
        return this != MANAGER;
    }
}
