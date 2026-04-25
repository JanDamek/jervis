package com.jervis.service.tts

import com.jervis.dto.tts.TtsRuleDto
import com.jervis.dto.tts.TtsRulePreviewDto
import com.jervis.dto.tts.TtsRulePreviewRequestDto
import com.jervis.dto.tts.TtsRulesSnapshotDto
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * kRPC surface for TTS normalization rules — the editable dictionary that
 * XTTS uses for acronym expansion / ID strip / markdown clean-up.
 *
 * Per guideline #9 (push-only): the list view subscribes to
 * [subscribeAll] instead of calling a unary list. Mutations return the
 * updated rule but UI relies on the stream to reflect the change.
 */
@Rpc
interface ITtsRuleService {
    /** Live snapshot of all rules; emits on every add/update/delete. */
    fun subscribeAll(): Flow<TtsRulesSnapshotDto>

    suspend fun add(rule: TtsRuleDto): TtsRuleDto
    suspend fun update(rule: TtsRuleDto): TtsRuleDto
    suspend fun delete(id: String)

    /** Dry-run: apply matching rules to [request.text] and return the
     *  normalized output plus hit trace. */
    suspend fun preview(request: TtsRulePreviewRequestDto): TtsRulePreviewDto
}
