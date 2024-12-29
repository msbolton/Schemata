use std::collections::HashMap;
use quick_xml::writer::Writer;
use quick_xml::events::{BytesStart, BytesEnd, Event};
use std::io::Cursor;
use crate::schemata::types::{AnnotationValue, SchemataAnnotation, SchemataEnum, SchemataField, SchemataSchema};
use crate::schemata::parser::SchemataParser;

pub struct SchemataToXsdGenerator {
    pub(crate) target_namespace: String,
    prefix_map: HashMap<String, String>,
}

impl SchemataToXsdGenerator {
    pub fn new(namespace: String) -> Self {
        Self {
            target_namespace: namespace,
            prefix_map: HashMap::new(),
        }
    }

    pub fn generate(&mut self, schemata_content: &str) -> Result<String, Box<dyn std::error::Error>> {
        // parse the schemata idl
        let parsed_file = SchemataParser::parse_schemata(schemata_content)?;
        // create xml writer
        let mut writer = Writer::new(Cursor::new(Vec::new()));
        // write xsd header
        self.write_xsd_header(&mut writer)?;

        for namespace in &parsed_file.namespaces {
            for schema in &namespace.schemas {
                self.generate_complex_type(&mut writer, schema)?;
            }

            for enum_type in &namespace.enums {
                self.generate_simple_type(&mut writer, enum_type)?;
            }
        }

        // write xsd footer
        self.write_xsd_footer(&mut writer)?;
        // convert to string
        let result = String::from_utf8(writer.into_inner().into_inner())?;
        Ok(result)
    }

    pub(crate) fn write_xsd_header<W: std::io::Write>(&mut self, writer: &mut Writer<W>) -> Result<(), quick_xml::Error> {
        let mut elem = BytesStart::new("xs:schema");
        elem.push_attribute(("xmlns:xs", "http://www.w3.org/2001/XMLSchema"));
        elem.push_attribute(("xmlns:tns", self.target_namespace.as_str()));
        elem.push_attribute(("targetNamespace", self.target_namespace.as_str()));
        elem.push_attribute(("elementFormDefault", "qualified"));
        writer.write_event(Event::Start(elem))?;
        Ok(())
    }

    pub(crate) fn write_xsd_footer<W: std::io::Write>(&mut self, writer: &mut Writer<W>) -> Result<(), quick_xml::Error> {
        writer.write_event(Event::End(BytesEnd::new("xs:schema")))?;
        Ok(())
    }

    pub(crate) fn generate_complex_type<W: std::io::Write>(&mut self, writer: &mut Writer<W>, schema: &SchemataSchema) -> Result<(), quick_xml::Error> {
        let mut complex_type_elem = BytesStart::new("xs:complexType");
        complex_type_elem.push_attribute(("name", schema.name.as_str()));
        writer.write_event(Event::Start(complex_type_elem))?;

        let sequence_elem = BytesStart::new("xs:sequence");
        writer.write_event(Event::Start(sequence_elem))?;

        // Generate fields
        for field in &schema.fields {
            self.generate_field(writer, field)?;
        }

        writer.write_event(Event::End(BytesEnd::new("xs:sequence")))?;
        writer.write_event(Event::End(BytesEnd::new("xs:complexType")))?;

        Ok(())
    }

    pub(crate) fn generate_field<W: std::io::Write>(&mut self, writer: &mut Writer<W>, field: &SchemataField) -> Result<(), quick_xml::Error> {
        let mut element = BytesStart::new("xs:element");
        element.push_attribute(("name", field.name.as_str()));
        element.push_attribute(("type", self.map_type(&field.type_name).as_str()));

        // Handle nullability and occurrence constraints
        if field.nullable {
            element.push_attribute(("minOccurs", "0"));
        }

        // Handle max occurs from annotations
        if let Some(max_occurs) = self.extract_max_occurs(&field.annotations) {
            element.push_attribute(("maxOccurs", max_occurs.as_str()));
        }

        // Handle inline schemas
        if let Some(inline_schema) = &field.inline_schema {
            writer.write_event(Event::Start(element))?;

            let mut complex_type_elem = BytesStart::new("xs:complexType");
            writer.write_event(Event::Start(complex_type_elem))?;

            let sequence_elem = BytesStart::new("xs:sequence");
            writer.write_event(Event::Start(sequence_elem))?;

            for inner_field in &inline_schema.fields {
                self.generate_field(writer, inner_field)?;
            }

            writer.write_event(Event::End(BytesEnd::new("xs:sequence")))?;
            writer.write_event(Event::End(BytesEnd::new("xs:complexType")))?;
            writer.write_event(Event::End(BytesEnd::new("xs:element")))?;
        } else {
            writer.write_event(quick_xml::events::Event::Empty(element))?;
        }

        Ok(())
    }

    pub(crate) fn generate_simple_type<W: std::io::Write>(&mut self, writer: &mut Writer<W>, enum_type: &SchemataEnum) -> Result<(), quick_xml::Error> {
        let mut simple_type_elem = BytesStart::new("xs:simpleType");
        simple_type_elem.push_attribute(("name", enum_type.name.as_str()));
        writer.write_event(Event::Start(simple_type_elem))?;

        let mut restriction_elem = BytesStart::new("xs:restriction");
        restriction_elem.push_attribute(("base", "xs:string"));
        writer.write_event(Event::Start(restriction_elem))?;

        for value in &enum_type.values {
            let mut enum_elem = BytesStart::new("xs:enumeration");
            enum_elem.push_attribute(("value", value.as_str()));
            writer.write_event(Event::Empty(enum_elem))?;
        }

        writer.write_event(Event::End(BytesEnd::new("xs:restriction")))?;
        writer.write_event(Event::End(BytesEnd::new("xs:simpleType")))?;

        Ok(())
    }

    pub(crate) fn map_type(&mut self, type_name: &str) -> String {
        match type_name {
            "int" => "xs:integer".to_string(),
            "string" => "xs:string".to_string(),
            "float" => "xs:float".to_string(),
            "datetime" => "xs:dateTime".to_string(),
            "bool" => "xs:boolean".to_string(),
            _ => format!("xs:{}", type_name),
        }
    }

    pub(crate) fn extract_max_occurs(&self, annotations: &[SchemataAnnotation]) -> Option<String> {
        // Extract @maxOccurs from annotations
        annotations
            .iter()
            .find_map(|ann| {
                if ann.name == "@maxOccurs" {
                    ann.params.get("value").and_then(|value| {
                        match value {
                            AnnotationValue::Integer(i) => Some(i.to_string()),
                            AnnotationValue::String(s) => Some(s.clone()),
                            _ => None
                        }
                    })
                } else {
                    None
                }
            })
    }
}