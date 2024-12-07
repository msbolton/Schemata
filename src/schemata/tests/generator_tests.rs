#[cfg(test)]
mod tests {
    use crate::schemata::SchemataGenerator;
    use super::*;
    use crate::xsd::types::{XsdAttribute, XsdComplexType, XsdElement, XsdRestriction, XsdSchema, XsdSimpleType};

    #[test]
    fn test_new_generator() {
        let generator = SchemataGenerator::new();
        assert!(generator.is_ok());
    }

    #[test]
    fn test_get_namespaces() {
        let generator = SchemataGenerator::new().unwrap();
        let xsd_schema = XsdSchema {
            target_namespace: Some("http://example.com".to_string()),
            complex_types: vec![],
            simple_types: vec![],
            elements: vec![],
            imported_schemas: vec![],
            namespaces: Default::default(),
        };

        let namespaces = generator.get_namespaces(&xsd_schema);
        assert_eq!(namespaces.len(), 1);
        assert_eq!(namespaces[0].name, "http://example.com");
    }

    #[test]
    fn test_get_schemas() {
        let generator = SchemataGenerator::new().unwrap();
        let complex_types = vec![
            XsdComplexType {
                name: Some("TestType".to_string()),
                sequence: vec![],
                attributes: vec![],
                mixed: false,
            },
        ];

        let schemas = generator.get_schemas(&complex_types);
        assert_eq!(schemas.len(), 1);
        assert_eq!(schemas[0].name, "TestType");
    }

    #[test]
    fn test_get_fields() {
        let generator = SchemataGenerator::new().unwrap();
        let elements = vec![
            XsdElement {
                name: "testField".to_string(),
                type_name: Some("string".to_string()),
                min_occurs: Some("0".to_string()),
                max_occurs: Some("1".to_string()),
                complex_type: None,
                simple_type: None,
                comment: None,
            },
        ];

        let fields = generator.get_fields(&elements);
        assert_eq!(fields.len(), 1);
        assert_eq!(fields[0].name, "testField");
        assert_eq!(fields[0].type_name, "string");
        assert!(fields[0].nullable);
    }

    #[test]
    fn test_get_enums() {
        let generator = SchemataGenerator::new().unwrap();
        let simple_types = vec![
            XsdSimpleType {
                name: Some("TestEnum".to_string()),
                restriction: Some(XsdRestriction {
                    base: "string".to_string(),
                    enumeration: vec!["Value1".to_string(), "Value2".to_string()],
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
                }),
                list: None,
                union: None,
            },
        ];

        let enums = generator.get_enums(&simple_types);
        assert_eq!(enums.len(), 1);
        assert_eq!(enums[0].name, "TestEnum");
        assert_eq!(enums[0].values, vec!["Value1".to_string(), "Value2".to_string()]);
    }

    #[test]
    fn test_get_annotations() {
        let generator = SchemataGenerator::new().unwrap();
        let element = XsdElement {
            name: "testField".to_string(),
            type_name: Some("string".to_string()),
            min_occurs: Some("0".to_string()),
            max_occurs: Some("unbounded".to_string()),
            complex_type: None,
            simple_type: None,
            comment: None,
        };

        let annotations = generator.get_annotations(&element);
        assert_eq!(annotations, "@minOccurs(0) @maxOccurs(unbounded)");
    }

    #[test]
    fn test_generate() {
        let generator = SchemataGenerator::new().unwrap();
        let xsd_schema = XsdSchema {
            target_namespace: Some("http://example.com".to_string()),
            complex_types: vec![
                XsdComplexType {
                    name: Some("TestType".to_string()),
                    sequence: vec![
                        XsdElement {
                            name: "testField".to_string(),
                            type_name: Some("string".to_string()),
                            min_occurs: Some("0".to_string()),
                            max_occurs: Some("1".to_string()),
                            complex_type: None,
                            simple_type: None,
                            comment: None,
                        },
                    ],
                    attributes: vec![],
                    mixed: false,
                },
            ],
            simple_types: vec![
                XsdSimpleType {
                    name: Some("TestEnum".to_string()),
                    restriction: Some(XsdRestriction {
                        base: "string".to_string(),
                        enumeration: vec!["Value1".to_string(), "Value2".to_string()],
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
                    }),
                    list: None,
                    union: None,
                },
            ],
            elements: vec![],
            imported_schemas: vec![],
            namespaces: Default::default(),
        };

        let result = generator.generate(xsd_schema);
        assert!(result.is_ok());

        let generated_code = result.unwrap();

        // Check for namespace declaration
        assert!(
            generated_code.contains("namespace http://example.com") ||
                generated_code.contains("namespace http://example/com") ||
                generated_code.contains("namespace http.example.com") ||
                generated_code.contains("namespace http_example_com"),
            "Generated code does not contain expected namespace. Generated code:\n{}",
            generated_code
        );

        // Check for schema declaration
        assert!(generated_code.contains("schema TestType"));

        // Check for field declaration
        assert!(generated_code.contains("testField string"));
        assert!(generated_code.contains("@minOccurs(0)"));
        assert!(generated_code.contains("@maxOccurs(1)"));

        // Check for enum declaration
        assert!(generated_code.contains("enum TestEnum"));
        assert!(generated_code.contains("Value1"));
        assert!(generated_code.contains("Value2"));

        // Check for proper formatting
        assert!(generated_code.contains("}"));  // Closing braces for schema and enum

        // Check that there are no unexpected elements
        assert!(!generated_code.contains("UnnamedSchema"));
        assert!(!generated_code.contains("UnnamedEnum"));

        // Print the generated code for debugging
        println!("Generated Code:\n{}", generated_code);
    }
}
