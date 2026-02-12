// cpg-export-query.sc — Joern CPG export for ArangoDB ingest
//
// Generates a pruned CPG export as JSON, keeping only agent-useful
// node types and edges (methods, types, calls, inheritance, type refs).
//
// Usage: joern --script cpg-export-query.sc --param inputPath=/path/to/project
//
// Output: $inputPath/.jervis/cpg-export.json

import scala.util.Using
import java.io.PrintWriter

@main def main(inputPath: String) = {
  println(s"[CPG Export] Importing code from: $inputPath")
  importCode(inputPath)
  println(s"[CPG Export] CPG generated, extracting nodes and edges...")

  // 1. Export internal methods (skip external/library methods)
  val methods = cpg.method.l
    .filter(!_.isExternal)
    .filter(_.filename != "<empty>")
    .map { m =>
      ujson.Obj(
        "name" -> m.name,
        "fullName" -> m.fullName,
        "signature" -> m.signature,
        "filename" -> m.filename,
        "lineNumber" -> m.lineNumber.getOrElse(-1),
      )
    }
  println(s"[CPG Export] Methods: ${methods.size}")

  // 2. Export internal type declarations (classes, interfaces, structs)
  val types = cpg.typeDecl.l
    .filter(!_.isExternal)
    .filter(_.filename != "<empty>")
    .map { t =>
      ujson.Obj(
        "name" -> t.name,
        "fullName" -> t.fullName,
        "filename" -> t.filename,
        "inheritsFrom" -> ujson.Arr(t.inheritsFromTypeFullName.l.map(ujson.Str(_)): _*),
      )
    }
  println(s"[CPG Export] Types: ${types.size}")

  // 3. Export call edges (internal caller → internal callee)
  val calls = cpg.call.l
    .filter(c => {
      val callees = c.callee.l
      callees.nonEmpty && !callees.head.isExternal
    })
    .filter(_.method != null)
    .filter(!_.method.isExternal)
    .map { c =>
      ujson.Obj(
        "callerMethod" -> c.method.fullName,
        "calledMethod" -> c.callee.fullName.headOption.getOrElse(""),
        "filename" -> c.filename,
        "lineNumber" -> c.lineNumber.getOrElse(-1),
      )
    }
  println(s"[CPG Export] Calls: ${calls.size}")

  // 4. Export member/field type references
  val typeRefs = cpg.member.l
    .filter(m => m.typeDecl != null && !m.typeDecl.isExternal)
    .map { m =>
      ujson.Obj(
        "className" -> m.typeDecl.fullName,
        "memberName" -> m.name,
        "memberType" -> m.typeFullName,
      )
    }
  println(s"[CPG Export] Type refs: ${typeRefs.size}")

  // 5. Combine and write JSON
  val result = ujson.Obj(
    "methods" -> ujson.Arr(methods: _*),
    "types" -> ujson.Arr(types: _*),
    "calls" -> ujson.Arr(calls: _*),
    "typeRefs" -> ujson.Arr(typeRefs: _*),
  )

  val outputPath = s"$inputPath/.jervis/cpg-export.json"
  new java.io.File(s"$inputPath/.jervis").mkdirs()
  Using(new PrintWriter(outputPath)) { pw =>
    pw.write(ujson.write(result, indent = 2))
  }

  println(s"[CPG Export] Written to: $outputPath")
  println(s"[CPG Export] Summary: ${methods.size} methods, ${types.size} types, ${calls.size} calls, ${typeRefs.size} typeRefs")
}
