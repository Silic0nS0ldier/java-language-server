use std::{fs::read_to_string, os::unix::process::CommandExt};

use serde::{Serialize, Deserialize};

#[derive(Serialize, Deserialize)]
struct Meta {
    add_to_path: String,
    binary: String,
}

fn main() {
    let meta_path = {
        let mut path = std::env::current_exe().unwrap();
        let file_name = path.file_name().unwrap();
        path.set_file_name(format!("{}{}", file_name.to_string_lossy(), "__meta.json"));
        path
    };

    let meta: Meta = {
        let data = read_to_string(meta_path).unwrap();
        serde_json::from_str(data.as_str()).unwrap()
    };

    let program_path = {
       let mut path = std::env::current_exe().unwrap();
       path.set_file_name(meta.binary);
       path
    };
    let path_item = {
        let mut path = std::env::current_exe().unwrap();
       path.pop();
       path.push(meta.add_to_path);
       path
    };

    let mut cmd = std::process::Command::new(program_path);

    // forward args
    cmd.args(std::env::args_os());

    // set path
    cmd.envs(std::env::vars());
    cmd.env("PATH", format!("{}:{}", path_item.to_str().unwrap(), std::env::var("PATH").unwrap()));

    // spawn
    let e = cmd.exec();

    // If we get here, something has gone wrong
    eprintln!("{:?}", e);
    std::process::exit(1);
}
