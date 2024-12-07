use std::collections::HashMap;
use quick_xml::events::{Event, BytesStart};
use quick_xml::reader::Reader;
use std::io::BufRead;

use super::types::*;

pub struct XsdParser;

impl XsdParser {
    pub fn parse<R: BufRead>(reader: R) -> Result<XsdSchema, Box<dyn std::error::Error>> {
        let mut xml_reader = Reader::from_reader(reader);
        xml_reader.trim_text(true);

        let mut buf = Vec::new();
        let mut schema = XsdSchema::default();
        let mut element_stack = Vec::new();
        let mut namespace_map: HashMap<String, String> = HashMap::new();

        loop {
            match xml_reader.read_event_into(&mut buf) {
                Ok(Event::Start(ref e)) => {
                    match e.local_name().as_ref() {
                        b"schema" => {
                            // Capture schema-level attributes and namespaces
                            schema.target_namespace = Self::get_attribute(e, "targetNamespace", &xml_reader);
                            // Collect namespace declarations
                            for attr in e.attributes() {
                                if let Ok(attr) = attr {
                                    let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                                    if key.starts_with("xmlns:") {
                                        let prefix = key.split(':').nth(1).unwrap_or_default();
                                        if let Ok(value) = attr.decode_and_unescape_value(&xml_reader) {
                                            namespace_map.insert(prefix.to_string(), value.to_string());
                                        }
                                    }
                                }
                            }
                        },
                        b"element" => {
                            let element = Self::parse_element(e, &mut xml_reader)?;
                            element_stack.push(element);
                        },
                        b"complexType" => {
                            let complex_type = Self::parse_complex_type(e, &mut xml_reader)?;
                            if complex_type.name.is_some() {
                                schema.complex_types.push(complex_type);
                            } else if let Some(element) = element_stack.last_mut() {
                                element.complex_type = Some(complex_type);
                            }
                        },
                        b"simpleType" => {
                            let simple_type = Self::parse_simple_type(e, &mut xml_reader)?;
                            schema.simple_types.push(simple_type);
                        },
                        b"include" | b"import" => {
                          // Handle schema includes and imports
                            let schema_location = Self::get_attribute(e, "schemaLocation", &xml_reader);
                            if let Some(location) = schema_location {
                                schema.imported_schemas.push(location);
                            }
                        },
                        _ => {
                            eprintln!("Unhandled top-level element: {:?}",
                                String::from_utf8_lossy(e.name().as_ref()));
                        }
                    }
                },
                Ok(Event::End(ref e)) => {
                    if e.local_name().as_ref() == b"element" {
                        if let Some(element) = element_stack.pop() {
                            schema.elements.push(element);
                        }
                    }
                },
                Ok(Event::Eof) => break,
                Err(e) => {
                    eprintln!("Error parsing XML: {:?}", e);
                    return Err(Box::new(e));
                },
                _ => {}
            }
            buf.clear();
        }
        // Store namespace mappings
        schema.namespaces = namespace_map;
        Ok(schema)
    }

    pub(crate) fn get_attribute<R: BufRead>(e: &BytesStart, attr_name: &str, reader: &Reader<R>) -> Option<String> {
        e.attributes()
            .find_map(|a| {
                let a = a.ok()?;
                let key = String::from_utf8_lossy(a.key.as_ref());
                // Handle qualified and unqualified attributes
                if key == attr_name || key.ends_with(&format!(":{}", attr_name)) {
                    match a.decode_and_unescape_value(reader) {
                        Ok(val) => Some(val.into_owned()),
                        Err(e) => {
                            eprintln!("Error decoding attribute {}: {:?}", attr_name, e);
                            None
                        }
                    }
                } else {
                    None
                }
            })
    }

