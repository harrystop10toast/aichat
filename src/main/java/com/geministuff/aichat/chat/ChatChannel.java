package com.geministuff.aichat.chat;

public enum ChatChannel {
    GUILD("guild"),
    PARTY("party"),
    PUBLIC("public");

    /** Also used as the ConversationMemory key for this channel. */
    public final String key;

    ChatChannel(String key) {
        this.key = key;
    }
}
