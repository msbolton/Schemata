package io.schemata.importer.sql

/**
 * Reads Postgres DDL, hand-written or as pg_dump writes it, into [SqlStatement]s. It never throws:
 * a statement it cannot read becomes a [SqlParseError] and reading resumes after the next `;`; a
 * file that does not lex is one error and no statements.
 *
 * Statements read: `CREATE SCHEMA`, `CREATE TABLE`, `CREATE [UNIQUE] INDEX`, `ALTER TABLE … ADD` of
 * a constraint, and `COMMENT ON TABLE|COLUMN`. Statements that define something with no place in
 * the result (views, types, functions, triggers, …) or carry data (`INSERT`, `UPDATE`, `DELETE`,
 * `COPY`) are [SqlStatement.Dropped]; statements with no bearing on the shape (`SET`, `GRANT`,
 * `OWNER TO`, sequences, …) are [SqlStatement.Ignored].
 *
 * A column's type is canonicalised to the spellings the SQL target writes: `character varying(n)`
 * is `varchar(n)`, `character(n)` is `char(n)`, `timestamp with time zone` is `timestamptz`,
 * `timestamp without time zone` is `timestamp`, `time with time zone` is `timetz`, `time without
 * time zone` is `time`, `int`/`int4` are `integer`, `int8` is `bigint`, `int2` is `smallint`,
 * `float4` is `real`, `float8` and `float` are `double precision`, `bool` is `boolean`, `decimal`
 * is `numeric`, modifiers are separated by `, `, a `pg_catalog.` qualifier is dropped, and array
 * suffixes (`[]`, `[3]`, `ARRAY`) are each `[]`. Anything else, `serial` and `bigserial` included,
 * is kept as written, quoted identifiers re-quoted.
 *
 * pg_dump splits two column forms out of `CREATE TABLE`, and the reader folds them back into the
 * table they name in the same file (a name unqualified on either side matches by table name alone):
 * `ALTER TABLE … ALTER COLUMN c ADD GENERATED … AS IDENTITY` marks `c` an identity, and `ALTER
 * TABLE … ALTER COLUMN c SET DEFAULT nextval(…)` on an `integer`, `bigint` or `smallint` column
 * makes it `serial`, `bigserial` or `smallserial` with no default, undoing the expansion of a
 * serial into a sequence. An inline `DEFAULT nextval(…)` on such a column reads the same way. An
 * `ALTER` folded into its table is not a statement of its own; one naming a table not in the file,
 * or a column the table lacks, stays [SqlStatement.Ignored].
 *
 * A `schemata:` comment trailing a column on its line, or alone on the next line when the column's
 * line has none, is the column's note.
 */
object SqlReader {
    fun read(path: String, text: String): SqlFile {
        val tokens =
            try {
                SqlLexer.lex(text)
            } catch (e: SqlSyntaxError) {
                return SqlFile(path, emptyList(), listOf(SqlParseError(e.pos, e.message ?: "")))
            }
        return Reader(tokens).file(path)
    }
}

/** First words of statements with no bearing on the schema's shape. */
private val IGNORED =
    setOf(
        "abort",
        "analyze",
        "begin",
        "checkpoint",
        "cluster",
        "commit",
        "deallocate",
        "discard",
        "drop",
        "end",
        "execute",
        "explain",
        "grant",
        "listen",
        "load",
        "lock",
        "notify",
        "prepare",
        "refresh",
        "reindex",
        "release",
        "reset",
        "revoke",
        "rollback",
        "savepoint",
        "security",
        "select",
        "set",
        "show",
        "start",
        "truncate",
        "unlisten",
        "vacuum",
        "values",
        "with",
    )

/** First words of statements that carry data, which an importer does not read. */
private val DATA = setOf("copy", "delete", "insert", "update")

/** `CREATE` forms, by their words after `CREATE`, that define nothing an importer keeps. */
private val CREATE_IGNORED =
    listOf(
            "sequence",
            "extension",
            "role",
            "user",
            "group",
            "database",
            "tablespace",
            "publication",
            "subscription",
            "server",
            "foreign data wrapper",
        )
        .map { it.split(' ') }

