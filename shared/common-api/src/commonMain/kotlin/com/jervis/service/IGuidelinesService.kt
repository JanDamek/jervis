package com.jervis.service

import com.jervis.dto.guidelines.GuidelinesDocumentDto
import com.jervis.dto.guidelines.GuidelinesUpdateRequest
import com.jervis.dto.guidelines.MergedGuidelinesDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IGuidelinesService {
    /** Get guidelines for a specific scope (raw, unmerged). Returns default if not found. */
    suspend fun getGuidelines(scope: String, clientId: String?, projectId: String?): GuidelinesDocumentDto

    /** Update guidelines for a specific scope. Only non-null categories are updated. */
    suspend fun updateGuidelines(request: GuidelinesUpdateRequest): GuidelinesDocumentDto

    /** Get merged (resolved) guidelines for a client+project context (GLOBAL → CLIENT → PROJECT). */
    suspend fun getMergedGuidelines(clientId: String?, projectId: String?): MergedGuidelinesDto

    /** Delete guidelines for a specific scope. Returns true if deleted. */
    suspend fun deleteGuidelines(scope: String, clientId: String?, projectId: String?): Boolean
}
