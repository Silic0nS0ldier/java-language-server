use std::{fs, io::BufWriter, path::PathBuf};

use libsui::{Elf, Macho, PortableExecutable, utils::{
    is_elf, is_macho, is_pe
}};
use clap::Parser;

#[derive(Parser)]
#[command()]
struct CliArgs {
    #[arg()]
    input: PathBuf,
    #[arg()]
    output: PathBuf,
}

fn main() {
    let args = CliArgs::parse();

    let exe = fs::read(args.input).unwrap();
    let mut out = BufWriter::new(Vec::new());
    let data = "data".as_bytes().to_vec();

    if is_elf(&exe) {
        Elf::new(&exe)
            .append("__virtual_path", &data, &mut out)
            .unwrap();
    } else if is_macho(&exe) {
        Macho::from(exe)
            .unwrap()
            .write_section("__virtual_path", data)
            .unwrap()
            .build_and_sign(&mut out)
            .unwrap();
    } else if is_pe(&exe) {
        PortableExecutable::from(&exe)
            .unwrap()
            .write_resource("__virtual_path", data)
            .unwrap()
            .build(&mut out)
            .unwrap();
    } else {
        eprintln!("Unrecognised file format");
        std::process::exit(1);
    }
}