/** `CREATE` forms, by their words after `CREATE`, that define something an importer cannot keep. */
private val CREATE_DROPPED =
    listOf(
            "view",
            "recursive view",
            "materialized view",
            "type",
            "domain",
            "function",
            "procedure",
            "trigger",
            "constraint trigger",
            "event trigger",
            "rule",
            "policy",
            "aggregate",
            "operator",
            "cast",
            "collation",
            "conversion",
            "default conversion",
            "text search",
            "foreign table",
            "statistics",
            "access method",
            "language",
            "trusted language",
            "procedural language",
            "transform",
        )
        .map { it.split(' ') }

/** Words that end a `DEFAULT` expression: the start of the column's next clause. */
private val COLUMN_CLAUSES =
    setOf(
        "not",
        "null",
        "default",
        "primary",
        "unique",
        "references",
        "check",
        "generated",
        "collate",
        "constraint",
        "compression",
        "storage",
    )

private val SERIAL_OF =
    mapOf("integer" to "serial", "bigint" to "bigserial", "smallint" to "smallserial")

private val SERIALS = setOf("serial", "bigserial", "smallserial", "serial2", "serial4", "serial8")

/** Clauses after a table's column list that change what the table is. */
private val TABLE_TRAILERS =
    mapOf(
        "inherits" to "INHERITS",
        "partition" to "PARTITION BY",
        "with" to "WITH",
        "without" to "WITHOUT OIDS",
        "tablespace" to "TABLESPACE",
        "using" to "USING",
        "on" to "ON COMMIT",
    )

private val TYPE_ALIASES =
    mapOf(
        "int" to "integer",
        "int4" to "integer",
        "int8" to "bigint",
        "int2" to "smallint",
        "float4" to "real",
        "float8" to "double precision",
        "bool" to "boolean",
        "decimal" to "numeric",
    )

private val INTERVAL_FIELDS = setOf("year", "month", "day", "hour", "minute", "second", "to")

private class Reader(private val all: List<SqlToken>) {
    /** The tokens without comments; [fullIndex] maps each back to its index in [all]. */
    private val toks: List<SqlToken>
    private val fullIndex: IntArray
    private var i = 0

    /** A column change from an `ALTER TABLE` to fold into its table; see [SqlReader]. */
    private class ColumnFix(
        val schema: String?,
        val table: String,
        val column: String,
        val identity: Boolean,
    )

    /** Set by [alter] when the statement it read is a [ColumnFix]; [file] collects it. */
    private var pendingFix: ColumnFix? = null

    init {
        val kept = all.withIndex().filter { it.value.kind != SqlTokenKind.COMMENT }
        toks = kept.map { it.value }
        fullIndex = kept.map { it.index }.toIntArray()
    }

    fun file(path: String): SqlFile {
        val statements = mutableListOf<SqlStatement>()
        val errors = mutableListOf<SqlParseError>()
        val fixes = mutableListOf<Pair<Int, ColumnFix>>()
        while (peek().kind != SqlTokenKind.EOF) {
            if (symbol(";")) continue
            pendingFix = null
            try {
                statements += statement()
                pendingFix?.let { fixes += statements.lastIndex to it }
                if (!symbol(";") && peek().kind != SqlTokenKind.EOF) fail("expected ';'")
            } catch (e: SqlSyntaxError) {
                errors += SqlParseError(e.pos, e.message ?: "")
                while (peek().kind != SqlTokenKind.EOF && !isSymbol(";")) i++
            }
        }
        return SqlFile(path, foldColumnFixes(statements, fixes), errors)
    }

    /** Applies each fix to its table, dropping the `ALTER` it came from; see [SqlReader]. */
    private fun foldColumnFixes(
        statements: List<SqlStatement>,
        fixes: List<Pair<Int, ColumnFix>>,
    ): List<SqlStatement> {
        val out =
            statements
                .map { s ->
                    if (s !is SqlStatement.CreateTable) s
                    else
                        SqlStatement.CreateTable(
                            s.table.copy(
                                columns =
                                    s.table.columns.map { c ->
                                        if (isNextval(c.default)) asSerial(c) ?: c else c
                                    }
                            )
                        )
                }
                .toMutableList()
        val folded = mutableSetOf<Int>()
        for ((index, fix) in fixes) {
            val at =
                out.indexOfFirst {
                    it is SqlStatement.CreateTable &&
                        it.table.name == fix.table &&
                        (fix.schema == null ||
                            it.table.schema == null ||
                            fix.schema == it.table.schema)
                }
            if (at < 0) continue
            val table = (out[at] as SqlStatement.CreateTable).table
            val c = table.columns.indexOfFirst { it.name == fix.column }
            if (c < 0) continue
            val column = table.columns[c]
            val changed =
                if (fix.identity) column.copy(identity = true, notNull = true)
                else asSerial(column) ?: continue
            out[at] =
                SqlStatement.CreateTable(
                    table.copy(columns = table.columns.toMutableList().also { it[c] = changed })
                )
            folded += index
        }
        return out.filterIndexed { k, _ -> k !in folded }
    }

