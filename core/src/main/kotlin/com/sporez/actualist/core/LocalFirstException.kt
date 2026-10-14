package com.sporez.actualist.core

/**
 * Typed failures of the local-first engine. Ported from Actualist's `LocalFirstError`;
 * only the cases the Android port can reach exist here.
 */
sealed class LocalFirstException(message: String) : Exception(message) {
    class InvalidSyncTimestamp : LocalFirstException("A sync message has an invalid timestamp.")
    class ClockDrift : LocalFirstException("A sync message is dated too far in the future.")
    class StoredChangeDatedInFuture(val wallTime: String) :
        LocalFirstException("A stored change is dated in the future ($wallTime).")
    class HybridLogicalClockOverflow : LocalFirstException("The local clock counter overflowed.")
    class InvalidSyncValue : LocalFirstException("A sync message value is unreadable.")
    class InvalidLocalWrite(val reason: String) : LocalFirstException("Invalid local write: $reason")
    class LocalWriteSuperseded : LocalFirstException("A newer change already exists for this value.")
    class SyncUploadNotConfirmed(val count: Int) :
        LocalFirstException("The server did not confirm $count uploaded change(s).")
    class SyncOutOfSync : LocalFirstException("This budget is out of sync with the server.")
    class EncryptedBudgetRequiresPassword : LocalFirstException("This budget is encrypted and needs its password.")
    class UndecryptableMessage : LocalFirstException("A sync message could not be decrypted.")
    class InvalidEncryptionKey : LocalFirstException("The encryption key is invalid.")
    class InvalidEncryptionPassword : LocalFirstException("The encryption password is incorrect.")
    class InvalidEncryptedPayload : LocalFirstException("An encrypted payload is malformed.")
    class UnsupportedEncryptionAlgorithm(val algorithm: String) :
        LocalFirstException("Unsupported encryption algorithm: $algorithm")
    class BudgetNotOpened : LocalFirstException("No budget is open.")
    class NumericValueOutOfRange : LocalFirstException("A number is out of range.")
    class UnsupportedBudgetAction : LocalFirstException("This action is not available for this budget type.")
    class SchemaUnavailable(val detail: String) : LocalFirstException("Budget schema unavailable: $detail")
    class InvalidDownloadedBudget(val detail: String) : LocalFirstException("The downloaded budget is invalid: $detail")
    class MalformedProtobuf(val detail: String) : LocalFirstException("Malformed sync payload: $detail")
}
