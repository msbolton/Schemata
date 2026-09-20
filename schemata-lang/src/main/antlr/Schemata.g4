grammar Schemata;

file          : namespaceDecl EOF ;
namespaceDecl : NAMESPACE qualifiedName ;
qualifiedName : IDENT ('.' IDENT)* ;

NAMESPACE : 'namespace' ;
IDENT     : [A-Za-z_] [A-Za-z0-9_]* ;

LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;