    private fun isNextval(e: SqlExpr?) = e is SqlExpr.Call && e.name == "nextval"

    /** The serial form of an integer [column], or null when its type has none. */
    private fun asSerial(column: SqlColumn): SqlColumn? {
        val serial = SERIAL_OF[column.type] ?: return null
        return column.copy(type = serial, default = null, notNull = true)
    }

    // ---- statements ----

    private fun statement(): List<SqlStatement> {
        val start = peek()
        return when {
            isWord("create") -> create()
            isWord("alter") -> alter()
            isWord("comment") && isWord("on", 1) -> listOf(commentOn())
            isWord("do") -> {
                skipStatement()
                listOf(SqlStatement.Dropped("DO", start.pos))
            }
            start.kind == SqlTokenKind.IDENT && start.text in DATA -> {
                skipStatement()
                listOf(SqlStatement.Dropped(start.text.uppercase(), start.pos))
            }
            start.kind == SqlTokenKind.IDENT && start.text in IGNORED -> {
                skipStatement()
                listOf(SqlStatement.Ignored(start.text.uppercase()))
            }
            else -> unknown()
        }
    }

    private fun unknown(): Nothing {
        val words =
            (0..1)
                .map { peek(it) }
                .takeWhile {
                    it.kind != SqlTokenKind.EOF &&
                        !(it.kind == SqlTokenKind.SYMBOL && it.text == ";")
                }
                .joinToString(" ") { it.text }
        fail("unknown statement '$words'")
    }

    private fun create(): List<SqlStatement> {
        val pos = peek().pos
        val first = i
        expectWord("create")
        if (word("or")) expectWord("replace")
        var temp = false
        var unlogged = false
        while (true) {
            when {
                word("global") || word("local") -> {}
                word("temp") || word("temporary") -> temp = true
                word("unlogged") -> unlogged = true
                else -> break
            }
        }
        if (isWord("schema")) return listOf(createSchema(pos))
        if (isWord("table") && temp) {
            skipStatement()
            return listOf(SqlStatement.Dropped("CREATE TEMP TABLE", pos))
        }
        if (isWord("table")) return listOf(createTable(pos, unlogged))
        if (isWord("unique") || isWord("index")) return listOf(createIndex(pos))
        fun matches(words: List<String>) = words.withIndex().all { (k, w) -> isWord(w, k) }
        val dropped = CREATE_DROPPED.filter(::matches).maxByOrNull { it.size }
        val ignored = CREATE_IGNORED.filter(::matches).maxByOrNull { it.size }
        val kind = { words: List<String> -> "CREATE " + words.joinToString(" ").uppercase() }
        return when {
            dropped != null -> {
                skipStatement()
                listOf(SqlStatement.Dropped(kind(dropped), pos))
            }
            ignored != null -> {
                skipStatement()
                listOf(SqlStatement.Ignored(kind(ignored)))
            }
            else -> {
                i = first
                unknown()
            }
        }
    }

    private fun createSchema(pos: SqlPos): SqlStatement {
        expectWord("schema")
        ifNotExists()
        val name =
            if (word("authorization")) ident("a role name")
            else ident("a schema name").also { if (word("authorization")) ident("a role name") }
        return SqlStatement.CreateSchema(name, pos)
    }

