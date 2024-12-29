#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_simple_schema() {
        let input = r#"
        namespace example.com {
            schema Person {
                name string
                age int @optional
            }
        }
        "#;

        let result = SchemataFileParser::parse_schemata(input)
            .expect("Should parse successfully");

        assert_eq!(result.namespaces.len(), 1);
        let namespace = &result.namespaces[0];
        assert_eq!(namespace.name, "example.com");
        assert_eq!(namespace.schemas.len(), 1);

        let schema = &namespace.schemas[0];
        assert_eq!(schema.name, "Person");
        assert_eq!(schema.fields.len(), 2);
    }

    #[test]
    fn test_parse_schema_with_nullable_field() {
        let input = r#"
        schema TestNullable {
            required_field: String
            nullable_field: Int?
        }
        "#;

        let parsed = SchemataParser::parse(Rule::schema, input).unwrap();
        let schema = SchemataParser::parse_schema(parsed.next().unwrap());

        assert_eq!(schema.name, "TestNullable");
        assert_eq!(schema.fields.len(), 2);

        let required_field = &schema.fields[0];
        assert_eq!(required_field.name, "required_field");
        assert_eq!(required_field.type_name, "String");
        assert!(!required_field.nullable);

        let nullable_field = &schema.fields[1];
        assert_eq!(nullable_field.name, "nullable_field");
        assert_eq!(nullable_field.type_name, "Int");
        assert!(nullable_field.nullable);
    }

    #[test]
    fn test_parse_annotated_schema() {
        let input = r#"
        namespace example.com {
            @version("1.0")
            @description("User profile schema")
            schema User {
                @required
                id string

                @validate("email")
                email string
            }
        }
        "#;

        let result = SchemataFileParser::parse_schemata(input)
            .expect("Should parse successfully");

        let schema = &result.namespaces[0].schemas[0];
        assert_eq!(schema.name, "User");
        assert_eq!(schema.annotations.len(), 2);
        assert_eq!(schema.fields.len(), 2);
    }

    #[test]
    fn test_parse_complex_schema() {
        let input = r#"
        namespace example.com {
            schema Product {
                id string
                name string
                price float
                tags list<string>

                @nested
                details {
                    manufacturer string
                    year int
                }
            }

            enum Category {
                ELECTRONICS,
                BOOKS,
                CLOTHING
            }
        }
        "#;

        let result = SchemataFileParser::parse_schemata(input)
            .expect("Should parse successfully");

        assert_eq!(result.namespaces.len(), 1);
        let namespace = &result.namespaces[0];

        assert_eq!(namespace.schemas.len(), 1);
        assert_eq!(namespace.enums.len(), 1);

        let schema = &namespace.schemas[0];
        assert_eq!(schema.name, "Product");
        assert_eq!(schema.fields.len(), 5);

        let enum_def = &namespace.enums[0];
        assert_eq!(enum_def.name, "Category");
        assert_eq!(enum_def.values.len(), 3);
    }
}