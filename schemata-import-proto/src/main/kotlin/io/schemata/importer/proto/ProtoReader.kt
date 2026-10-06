package io.schemata.importer.proto

/**
 * Reads one `.proto` file (proto2, proto3, or editions) into a [ProtoFile]; every construct the
 * lowering reads or drops is kept with its position.
 */
object ProtoReader {
    /** The largest field number; `max` in a message's `reserved` range reads as this. */
    const val MAX_FIELD_NUMBER = 536870911

    fun read(path: String, text: String): ProtoFile = Parser(path, ProtoLexer.lex(text)).file()

    private class Parser(private val path: String, private val tokens: List<Token>) {
        private var i = 0

        /** Comments that stand on their own lines since the last token consumed. */
        private val pending = mutableListOf<Token>()

        /**
         * The line the last consumed token ended on; a comment starting there trails that token.
         */
        private var lastLine = 0

        private fun peek(): Token {
            skipComments()
            return tokens[i]
        }

        private fun skipComments() {
            while (tokens[i].kind == TokenKind.COMMENT) {
                val c = tokens[i++]
                if (c.pos.line != lastLine) pending += c
            }
        }

        /** The token after the next one, comments skipped. */
        private fun peekSecond(): Token {
            if (peek().kind == TokenKind.EOF) return tokens[i]
            var j = i + 1
            while (tokens[j].kind == TokenKind.COMMENT) j++
            return tokens[j]
        }

        private fun next(): Token {
            val t = peek()
            if (t.kind != TokenKind.EOF) i++
            lastLine = t.endLine
            pending.clear()
            return t
        }

        private fun fail(message: String, at: Pos = peek().pos): Nothing =
            throw ProtoSyntaxError(at, message)

        private fun at(sym: String) = peek().let { it.kind == TokenKind.SYMBOL && it.text == sym }

        private fun atIdent(word: String) =
            peek().let { it.kind == TokenKind.IDENT && it.text == word }

        private fun expect(sym: String): Token = if (at(sym)) next() else fail("expected '$sym'")

        private fun ident(what: String): Token =
            if (peek().kind == TokenKind.IDENT) next() else fail("expected $what")

        private fun string(): String =
            if (peek().kind == TokenKind.STRING) next().text else fail("expected a string")

        private fun int(): Int {
            val negative = at("-")
            if (negative) next()
            val n = peek()
            if (n.kind != TokenKind.INT) fail("expected a number")
            next()
            val v =
                parseLong(n.text)
                    ?.let { if (negative) -it else it }
                    ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
                    ?: fail("bad number '${n.text}'", n.pos)
            return v.toInt()
        }

        /** Decimal, `0x` hex, or leading-zero octal, as proto writes integers. */
        private fun parseLong(s: String): Long? =
            when {
                s.startsWith("0x") || s.startsWith("0X") -> s.substring(2).toLongOrNull(16)
                s.length > 1 && s.startsWith("0") -> s.toLongOrNull(8)
                else -> s.toLongOrNull()
            }

        /**
         * The doc of the declaration starting at [pos]: the run of comments on consecutive lines
         * ending on the line before it (or on its own line), with comment markers removed. A
         * comment separated from the declaration by a blank line is detached and is not a doc.
         */
        private fun takeDoc(pos: Pos): String? {
            var k = pending.size
            var line = pos.line
            if (k > 0 && pending[k - 1].endLine == line) {
                line = pending[k - 1].pos.line
                k--
            }
            while (k > 0 && pending[k - 1].endLine == line - 1) {
                line = pending[k - 1].pos.line
                k--
            }
            val block = pending.subList(k, pending.size).toList()
            pending.clear()
            if (block.isEmpty()) return null
            return block
                .flatMap { c ->
                    if (c.block) c.text.lines().map { it.trim().removePrefix("*").trimStart() }
                    else listOf(c.text.removePrefix(" "))
                }
                .joinToString("\n")
                .trim()
                .ifEmpty { null }
        }

        /**
         * A comment on [line], the line of the token just consumed, as (note, trailing): a comment
         * starting with `schemata:` gives the trimmed text after it as the note; any other is
         * trailing.
         */
        private fun takeTrailing(line: Int): Pair<String?, String?> {
            val t = tokens[i]
            if (t.kind != TokenKind.COMMENT || t.pos.line != line) return null to null
            i++
            val trimmed = t.text.trim()
            return if (!t.block && trimmed.startsWith("schemata:"))
                trimmed.removePrefix("schemata:").trim() to null
            else null to t.text
        }

