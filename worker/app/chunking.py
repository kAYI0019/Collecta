from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional


@dataclass
class Chunk:
    text: str
    page_index: Optional[int]
    position: int


def normalize_text(text: str) -> str:
    t = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = [ln.strip() for ln in t.split("\n")]
    lines = [ln for ln in lines if ln]
    return "\n".join(lines).strip()


def chunk_text_by_chars(
    text: str,
    max_chars: int = 2500,
    min_chars: int = 300,
    overlap_chars: int = 200,
    page_index: Optional[int] = None,
    start_position: int = 0,
) -> List[Chunk]:
    t = normalize_text(text)
    if not t:
        return []

    if len(t) <= max_chars:
        return [Chunk(text=t, page_index=page_index, position=start_position)]

    chunks: List[Chunk] = []
    pos = start_position
    i = 0
    n = len(t)

    while i < n:
        end = min(i + max_chars, n)
        piece = t[i:end].strip()
        if piece:
            chunks.append(Chunk(text=piece, page_index=page_index, position=pos))
            pos += 1
        if end >= n:
            break
        i = max(0, end - overlap_chars)

    if len(chunks) >= 2 and len(chunks[-1].text) < min_chars:
        merged = (chunks[-2].text + "\n" + chunks[-1].text).strip()
        chunks[-2] = Chunk(
            text=merged,
            page_index=chunks[-2].page_index,
            position=chunks[-2].position,
        )
        chunks.pop()

    return chunks
