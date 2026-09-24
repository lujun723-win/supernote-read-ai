#!/usr/bin/env python3
"""Generate the compact offline vocabulary-hint index from bundled ECDICT."""

from __future__ import annotations

import gzip
import re
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path


WORD_RE = re.compile(r"^[A-Za-z][A-Za-z'-]{1,29}$")
META_RE = re.compile(
    r"\((?P<meta>(?:[中高四六研雅托宝]+\s+)?(?:-?\d+|-)/(?:-?\d+|-))\)\s*$"
)
LEMMA_RE = re.compile(r"\[原型\].*? 是 ([A-Za-z][A-Za-z'-]*) 的")
CHINESE_RE = re.compile(r"[\u3400-\u9fff]")
PROPER_NAME_RE = re.compile(r"(?:男子名|女子名|人名|姓氏|地名)")
POS_RE = re.compile(r"^(?:[a-z]{1,6}\.)+\s*", re.IGNORECASE)
TAG_ORDER = (("中", 1), ("高", 2), ("四", 3), ("六", 4), ("研", 5))


@dataclass(frozen=True)
class Record:
    level: int
    rank: int
    gloss: str


def level_from_metadata(metadata: str) -> tuple[int, int] | None:
    ranks = [int(value) for value in re.findall(r"-?\d+", metadata)[-2:]]
    positive_ranks = [value for value in ranks if value > 0]
    rank = min(positive_ranks) if positive_ranks else 999_999

    for tag, level in TAG_ORDER:
        if tag in metadata:
            return level, rank
    if "雅" in metadata or "托" in metadata or "宝" in metadata:
        return 6, rank
    if not positive_ranks:
        return None

    if rank <= 1_000:
        level = 0
    elif rank <= 2_500:
        level = 1
    elif rank <= 4_500:
        level = 2
    elif rank <= 7_000:
        level = 3
    elif rank <= 10_000:
        level = 4
    elif rank <= 15_000:
        level = 5
    else:
        return None
    return level, rank


def short_gloss(definition: str) -> str | None:
    for raw_line in definition.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("*", "[", "(")) or not CHINESE_RE.search(line):
            continue
        line = POS_RE.sub("", line)
        line = re.sub(r"^[\[\u3010][^\]\u3011]+[\]\u3011]\s*", "", line)
        chinese = CHINESE_RE.search(line)
        if not chinese:
            continue
        line = line[chinese.start() :]
        line = re.split(r"[\uff08(]", line, maxsplit=1)[0].strip()
        line = re.split(r"[,，;；]", line, maxsplit=1)[0].strip()
        line = line.replace("\t", " ")
        if not line:
            continue
        return line if len(line) <= 14 else line[:13] + "…"
    return None


def is_proper_name_only(definition: str) -> bool:
    for raw_line in definition.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("*", "[", "(")) or not CHINESE_RE.search(line):
            continue
        marker = PROPER_NAME_RE.search(line)
        if not marker:
            return False
        return not re.search(r"[,，;；]", line[: marker.start()])
    return False


def iter_entries(index: bytes, dictionary: bytes):
    position = 0
    while position < len(index):
        end = index.index(0, position)
        word = index[position:end].decode("utf-8", "replace")
        position = end + 1
        offset, size = struct.unpack(">II", index[position : position + 8])
        position += 8
        if WORD_RE.fullmatch(word):
            if word == word.lower():
                yield word, dictionary[offset : offset + size].decode("utf-8", "replace")


def generate(source: Path, target: Path) -> int:
    with zipfile.ZipFile(source) as archive:
        index_name = next(name for name in archive.namelist() if name.endswith(".idx"))
        dictionary_name = next(name for name in archive.namelist() if name.endswith(".dict"))
        index = archive.read(index_name)
        dictionary = archive.read(dictionary_name)

    direct: dict[str, Record] = {}
    aliases: dict[str, tuple[str, str]] = {}
    for word, definition in iter_entries(index, dictionary):
        if is_proper_name_only(definition):
            continue
        metadata_match = META_RE.search(definition)
        classification = level_from_metadata(metadata_match.group("meta")) if metadata_match else None
        gloss = short_gloss(definition)
        if classification and gloss:
            direct.setdefault(word, Record(*classification, gloss))
        lemma_match = LEMMA_RE.search(definition)
        if lemma_match and gloss:
            aliases[word] = (lemma_match.group(1).lower(), gloss)

    records = dict(direct)
    for word, (lemma, gloss) in aliases.items():
        base = direct.get(lemma)
        if base:
            records[word] = Record(base.level, base.rank, gloss)

    target.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(target, "wt", encoding="utf-8", newline="\n", compresslevel=9) as output:
        output.write("# word\tlevel\trank\tgloss\n")
        for word in sorted(records):
            record = records[word]
            output.write(f"{word}\t{record.level}\t{record.rank}\t{record.gloss}\n")
    return len(records)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    source = root / "app/src/deepseekBundled/assets/ecdict-stardict-28.zip"
    # Android's asset merger transparently expands files ending in .gz and
    # drops the suffix. Keep the gzip payload under a neutral extension so the
    # runtime path and compression format stay unchanged.
    target = root / "app/src/main/assets/vocabulary_hints.dat"
    if not source.is_file():
        raise SystemExit(f"Missing bundled ECDICT: {source}")
    count = generate(source, target)
    print(f"Generated {count} vocabulary records: {target}")


if __name__ == "__main__":
    main()
