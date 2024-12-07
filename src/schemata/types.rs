use serde::Serialize;

#[derive(Serialize)]
pub struct SchemataNamespace {
    pub name: String,
    pub schemas: Vec<SchemataSchema>,
    pub enums: Vec<SchemataEnum>,
}

#[derive(Serialize)]
pub struct SchemataSchema {
    pub name: String,
    pub comment: Option<String>,
    pub fields: Vec<SchemataField>,
}

#[derive(Serialize)]
pub struct SchemataField {
    pub name: String,
    pub type_name: String,
    pub nullable: bool,
    pub annotations: String,
    pub comment: Option<String>,
    pub inline_schema: Option<SchemataSchema>,
}

#[derive(Serialize)]
pub struct SchemataEnum {
    pub name: String,
    pub comment: Option<String>,
    pub values: Vec<String>,
}
