package com.mycompany.p2pchat.bootstrap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

public class BootstrapEventLog {

    private static final int MAX_EVENTS = 200;
    private final Deque<EventEntry> events = new ConcurrentLinkedDeque<>();

    public void info(String type, String peer, String detail) {
        add("INFO", type, peer, detail);
    }

    public void warn(String type, String peer, String detail) {
        add("WARN", type, peer, detail);
    }

    public void error(String type, String peer, String detail) {
        add("ERROR", type, peer, detail);
    }

    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (EventEntry entry : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", entry.timestamp());
            row.put("level", entry.level());
            row.put("type", entry.type());
            row.put("peer", entry.peer());
            row.put("detail", entry.detail());
            payload.add(row);
        }
        return payload;
    }

    private void add(String level, String type, String peer, String detail) {
        events.addFirst(new EventEntry(Instant.now().toString(), level, type, peer, detail));
        while (events.size() > MAX_EVENTS) {
            events.pollLast();
        }
    }

    private record EventEntry(String timestamp, String level, String type, String peer, String detail) {}
}
