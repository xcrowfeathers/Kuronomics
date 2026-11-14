package de.kuro.kuronomics.mailbox;

/** Only store notifications here. Claimable items/assets need their own storage. */
public enum MailboxEventType {
    PAYDAY_REPORT,
    DEPOSIT,
    WITHDRAWAL,
    PAYMENT_SENT,
    PAYMENT_RECEIVED,
    RENT_WARNING,
    AUCTION_OUTBID,
    AUCTION_NO_BIDS,
    AUCTION_WON,
    AUCTION_PAYMENT_COMPLETED,
    AUCTION_PAYMENT_TIMEOUT,
    AUCTION_SOLD,
    AUCTION_RETURNED,
    AUCTION_CLAIM_READY,
    SYSTEM
}