        fun file(): ProtoFile {
            var syntax = "proto2"
            var edition: String? = null
            var pkg: String? = null
            val imports = mutableListOf<ProtoImport>()
            val options = mutableListOf<ProtoOption>()
            val messages = mutableListOf<ProtoMessage>()
            val enums = mutableListOf<ProtoEnum>()
            val services = mutableListOf<ProtoService>()
            val dropped = mutableListOf<Pair<String, Pos>>()
            if (atIdent("syntax")) {
                next()
                expect("=")
                val syntaxPos = peek().pos
                syntax = string()
                if (syntax != "proto2" && syntax != "proto3")
                    fail("unknown syntax \"$syntax\"", syntaxPos)
                expect(";")
            } else if (atIdent("edition")) {
                next()
                expect("=")
                edition = string()
                syntax = "editions"
                expect(";")
            }
            while (peek().kind != TokenKind.EOF) {
                val t = peek()
                when {
                    at(";") -> next()
                    atIdent("package") -> {
                        next()
                        pkg = qualifiedName()
                        expect(";")
                    }
                    atIdent("import") -> {
                        next()
                        val public = atIdent("public").also { if (it) next() }
                        val weak = !public && atIdent("weak").also { if (it) next() }
                        imports += ProtoImport(string(), public, weak, t.pos)
                        expect(";")
                    }
                    atIdent("option") -> options += option()
                    atIdent("message") -> messages += message()
                    atIdent("enum") -> enums += enum()
                    atIdent("service") -> services += service()
                    atIdent("extend") -> {
                        dropped += "extend" to t.pos
                        next()
                        typeName()
                        skipBlock()
                    }
                    else -> fail("expected a declaration")
                }
            }
            return ProtoFile(
                path,
                syntax,
                edition,
                pkg,
                imports,
                options,
                messages,
                enums,
                services,
                dropped,
            )
        }

        private fun qualifiedName(): String {
            val sb = StringBuilder(ident("a name").text)
            while (at(".")) {
                next()
                sb.append('.').append(ident("a name").text)
            }
            return sb.toString()
        }

        /** A type reference as written: an optional leading dot, then dotted identifiers. */
        private fun typeName(): String {
            val sb = StringBuilder()
            if (at(".")) {
                next()
                sb.append('.')
            }
            sb.append(qualifiedName())
            return sb.toString()
        }

        private fun option(): ProtoOption {
            next() // option
            val name = optionName()
            expect("=")
            val value = constant()
            expect(";")
            return ProtoOption(name, value)
        }

        /** `name`, `(ext.name)`, and either followed by `.sub` parts, kept as written. */
        private fun optionName(): String {
            val sb = StringBuilder()
            if (at("(")) {
                next()
                sb.append('(').append(typeName()).append(')')
                expect(")")
            } else {
                sb.append(ident("an option name").text)
            }
            while (at(".")) {
                next()
                sb.append('.').append(ident("an option name").text)
            }
            return sb.toString()
        }

        /**
         * A constant's source text; an aggregate `{ … }` is skipped by brace balance and returned
         * as `{…}`.
         */
        private fun constant(): String {
            val t = peek()
            return when {
                at("{") -> {
                    skipBlock()
                    "{…}"
                }
                at("-") || at("+") -> {
                    next()
                    val n = peek()
                    if (
                        n.kind != TokenKind.INT &&
                            n.kind != TokenKind.FLOAT &&
                            n.kind != TokenKind.IDENT
                    ) {
                        fail("expected a number")
                    }
                    next()
                    t.text + n.text
                }
                t.kind == TokenKind.STRING -> next().text
                t.kind == TokenKind.IDENT -> qualifiedName()
                t.kind == TokenKind.INT || t.kind == TokenKind.FLOAT -> next().text
                else -> fail("expected a constant")
            }
        }

        private fun skipBlock() {
            val open = expect("{")
            var depth = 1
            while (depth > 0) {
                val t = next()
                if (t.kind == TokenKind.EOF) fail("unterminated block", open.pos)
                if (t.kind == TokenKind.SYMBOL && t.text == "{") depth++
                if (t.kind == TokenKind.SYMBOL && t.text == "}") depth--
            }
        }

        /** Skips to and past the next `;`, for statements read only for their position. */
        private fun skipStatement() {
            while (!at(";")) {
                if (peek().kind == TokenKind.EOF) fail("expected ';'")
                next()
            }
            next()
        }

        private fun fieldOptions(): List<ProtoOption> {
            if (!at("[")) return emptyList()
            next()
            val out = mutableListOf<ProtoOption>()
            while (true) {
                val name = optionName()
                expect("=")
                out += ProtoOption(name, constant())
                if (at(",")) {
                    next()
                    continue
                }
                expect("]")
                return out
            }
        }

