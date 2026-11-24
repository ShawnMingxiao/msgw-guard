package com.msgwguard.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Time {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Time() {
    }

    public static String now() {
        return LocalDateTime.now().format(FORMAT);
    }
}