    private fun createTable(pos: SqlPos, unlogged: Boolean): SqlStatement {
        expectWord("table")
        ifNotExists()
        val (schema, name) = qualifiedName("a table name")
        val form =
            when {
                isWord("partition") && isWord("of", 1) -> "CREATE TABLE PARTITION OF"
                isWord("of") -> "CREATE TABLE OF"
                isWord("as") -> "CREATE TABLE AS"
                else -> null
            }
        if (form != null) {
            skipStatement()
            return SqlStatement.Dropped(form, pos)
        }
        expectSymbol("(")
        val columns = mutableListOf<SqlColumn>()
        val constraints = mutableListOf<SqlConstraint>()
        val dropped = mutableListOf<String>()
        if (unlogged) dropped += "UNLOGGED"
        if (!symbol(")")) {
            do {
                val before = columns.size
                element(columns, constraints, dropped)
                if (columns.size > before) {
                    val end = if (isSymbol(",")) i + 1 else i
                    columns[before] = columns[before].copy(note = note(i - 1, end))
                }
            } while (symbol(","))
            if (!symbol(")")) fail("expected ',' or ')'")
        }
        var depth = 0
        while (!atStatementEnd()) {
            val t = peek()
            if (t.kind == SqlTokenKind.SYMBOL && t.text == "(") depth++
            if (t.kind == SqlTokenKind.SYMBOL && t.text == ")") depth--
            if (depth == 0 && t.kind == SqlTokenKind.IDENT)
                TABLE_TRAILERS[t.text]?.let { dropped += it }
            i++
        }
        return SqlStatement.CreateTable(
            SqlTable(schema, name, columns, constraints, dropped, null, pos)
        )
    }

    /**
     * One entry of a table's parenthesised list: a column, a table constraint, or a dropped form.
     */
    private fun element(
        columns: MutableList<SqlColumn>,
        constraints: MutableList<SqlConstraint>,
        dropped: MutableList<String>,
    ) {
        when {
            word("constraint") -> {
                val name = ident("a constraint name")
                tableConstraint(name)?.let { constraints += it }
                    ?: run { dropped += "EXCLUDE constraint" }
            }
            isWord("primary") || isWord("unique") || isWord("check") || isWord("foreign") ->
                constraints += tableConstraint(null)!!
            isWord("exclude") && (isWord("using", 1) || isSymbol("(", 1)) -> {
                skipElement()
                dropped += "EXCLUDE constraint"
            }
            isWord("like") -> {
                skipElement()
                dropped += "LIKE"
            }
            peek().kind == SqlTokenKind.IDENT || peek().kind == SqlTokenKind.QIDENT ->
                columns += column(constraints, dropped)
            else -> fail("expected a column name")
        }
    }

    private fun column(
        constraints: MutableList<SqlConstraint>,
        dropped: MutableList<String>,
    ): SqlColumn {
        val pos = peek().pos
        val name = ident("a column name")
        val type = typeText()
        var notNull = type.substringBefore('[') in SERIALS
        var default: SqlExpr? = null
        var identity = false
        var constraintName: String? = null
        while (true) {
            if (word("constraint")) {
                constraintName = ident("a constraint name")
                continue
            }
            when {
                word("not") -> {
                    expectWord("null")
                    notNull = true
                }
                word("null") -> {}
                word("default") -> default = defaultExpr()
                word("primary") -> {
                    expectWord("key")
                    options(mutableListOf())
                    constraints += SqlConstraint.PrimaryKey(constraintName, listOf(name))
                    notNull = true
                }
                word("unique") -> {
                    nullsDistinct()
                    options(mutableListOf())
                    constraints += SqlConstraint.Unique(constraintName, listOf(name))
                }
                isWord("check") -> constraints += check(constraintName)
                isWord("references") -> constraints += references(constraintName, listOf(name))
                word("generated") -> {
                    if (!word("always")) {
                        expectWord("by")
                        expectWord("default")
                    }
                    expectWord("as")
                    if (word("identity")) {
                        identity = true
                        notNull = true
                        if (isSymbol("(")) skipParens()
                    } else {
                        if (!isSymbol("(")) fail("expected IDENTITY or '('")
                        skipParens()
                        expectWord("stored")
                        dropped += "GENERATED … STORED on column '$name'"
                    }
                }
                word("collate") -> qualifiedName("a collation")
                word("compression") || word("storage") -> ident("a method")
                else -> break
            }
            constraintName = null
        }
        return SqlColumn(name, type, notNull, default, null, null, identity, pos)
    }

