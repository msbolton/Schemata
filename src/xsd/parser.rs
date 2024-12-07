use quick_xml::events::{Event, BytesStart};
use quick_xml::reader::Reader;
use std::io::BufRead;

use super::types::*;

pub struct XsdParser;

impl XsdParser {
    pub fn parse<R: BufRead>(reader: R) -> Result<XsdSchema, Box<dyn std::error::Error>> {
        let mut xml_reader = Reader::from_reader(reader);
        let mut buf = Vec::new();
        let mut schema = XsdSchema::default();
        let mut element_stack = Vec::new();

        loop {
        match xml_reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => match e.name().as_ref() {
                        b"xs:schema" => {
                    schema.target_namespace = Self::get_attribute(e, "targetNamespace", &xml_reader);
                        },
                        b"xs:element" => {
                    let element = Self::parse_element(e, &mut xml_reader)?;
                            element_stack.push(element);
                        },
                        b"xs:complexType" => {
                            let complex_type = Self::parse_complex_type(e, &mut xml_reader)?;
                            if complex_type.name.is_some() {
                                schema.complex_types.push(complex_type);
                            } else if let Some(element) = element_stack.last_mut() {
                                element.complex_type = Some(complex_type);
                            }
                        },
                        b"xs:simpleType" => {
                    schema.simple_types.push(Self::parse_simple_type(e, &mut xml_reader)?);
                        },
                        _ => {}
            },
            Event::End(ref e) if e.name().as_ref() == b"xs:element" => {
                        if let Some(element) = element_stack.pop() {
                            schema.elements.push(element);
                        }
            },
            Event::Eof => break,
                _ => {}
            }
            buf.clear();
        }
        Ok(schema)
    }

    pub(crate) fn get_attribute<R: BufRead>(e: &BytesStart, attr_name: &str, reader: &Reader<R>) -> Option<String> {
        e.attributes()
            .find_map(|a| {
                let a = a.ok()?;
                if a.key.as_ref() == attr_name.as_bytes() {
                    a.decode_and_unescape_value(reader).ok().map(|v| v.into_owned())
                } else {
                    None
                }
            })
    }

    pub(crate) fn parse_element<R: BufRead>(e: &BytesStart, reader: &mut Reader<R>) -> Result<XsdElement, Box<dyn std::error::Error>> {
        let mut element = XsdElement {
            name: Self::get_attribute(e, "name", reader).unwrap_or_default(),
            type_name: Self::get_attribute(e, "type", reader),
            min_occurs: Self::get_attribute(e, "minOccurs", reader)
                .and_then(|v| v.parse().ok()),
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
                    Ok(Event::Start(ref e)) if e.name().as_ref() == b"xs:complexType" => {
                        element.complex_type = Some(Self::parse_complex_type(e, reader)?);
                        break;
                    },
                    Ok(Event::End(ref e)) if e.name().as_ref() == b"xs:element" => break,
                    Ok(Event::Eof) => return Err("Unexpected end of file.".into()),
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
        loop {
            match reader.read_event_into(&mut buf) {
                Ok(Event::Start(ref e)) => {
                    match e.name().as_ref() {
                        b"xs:sequence" => {
                            complex_type.sequence = Self::parse_sequence(reader)?;
                        },
                        b"xs:attribute" => {
                            complex_type.attributes.push(Self::parse_attribute(e, reader));
                        },
                        // handle other complex type components
                        _ => {}
                    }
                },
                Ok(Event::End(ref e)) if e.name().as_ref() == b"xs:complexType" => break,
                Ok(Event::Eof) => return Err("Unexpected end of file".into()),
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
        loop {
            match reader.read_event_into(&mut buf) {
                Ok(Event::Start(ref e)) if e.name().as_ref() == b"xs:element" => {
                let element = Self::parse_element(e, reader)?; // Use ? to propagate the error
                sequence.push(element);
                },
                Ok(Event::End(ref e)) if e.name().as_ref() == b"xs:sequence" => break,
                Ok(Event::Eof) => return Err("Unexpected end of file".into()),
                Err(e) => return Err(Box::new(e)),
                _ => {}
            }
            buf.clear();
        }
        Ok(sequence)
    }

    pub(crate) fn parse_attribute<R: BufRead>(e: &BytesStart, reader: &mut Reader<R>) -> XsdAttribute {
        XsdAttribute {
            name: Self::get_attribute(e, "name", reader).unwrap_or_default(),
            type_name: Self::get_attribute(e, "type", reader).unwrap_or_default(),
            use_type: Self::get_attribute(e, "use", reader).unwrap_or_default(),
            default: Self::get_attribute(e, "default", reader),
            fixed: Self::get_attribute(e, "fixed", reader),
        }
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
                    match e.name().as_ref() {
                        b"xs:restriction" => {
                            simple_type.restriction = Some(Self::parse_restriction(e, reader)?);
                        },
                        b"xs:list" => {
                          simple_type.list = Self::get_attribute(e, "itemType", reader);
                        },
                        b"xs:union" => {
                            simple_type.union = Self::get_attribute(e, "memberTypes", reader)
                                .map(|v| v.split_whitespace().map(String::from).collect());
                        },
                        _ => {}
                    }
                },
                Ok(Event::End(ref e)) if e.name().as_ref() == b"xs:simpleType" => break,
                Ok(Event::Eof) => return Err("Unexpected end of file".into()),
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
                    match e.name().as_ref() {
                        b"xs:enumeration" => {
                            if let Some(value) = Self::get_attribute(e, "value", reader) {
                                restriction.enumeration.push(value);
                            }
                        },
                        b"xs:pattern" => {
                            restriction.pattern = Self::get_attribute(e, "value", reader);
                        },
                        b"xs:minInclusive" => {
                            restriction.min_inclusive = Self::get_attribute(e, "value", reader);
                        },
                        b"xs:maxInclusive" => {
                            restriction.max_inclusive = Self::get_attribute(e, "value", reader);
                        },
                        b"xs:minExclusive" => {
                            restriction.min_exclusive = Self::get_attribute(e, "value", reader);
                        },
                        b"xs:maxExclusive" => {
                            restriction.max_exclusive = Self::get_attribute(e, "value", reader);
                        },
                        b"xs:length" => {
                            restriction.length = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        b"xs:minLength" => {
                            restriction.min_length = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        b"xs:maxLength" => {
                            restriction.max_length = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        b"xs:totalDigits" => {
                            restriction.total_digits = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        b"xs:fractionDigits" => {
                            restriction.fraction_digits = Self::get_attribute(e, "value", reader).and_then(|v| v.parse().ok());
                        },
                        _ => {}
                    }
                },
                Ok(Event::End(ref e)) if e.name().as_ref() == b"xs:restriction" => break,
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
}