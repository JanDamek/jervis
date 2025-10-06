importCpg("{{CPG_PATH}}")
import io.shiftleft.semanticcpg.language._

val language = "{{LANGUAGE}}"
val analysisQuery = "{{ANALYSIS_QUERY}}"
val maxResults = {{MAX_RESULTS}}
val includeExternal = {{INCLUDE_EXTERNAL}}

// Execute the analysis query using Joern CPG API
val analysisResults = try {
  analysisQuery match {
    case query if query.contains("method") && query.contains("caller") =>
      // Call graph analysis - find callers of specified methods
      val methodPattern = "{{METHOD_PATTERN}}"
      cpg.method.name(methodPattern).map { method =>
        val callers = if (includeExternal) {
          method.caller.take(maxResults).l
        } else {
          method.caller.filterNot(c => c.fullName.startsWith("java.") || c.fullName.startsWith("scala.")).take(maxResults).l
        }
        Map(
          "method" -> method.name,
          "fullName" -> method.fullName,
          "signature" -> method.signature,
          "filePath" -> method.filename,
          "lineNumber" -> method.lineNumber.headOption.getOrElse(0),
          "callers" -> callers.map(c => Map(
            "name" -> c.name,
            "fullName" -> c.fullName,
            "signature" -> c.signature,
            "filePath" -> c.filename
          ))
        )
      }.take(maxResults).l
      
    case query if query.contains("method") && query.contains("callee") =>
      // Call graph analysis - find callees of specified methods
      val methodPattern = "{{METHOD_PATTERN}}"
      cpg.method.name(methodPattern).map { method =>
        val callees = if (includeExternal) {
          method.callee.take(maxResults).l
        } else {
          method.callee.filterNot(c => c.fullName.startsWith("java.") || c.fullName.startsWith("scala.")).take(maxResults).l
        }
        Map(
          "method" -> method.name,
          "fullName" -> method.fullName,
          "signature" -> method.signature,
          "filePath" -> method.filename,
          "lineNumber" -> method.lineNumber.headOption.getOrElse(0),
          "callees" -> callees.map(c => Map(
            "name" -> c.name,
            "fullName" -> c.fullName,
            "signature" -> c.signature,
            "filePath" -> c.filename
          ))
        )
      }.take(maxResults).l
      
    case query if query.contains("vulnerability") || query.contains("security") =>
      // Security analysis - find potential vulnerabilities
      val sqlInjection = cpg.call.name(".*execute.*|.*query.*").whereNot(_.argument.isLiteral).map { call =>
        Map(
          "type" -> "SQL_INJECTION_RISK",
          "location" -> call.code,
          "method" -> call.method.name.headOption.getOrElse(""),
          "filePath" -> call.filename,
          "lineNumber" -> call.lineNumber.headOption.getOrElse(0)
        )
      }.take(maxResults / 2).l
      
      val xss = cpg.call.name(".*print.*|.*write.*|.*append.*").whereNot(_.argument.isLiteral).map { call =>
        Map(
          "type" -> "XSS_RISK",
          "location" -> call.code,
          "method" -> call.method.name.headOption.getOrElse(""),
          "filePath" -> call.filename,
          "lineNumber" -> call.lineNumber.headOption.getOrElse(0)
        )
      }.take(maxResults / 2).l
      
      sqlInjection ++ xss
      
    case query if query.contains("dataflow") || query.contains("flow") =>
      // Data flow analysis
      val methodPattern = "{{METHOD_PATTERN}}"
      cpg.method.name(methodPattern).map { method =>
        Map(
          "method" -> method.name,
          "fullName" -> method.fullName,
          "parameters" -> method.parameter.map(p => Map(
            "name" -> p.name,
            "type" -> p.typeFullName
          )).l,
          "returns" -> method.methodReturn.typeFullName,
          "calls" -> method.call.take(10).map(c => Map(
            "name" -> c.name,
            "code" -> c.code
          )).l
        )
      }.take(maxResults).l
      
    case _ =>
      // General code query - methods overview
      val methods = if (includeExternal) {
        cpg.method.take(maxResults).l
      } else {
        cpg.method.filterNot(m => m.fullName.startsWith("java.") || m.fullName.startsWith("scala.")).take(maxResults).l
      }
      
      methods.map { method =>
        Map(
          "method" -> method.name,
          "fullName" -> method.fullName,
          "signature" -> method.signature,
          "filePath" -> method.filename,
          "lineNumber" -> method.lineNumber.headOption.getOrElse(0),
          "callerCount" -> method.caller.size,
          "calleeCount" -> method.callee.size
        )
      }
  }
} catch {
  case e: Exception => List(Map("error" -> e.getMessage, "query" -> analysisQuery))
}

// Format output as JSON
val result = Map(
  "language" -> language,
  "analysisQuery" -> analysisQuery,
  "maxResults" -> maxResults,
  "includeExternal" -> includeExternal,
  "resultCount" -> analysisResults.size,
  "results" -> analysisResults
)

println(result.toJson)
