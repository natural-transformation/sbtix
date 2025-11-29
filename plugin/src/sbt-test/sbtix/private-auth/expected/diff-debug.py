#!/usr/bin/env python3
import sys
import difflib
from pathlib import Path


def main():
    if len(sys.argv) != 3:
        print("Usage: diff-debug.py <file-a> <file-b>", file=sys.stderr)
        return 0

    file_a, file_b = sys.argv[1], sys.argv[2]
    text_a = Path(file_a).read_text().splitlines(keepends=True)
    text_b = Path(file_b).read_text().splitlines(keepends=True)

    diff = difflib.unified_diff(text_a, text_b, fromfile=file_a, tofile=file_b)
    for line in diff:
        sys.stdout.write(line)

    return 0


if __name__ == "__main__":
    sys.exit(main())

