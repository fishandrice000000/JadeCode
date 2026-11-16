#!/usr/bin/env python3
"""Format explicitly selected Java sources using the installed VS Code Java extension."""
import argparse
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check', action='store_true', help='Report differences without writing')
parser.add_argument('files', nargs='+', type=Path)
args = parser.parse_args()
project = Path(__file__).resolve().parent.parent
override = os.environ.get('JADECODE_JAVA_EXTENSION')
candidates = [Path(override)] if override else [
    extension
    for directory in ('.vscode-server', '.vscode', '.vscode-insiders')
    for extension in (Path.home() / directory / 'extensions').glob('redhat.java-*')
]
if not candidates:
    parser.error('Install redhat.java or set JADECODE_JAVA_EXTENSION to its directory')
extension = max(candidates, key=lambda p: tuple(
    int(part) for part in json.loads((p / 'package.json').read_text())['version'].split('.')[:3]
))
java = next(extension.glob('jre/*/bin/java'), None)
if java is None:
    parser.error('The Java extension has no bundled runtime; select one with JADECODE_JAVA_EXTENSION')
files = [p.resolve() for p in args.files]
if any(not p.is_file() or p.suffix != '.java' for p in files):
    parser.error('Every argument must name an existing Java source file')
raise SystemExit(subprocess.run([
    str(java), '-cp', str(extension / 'server/plugins/*'),
    str(project / 'scripts/JavaFormatter.java'), str(project / 'java-formatter.xml'),
    str(args.check).lower(), *map(str, files),
]).returncode)
