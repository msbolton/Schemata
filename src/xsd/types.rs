use std::collections::HashMap;

#[derive(Debug, Default)]
pub struct XsdSchema {
    pub target_namespace: Option<String>,
    pub elements: Vec<XsdElement>,
    pub complex_types: Vec<XsdComplexType>,
    pub simple_types: Vec<XsdSimpleType>,
    // Add other top-level components as needed
    pub namespaces: HashMap<String, String>,
    pub imported_schemas: Vec<String>,
}

#[derive(Debug, Default)]
pub struct XsdElement {
    pub name: String,
    pub type_name: Option<String>,
    pub min_occurs: Option<String>,
    pub max_occurs: Option<String>,
    pub complex_type: Option<XsdComplexType>,
    pub simple_type: Option<XsdSimpleType>,
    pub comment: Option<String>,
}

#[derive(Debug, Default)]
pub struct XsdAttribute {
    pub name: String,
    pub type_name: String,
    pub use_type: String,
    pub default: Option<String>,
    pub fixed: Option<String>,
}

#[derive(Debug, Default)]
pub struct XsdComplexType {
    pub name: Option<String>,
    pub sequence: Vec<XsdElement>,
    pub attributes: Vec<XsdAttribute>,
    pub mixed: bool,
}

#[derive(Debug, Default)]
pub struct XsdSimpleType {
    pub name: Option<String>,
    pub restriction: Option<XsdRestriction>,
    pub list: Option<String>,
    pub union: Option<Vec<String>>,
}

#[derive(Debug, Default)]
pub struct XsdRestriction {
    pub base: String,
    pub enumeration: Vec<String>,
    pub pattern: Option<String>,
    pub min_inclusive: Option<String>,
    pub max_inclusive: Option<String>,
    pub min_exclusive: Option<String>,
    pub max_exclusive: Option<String>,
    pub length: Option<usize>,
    pub min_length: Option<usize>,
    pub max_length: Option<usize>,
    pub total_digits: Option<usize>,
    pub fraction_digits: Option<usize>,
}
