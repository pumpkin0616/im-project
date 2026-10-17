package com.example.im.message.domain;

public enum MessageType {
    TEXT(1),
    IMAGE(2),
    FILE(3);

    private final int databaseCode;

    MessageType(int databaseCode) {
        this.databaseCode = databaseCode;
    }

    public int databaseCode() {
        return databaseCode;
    }
}

