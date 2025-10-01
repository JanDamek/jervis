importCpg("{{CPG_PATH}}")

val targetFile = "{{TARGET_FILE}}"
val language = cpg.metaData.language.headOption.getOrElse("UNKNOWN")

// Get classes from the target file
val classData = cpg.typeDecl.filterNot(_.isExternal)
  .filter(node => node.filename.contains(targetFile) || node.filename.endsWith(targetFile.split("/").last))
  .map { cls =>
    val lineStart = cls.lineNumber.headOption.getOrElse(0)
    val lineEnd = cls.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(lineStart)
    s"""{
      "type": "CLASS",
      "name": "${cls.name}",
      "fullName": "${cls.fullName}",
      "filePath": "${cls.filename}",
      "lineStart": $lineStart,
      "lineEnd": $lineEnd,
      "nodeId": "${cls.id}",
      "language": "$language"
    }"""
  }.toList

// Get methods from the target file
val methodData = cpg.method.filterNot(_.isExternal)
  .filter(node => node.filename.contains(targetFile) || node.filename.endsWith(targetFile.split("/").last))
  .map { method =>
    val lineStart = method.lineNumber.headOption.getOrElse(0)
    val lineEnd = method.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(lineStart)
    val parentClass = method.typeDecl.name.headOption.getOrElse("")
    s"""{
      "type": "METHOD",
      "name": "${method.name}",
      "fullName": "${method.fullName}",
      "signature": "${method.signature}",
      "filePath": "${method.filename}",
      "lineStart": $lineStart,
      "lineEnd": $lineEnd,
      "nodeId": "${method.id}",
      "parentClass": "$parentClass",
      "language": "$language"
    }"""
  }.toList

// Combine and output
val allData = classData ++ methodData
allData.foreach(println)