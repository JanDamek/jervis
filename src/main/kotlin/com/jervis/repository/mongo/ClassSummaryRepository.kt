package com.jervis.repository.mongo

import com.jervis.entity.mongo.ClassSummaryDocument
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for class summary documents.
 */
@Repository
interface ClassSummaryRepository : CoroutineCrudRepository<ClassSummaryDocument, ObjectId>
