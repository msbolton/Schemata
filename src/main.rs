mod converter;
mod parser;

use clap::Parser;

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

fn main() {
    let cli = Cli::parse();
    env_logger::init();


}
