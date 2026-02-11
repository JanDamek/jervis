"""Tree-sitter based code parser for extracting structural info from source files.

Extracts class/interface names, qualified names, method signatures, and visibility
from source files. Used during git structural ingest to populate class graph nodes.

Supported languages: Kotlin, Java, Python, TypeScript, JavaScript, Go, Rust.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path

logger = logging.getLogger(__name__)

# Language name -> tree-sitter language identifier mapping
_EXTENSION_TO_LANG: dict[str, str] = {
    ".kt": "kotlin",
    ".kts": "kotlin",
    ".java": "java",
    ".py": "python",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".js": "javascript",
    ".jsx": "javascript",
    ".go": "go",
    ".rs": "rust",
}

# Lazy-loaded tree-sitter languages
_loaded_languages: dict[str, object] = {}


@dataclass
class ClassInfo:
    """Extracted class/interface information."""
    name: str
    qualified_name: str = ""
    file_path: str = ""
    visibility: str = "public"
    is_interface: bool = False
    methods: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "qualifiedName": self.qualified_name or self.name,
            "filePath": self.file_path,
            "visibility": self.visibility,
            "isInterface": self.is_interface,
            "methods": self.methods,
        }


def _get_language(lang_name: str):
    """Lazy-load a tree-sitter language."""
    if lang_name in _loaded_languages:
        return _loaded_languages[lang_name]

    try:
        from tree_sitter_languages import get_language
        lang = get_language(lang_name)
        _loaded_languages[lang_name] = lang
        return lang
    except Exception as e:
        logger.warning("Failed to load tree-sitter language %s: %s", lang_name, e)
        return None


def _get_parser(lang_name: str):
    """Create a tree-sitter parser for the given language."""
    lang = _get_language(lang_name)
    if not lang:
        return None

    try:
        from tree_sitter_languages import get_parser
        return get_parser(lang_name)
    except Exception as e:
        logger.warning("Failed to create parser for %s: %s", lang_name, e)
        return None


def detect_language(file_path: str) -> str | None:
    """Detect language from file extension."""
    ext = Path(file_path).suffix.lower()
    return _EXTENSION_TO_LANG.get(ext)


def parse_file(file_path: str, content: str) -> list[ClassInfo]:
    """Parse a source file and extract class/interface definitions.

    Args:
        file_path: Path to the file (used for language detection and qualified names).
        content: Source code content.

    Returns:
        List of ClassInfo extracted from the file.
    """
    lang = detect_language(file_path)
    if not lang:
        return []

    parser = _get_parser(lang)
    if not parser:
        return []

    try:
        tree = parser.parse(content.encode("utf-8"))
        root = tree.root_node

        if lang == "kotlin":
            return _extract_kotlin(root, content, file_path)
        elif lang == "java":
            return _extract_java(root, content, file_path)
        elif lang == "python":
            return _extract_python(root, content, file_path)
        elif lang in ("typescript", "javascript"):
            return _extract_typescript(root, content, file_path)
        elif lang == "go":
            return _extract_go(root, content, file_path)
        elif lang == "rust":
            return _extract_rust(root, content, file_path)
        else:
            return []
    except Exception as e:
        logger.warning("Failed to parse %s: %s", file_path, e)
        return []


def parse_files(files: list[tuple[str, str]], max_files: int = 100) -> list[ClassInfo]:
    """Parse multiple files and aggregate class info.

    Args:
        files: List of (file_path, content) tuples.
        max_files: Maximum number of files to parse.

    Returns:
        Aggregated list of ClassInfo from all files.
    """
    all_classes: list[ClassInfo] = []
    for file_path, content in files[:max_files]:
        classes = parse_file(file_path, content)
        all_classes.extend(classes)
    return all_classes


# ---------------------------------------------------------------------------
# Language-specific extractors
# ---------------------------------------------------------------------------

def _text(node, source: str) -> str:
    """Get text content of a node."""
    return source[node.start_byte:node.end_byte]


def _find_children(node, type_name: str) -> list:
    """Find all direct children of a given type."""
    return [c for c in node.children if c.type == type_name]


def _find_descendants(node, type_name: str) -> list:
    """Find all descendants of a given type (recursive)."""
    results = []
    for c in node.children:
        if c.type == type_name:
            results.append(c)
        results.extend(_find_descendants(c, type_name))
    return results


def _extract_visibility(node, source: str, default: str = "public") -> str:
    """Extract visibility modifier from modifiers node."""
    for child in node.children:
        if child.type == "modifiers" or child.type == "modifier_list":
            mod_text = _text(child, source).lower()
            for vis in ("public", "private", "protected", "internal"):
                if vis in mod_text:
                    return vis
        if child.type in ("visibility_modifier", "access_modifier"):
            return _text(child, source).lower()
    return default


def _extract_package_kotlin_java(root, source: str) -> str:
    """Extract package declaration from Kotlin or Java file."""
    for child in root.children:
        if child.type == "package_header" or child.type == "package_declaration":
            # Find the identifier within
            for sub in child.children:
                if sub.type == "identifier" or sub.type == "scoped_identifier":
                    return _text(sub, source)
    return ""


def _extract_kotlin(root, source: str, file_path: str) -> list[ClassInfo]:
    """Extract classes and interfaces from Kotlin source."""
    package = _extract_package_kotlin_java(root, source)
    classes: list[ClassInfo] = []

    for node in _find_descendants(root, "class_declaration"):
        name_node = node.child_by_field_name("name")
        if not name_node:
            # Try finding simple_identifier child
            for c in node.children:
                if c.type == "type_identifier" or c.type == "simple_identifier":
                    name_node = c
                    break
        if not name_node:
            continue

        name = _text(name_node, source)
        qualified = f"{package}.{name}" if package else name
        visibility = _extract_visibility(node, source)

        # Check if interface
        is_interface = False
        for c in node.children:
            t = _text(c, source)
            if t == "interface":
                is_interface = True
                break

        # Extract methods
        methods = []
        for func_node in _find_descendants(node, "function_declaration"):
            fn_name = func_node.child_by_field_name("name")
            if not fn_name:
                for c in func_node.children:
                    if c.type == "simple_identifier":
                        fn_name = c
                        break
            if fn_name:
                methods.append(_text(fn_name, source))

        classes.append(ClassInfo(
            name=name,
            qualified_name=qualified,
            file_path=file_path,
            visibility=visibility,
            is_interface=is_interface,
            methods=methods[:50],
        ))

    # Also look for object declarations (Kotlin singletons)
    for node in _find_descendants(root, "object_declaration"):
        name_node = node.child_by_field_name("name")
        if not name_node:
            for c in node.children:
                if c.type == "type_identifier" or c.type == "simple_identifier":
                    name_node = c
                    break
        if not name_node:
            continue

        name = _text(name_node, source)
        qualified = f"{package}.{name}" if package else name

        methods = []
        for func_node in _find_descendants(node, "function_declaration"):
            fn_name = func_node.child_by_field_name("name")
            if not fn_name:
                for c in func_node.children:
                    if c.type == "simple_identifier":
                        fn_name = c
                        break
            if fn_name:
                methods.append(_text(fn_name, source))

        classes.append(ClassInfo(
            name=name,
            qualified_name=qualified,
            file_path=file_path,
            visibility=_extract_visibility(node, source),
            is_interface=False,
            methods=methods[:50],
        ))

    return classes


def _extract_java(root, source: str, file_path: str) -> list[ClassInfo]:
    """Extract classes and interfaces from Java source."""
    package = _extract_package_kotlin_java(root, source)
    classes: list[ClassInfo] = []

    for node_type in ("class_declaration", "interface_declaration", "enum_declaration"):
        for node in _find_descendants(root, node_type):
            name_node = node.child_by_field_name("name")
            if not name_node:
                continue

            name = _text(name_node, source)
            qualified = f"{package}.{name}" if package else name
            is_interface = node_type == "interface_declaration"
            visibility = _extract_visibility(node, source)

            methods = []
            for method_node in _find_descendants(node, "method_declaration"):
                mn = method_node.child_by_field_name("name")
                if mn:
                    methods.append(_text(mn, source))

            classes.append(ClassInfo(
                name=name,
                qualified_name=qualified,
                file_path=file_path,
                visibility=visibility,
                is_interface=is_interface,
                methods=methods[:50],
            ))

    return classes


def _extract_python(root, source: str, file_path: str) -> list[ClassInfo]:
    """Extract classes from Python source."""
    # Derive module name from file path
    module = Path(file_path).stem
    classes: list[ClassInfo] = []

    for node in _find_descendants(root, "class_definition"):
        name_node = node.child_by_field_name("name")
        if not name_node:
            continue

        name = _text(name_node, source)
        qualified = f"{module}.{name}"

        methods = []
        for func_node in _find_descendants(node, "function_definition"):
            fn_name = func_node.child_by_field_name("name")
            if fn_name:
                method_name = _text(fn_name, source)
                if not method_name.startswith("_") or method_name == "__init__":
                    methods.append(method_name)

        visibility = "private" if name.startswith("_") else "public"

        classes.append(ClassInfo(
            name=name,
            qualified_name=qualified,
            file_path=file_path,
            visibility=visibility,
            is_interface=False,
            methods=methods[:50],
        ))

    return classes


def _extract_typescript(root, source: str, file_path: str) -> list[ClassInfo]:
    """Extract classes and interfaces from TypeScript/JavaScript source."""
    module = Path(file_path).stem
    classes: list[ClassInfo] = []

    for node_type in ("class_declaration", "interface_declaration"):
        for node in _find_descendants(root, node_type):
            name_node = node.child_by_field_name("name")
            if not name_node:
                continue

            name = _text(name_node, source)
            qualified = f"{module}.{name}"
            is_interface = node_type == "interface_declaration"

            methods = []
            for method_node in _find_descendants(node, "method_definition"):
                mn = method_node.child_by_field_name("name")
                if mn:
                    methods.append(_text(mn, source))

            classes.append(ClassInfo(
                name=name,
                qualified_name=qualified,
                file_path=file_path,
                visibility="public",
                is_interface=is_interface,
                methods=methods[:50],
            ))

    return classes


def _extract_go(root, source: str, file_path: str) -> list[ClassInfo]:
    """Extract type declarations from Go source."""
    module = Path(file_path).stem
    classes: list[ClassInfo] = []

    for node in _find_descendants(root, "type_declaration"):
        for spec in _find_children(node, "type_spec"):
            name_node = spec.child_by_field_name("name")
            if not name_node:
                continue

            name = _text(name_node, source)
            qualified = f"{module}.{name}"

            type_node = spec.child_by_field_name("type")
            is_interface = type_node and type_node.type == "interface_type"

            methods = []
            if is_interface and type_node:
                for method_spec in _find_descendants(type_node, "method_spec"):
                    mn = method_spec.child_by_field_name("name")
                    if mn:
                        methods.append(_text(mn, source))

            visibility = "public" if name[0].isupper() else "private"

            classes.append(ClassInfo(
                name=name,
                qualified_name=qualified,
                file_path=file_path,
                visibility=visibility,
                is_interface=is_interface,
                methods=methods[:50],
            ))

    return classes


def _extract_rust(root, source: str, file_path: str) -> list[ClassInfo]:
    """Extract struct/trait declarations from Rust source."""
    module = Path(file_path).stem
    classes: list[ClassInfo] = []

    for node_type, is_iface in [("struct_item", False), ("trait_item", True)]:
        for node in _find_descendants(root, node_type):
            name_node = node.child_by_field_name("name")
            if not name_node:
                continue

            name = _text(name_node, source)
            qualified = f"{module}::{name}"
            visibility = "public" if any(
                _text(c, source) == "pub" for c in node.children
            ) else "private"

            methods = []
            if is_iface:
                for func_node in _find_descendants(node, "function_item"):
                    fn_name = func_node.child_by_field_name("name")
                    if fn_name:
                        methods.append(_text(fn_name, source))

            classes.append(ClassInfo(
                name=name,
                qualified_name=qualified,
                file_path=file_path,
                visibility=visibility,
                is_interface=is_iface,
                methods=methods[:50],
            ))

    # Also extract impl blocks methods and associate with struct
    for node in _find_descendants(root, "impl_item"):
        type_node = node.child_by_field_name("type")
        if not type_node:
            continue
        type_name = _text(type_node, source)

        methods = []
        for func_node in _find_descendants(node, "function_item"):
            fn_name = func_node.child_by_field_name("name")
            if fn_name:
                methods.append(_text(fn_name, source))

        # Find matching class and add methods
        for cls in classes:
            if cls.name == type_name:
                cls.methods.extend(methods[:50])
                break

    return classes