    /**
     * A `DEFAULT` expression: the tokens up to the column's next clause, `,`, or the closing `)`,
     * read as an expression.
     */
    private fun defaultExpr(): SqlExpr {
        val from = i
        var depth = 0
        while (!atStatementEnd()) {
            val t = peek()
            if (depth == 0 && i > from) {
                if (t.kind == SqlTokenKind.SYMBOL && (t.text == "," || t.text == ")")) break
                if (t.kind == SqlTokenKind.IDENT && t.text in COLUMN_CLAUSES) break
            }
            if (t.kind == SqlTokenKind.SYMBOL && (t.text == "(" || t.text == "[")) depth++
            if (t.kind == SqlTokenKind.SYMBOL && (t.text == ")" || t.text == "]")) {
                if (depth == 0) break
                depth--
            }
            i++
        }
        if (i == from) fail("expected a default value")
        return SqlExprs.parse(toks, from, i)
    }

    /**
     * A table constraint after its optional `CONSTRAINT name`, or null for an `EXCLUDE` constraint,
     * which is skipped.
     */
    private fun tableConstraint(name: String?): SqlConstraint? =
        when {
            word("primary") -> {
                expectWord("key")
                val columns = columnList()
                options(mutableListOf())
                SqlConstraint.PrimaryKey(name, columns)
            }
            word("unique") -> {
                nullsDistinct()
                val columns = columnList()
                options(mutableListOf())
                SqlConstraint.Unique(name, columns)
            }
            isWord("check") -> check(name)
            word("foreign") -> {
                expectWord("key")
                references(name, columnList())
            }
            word("exclude") -> {
                skipElement()
                null
            }
            else -> fail("expected a constraint")
        }

    private fun check(name: String?): SqlConstraint.Check {
        expectWord("check")
        if (!isSymbol("(")) fail("expected '('")
        val open = i
        skipParens()
        val close = i - 1
        options(mutableListOf())
        return SqlConstraint.Check(
            name,
            SqlExprs.parse(toks, open + 1, close),
            SqlExprs.text(toks, open + 1, close),
        )
    }

    private fun references(name: String?, columns: List<String>): SqlConstraint.ForeignKey {
        expectWord("references")
        val (schema, table) = qualifiedName("a table name")
        val refColumns = if (isSymbol("(")) columnList() else emptyList()
        var onDelete: String? = null
        var onUpdate: String? = null
        val extras = mutableListOf<String>()
        while (true) {
            when {
                isWord("on") && isWord("delete", 1) -> {
                    i += 2
                    onDelete = action()
                }
                isWord("on") && isWord("update", 1) -> {
                    i += 2
                    onUpdate = action()
                }
                !option(extras) -> break
            }
        }
        return SqlConstraint.ForeignKey(
            name,
            columns,
            schema,
            table,
            refColumns,
            onDelete,
            onUpdate,
            extras,
        )
    }

    private fun action(): String =
        when {
            word("cascade") -> "CASCADE"
            word("restrict") -> "RESTRICT"
            word("no") -> {
                expectWord("action")
                "NO ACTION"
            }
            word("set") -> {
                val what =
                    when {
                        word("null") -> "SET NULL"
                        word("default") -> "SET DEFAULT"
                        else -> fail("expected NULL or DEFAULT")
                    }
                if (isSymbol("(")) columnList()
                what
            }
            else -> fail("expected a referential action")
        }

    /**
     * Consumes the options that may follow a constraint, recording in [extras] those that matter.
     */
    private fun options(extras: MutableList<String>) {
        while (option(extras)) {}
    }

    /**
     * One constraint option, if one is next: index parameters, deferral, `MATCH`, `NOT VALID`, `NO
     * INHERIT`. `DEFERRABLE`, `INITIALLY DEFERRED`, `MATCH FULL` and `MATCH PARTIAL` go to
     * [extras]; the others leave behaviour as it would be without them.
     */
    private fun option(extras: MutableList<String>): Boolean {
        when {
            word("include") -> columnList()
            isWord("with") && isSymbol("(", 1) -> {
                i++
                skipParens()
            }
            isWord("using") && isWord("index", 1) -> {
                i += 2
                expectWord("tablespace")
                ident("a tablespace name")
            }
            word("deferrable") -> extras += "DEFERRABLE"
            isWord("not") && isWord("deferrable", 1) -> i += 2
            isWord("not") && isWord("valid", 1) -> i += 2
            isWord("no") && isWord("inherit", 1) -> i += 2
            word("initially") -> {
                if (word("deferred")) extras += "INITIALLY DEFERRED" else expectWord("immediate")
            }
            word("match") -> {
                when {
                    word("full") -> extras += "MATCH FULL"
                    word("partial") -> extras += "MATCH PARTIAL"
                    else -> expectWord("simple")
                }
            }
            else -> return false
        }
        return true
    }