    pub fn parse_element<R: BufRead>(e: &BytesStart, reader: &mut Reader<R>) -> Result<XsdElement, Box<dyn std::error::Error>> {
        let mut element = XsdElement {
            name: Self::get_attribute(e, "name", reader).unwrap_or_default(),
            type_name: Self::get_attribute(e, "type", reader),
            min_occurs: Self::get_attribute(e, "minOccurs", reader),
            max_occurs: Self::get_attribute(e, "maxOccurs", reader),
            complex_type: None,
            simple_type: None,
            comment: None,
        };

        // Parse nested complex type if present
        if element.type_name.is_none() {
            let mut buf = Vec::new();
            loop {
                match reader.read_event_into(&mut buf) {
                    Ok(Event::Start(ref e)) if e.local_name().as_ref() == b"complexType" => {
                        element.complex_type = Some(Self::parse_complex_type(e, reader)?);
                        break;
                    },
                    Ok(Event::End(ref e)) if e.local_name().as_ref() == b"element" => break,
                    Ok(Event::Eof) => return Err("Unexpected end of file while parsing <element>".into()),
                    Err(e) => return Err(Box::new(e)),
                    _ => {}
                }
                buf.clear();
            }
        }

        element.comment = Self::extract_comment(reader);

        Ok(element)
    }

    pub(crate) fn parse_complex_type<R: BufRead>(e: &BytesStart, reader: &mut Reader<R>) -> Result<XsdComplexType, Box<dyn std::error::Error>> {
        let mut complex_type = XsdComplexType {
            name: Self::get_attribute(e, "name", reader),
            sequence: Vec::new(),
            attributes: Vec::new(),
            mixed: Self::get_attribute(e, "mixed", reader)
                .map(|v| v == "true")
                    .unwrap_or(false),
        };

        let mut buf = Vec::new();
        let mut depth = 0;
        loop {
            match reader.read_event_into(&mut buf) {
                Ok(Event::Start(ref e)) => {
                    depth += 1;
                    match e.local_name().as_ref() {
                        b"sequence" => {
                            complex_type.sequence = Self::parse_sequence(reader)?;
                        },
                        b"attribute" => {
                            if let Ok(attr) = Self::parse_attribute(e, reader) {
                                complex_type.attributes.push(attr);
                            }
                        },
                        _ => {
                            println!("Unhandled start element: {:?}", String::from_utf8_lossy(e.local_name().as_ref()));
                        }
                    }
                },
                Ok(Event::Empty(ref e)) => {
                    if e.local_name().as_ref() == b"attribute" {
                        if let Ok(attr) = Self::parse_attribute(e, reader) {
                            complex_type.attributes.push(attr);
                        }
                    }
                },
                Ok(Event::End(ref e)) => {
                    if e.local_name().as_ref() == b"complexType" {
                        return Ok(complex_type)
                    }
                    depth -= 1;
                },
                Ok(Event::Eof) => return Err(format!("Unexpected end of file while parsing <complexType> (depth: {})", depth).into()),
                Err(e) => return Err(Box::new(e)),
                _ => {}
            }
            buf.clear();
        }
        Ok(complex_type)
    }

    pub(crate) fn parse_sequence<R: BufRead>(reader: &mut Reader<R>) -> Result<Vec<XsdElement>, Box<dyn std::error::Error>> {
        let mut sequence = Vec::new();
        let mut buf = Vec::new();
        let mut depth = 0;

        loop {
            match reader.read_event_into(&mut buf) {
                Ok(Event::Start(ref start_element)) => {
                    depth += 1;
                    if start_element.local_name().as_ref() == b"element" {
                        if let Ok(element) = Self::parse_element(start_element, reader) {
                            sequence.push(element);
                        }
                    }
                },
                Ok(Event::Empty(ref e)) => {
                  if e.local_name().as_ref() == b"element" {
                      sequence.push(Self::parse_element(e, reader)?);
                  }
                },
                Ok(Event::End(ref e)) => {
                    if depth == 0 && e.local_name().as_ref() == b"sequence" {
                        return Ok(sequence);
                    }
                    depth -= 1;
                },
                Ok(Event::Eof) => {
                    return Err(format!("Unexpected end of file while parsing <sequence> (depth: {})", depth).into());
                },
                Err(e) => return Err(Box::new(e)),
                _ => {}
            }
            buf.clear();
        }
        Ok(sequence)
    }

    pub(crate) fn parse_attribute<R: BufRead>(e: &BytesStart, reader: &mut Reader<R>) -> Result<XsdAttribute, Box<dyn std::error::Error>> {
        Ok(XsdAttribute {
            name: Self::get_attribute(e, "name", reader).unwrap_or_default(),
            type_name: Self::get_attribute(e, "type", reader).unwrap_or_default(),
            use_type: Self::get_attribute(e, "use", reader).unwrap_or_default(),
            default: Self::get_attribute(e, "default", reader),
            fixed: Self::get_attribute(e, "fixed", reader),
        })
    }

