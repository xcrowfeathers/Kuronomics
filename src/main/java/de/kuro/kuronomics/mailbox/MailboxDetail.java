package de.kuro.kuronomics.mailbox;

import java.util.Objects;

public record MailboxDetail(Kind kind, String label, Long amount) {
    public enum Kind { INCOMING, OUTGOING, WARNING, INFO }

    public MailboxDetail {
        Objects.requireNonNull(kind);
        label = Objects.requireNonNull(label).strip();
        if (label.isEmpty()) throw new IllegalArgumentException("Invalid mailbox detail");
        if (label.length() > 256) label = label.substring(0, 256);
    }
}
