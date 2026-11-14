package de.kuro.kuronomics.economy;

import java.io.*;
import java.security.*;
import java.util.*;
import net.minecraft.nbt.*;

/**
 * Versioned journal hashes. The tree caches the latest snapshot; don't change its tags afterward.
 * Version 2 keeps lists in a fixed format; version 3 also expands the company-record list.
 */
final class FinancialDigest {
    record Node(Tag tag, byte[] hash, Map<String, Node> fields, List<Node> items, int version, boolean expanded) {}
    static Node tree(Tag tag, Node previous) throws IOException {
        return tree(tag, previous, 2, false);
    }
    /** Expand company records, but keep large contract/account lists as compact leaves in the fixed format. */
    static Node tree3(Tag tag, Node previous) throws IOException { return tree(tag, previous, 3, false); }
    private static Node tree(Tag tag, Node previous, int version, boolean expanded) throws IOException {
        if (previous != null && previous.version() != version) previous = null;
        if (previous != null && previous.tag() == tag && previous.expanded() == expanded) return previous;
        MessageDigest digest = sha256();
        Map<String, Node> fields = Map.of(); List<Node> items = List.of();
        try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            output.writeByte(tag.getId());
            if (tag instanceof CompoundTag compound) {
                String[] keys = compound.getAllKeys().toArray(String[]::new); Arrays.sort(keys); output.writeInt(keys.length);
                var children = new HashMap<String, Node>();
                for (String key : keys) {
                    Tag value = compound.get(key);
                    Node child = tree(value, previous == null ? null : previous.fields().get(key), version,
                            version >= 3 && key.equals("companies") && value instanceof ListTag);
                    children.put(key, child); output.writeUTF(key); output.write(child.hash());
                }
                fields = Map.copyOf(children);
            } else if (tag instanceof ListTag list) {
                output.writeInt(list.size()); var children = new ArrayList<Node>(list.size());
                List<Node> old = previous == null ? List.of() : previous.items(); IdentityHashMap<Tag, Node> moved = null;
                for (int i = 0; i < list.size(); i++) {
                    Tag value = list.get(i); Node child = i < old.size() ? old.get(i) : null;
                    if (child == null || child.tag() != value) {
                        if (moved == null) { moved = new IdentityHashMap<>(); for (Node node : old) moved.put(node.tag(), node); }
                        child = moved.get(value);
                    }
                    if (expanded) child = tree(value, child, version, false);
                    else if (child == null) child = new Node(value, FinancialJournal.hash(value), Map.of(), List.of(), version, false);
                    children.add(child); output.write(child.hash());
                }
                items = List.copyOf(children);
            } else tag.write(output);
        }
        return new Node(tag, digest.digest(), fields, items, version, expanded);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private FinancialDigest() {}
}
