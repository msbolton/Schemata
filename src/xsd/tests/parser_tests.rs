#[cfg(test)]
mod tests {
    use super::*;
    use crate::xsd::XsdParser;
    use quick_xml::events::Event;
    use quick_xml::reader::Reader;
    use std::io::BufReader;

    // Helper function to create a Reader from a string
    fn create_reader(xml: &str) -> Reader<BufReader<&[u8]>> {
        let mut reader = Reader::from_reader(BufReader::new(xml.as_bytes()));
        reader.trim_text(true);
        reader
    }

    #[test]
    fn test_get_attribute() {
        let xml = r#"<xs:element xmlns:xs="http://www.w3.org/2001/XMLSchema" name="test" type="string" />"#;
        let mut reader = create_reader(xml);
        let mut buf = Vec::new();

        let event = reader.read_event_into(&mut buf).expect("Failed to read event");

        match event {
            Event::Start(e) | Event::Empty(e) => {
                assert_eq!(
                    XsdParser::get_attribute(&e, "name", &reader),
                    Some("test".to_string())
                );
                assert_eq!(
                    XsdParser::get_attribute(&e, "type", &reader),
                    Some("string".to_string())
                );
                assert_eq!(XsdParser::get_attribute(&e, "nonexistent", &reader), None);
            }
            _ => panic!("Unexpected event type"),
        }
    }

    #[test]
    fn test_parse_element() {
        let xml = r#"<xs:element xmlns:xs="http://www.w3.org/2001/XMLSchema" name="test" type="xs:string" minOccurs="0" maxOccurs="unbounded" />"#;
        let mut reader = create_reader(xml);
        let mut buf = Vec::new();

        let event = reader.read_event_into(&mut buf).expect("Failed to read event");

        match event {
            Event::Start(e) | Event::Empty(e) => {
                let element =
                    XsdParser::parse_element(&e, &mut reader).expect("Failed to parse element");
                assert_eq!(element.name, "test");
                assert_eq!(element.type_name, Some("xs:string".to_string()));
                assert_eq!(element.min_occurs, Some("0".to_string()));
                assert_eq!(element.max_occurs, Some("unbounded".to_string()));
            }
            _ => panic!("Unexpected event type"),
        }
    }

    #[test]
    fn test_parse_complex_type_with_nested_elements() {
        let xml = r#"
        <xs:complexType xmlns:xs="http://www.w3.org/2001/XMLSchema" name="TestType">
            <xs:sequence>
                <xs:element name="child1" type="xs:string" />
                <xs:element name="child2" type="xs:int" minOccurs="0" />
            </xs:sequence>
            <xs:attribute name="testAttr" type="xs:boolean" use="optional" />
        </xs:complexType>
        "#.trim();

        let mut reader = create_reader(xml);
        let mut buf = Vec::new();

        let event = reader.read_event_into(&mut buf).expect("Failed to read event");

        match event {
            Event::Start(e) => {
                let complex_type = XsdParser::parse_complex_type(&e, &mut reader)
                    .expect("Failed to parse complex type");

                // Debug print to understand what's happening
                println!("Sequence length: {}", complex_type.sequence.len());
                println!("Attributes length: {}", complex_type.attributes.len());

                assert_eq!(complex_type.name, Some("TestType".to_string()));

                // Verify sequence
                assert_eq!(
                    complex_type.sequence.len(),
                    2,
                    "Sequence should contain 2 elements"
                );
                assert_eq!(complex_type.sequence[0].name, "child1");
                assert_eq!(
                    complex_type.sequence[0].type_name,
                    Some("xs:string".to_string())
                );
                assert_eq!(complex_type.sequence[1].name, "child2");
                assert_eq!(
                    complex_type.sequence[1].type_name,
                    Some("xs:int".to_string())
                );

                // Verify attributes
                assert_eq!(complex_type.attributes.len(), 1, "Should have 1 attribute");
                assert_eq!(complex_type.attributes[0].name, "testAttr");
                assert_eq!(complex_type.attributes[0].type_name, "xs:boolean");
                assert_eq!(complex_type.attributes[0].use_type, "optional");
            }
            _ => panic!("Unexpected event type"),
        }
    }

    #[test]
    fn test_parse_simple_type_with_complex_restriction() {
        let xml = r#"
        <xs:simpleType xmlns:xs="http://www.w3.org/2001/XMLSchema" name="TestEnum">
            <xs:restriction base="xs:string">
                <xs:enumeration value="One" />
                <xs:enumeration value="Two" />
                <xs:enumeration value="Three" />
                <xs:length value="3" />
                <xs:pattern value="[A-Z][a-z][a-z]" />
            </xs:restriction>
        </xs:simpleType>
        "#
        .trim();
        let mut reader = create_reader(xml);
        let mut buf = Vec::new();
        if let Ok(Event::Start(e)) = reader.read_event_into(&mut buf) {
            let simple_type = XsdParser::parse_simple_type(&e, &mut reader).unwrap();
            assert_eq!(simple_type.name, Some("TestEnum".to_string()));
            assert!(simple_type.restriction.is_some());

            let restriction = simple_type.restriction.unwrap();
            assert_eq!(restriction.base, "xs:string");
            assert_eq!(restriction.enumeration, vec!["One", "Two", "Three"]);

            // Additional restriction details
            assert_eq!(restriction.length, Some(3));
            assert_eq!(restriction.pattern, Some("[A-Z][a-z][a-z]".to_string()));
        } else {
            panic!("Failed to parse XML");
        }
    }

    #[test]
    fn test_parse_schema_with_namespaces() {
        let xml = r#"
        <xs:schema
            xmlns:xs="http://www.w3.org/2001/XMLSchema"
            xmlns:my="http://example.com/namespace"
            targetNamespace="http://example.com/test">
            <xs:element name="testElement" type="my:TestType" />
        </xs:schema>
        "#
        .trim();

        let reader = BufReader::new(xml.as_bytes());
        let schema = XsdParser::parse(reader).unwrap();

        assert_eq!(
            schema.target_namespace,
            Some("http://example.com/test".to_string())
        );

        // Verify namespace map
        assert!(schema.namespaces.contains_key("xs"));
        assert!(schema.namespaces.contains_key("my"));
        assert_eq!(
            schema.namespaces.get("xs"),
            Some(&"http://www.w3.org/2001/XMLSchema".to_string())
        );
    }
}
