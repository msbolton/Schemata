grammar Schemata;

// ---- parser ---------------------------------------------------------------

file          : doc? annotation* namespaceDecl importDecl* topLevel* EOF ;
namespaceDecl : NAMESPACE qualifiedName ;
importDecl    : IMPORT qualifiedName (AS IDENT)? ;
qualifiedName : IDENT ('.' IDENT)* ;

topLevel      : declaration | reservedFutureDecl ;
declaration   : recordDecl | enumDecl | unionDecl | aliasDecl ;

recordDecl    : doc? annotation* RECORD IDENT '{' recordMember* '}' ;
recordMember  : field | declaration | reservedStmt ;
field         : doc? annotation* ORDINAL? IDENT ':' typeExpr ('=' literal)? ;

enumDecl      : doc? annotation* ENUM IDENT '{' (enumValue ','?)* reservedStmt* '}' ;
enumValue     : doc? annotation* ORDINAL? IDENT ;

unionDecl     : doc? annotation* UNION IDENT '=' unionMember ('|' unionMember)* ;
unionMember   : ORDINAL? typeExpr ;

aliasDecl     : doc? annotation* ALIAS IDENT '=' typeExpr ;

reservedStmt  : RESERVED reservedItem (',' reservedItem)* ;
reservedItem  : ORDINAL (RANGE ORDINAL)? | STRING_LITERAL ;

// `service`, `operation`, `stream` parse to a node so the AST builder can report them as
// reserved for a future version (spec §2.5) instead of a generic syntax error.
reservedFutureDecl : (SERVICE | OPERATION | STREAM) IDENT? block? ;
block         : '{' (block | ~('{' | '}'))* '}' ;

typeExpr      : qualifiedName typeArgs? refinements? QUESTION? ;
typeArgs      : '<' typeExpr (',' typeExpr)* '>' ;
refinements   : '(' refinement (',' refinement)* ')' ;
refinement    : IDENT '=' literal | literal ;

annotation    : '@' IDENT ('(' (annotationArg (',' annotationArg)*)? ')')? ;
annotationArg : IDENT '=' annotationValue | literal ;
annotationValue : literal | '(' IDENT (',' IDENT)* ')' ;

literal       : INT_LITERAL | FLOAT_LITERAL | STRING_LITERAL | TRUE | FALSE | IDENT ;
doc           : DOC_COMMENT+ ;

// ---- lexer ----------------------------------------------------------------
// Keywords before IDENT so they win the equal-length tie. DOC_COMMENT before LINE_COMMENT for the
// same reason: both match a whole `/// …` line, and the first declared wins.

NAMESPACE : 'namespace' ;
IMPORT    : 'import' ;
AS        : 'as' ;
RECORD    : 'record' ;
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
STRING_LITERAL : '"' (~["\\\r\n] | '\\' .)* '"' ;
RANGE          : '..' ;
QUESTION       : '?' ;
IDENT          : [A-Za-z_] [A-Za-z0-9_]* ;

DOC_COMMENT   : '///' ~[\r\n]* ;
LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;
