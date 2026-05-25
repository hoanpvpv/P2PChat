package com.mycompany.p2pchat.peer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Rendezvous Hashing (Highest Random Weight) — Thaler & Ravishankar, 1996.
 * Deterministic coordinator selection without central authority.
 * Each peer computes the same coordinator set from the same member list + groupId.
 */
public class HRWHash {

    /**
     * Select top-K coordinators from members for a given groupId.
     * Score = first 8 bytes of SHA-256(groupId + "|" + address), interpreted as unsigned long.
     * Members are sorted descending by score; top K are coordinators.
     */
    public static List<String> topK(List<String> members, String groupId, int k) {
        return members.stream()
                .sorted(Comparator.comparingLong((String m) -> score(groupId, m)).reversed())
                .limit(Math.min(k, members.size()))
                .collect(Collectors.toList());
    }

    /**
     * Compute HRW score for a member address within a group.
     */
    public static long score(String groupId, String address) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((groupId + "|" + address).getBytes(StandardCharsets.UTF_8));
            return bytesToLong(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Convert first 8 bytes of hash to unsigned long value.
     */
    private static long bytesToLong(byte[] bytes) {
        long value = 0;
        for (int i = 0; i < 8 && i < bytes.length; i++) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }

    /**
     * Check if a given address is a coordinator for the group.
     */
    public static boolean isCoordinator(List<String> members, String groupId, String address, int k) {
        return topK(members, groupId, k).contains(address);
    }

    /**
     * Get the rank (0-based) of an address in the coordinator ordering.
     * Returns -1 if address is not in members.
     */
    public static int getRank(List<String> members, String groupId, String address) {
        List<String> sorted = members.stream()
                .sorted(Comparator.comparingLong((String m) -> score(groupId, m)).reversed())
                .collect(Collectors.toList());
        return sorted.indexOf(address);
    }
}