    pub(crate) fn parse_simple_type<R: BufRead>(e: &BytesStart, reader: &mut Reader<R>) -> Result<XsdSimpleType, Box<dyn std::error::Error>> {
        let mut simple_type = XsdSimpleType {
            name: Self::get_attribute(e, "name", reader),
            restriction: None,
            list: None,
            union: None,
        };

        let mut buf = Vec::new();
        loop {
            match reader.read_event_into(&mut buf) {
                Ok(Event::Start(ref e)) => {
                    match e.local_name().as_ref() {
                        b"restriction" => {
                            simple_type.restriction = Some(Self::parse_restriction(e, reader)?);
                        },
                        b"list" => {
                          simple_type.list = Self::get_attribute(e, "itemType", reader);
                        },
                        b"union" => {
                            simple_type.union = Self::get_attribute(e, "memberTypes", reader)
                                .map(|v| v.split_whitespace().map(String::from).collect());
                        },
                        _ => {}
                    }
                },
                Ok(Event::End(ref e)) if e.local_name().as_ref() == b"simpleType" => break,
                Ok(Event::Eof) => return Err("Unexpected end of file while parsing <simpleType>".into()),
                Err(e) => return Err(Box::new(e)),
                _ => {}
            }
            buf.clear();
        }
        Ok(simple_type)
    }

    pub(crate) fn parse_restriction<R: BufRead>(e: &BytesStart, reader: &mut Reader<R>) -> Result<XsdRestriction, Box<dyn std::error::Error>> {
        let mut restriction = XsdRestriction {
            base: Self::get_attribute(e, "base", reader).unwrap_or_default(),
            enumeration: Vec::new(),
            pattern: None,
            min_inclusive: None,
            max_inclusive: None,
            min_exclusive: None,
            max_exclusive: None,
            length: None,
            min_length: None,
            max_length: None,
            total_digits: None,
            fraction_digits: None,
        };

        let mut buf = Vec::new();
        loop {
            match reader.read_event_into(&mut buf) {
                Ok(Event::Empty(ref e)) => {
                    match e.local_name().as_ref() {
                        b"enumeration" => {
                            if let Some(value) = Self::get_attribute(e, "value", reader) {
                                restriction.enumeration.push(value);
                            }
                        },
                        b"pattern" => {
                            restriction.pattern = Self::get_attribute(e, "value", reader);
                        },
                        b"minInclusive" => {
                            restriction.min_inclusive = Self::get_attribute(e, "value", reader);
                        },
                        b"maxInclusive" => {
                            restriction.max_inclusive = Self::get_attribute(e, "value", reader);
                        },
                        b"minExclusive" => {
                            restriction.min_exclusive = Self::get_attribute(e, "value", reader);
                        },
                        b"maxExclusive" => {
                            restriction.max_exclusive = Self::get_attribute(e, "value", reader);
                        },
                        b"length" => {
                            restriction.length = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        b"minLength" => {
                            restriction.min_length = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        b"maxLength" => {
                            restriction.max_length = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        b"totalDigits" => {
                            restriction.total_digits = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        b"fractionDigits" => {
                            restriction.fraction_digits = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        _ => {}
                    }
                },
                Ok(Event::End(ref e)) if e.local_name().as_ref() == b"restriction" => break,
                Ok(Event::Eof) => return Err("Unexpected end of file".into()),
                Err(e) => return Err(Box::new(e)),
                _ => {}
            }
            buf.clear();
        }
        Ok(restriction)
    }

    pub(crate) fn extract_comment<R: BufRead>(reader: &mut Reader<R>) -> Option<String> {
        // TODO: Implement extraction logic
        None
    }

    fn log_parsing_progress(element: &str, details: Option<&str>) {
        if cfg!(debug_assertions) {
            let detail_str = details.unwrap_or("No details");
            println!("Parsing {}: {}", element, detail_str);
        }
    }
}

trait XsdParserError: std::error::Error {
    fn get_context(&self) -> String;
}

#[derive(Debug)]
struct XsdParsingError {
    message: String,
    context: String,
}

impl std::fmt::Display for XsdParsingError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "XSD Parsing Error: {} (Context: {})", self.message, self.context)
    }
}

impl std::error::Error for XsdParsingError {}