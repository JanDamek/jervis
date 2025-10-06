importCpg("{{CPG_PATH}}")
import io.shiftleft.semanticcpg.language.locationCreator
import io.shiftleft.semanticcpg.language.LazyLocation.apply

val language = "{{LANGUAGE}}"

// Get all classes (not filtered by file)
val classData = cpg.typeDecl.filterNot(_.isExternal)
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

// Get all methods (not filtered by file)
val methodData = cpg.method.filterNot(_.isExternal)
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

// Get all functions (functions are also methods in Joern, but we differentiate by context)
val functionData = cpg.method.filterNot(_.isExternal).filterNot(_.typeDecl.nonEmpty)
  .map { func =>
    val lineStart = func.lineNumber.headOption.getOrElse(0)
    val lineEnd = func.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(lineStart)
    s"""{ 
      "type": "FUNCTION",
      "name": "${func.name}",
      "fullName": "${func.fullName}",
      "signature": "${func.signature}",
      "filePath": "${func.filename}",
      "lineStart": $lineStart,
      "lineEnd": $lineEnd,
      "nodeId": "${func.id}",
      "language": "$language"
    }"""
  }.toList

// Get all variables (local variables)
val variableData = cpg.local
  .filter(_.method.nonEmpty)
  .map { variable =>
    val lineStart = variable.lineNumber.headOption.getOrElse(0)
    val lineEnd = variable.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(lineStart)
    val parentMethod = variable.method.name.headOption.getOrElse("")
    s"""{ 
      "type": "VARIABLE",
      "name": "${variable.name}",
      "fullName": "${variable.name}",
      "filePath": "${variable.filename}",
      "lineStart": $lineStart,
      "lineEnd": $lineEnd,
      "nodeId": "${variable.id}",
      "parentClass": "$parentMethod",
      "language": "$language"
    }"""
  }.toList

// Get all method calls
val callData = cpg.call.filterNot(_.name.startsWith("<"))
  .filter(_.method.nonEmpty)
  .map { call =>
    val lineStart = call.lineNumber.headOption.getOrElse(0)
    val lineEnd = call.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(lineStart)
    val parentMethod = call.method.name.headOption.getOrElse("")
    s"""{ 
      "type": "CALL",
      "name": "${call.name}",
      "fullName": "${call.name}",
      "signature": "${call.signature}",
      "filePath": "${call.filename}",
      "lineStart": $lineStart,
      "lineEnd": $lineEnd,
      "nodeId": "${call.id}",
      "parentClass": "$parentMethod",
      "language": "$language"
    }"""
  }.toList

// Get all imports
val importData = cpg.imports
  .map { imp =>
    val lineStart = imp.lineNumber.headOption.getOrElse(0)
    val lineEnd = imp.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(lineStart)
    s"""{ 
      "type": "IMPORT",
      "name": "${imp.importedEntity.getOrElse(imp.code)}",
      "fullName": "${imp.importedEntity.getOrElse(imp.code)}",
      "filePath": "${imp.filename}",
      "lineStart": $lineStart,
      "lineEnd": $lineEnd,
      "nodeId": "${imp.id}",
      "language": "$language"
    }"""
  }.toList

// Get all fields/members
val fieldData = cpg.member
  .map { field =>
    val lineStart = field.lineNumber.headOption.getOrElse(0)
    val lineEnd = field.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(lineStart)
    val parentClass = field.typeDecl.name.headOption.getOrElse("")
    s"""{ 
      "type": "FIELD",
      "name": "${field.name}",
      "fullName": "${field.name}",
      "filePath": "${field.filename}",
      "lineStart": $lineStart,
      "lineEnd": $lineEnd,
      "nodeId": "${field.id}",
      "parentClass": "$parentClass",
      "language": "$language"
    }"""
  }.toList

// Get all parameters
val parameterData = cpg.parameter.filterNot(_.name == "this")
  .filter(_.method.nonEmpty)
  .map { param =>
    val lineStart = param.lineNumber.headOption.getOrElse(0)
    val lineEnd = param.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(lineStart)
    val parentMethod = param.method.name.headOption.getOrElse("")
    s"""{ 
      "type": "PARAMETER",
      "name": "${param.name}",
      "fullName": "${param.name}",
      "filePath": "${param.filename}",
      "lineStart": $lineStart,
      "lineEnd": $lineEnd,
      "nodeId": "${param.id}",
      "parentClass": "$parentMethod",
      "language": "$language"
    }"""
  }.toList

// Get all files
val fileData = cpg.file
  .map { file =>
    s"""{ 
      "type": "FILE",
      "name": "${file.name}",
      "fullName": "${file.name}",
      "filePath": "${file.name}",
      "lineStart": 1,
      "lineEnd": ${file.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(1)},
      "nodeId": "${file.id}",
      "language": "$language"
    }"""
  }.toList

// Get all packages/namespaces
val packageData = cpg.namespaceBlock
  .map { pkg =>
    val lineStart = pkg.lineNumber.headOption.getOrElse(0)
    val lineEnd = pkg.ast.lineNumber.l.filter(_ > 0).maxOption.getOrElse(lineStart)
    s"""{ 
      "type": "PACKAGE",
      "name": "${pkg.name}",
      "fullName": "${pkg.fullName}",
      "filePath": "${pkg.filename}",
      "lineStart": $lineStart,
      "lineEnd": $lineEnd,
      "nodeId": "${pkg.id}",
      "language": "$language"
    }"""
  }.toList

// Get all modules (using namespace as well for module-like structures)
val moduleData = cpg.namespace.filterNot(_.name == "<global>")
  .map { module =>
    s"""{ 
      "type": "MODULE",
      "name": "${module.name}",
      "fullName": "${module.name}",
      "filePath": "",
      "lineStart": 0,
      "lineEnd": 0,
      "nodeId": "${module.id}",
      "language": "$language"
    }"""
  }.toList

// Combine and output all data
val allData = classData ++ methodData ++ functionData ++ variableData ++ callData ++ importData ++ fieldData ++ parameterData ++ fileData ++ packageData ++ moduleData
allData.foreach(println)