    private fun nullsDistinct() {
        if (word("nulls")) {
            word("not")
            expectWord("distinct")
        }
    }

    private fun createIndex(pos: SqlPos): SqlStatement {
        val unique = word("unique")
        expectWord("index")
        word("concurrently")
        ifNotExists()
        val name = if (isWord("on")) null else ident("an index name")
        expectWord("on")
        word("only")
        val (schema, table) = qualifiedName("a table name")
        val using = if (word("using")) ident("an access method") else null
        expectSymbol("(")
        val columns = mutableListOf<String>()
        var expression = false
        do {
            val from = i
            skipElement()
            val element = toks.subList(from, i)
            val plain =
                element.isNotEmpty() &&
                    element.all {
                        it.kind == SqlTokenKind.IDENT ||
                            it.kind == SqlTokenKind.QIDENT ||
                            (it.kind == SqlTokenKind.SYMBOL && it.text == ".")
                    } &&
                    element[0].kind != SqlTokenKind.SYMBOL
            if (plain) columns += element[0].text else expression = true
        } while (symbol(","))
        expectSymbol(")")
        var filtered = false
        var depth = 0
        while (!atStatementEnd()) {
            if (isSymbol("(")) depth++
            if (isSymbol(")")) depth--
            if (depth == 0 && isWord("where")) filtered = true
            i++
        }
        if (expression) return SqlStatement.Dropped("CREATE INDEX on an expression", pos)
        return SqlStatement.CreateIndex(name, unique, schema, table, columns, using, filtered, pos)
    }

    private fun alter(): List<SqlStatement> {
        val pos = peek().pos
        expectWord("alter")
        if (!word("table")) {
            val kind = "ALTER " + peek().text.uppercase()
            skipStatement()
            return listOf(SqlStatement.Ignored(kind))
        }
        if (word("if")) expectWord("exists")
        word("only")
        val (schema, table) = qualifiedName("a table name")
        symbol("*")
        val out = mutableListOf<SqlStatement>()
        do {
            if (isWord("alter") && !isWord("constraint", 1)) {
                i++
                word("column")
                val column = ident("a column name")
                val identity = isWord("add") && isWord("generated", 1)
                if (identity) {
                    i += 2
                    if (!word("always")) {
                        expectWord("by")
                        expectWord("default")
                    }
                    expectWord("as")
                    expectWord("identity")
                    if (isSymbol("(")) skipParens()
                }
                val nextval =
                    !identity &&
                        isWord("set") &&
                        isWord("default", 1) &&
                        run {
                            i += 2
                            isNextval(defaultExpr())
                        }
                if (out.isEmpty() && (identity || nextval) && atStatementEnd()) {
                    pendingFix = ColumnFix(schema, table, column, identity)
                }
                skipStatement()
                return out + SqlStatement.Ignored("ALTER COLUMN")
            }
            if (!word("add")) {
                skipStatement()
                return out + SqlStatement.Ignored("ALTER TABLE")
            }
            val constraintFollows =
                listOf("constraint", "primary", "unique", "check", "foreign", "exclude").any {
                    isWord(it)
                }
            if (!constraintFollows) {
                skipStatement()
                return out + SqlStatement.Dropped("ALTER TABLE ADD COLUMN", pos)
            }
            val name = if (word("constraint")) ident("a constraint name") else null
            out +=
                tableConstraint(name)?.let { SqlStatement.AlterAdd(schema, table, it, pos) }
                    ?: SqlStatement.Dropped("EXCLUDE constraint", pos)
        } while (symbol(","))
        return out
    }

