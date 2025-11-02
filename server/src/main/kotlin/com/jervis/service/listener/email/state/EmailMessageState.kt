package com.jervis.service.listener.email.state

enum class EmailMessageState {
    NEW,
    INDEXED,
    FAILED, // Message couldn't be fetched from IMAP (not found, invalid ID, etc.)
}