        private fun atMap() =
            atIdent("map") && peekSecond().let { it.kind == TokenKind.SYMBOL && it.text == "<" }

        /**
         * A proto2 group: `[label] group Name = N [options] { … }`, read past and recorded as
         * dropped.
         */
        private fun group(dropped: MutableList<Pair<String, Pos>>) {
            dropped += "group" to peek().pos
            if (!atIdent("group")) next() // label
            next() // group
            ident("a group name")
            expect("=")
            int()
            fieldOptions()
            skipBlock()
        }

        private fun message(): ProtoMessage {
            val doc = takeDoc(peek().pos)
            val start = next() // message
            val name = ident("a message name").text
            expect("{")
            val fields = mutableListOf<ProtoField>()
            val oneofs = mutableListOf<String>()
            val messages = mutableListOf<ProtoMessage>()
            val enums = mutableListOf<ProtoEnum>()
            val reserved = mutableListOf<ProtoReserved>()
            val options = mutableListOf<ProtoOption>()
            val dropped = mutableListOf<Pair<String, Pos>>()
            while (!at("}")) {
                val t = peek()
                when {
                    at(";") -> next()
                    atIdent("option") -> options += option()
                    atIdent("message") -> messages += message()
                    atIdent("enum") -> enums += enum()
                    atIdent("reserved") -> reserved += reserved(MAX_FIELD_NUMBER)
                    atIdent("extensions") -> {
                        dropped += "extensions" to t.pos
                        skipStatement()
                    }
                    atIdent("extend") -> {
                        dropped += "extend" to t.pos
                        next()
                        typeName()
                        skipBlock()
                    }
                    atIdent("oneof") -> {
                        next()
                        val oneofName = ident("a oneof name").text
                        oneofs += oneofName
                        expect("{")
                        while (!at("}")) {
                            when {
                                at(";") -> next()
                                // A oneof's own options do not apply to the message.
                                atIdent("option") -> option()
                                atIdent("group") -> group(dropped)
                                else -> fields += field(oneofName)
                            }
                        }
                        expect("}")
                    }
                    atMap() -> fields += field(null)
                    (atIdent("optional") || atIdent("required") || atIdent("repeated")) &&
                        peekSecond().let { it.kind == TokenKind.IDENT && it.text == "group" } ->
                        group(dropped)
                    atIdent("group") -> group(dropped)
                    else -> fields += field(null)
                }
            }
            val close = expect("}")
            takeTrailing(close.pos.line)
            return ProtoMessage(
                name,
                fields,
                oneofs,
                messages,
                enums,
                reserved,
                options,
                doc,
                start.pos,
                dropped,
            )
        }

        /** A field; inside a oneof ([oneof] set) fields take no label. */
        private fun field(oneof: String?): ProtoField {
            val start = peek()
            val doc = takeDoc(start.pos)
            var label = Label.NONE
            if (oneof == null) {
                when {
                    atIdent("optional") -> label = Label.OPTIONAL
                    atIdent("required") -> label = Label.REQUIRED
                    atIdent("repeated") -> label = Label.REPEATED
                }
                if (label != Label.NONE) next()
            }
            var mapKey: String? = null
            var mapValue: String? = null
            val type =
                if (atMap()) {
                    next()
                    expect("<")
                    mapKey = typeName()
                    expect(",")
                    mapValue = typeName()
                    expect(">")
                    "map"
                } else {
                    typeName()
                }
            val name = ident("a field name").text
            expect("=")
            val number = int()
            val options = fieldOptions()
            val end = expect(";")
            val (note, trailing) = takeTrailing(end.pos.line)
            return ProtoField(
                label,
                type,
                mapKey,
                mapValue,
                name,
                number,
                options,
                doc,
                note,
                trailing,
                oneof,
                start.pos,
            )
        }

        private fun enum(): ProtoEnum {
            val doc = takeDoc(peek().pos)
            val start = next() // enum
            val name = ident("an enum name").text
            expect("{")
            val values = mutableListOf<ProtoEnumValue>()
            val reserved = mutableListOf<ProtoReserved>()
            val options = mutableListOf<ProtoOption>()
            while (!at("}")) {
                when {
                    at(";") -> next()
                    atIdent("option") -> options += option()
                    atIdent("reserved") -> reserved += reserved(Int.MAX_VALUE)
                    else -> {
                        val v = peek()
                        val vdoc = takeDoc(v.pos)
                        val vname = ident("an enum value name").text
                        expect("=")
                        val number = int()
                        val vopts = fieldOptions()
                        val end = expect(";")
                        val (_, trailing) = takeTrailing(end.pos.line)
                        values += ProtoEnumValue(vname, number, vopts, vdoc, trailing, v.pos)
                    }
                }
            }
            val close = expect("}")
            takeTrailing(close.pos.line)
            return ProtoEnum(name, values, reserved, options, doc, start.pos)
        }