    private fun commentOn(): SqlStatement {
        val pos = peek().pos
        expectWord("comment")
        expectWord("on")
        val kind: String
        val schema: String?
        val table: String
        var column: String? = null
        when {
            word("table") -> {
                kind = "TABLE"
                qualifiedName("a table name").let { (s, t) ->
                    schema = s
                    table = t
                }
            }
            word("column") -> {
                kind = "COLUMN"
                val parts = mutableListOf(ident("a table name"))
                while (symbol(".")) parts += ident("a name")
                if (parts.size !in 2..3) fail("expected a table and column name")
                schema = if (parts.size == 3) parts[0] else null
                table = parts[parts.size - 2]
                column = parts.last()
            }
            else -> {
                val what = peek().text.uppercase()
                skipStatement()
                return SqlStatement.Ignored("COMMENT ON $what")
            }
        }
        expectWord("is")
        if (word("null")) return SqlStatement.Ignored("COMMENT ON $kind")
        val t = peek()
        if (t.kind != SqlTokenKind.STRING) fail("expected a string")
        i++
        return SqlStatement.CommentOn(kind, schema, table, column, t.text, pos)
    }

    // ---- types ----

    /** A column type in canonical spelling; see [SqlReader]. */
    private fun typeText(): String {
        val first = peek()
        if (first.kind != SqlTokenKind.IDENT && first.kind != SqlTokenKind.QIDENT)
            fail("expected a type")
        i++
        var qualifier: SqlToken? = null
        var nameTok = first
        if (isSymbol(".")) {
            i++
            qualifier = first
            nameTok = peek()
            if (nameTok.kind != SqlTokenKind.IDENT && nameTok.kind != SqlTokenKind.QIDENT)
                fail("expected a type")
            i++
        }
        if (qualifier?.let { it.kind == SqlTokenKind.IDENT && it.text == "pg_catalog" } == true)
            qualifier = null
        val base: String =
            if (qualifier != null || nameTok.kind == SqlTokenKind.QIDENT) {
                listOfNotNull(qualifier, nameTok).joinToString(".") { written(it) } + modifiers()
            } else {
                builtin(nameTok.text)
            }
        var arrays = ""
        while (true) {
            when {
                symbol("[") -> {
                    if (peek().kind == SqlTokenKind.NUMBER) i++
                    expectSymbol("]")
                    arrays += "[]"
                }
                word("array") -> {
                    if (symbol("[")) {
                        if (peek().kind == SqlTokenKind.NUMBER) i++
                        expectSymbol("]")
                    }
                    arrays += "[]"
                }
                else -> break
            }
        }
        return base + arrays
    }

    /** An unqualified, unquoted type [name] already consumed, with what follows it. */
    private fun builtin(name: String): String =
        when (name) {
            "character",
            "char" -> (if (word("varying")) "varchar" else "char") + modifiers()
            "national" -> {
                if (!word("character") && !word("char")) fail("expected CHARACTER")
                builtin("character")
            }
            "bit" -> if (word("varying")) "bit varying" + modifiers() else "bit" + modifiers()
            "double" -> {
                expectWord("precision")
                "double precision"
            }
            "timestamp",
            "time" -> {
                val mods = modifiers()
                val zone =
                    when {
                        word("with") -> true
                        word("without") -> false
                        else -> null
                    }
                if (zone != null) {
                    expectWord("time")
                    expectWord("zone")
                }
                (if (zone == true) name + "tz" else name) + mods
            }
            "interval" -> {
                val fields = mutableListOf<String>()
                while (peek().kind == SqlTokenKind.IDENT && peek().text in INTERVAL_FIELDS) {
                    fields += peek().text
                    i++
                }
                (listOf("interval") + fields).joinToString(" ") + modifiers()
            }
            "float" -> {
                val mods = modifiers()
                val precision = mods.removeSurrounding("(", ")").toIntOrNull()
                if (precision != null && precision <= 24) "real" else "double precision"
            }
            else -> (TYPE_ALIASES[name] ?: name) + modifiers()
        }

    /** A type's `(…)` modifiers, separated by `, `, or "" when there are none. */
    private fun modifiers(): String {
        if (!symbol("(")) return ""
        val parts = mutableListOf<String>()
        do {
            val t = peek()
            if (
                t.kind != SqlTokenKind.NUMBER &&
                    t.kind != SqlTokenKind.IDENT &&
                    t.kind != SqlTokenKind.QIDENT
            ) {
                fail("expected a type modifier")
            }
            parts += written(t)
            i++
        } while (symbol(","))
        expectSymbol(")")
        return parts.joinToString(", ", "(", ")")
    }

