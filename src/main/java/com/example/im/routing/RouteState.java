package com.example.im.routing;

public enum RouteState {
    ACTIVE(1), MIGRATING(2), DISABLED(3);

    private final int databaseCode;

    RouteState(int databaseCode) {
        this.databaseCode = databaseCode;
    }

    public int databaseCode() {
        return databaseCode;
    }

    public static RouteState fromDatabase(int value) {
        return switch (value) {
            case 1 -> ACTIVE;
            case 2 -> MIGRATING;
            case 3 -> DISABLED;
            default -> throw new IllegalArgumentException("Unknown route state " + value);
        };
    }
}
