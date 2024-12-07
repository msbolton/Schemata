#[cfg(test)]
mod tests {
    
    use quick_xml::reader::Reader;
    use std::io::BufReader;
    use quick_xml::events::Event;
    use crate::xsd::XsdParser;

    // Helper function to create a Reader from a string
    fn create_reader(xml: &str) -> Reader<BufReader<&[u8]>> {
        Reader::from_reader(BufReader::new(xml.as_bytes()))
    }

    #[test]
    fn test_get_attribute() {
        let xml = r#"<element name="test" type="string" />"#;
        let mut reader = create_reader(xml);
        let mut buf = Vec::new();
        if let Ok(Event::Start(e)) = reader.read_event_into(&mut buf) {
            assert_eq!(
                XsdParser::get_attribute(&e, "name", &reader),
                Some("test".to_string())
            );
            assert_eq!(
                XsdParser::get_attribute(&e, "type", &reader),
                Some("string".to_string())
            );
            assert_eq!(XsdParser::get_attribute(&e, "nonexistent", &reader), None);
        } else {
            panic!("Failed to parse XML");
        }
    }

    #[test]
    fn test_parse_element() {
        let xml = r#"<xs:element name="test" type="xs:string" minOccurs="0" maxOccurs="unbounded" />"#;
        let mut reader = create_reader(xml);
        let mut buf = Vec::new();
        if let Ok(Event::Start(e)) = reader.read_event_into(&mut buf) {
            let element = XsdParser::parse_element(&e, &mut reader).unwrap();
            assert_eq!(element.name, "test");
            assert_eq!(element.type_name, Some("xs:string".to_string()));
            assert_eq!(element.min_occurs, Some(0));
            assert_eq!(element.max_occurs, Some("unbounded".to_string()));
        } else {
            panic!("Failed to parse XML");
        }
    }

    #[test]
    fn test_parse_complex_type() {
        let xml = r#"
        <xs:complexType name="TestType">
            <xs:sequence>
                <xs:element name="child" type="xs:string" />
            </xs:sequence>
        </xs:complexType>
        "#;
        let mut reader = create_reader(xml);
        let mut buf = Vec::new();
        if let Ok(Event::Start(e)) = reader.read_event_into(&mut buf) {
            let complex_type = XsdParser::parse_complex_type(&e, &mut reader).unwrap();
            assert_eq!(complex_type.name, Some("TestType".to_string()));
            assert_eq!(complex_type.sequence.len(), 1);
            assert_eq!(complex_type.sequence[0].name, "child");
            assert_eq!(complex_type.sequence[0].type_name, Some("xs:string".to_string()));
        } else {
            panic!("Failed to parse XML");
        }
    }

    #[test]
    fn test_parse_simple_type() {
        let xml = r#"
        <xs:simpleType name="TestEnum">
            <xs:restriction base="xs:string">
                <xs:enumeration value="One" />
                <xs:enumeration value="Two" />
                <xs:enumeration value="Three" />
            </xs:restriction>
        </xs:simpleType>
        "#;
        let mut reader = create_reader(xml);
        let mut buf = Vec::new();
        if let Ok(Event::Start(e)) = reader.read_event_into(&mut buf) {
            let simple_type = XsdParser::parse_simple_type(&e, &mut reader).unwrap();
            assert_eq!(simple_type.name, Some("TestEnum".to_string()));
            assert!(simple_type.restriction.is_some());
            let restriction = simple_type.restriction.unwrap();
            assert_eq!(restriction.base, "xs:string");
            assert_eq!(restriction.enumeration, vec!["One", "Two", "Three"]);
        } else {
            panic!("Failed to parse XML");
        }
    }

    // Add more tests for other parsing functions...
}
