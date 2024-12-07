use tera::{Context, Tera};
use super::types::{SchemataEnum, SchemataField, SchemataNamespace, SchemataSchema};
use crate::xsd::types::{XsdComplexType, XsdElement, XsdSchema, XsdSimpleType};


pub struct SchemataGenerator {
    tera: Tera,
}

impl SchemataGenerator {
    pub fn new() -> Result<Self, Box<dyn std::error::Error>> {
        let mut tera = Tera::default();
        tera.add_raw_template("schema", include_str!("../../templates/schemata/schema.tera"))?;
        Ok(Self { tera })
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
                    values: r.enumeration.clone(),
                })
            }).collect()
    }

    pub(crate) fn get_annotations(&self, element: &XsdElement) -> String {
        let mut annotations = Vec::new();

        if let Some(min_occurs) = &element.min_occurs {
            annotations.push(format!("@minOccurs({})", min_occurs));
        }
        if let Some(max_occurs) = &element.max_occurs {
            annotations.push(format!("@maxOccurs({})", max_occurs));
        }

        annotations.join(" ")
    }
}