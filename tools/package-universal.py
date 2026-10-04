#!/usr/bin/env python3
"""Add official native runtimes to a freshly built Kotlin Toolchain executable JAR, so that one
Tessera jar runs on macOS, Windows and Linux, x64 and ARM64 (Java 25 needed). Taken from Aster
Compose's tools/package-universal.py.

    ./kotlin package -m app -p jvm -f executable-jar --build-dir <dir>
    tools/package-universal.py --input <dir>/…/app-jvm-executable.jar --output ~/Code/Tessera-universel.jar
"""
import argparse
import concurrent.futures
import hashlib
import io
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

TARGETS = ('macos-arm64', 'macos-x64', 'windows-x64', 'windows-arm64', 'linux-x64', 'linux-arm64')

def class_clashes(source, names):
    """Tessera classes present in two module jars (the JVM loads only one: Aster met it on 2026-10-02)."""
    owners = {}
    for name in names:
        if not re.fullmatch(r'BOOT-INF/lib/[a-z-]+-jvm\.jar', name) or name.endswith('/app-jvm.jar'):
            continue
        with zipfile.ZipFile(io.BytesIO(source.read(name))) as module:
            for entry in module.namelist():
                if entry.startswith('tessera/') and entry.endswith('.class'):
                    owners.setdefault(entry, []).append(name.rsplit('/', 1)[1])
    return sorted(f'{k} ({" & ".join(v)})' for k, v in owners.items() if len(v) > 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error('Input and output must differ; never replace a running application JAR.')
    with zipfile.ZipFile(args.input) as source:
        names = source.namelist()
        versions = [re.fullmatch(r'BOOT-INF/lib/skiko-awt-([0-9][^/]+)\.jar', n) for n in names]
        versions = [m.group(1) for m in versions if m]
        if len(versions) != 1:
            raise ValueError('Expected exactly one Skiko implementation version')
        version = versions[0]
        clashes = class_clashes(source, names)
        if clashes:
            raise ValueError('Classes defined by two modules (the JVM loads only one; a Kotlin private top-level class is still public to it): ' + ', '.join(clashes))
        index = source.read('BOOT-INF/classpath.idx').decode()
        with tempfile.TemporaryDirectory(prefix='tessera-universal-') as temp:
            def runtime(target):
                filename = f'skiko-awt-runtime-{target}-{version}.jar'
                name = f'BOOT-INF/lib/{filename}'
                if name in names:
                    data = source.read(name)
                else:
                    url = f'https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-{target}/{version}/{filename}'
                    path = Path(temp) / filename
                    subprocess.run(['curl', '--fail', '--silent', '--show-error', '--location', '--retry', '2', '--max-time', '180', '--output', str(path), url], check=True)
                    checksum = subprocess.check_output(['curl', '--fail', '--silent', '--show-error', '--location', '--max-time', '30', url + '.sha1'], text=True).strip().split()[0]
                    data = path.read_bytes()
                    if hashlib.sha1(data).hexdigest() != checksum:
                        raise ValueError(f'Checksum mismatch: {filename}')
                with zipfile.ZipFile(io.BytesIO(data)) as native:
                    if native.testzip() is not None or not any(n.endswith(('.dylib', '.dll', '.so')) for n in native.namelist()):
                        raise ValueError(f'Invalid native runtime: {filename}')
                return name, data
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                runtimes = dict(pool.map(runtime, TARGETS))
            args.output.parent.mkdir(parents=True, exist_ok=True)
            temporary = args.output.with_suffix('.jar.tmp')
            with zipfile.ZipFile(temporary, 'w') as output:
                for entry in source.infolist():
                    if entry.filename == 'BOOT-INF/classpath.idx' or entry.filename in runtimes:
                        continue
                    output.writestr(entry, source.read(entry.filename))
                for name, data in runtimes.items():
                    # Spring Boot requires nested libraries to be STORED, not deflated.
                    output.writestr(name, data, compress_type=zipfile.ZIP_STORED)
                    if f'"{name}"' not in index:
                        index += f'- "{name}"\n'
                output.writestr('BOOT-INF/classpath.idx', index)
            with zipfile.ZipFile(temporary) as check:
                if check.testzip() is not None:
                    raise ValueError('Invalid output archive')
                assert all(check.getinfo(n).compress_type == zipfile.ZIP_STORED for n in runtimes)
            temporary.replace(args.output)
    digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
    args.output.with_suffix('.jar.sha256').write_text(f'{digest}  {args.output.name}\n')
    print(f'{args.output}: {args.output.stat().st_size / 1_000_000:.1f} MB; Skiko {version}; {", ".join(TARGETS)}')

if __name__ == '__main__':
    main()
