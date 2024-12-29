use std::collections::HashMap;
use serde::Serialize;
use pest::error::Error as PestError;

#[derive(Serialize, Debug, Clone)]
pub struct SchemataFile {
    pub namespaces: Vec<SchemataNamespace>,
}

#[derive(Serialize, Debug, Clone)]
pub struct SchemataNamespace {
    pub name: String,
    pub schemas: Vec<SchemataSchema>,
    pub enums: Vec<SchemataEnum>,
}

#[derive(Serialize, Debug, Clone)]
pub struct SchemataSchema {
    pub name: String,
    pub comment: Option<String>,
    pub annotations: Vec<SchemataAnnotation>,
    pub fields: Vec<SchemataField>,
}

#[derive(Serialize, Debug, Clone)]
pub struct SchemataField {
    pub name: String,
    pub type_name: String,
    pub nullable: bool,
    pub annotations: Vec<SchemataAnnotation>,
    pub comment: Option<String>,
    pub inline_schema: Option<SchemataSchema>,
}

#[derive(Serialize, Debug, Clone)]
pub struct SchemataAnnotation {
    pub name: String,
    pub params: HashMap<String, AnnotationValue>,
}

#[derive(Serialize, Debug, Clone)]
pub struct SchemataEnum {
    pub name: String,
    pub comment: Option<String>,
    pub annotations: Vec<SchemataAnnotation>,
    pub values: Vec<String>,
}

#[derive(Serialize, Debug, Clone)]
pub enum AnnotationValue {
    String(String),
    Integer(i32),
    Boolean(bool),
}

#[derive(Debug, thiserror::Error)]
pub enum ParseError {
    #[error("Syntax error")]
    SyntaxError,
    #[error("Invalid structure")]
    InvalidStructure,
    #[error("Parsing failed: {0}")]
    Custom(String),
    #[error("Pest parsing error: {0}")]
    PestError(String),
}

#[derive(Debug, thiserror::Error)]
pub enum GenerationError {
    #[error("Generation failed")]
    GenerationFailed,
    #[error("Invalid schema structure")]
    InvalidSchema,
}