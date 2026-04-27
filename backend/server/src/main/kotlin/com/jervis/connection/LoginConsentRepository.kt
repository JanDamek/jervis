package com.jervis.connection

import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for the singleton LoginConsentDocument (`_id="GLOBAL"`).
 *
 * Reads/writes are atomic at the Mongo document level — the service
 * layer owns concurrency control via a coroutine Mutex, since the
 * shape (one document) makes optimistic locking unnecessary.
 */
@Repository
interface LoginConsentRepository : CoroutineCrudRepository<LoginConsentDocument, String>
