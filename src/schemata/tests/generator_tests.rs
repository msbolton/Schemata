#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use crate::schemata::generator::SchemataToXsdGenerator;
    use crate::schemata::types::{AnnotationValue, SchemataAnnotation};

    #[test]
    fn test_new_generator() {
        let namespace = "http://example.com".to_string();
        let generator = SchemataToXsdGenerator::new(namespace);
        assert_eq!(generator.target_namespace, "http://example.com");
    }

    #[test]
    fn test_map_type() {
        let mut generator = SchemataToXsdGenerator::new("http://example.com".to_string());

        assert_eq!(generator.map_type("int"), "xs:integer");
        assert_eq!(generator.map_type("string"), "xs:string");
        assert_eq!(generator.map_type("float"), "xs:float");
        assert_eq!(generator.map_type("datetime"), "xs:dateTime");
        assert_eq!(generator.map_type("bool"), "xs:boolean");
        assert_eq!(generator.map_type("custom"), "xs:custom");
    }

    #[test]
    fn test_extract_max_occurs() {
        let mut generator = SchemataToXsdGenerator::new("http://example.com".to_string());

        // Prepare annotations
        let annotations = vec![
            SchemataAnnotation {
                name: "maxOccurs".to_string(),
                params: HashMap::from([
                    ("value".to_string(), AnnotationValue::Integer(10))
                ])
            },
            SchemataAnnotation {
                name: "maxOccurs".to_string(),
                params: HashMap::from([
                    ("value".to_string(), AnnotationValue::String("unbounded".to_string()))
                ])
            }
        ];

        // Test number value
        let max_occurs_number = generator.extract_max_occurs(&annotations[0..1]);
        assert_eq!(max_occurs_number, Some("10".to_string()));

        // Test string value
        let max_occurs_string = generator.extract_max_occurs(&annotations[1..]);
        assert_eq!(max_occurs_string, Some("unbounded".to_string()));

        // Test no matching annotation
        let no_max_occurs = generator.extract_max_occurs(&[]);
        assert_eq!(no_max_occurs, None);
    }

    #[test]
    fn test_generate() {
        // Prepare test input
        let schemata_content = r#"
        namespace http://example.com

        schema Person {
            name string
            age int @maxOccurs(10)
        }

        enum Gender {
            Male
            Female
        }
        "#;

        // Create generator
        let mut generator = SchemataToXsdGenerator::new("http://example.com".to_string());

        // Generate XSD
        let result = generator.generate(schemata_content);

        // Assert generation succeeds
        assert!(result.is_ok());

        // Get generated XSD
        let xsd = result.unwrap();

        // Validate XSD contents
        assert!(xsd.contains("targetNamespace=\"http://example.com\""));
        assert!(xsd.contains("<xs:complexType name=\"Person\">"));
        assert!(xsd.contains("<xs:element name=\"name\" type=\"xs:string\"/>"));
        assert!(xsd.contains("<xs:element name=\"age\" type=\"xs:integer\" maxOccurs=\"10\"/>"));
        assert!(xsd.contains("<xs:simpleType name=\"Gender\">"));
        assert!(xsd.contains("<xs:enumeration value=\"Male\"/>"));
        assert!(xsd.contains("<xs:enumeration value=\"Female\"/>"));
    }
}
