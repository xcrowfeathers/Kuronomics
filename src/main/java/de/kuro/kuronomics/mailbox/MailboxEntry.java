package de.kuro.kuronomics.mailbox;

import java.util.*;

/** Save player identities as UUIDs. Resolve names when building the GUI view. */
public record MailboxEntry(UUID id, MailboxEventType type, long createdAt, Long amount, Long balance,
                           UUID otherPlayer, String subject, List<MailboxDetail> details) {
    public MailboxEntry {
        Objects.requireNonNull(id); Objects.requireNonNull(type);
        if (createdAt < 0) throw new IllegalArgumentException("Invalid mailbox timestamp");
        subject = subject == null ? "" : subject.strip();
        if (subject.length() > 256) subject = subject.substring(0, 256);
        details = List.copyOf(Objects.requireNonNull(details));
        if (details.size() > 64) throw new IllegalArgumentException("Too many mailbox details");
    }
}
