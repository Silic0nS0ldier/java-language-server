import mfhFetch from 'make-fetch-happen';
import url from 'node:url';
import path from "node:path";
import fs from "node:fs";
import ssri from "ssri";
import streamPromises from "node:stream/promises";
import * as tar from "tar";

const __dirname = path.dirname(url.fileURLToPath(import.meta.url));

type JreIndex = Record<string, {
    url: string,
    sha256: string,
    os: string,
    cpu: string[],
}>;

export type ResolvedJre = {
    id: string,
    url: string,
    sha256: string,
    archiveDirPath: string,
    jrePath: string,
    javaPath: string,
}

export async function resolveJre(globalStoragePath: string): Promise<ResolvedJre> {
    // Retrieve list of JRE candidates
    const jreIndexJsonPath = path.join(__dirname, '../jre.json');
    const jreIndexJson = await fs.promises.readFile(jreIndexJsonPath, "utf-8");
    const jreIndex: JreIndex = JSON.parse(jreIndexJson);

    // Pick one appropriate for the platform
    for (const id in jreIndex) {
        if (Object.prototype.hasOwnProperty.call(jreIndex, id)) {
            const jre = jreIndex[id];
            if (jre.os !== process.platform) {
                continue;
            }
            if (!jre.cpu.some(cpu => cpu === process.arch)) {
                continue;
            }

            // Matches current platform
            const jrePath = path.join(globalStoragePath, "jre", id);
            const javaPath = path.join(jrePath, "bin", process.platform === "win32" ? "java.exe" : "java");
            const archivePath = path.join(globalStoragePath, "jre-archive", id);
            return {
                id,
                archiveDirPath: archivePath,
                jrePath,
                javaPath,
                sha256: jre.sha256,
                url: jre.url,
            };
        }
    }

    throw new Error("Could not find suitable JRE for current platform.");
}

export async function downloadJre(resolvedJre: ResolvedJre, progress: { report(_: { message?: string, increment?: number }): void }) {
    const integrity = ssri.fromHex(resolvedJre.sha256, "sha256");

    // Start download
    progress.report({ message: "Downloading JRE", increment: 5 });// 5
    const res = await mfhFetch(resolvedJre.url, {
        integrity,
    });

    // Write file (download may still be occuring at this time)
    // TODO Report download progress via size
    const archiveExt = resolvedJre.url.endsWith("zip")
        ? "zip"
        : "tar.gz";
    const archivePath = path.join(resolvedJre.archiveDirPath, `${resolvedJre.id}.${archiveExt}`);
    fs.promises.mkdir(resolvedJre.archiveDirPath, { recursive: true });
    const writeStream = fs.createWriteStream(archivePath, { autoClose: true });
    await streamPromises.finished(res.body.pipe(writeStream));

    // TODO Extract
    progress.report({ message: "Extracting JRE", increment: 60 });// 65
    if (archiveExt === "zip") {
        // Extract with "adm-zip"
        throw new Error("Not implemented");
    } else {
        // Extract with "tar"
        await tar.extract({
            file: archivePath,
            cwd: resolvedJre.jrePath,
            strip: 1,
        });
    }

    progress.report({ increment: 35 });// 100
}
