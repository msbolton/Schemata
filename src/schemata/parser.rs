use pest::Parser;
use pest::iterators::{Pair, Pairs};
use pest_derive::Parser;
use std::collections::HashMap;
use super::types::{
    SchemataFile,
    SchemataNamespace,
    SchemataSchema,
    SchemataField,
    SchemataEnum,
    SchemataAnnotation,
    AnnotationValue,
    ParseError,
};

#[derive(Parser)]
#[grammar = "grammars/schemata.pest"]
pub struct SchemataParser;

impl SchemataParser {
    pub fn parse_schemata(input: &str) -> Result<SchemataFile, ParseError> {
        let file = Self::parse(Rule::file, input)
            .map_err(|e| ParseError::PestError(e.to_string()))?
            .next()
            .expect("Unable to parse file");

        let mut namespaces = Vec::new();

        for pair in file.into_inner() {
            match pair.as_rule() {
                Rule::namespace_declaration => {
                    namespaces.push(Self::parse_namespace(pair));
                },
                Rule::COMMENT | Rule::EOI => {},
                _ => return Err(ParseError::InvalidStructure),
            }
        }

        Ok(SchemataFile {
            namespaces,
        })
    }

    fn parse_namespace(pair: Pair<Rule>) -> SchemataNamespace {
        let mut inner = pair.into_inner();

        let name = inner.next()
            .expect("Namespace name")
            .as_str()
            .to_string();

        let mut schemas = Vec::new();
        let mut enums = Vec::new();

        for next_pair in inner {
            match next_pair.as_rule() {
                Rule::schema => {
                    schemas.push(Self::parse_schema(next_pair));
                },
                Rule::enum_rule => {
                    enums.push(Self::parse_enum(next_pair));
                },
                _ => {}
            }
        }

        SchemataNamespace {
            name,
            schemas,
            enums,
        }
    }

    fn parse_schema(pair: Pair<Rule>) -> SchemataSchema {
        let mut inner = pair.into_inner();

        let mut annotations = Vec::new();
        let mut name = String::new();
        let mut fields = Vec::new();

        for next_pair in inner {
            match next_pair.as_rule() {
                Rule::annotation => {
                    annotations.push(Self::parse_annotation(next_pair));
                },
                Rule::identifier => {
                    name = next_pair.as_str().to_string();
                },
                Rule::field => {
                    fields.push(Self::parse_field(next_pair));
                },
                _ => {}
            }
        }

        SchemataSchema {
            name,
            comment: None,
            annotations,
            fields,
        }
    }

    fn parse_field(pair: Pair<Rule>) -> SchemataField {
        let mut inner = pair.into_inner();

        let name = inner.next().expect("Field name").as_str().to_string();
        let type_info = Self::parse_type(inner.next().expect("Field type"));
        let mut annotations = Vec::new();
        let mut inline_schema = None;
        let mut comment = None;

        for next_pair in inner {
            match next_pair.as_rule() {
                Rule::annotation => {
                    annotations.push(Self::parse_annotation(next_pair));
                },
                Rule::inline_schema => {
                    inline_schema = Some(Self::parse_inline_schema(next_pair));
                },
                Rule::COMMENT => {
                    comment = Some(next_pair.as_str().to_string());
                },
                _ => {}
            }
        }

        SchemataField {
            name,
            type_name: type_info.0,
            nullable: type_info.1,
            annotations,
            comment,
            inline_schema,
        }
    }

    fn parse_type(pair: Pair<Rule>) -> (String, bool) {
        let type_str = pair.as_str();
        let nullable = type_str.ends_with('?');
        let type_name = if nullable {
            type_str[..type_str.len() - 1].to_string()
        } else {
            type_str.to_string()
        };
        (type_name, nullable)
    }

    fn parse_inline_schema(pair: Pair<Rule>) -> SchemataSchema {
        let mut fields = Vec::new();

        for next_pair in pair.into_inner() {
            match next_pair.as_rule() {
                Rule::field => {
                    fields.push(Self::parse_field(next_pair));
                },
                _ => {}
            }
        }

        SchemataSchema {
            name: "InlineSchema".to_string(),
            comment: None,
            annotations: Vec::new(),
            fields,
        }
    }

    fn parse_enum(pair: Pair<Rule>) -> SchemataEnum {
        let mut inner = pair.into_inner();

        let mut annotations = Vec::new();
        let mut name = String::new();
        let mut values = Vec::new();

        for next_pair in inner {
            match next_pair.as_rule() {
                Rule::annotation => {
                    annotations.push(Self::parse_annotation(next_pair));
                },
                Rule::identifier => {
                    if name.is_empty() {
                        name = next_pair.as_str().to_string();
                    } else {
                        values.push(next_pair.as_str().to_string());
                    }
                },
                Rule::enum_rule => {
                    values.push(next_pair.as_str().to_string());
                },
                _ => {}
            }
        }

        SchemataEnum {
            name,
            annotations,
            values,
            comment: None,
        }
    }

    fn parse_annotation(pair: Pair<Rule>) -> SchemataAnnotation {
        let mut inner = pair.into_inner();

        let name = inner.next()
            .expect("Annotation name")
            .as_str()
            .to_string();

        let mut params = HashMap::new();

        if let Some(params_pair) = inner.next() {
            for param_pair in params_pair.into_inner() {
                let mut param_inner = param_pair.into_inner();

                let key = param_inner.next()
                    .expect("Param key")
                    .as_str()
                    .to_string();

                let value_pair = param_inner.next()
                    .expect("Param value");

                let value = match value_pair.as_rule() {
                    Rule::string_literal => AnnotationValue::String(
                        value_pair.as_str()
                            .trim_matches('"')
                            .to_string()
                    ),
                    Rule::number_literal => AnnotationValue::Integer(
                        value_pair.as_str()
                            .parse()
                            .expect("Invalid integer")
                    ),
                    Rule::boolean_literal => AnnotationValue::Boolean(
                        value_pair.as_str() == "true"
                    ),
                    _ => panic!("Unexpected annotation value type"),
                };

                params.insert(key, value);
            }
        }

        SchemataAnnotation {
            name,
            params,
        }
    }
}