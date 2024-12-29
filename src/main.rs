mod xsd;
mod schemata;

use std::fs::File;
use std::io::BufReader;
use clap::Parser;
use xsd::XsdParser;
use crate::xsd::generator::XsdToSchemataGenerator;

#[derive(Parser)]
#[clap(version = "1.0", author = "Michael Bolton", about = "...")]
struct Cli {
    #[clap(short, long)]
    name: String,
}

#[derive(Parser)]
enum Commands {
    Convert(Convert),
}

#[derive(Parser)]
struct Convert {
    #[clap(value_parser)]
    input: String,
    #[clap(short = 'f', long = "format", value_enum)]
    format: InputFormat,
    #[clap(short = 'o', long = "output", value_parser)]
    output: String,
}

#[derive(clap::ValueEnum, Clone)]
enum InputFormat {
    Protobuf,
    Xsd,
    Avro,
}

fn handle_xsd(input: &str, output: &str) -> Result<(), Box<dyn std::error::Error>> {
    log::info!("Parsing the XSD file {}...", input);
    let file = File::open(input)?;
    let reader = BufReader::new(file);
    let schema = XsdParser::parse(reader)?;

    log::info!("Generating Schemata...");
    let generator = XsdToSchemataGenerator::new()?;
    let schemata = generator.generate(schema)?;

    log::info!("Writing the output to {}...", output);
    std::fs::write(output, schemata)?;

    log::info!("Done!");
    Ok(())
}

fn main() {
    let cli = Cli::parse();
    env_logger::init();

    match Commands::parse() {
        Commands::Convert(convert) => {
            match convert.format {
                InputFormat::Xsd => {
                    if let Err(e) = handle_xsd(&convert.input, &convert.output) {
                        log::error!("Error: {}", e);
                    }
                }
                _ => {
                    log::error!("Unsupported format");
                }
            }
        }
    }
}
