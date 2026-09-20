grammar Schemata;

// ---- parser ---------------------------------------------------------------

file          : namespaceDecl declaration* EOF ;
namespaceDecl : NAMESPACE qualifiedName ;
qualifiedName : IDENT ('.' IDENT)* ;

declaration   : recordDecl ;
recordDecl    : RECORD IDENT '{' field* '}' ;
field         : IDENT ':' typeRef ;
typeRef       : IDENT QUESTION? ;

// ---- lexer ----------------------------------------------------------------
// Keywords must be declared before IDENT so they win the tie on equal length.

NAMESPACE : 'namespace' ;
RECORD    : 'record' ;
QUESTION  : '?' ;
IDENT     : [A-Za-z_] [A-Za-z0-9_]* ;

LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;
