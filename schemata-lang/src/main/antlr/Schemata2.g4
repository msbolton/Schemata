grammar Schemata2;

// ---- parser ---------------------------------------------------------------

// The header's attributes follow the schema name. With no import between the header and the first
// declaration, an attribute there is read as the header's (the loop is greedy); a doc comment on
// the declaration, or an import, ends the header.
file          : doc? schemaDecl importDecl* topLevel* EOF ;
schemaDecl    : SCHEMA qualifiedName attribute* ;
importDecl    : IMPORT qualifiedName (AS IDENT)? ;
qualifiedName : IDENT ('.' IDENT)* ;

topLevel      : declaration | serviceDecl | reservedFutureDecl ;
declaration   : modelDecl | enumDecl | unionDecl | aliasDecl ;

// Block attributes (`@@x`) close a model body and belong to the model.
modelDecl     : doc? attribute* MODEL IDENT '{' modelMember* blockAttribute* '}' ;
modelMember   : field | declaration | reservedStmt ;
// `[#n] name Type [{ options }] [@attributes] [= default]`. A field's trailing attributes are read
// greedily, so an attribute after a field and before a nested declaration is the field's unless a
// doc comment starts the declaration.
field         : doc? ORDINAL? IDENT typeExpr optionBlock? attribute* ('=' literal)? ;

enumDecl      : doc? attribute* ENUM IDENT '{' enumBody '}' ;
// Commas between values are optional, so a 1.x-style list still reads.
enumBody      : (enumValue ','?)* reservedStmt* ;
enumValue     : doc? attribute* ORDINAL? IDENT ;

unionDecl     : doc? attribute* UNION IDENT '=' unionMember ('|' unionMember)* ;
unionMember   : doc? ORDINAL? typeExpr ;

// Options on an alias apply to every use of it.
aliasDecl     : doc? attribute* ALIAS IDENT '=' typeExpr optionBlock? ;

reservedStmt  : RESERVED reservedItem (',' reservedItem)* ;
reservedItem  : ORDINAL (RANGE ORDINAL)? | STRING_LITERAL ;

// A service is its own kind of top-level thing, never a declaration: a service cannot be nested
// in a model and a model cannot be named after one by accident.
serviceDecl   : doc? attribute* SERVICE IDENT '{' serviceMember* '}' ;
serviceMember : operation | reservedStmt ;
operation     : doc? attribute* ORDINAL? IDENT '(' payload? ')' (':' payload)? binding? ;
payload       : STREAM? typeExpr ;
// The verb is an identifier, not a token: `get` and `post` stay legal field names. The builder
// checks it against the HTTP methods.
binding       : IDENT STRING_LITERAL ;

// `operation` parses to a node so the AST builder can report it as reserved for a future version
// instead of a generic syntax error.
reservedFutureDecl : OPERATION IDENT? block? ;
block         : '{' (block | ~('{' | '}'))* '}' ;

// A type: a name with optional arguments, an inline enum, or an inline shape; then `?` for a
// nullable element and `[]` (optionally `?`) for a list of it. `decimal(19, 4)` keeps its
// parenthesised precision and scale because they are part of the type. A field is
// `IDENT typeExpr optionBlock?`, so after a field's name the first `{ … }` is an inline shape and
// the one after a type is its options.
typeExpr      : typeCore QUESTION? ('[' ']' QUESTION?)? ;
typeCore      : qualifiedName typeArgs? decimalArgs?
              | ENUM '{' enumBody '}'
              | '{' modelMember* blockAttribute* '}' ;
typeArgs      : '<' typeArg (',' typeArg)* '>' ;
// Options on a type argument constrain a map's key or value.
typeArg       : typeExpr optionBlock? ;
decimalArgs   : '(' INT_LITERAL ',' INT_LITERAL ')' ;

// Option names are plain identifiers, never keywords, so `index int32 { index }` reads. A value is
// a number, a string, or a boolean but never a bare name: `{ id unique }` is two flags, not `id`
// set to `unique`.
optionBlock   : '{' option (','? option)* '}' ;
option        : IDENT optionValue? ;
optionValue   : INT_LITERAL | FLOAT_LITERAL | STRING_LITERAL | TRUE | FALSE ;

// A target's key vocabulary is its own, so a reserved word reads fine as an attribute name or key
// (`@sql(schema: "shop")`); only a declared name is barred from reusing one.
attribute      : '@' attributeName ('(' (attrArg (',' attrArg)*)? ')')? ;
blockAttribute : '@@' attributeName ('(' (attrArg (',' attrArg)*)? ')')? ;
attributeName  : IDENT | keyword ;
attrArg        : attrKey ':' attrValue | IDENT | literal ;
attrKey        : IDENT | keyword ;
attrValue      : literal | '(' IDENT (',' IDENT)* ')' ;

keyword       : SCHEMA | IMPORT | AS | MODEL | ENUM | UNION | ALIAS | RESERVED | TRUE | FALSE | SERVICE | OPERATION | STREAM ;
literal       : INT_LITERAL | FLOAT_LITERAL | STRING_LITERAL | TRUE | FALSE | IDENT ;
doc           : DOC_COMMENT+ ;

// ---- lexer ----------------------------------------------------------------
// Keywords before IDENT so they win the equal-length tie. DOC_COMMENT before LINE_COMMENT for the
// same reason: both match a whole `/// …` line, and the first declared wins.

SCHEMA    : 'schema' ;
IMPORT    : 'import' ;
AS        : 'as' ;
MODEL     : 'model' ;
ENUM      : 'enum' ;
UNION     : 'union' ;
ALIAS     : 'alias' ;
RESERVED  : 'reserved' ;
TRUE      : 'true' ;
FALSE     : 'false' ;
SERVICE   : 'service' ;
OPERATION : 'operation' ;
STREAM    : 'stream' ;

ORDINAL        : '#' [0-9]+ ;
FLOAT_LITERAL  : '-'? [0-9]+ '.' [0-9]+ ;
INT_LITERAL    : '-'? [0-9]+ ;
STRING_LITERAL : '"' (~["\\\r\n] | '\\' ~[\r\n])* '"' ;
RANGE          : '..' ;
QUESTION       : '?' ;
IDENT          : [A-Za-z_] [A-Za-z0-9_]* ;

DOC_COMMENT   : '///' ~[\r\n]* ;
LINE_COMMENT  : '//' ~[\r\n]* -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN) ;
WS            : [ \t\r\n]+ -> skip ;