        /**
         * `reserved` ranges (`n`, `n to m`, `n to max`, with `max` read as [max]) or names, quoted
         * or, in editions, bare identifiers.
         */
        private fun reserved(max: Int): ProtoReserved {
            val start = next() // reserved
            val ranges = mutableListOf<Pair<Int, Int>>()
            val names = mutableListOf<String>()
            while (true) {
                when (peek().kind) {
                    TokenKind.STRING -> names += string()
                    TokenKind.IDENT -> names += next().text
                    else -> {
                        val from = int()
                        var to = from
                        if (atIdent("to")) {
                            next()
                            to =
                                if (atIdent("max")) {
                                    next()
                                    max
                                } else {
                                    int()
                                }
                        }
                        ranges += from to to
                    }
                }
                if (at(",")) {
                    next()
                    continue
                }
                expect(";")
                return ProtoReserved(ranges, names, start.pos)
            }
        }

        private fun service(): ProtoService {
            val doc = takeDoc(peek().pos)
            val start = next() // service
            val name = ident("a service name").text
            expect("{")
            val rpcs = mutableListOf<ProtoRpc>()
            val options = mutableListOf<ProtoOption>()
            val reservedNotes = mutableListOf<Pair<String, Pos>>()
            while (true) {
                peek()
                reservedNotes += takeReservedNotes()
                if (at("}")) break
                when {
                    at(";") -> next()
                    atIdent("option") -> options += option()
                    atIdent("rpc") -> rpcs += rpc()
                    else -> fail("expected an rpc")
                }
            }
            val close = expect("}")
            takeTrailing(close.pos.line)
            return ProtoService(name, rpcs, options, doc, reservedNotes, start.pos)
        }

        /**
         * Removes the `//` comments starting with `schemata: reserved` from the comments standing
         * on their own lines, returning the trimmed text after `schemata:` of each with where the
         * comment starts; the rest, other `schemata:` comments included, stay to become the next
         * declaration's doc.
         */
        private fun takeReservedNotes(): List<Pair<String, Pos>> {
            val notes = pending.filter { !it.block && reservedNote(it.text) != null }
            pending.removeAll(notes)
            return notes.map { reservedNote(it.text)!! to it.pos }
        }

        /** The text after `schemata:` when [comment] is a `schemata: reserved` note, else null. */
        private fun reservedNote(comment: String): String? {
            val text = comment.trim()
            if (!text.startsWith("schemata:")) return null
            val note = text.removePrefix("schemata:").trim()
            val word = note.takeWhile { !it.isWhitespace() }
            return note.takeIf { word == "reserved" }
        }

        /**
         * `rpc Name ( … ) returns ( … )` ended by `;` or by a body of `option` statements; the
         * `schemata:` note trails the `;` or the body's `{`.
         */
        private fun rpc(): ProtoRpc {
            val doc = takeDoc(peek().pos)
            val start = next() // rpc
            val name = ident("an rpc name").text
            val request = rpcType()
            if (!atIdent("returns")) fail("expected 'returns'")
            next()
            val response = rpcType()
            val options = mutableListOf<ProtoOption>()
            val note: String?
            if (at("{")) {
                val open = next()
                note = takeTrailing(open.pos.line).first
                while (!at("}")) {
                    when {
                        peek().kind == TokenKind.EOF -> fail("unterminated block", open.pos)
                        at(";") -> next()
                        atIdent("option") -> options += option()
                        // Anything else in an rpc body is read past, nested blocks whole.
                        at("{") -> skipBlock()
                        else -> next()
                    }
                }
                val close = next()
                takeTrailing(close.pos.line)
            } else {
                val semi = expect(";")
                note = takeTrailing(semi.pos.line).first
            }
            return ProtoRpc(name, request, response, options, doc, note, start.pos)
        }

        /** `( [stream] Type )`; a type named `stream` (or `stream.X`) is read as the type. */
        private fun rpcType(): ProtoRpcType {
            expect("(")
            var stream = false
            if (atIdent("stream")) {
                val s = peek()
                val after = peekSecond()
                val typeNamedStream =
                    after.kind == TokenKind.SYMBOL &&
                        (after.text == ")" ||
                            (after.text == "." &&
                                after.pos == Pos(s.pos.line, s.pos.col + s.text.length)))
                if (!typeNamedStream) {
                    next()
                    stream = true
                }
            }
            val name = typeName()
            expect(")")
            return ProtoRpcType(name, stream)
        }
    }
}