    private fun written(t: SqlToken): String =
        if (t.kind == SqlTokenKind.QIDENT) "\"" + t.text.replace("\"", "\"\"") + "\"" else t.text

    // ---- notes ----

    /**
     * The note of the column whose last token is `toks[last]`, from the comments before
     * `toks[end]`: a `schemata:` comment on the column's last line, else one on the line after it.
     */
    private fun note(last: Int, end: Int): String? {
        val line = toks[last].pos.line
        val between =
            all.subList(fullIndex[last] + 1, fullIndex[end]).filter {
                it.kind == SqlTokenKind.COMMENT
            }
        fun noteOn(l: Int) =
            between
                .filter { it.pos.line == l }
                .firstNotNullOfOrNull { c ->
                    c.text
                        .trim()
                        .takeIf { it.startsWith("schemata:") }
                        ?.removePrefix("schemata:")
                        ?.trim()
                }
        return noteOn(line) ?: noteOn(line + 1)
    }

    // ---- tokens ----

    private fun peek(k: Int = 0): SqlToken = toks[minOf(i + k, toks.size - 1)]

    private fun isWord(word: String, k: Int = 0) =
        peek(k).let { it.kind == SqlTokenKind.IDENT && it.text == word }

    private fun isSymbol(symbol: String, k: Int = 0) =
        peek(k).let { it.kind == SqlTokenKind.SYMBOL && it.text == symbol }

    private fun word(word: String): Boolean = isWord(word).also { if (it) i++ }

    private fun symbol(symbol: String): Boolean = isSymbol(symbol).also { if (it) i++ }

    private fun expectWord(word: String) {
        if (!word(word)) fail("expected ${word.uppercase()}")
    }

    private fun expectSymbol(symbol: String) {
        if (!symbol(symbol)) fail("expected '$symbol'")
    }

    private fun fail(message: String): Nothing = throw SqlSyntaxError(peek().pos, message)

    private fun ident(what: String): String {
        val t = peek()
        if (t.kind != SqlTokenKind.IDENT && t.kind != SqlTokenKind.QIDENT) fail("expected $what")
        i++
        return t.text
    }

    private fun qualifiedName(what: String): Pair<String?, String> {
        val first = ident(what)
        return if (symbol(".")) first to ident(what) else null to first
    }

    private fun ifNotExists() {
        if (word("if")) {
            expectWord("not")
            expectWord("exists")
        }
    }

    private fun columnList(): List<String> {
        expectSymbol("(")
        val names = mutableListOf<String>()
        do names += ident("a column name") while (symbol(","))
        expectSymbol(")")
        return names
    }

    private fun atStatementEnd() = peek().kind == SqlTokenKind.EOF || isSymbol(";")

    /** From `(` past its matching `)`. */
    private fun skipParens() {
        var depth = 0
        do {
            if (peek().kind == SqlTokenKind.EOF) fail("expected ')'")
            if (isSymbol("(")) depth++
            if (isSymbol(")")) depth--
            i++
        } while (depth > 0)
    }

    /** To the `,` or `)` that ends the current list entry, without consuming it. */
    private fun skipElement() {
        var depth = 0
        while (!atStatementEnd()) {
            if (isSymbol("(") || isSymbol("[")) depth++
            if (isSymbol(")") || isSymbol("]")) {
                if (depth == 0) return
                depth--
            }
            if (depth == 0 && isSymbol(",")) return
            i++
        }
    }

    /**
     * To the `;` that ends the statement, without consuming it. A `;` inside parentheses or inside
     * a `BEGIN ATOMIC … END` function body does not end it.
     */
    private fun skipStatement() {
        var depth = 0
        var block = 0
        while (peek().kind != SqlTokenKind.EOF) {
            if (isSymbol("(")) depth++
            if (isSymbol(")")) depth--
            if (depth == 0) {
                when {
                    block == 0 && isWord("begin") && isWord("atomic", 1) -> {
                        block = 1
                        i += 2
                        continue
                    }
                    block > 0 && isWord("case") -> block++
                    block > 0 && isWord("end") -> block--
                    block == 0 && isSymbol(";") -> return
                }
            }
            i++
        }
    }
}
