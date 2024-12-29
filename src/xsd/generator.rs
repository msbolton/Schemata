use std::collections::HashMap;
use tera::{Context, Tera};
use crate::schemata::types::{AnnotationValue, SchemataAnnotation, SchemataEnum, SchemataField, SchemataNamespace, SchemataSchema};
use crate::xsd::types::{XsdComplexType, XsdElement, XsdSchema, XsdSimpleType};


pub struct XsdToSchemataGenerator {
    tera: Tera,
}

impl XsdToSchemataGenerator {
    pub fn new() -> Result<Self, Box<dyn std::error::Error>> {
        let mut tera = Tera::default();
        tera.add_raw_template("schema", include_str!("../../templates/schemata/schema.tera"))?;
        Ok(Self { tera })
    }

    pub fn default() -> Result<Self, Box<dyn std::error::Error>> {
        Self::new()
    }

    pub fn generate(&self, xsd_schema: XsdSchema) -> Result<String, Box<dyn std::error::Error>> {
        let mut context = Context::new();

        let namespaces = self.get_namespaces(&xsd_schema);
        context.insert("namespaces", &namespaces);

        Ok(self.tera.render("schema", &context)?)
    }

    pub(crate) fn get_namespaces(&self, xsd_schema: &XsdSchema) -> Vec<SchemataNamespace> {
        // Group schemas and enums by namespace
        vec![SchemataNamespace {
            name: xsd_schema.target_namespace.clone().unwrap_or_else(|| "default".to_string()),
            schemas: self.get_schemas(&xsd_schema.complex_types),
            enums: self.get_enums(&xsd_schema.simple_types),
        }]
    }

    pub(crate) fn get_schemas(&self, complex_types: &[XsdComplexType]) -> Vec<SchemataSchema> {
        complex_types.iter().map(|ct| {
            SchemataSchema {
                name: ct.name.clone().unwrap_or_else(|| "UnnamedSchema".to_string()),
                comment: None,
                annotations: self.get_complex_type_annotations(ct),
                fields: self.get_fields(&ct.sequence),
            }
        }).collect()
    }

    pub(crate) fn get_fields(&self, elements: &[XsdElement]) -> Vec<SchemataField> {
        elements.iter().map(|e| {
            SchemataField {
                name: e.name.clone(),
                type_name: e.type_name.clone().unwrap_or_else(|| "string".to_string()),
                nullable: e.min_occurs.as_ref().map_or(false, |m| m.to_string() == "0"),
                annotations: self.get_annotations(e),
                comment: e.comment.clone(),
                inline_schema: e.complex_type.as_ref().map(|ct| SchemataSchema {
                    name: "InlineSchema".to_string(),
                    comment: None,
                    annotations: vec![],
                    fields: self.get_fields(&ct.sequence),
                }),
            }
        }).collect()
    }

    pub(crate) fn get_enums(&self, simple_types: &[XsdSimpleType]) -> Vec<SchemataEnum> {
        simple_types.iter()
            .filter_map(|st| {
                st.restriction.as_ref().map(|r| SchemataEnum {
                    name: st.name.clone().unwrap_or_else(|| "UnnamedEnum".to_string()),
                    comment: None,
                    annotations: vec![],
                    values: r.enumeration.clone(),
                })
            }).collect()
    }

    pub(crate) fn get_annotations(&self, element: &XsdElement) -> Vec<SchemataAnnotation> {
        let mut annotations = Vec::new();

        if let Some(min_occurs) = &element.min_occurs {
            annotations.push(SchemataAnnotation {
                name: "minOccurs".to_string(),
                params: HashMap::from([
                    ("value".to_string(), AnnotationValue::String(min_occurs.to_string()))
                ])
            });
        }

        if let Some(min_occurs) = &element.min_occurs {
            annotations.push(SchemataAnnotation {
                name: "maxOccurs".to_string(),
                params: HashMap::from([
                    ("value".to_string(), AnnotationValue::String(min_occurs.to_string()))
                ])
            });
        }

        annotations
    }

    pub(crate) fn get_complex_type_annotations(&self, complex_type: &XsdComplexType) -> Vec<SchemataAnnotation> {
        let mut annotations = Vec::new();

        if complex_type.mixed {
            annotations.push(SchemataAnnotation {
                name: "mixed".to_string(),
                params: HashMap::from([
                    ("value".to_string(), AnnotationValue::Boolean(true))
                ])
            });
        }

        for attribute in &complex_type.attributes {
            annotations.push(SchemataAnnotation {
                name: "attribute".to_string(),
                params: HashMap::from([
                    ("name".to_string(), AnnotationValue::String(attribute.name.clone())),
                    ("use".to_string(), AnnotationValue::String(attribute.use_type.clone()))
                ])
            })
        }
        // TODO add more complex type specific annotations
        // e.g. checking for inheritance, abstract types, etc
        annotations
    }
}