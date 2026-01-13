from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

from fastapi import FastAPI
from pydantic import BaseModel, Field

from .chunking import chunk_text_by_chars, normalize_text
from .extractors import (
    detect_mime_from_ext,
    extract_docx,
    extract_pdf_pages,
    extract_pptx_slides,
    extract_txt,
)
from .es import bulk_index, get_es

INDEX_NAME = "collecta_chunks"

app = FastAPI(title="Collecta Worker", version="0.2.0")


# -----------------------------
# Request Models
# -----------------------------
class ChunkingOptions(BaseModel):
    max_chars: int = 2500
    min_chars: int = 300
    overlap_chars: int = 200


class IndexOptions(BaseModel):
    chunking: ChunkingOptions = Field(default_factory=ChunkingOptions)


class DocumentInfo(BaseModel):
    file_path: str
    mime_type: Optional[str] = None
    file_name: Optional[str] = None


class IndexDocumentRequest(BaseModel):
    job_id: str
    resource_id: int

    # 아래 메타는 검색 필터/정렬 성능을 위해 ES 문서에도 같이 저장하는 걸 권장
    resource_type: str = "document"  # link | document
    domain: Optional[str] = None
    tags: List[str] = Field(default_factory=list)
    status: str = "todo"  # todo | in_progress | done
    is_pinned: bool = False
    created_at: Optional[str] = None  # ISO-8601 string

    document: DocumentInfo
    options: IndexOptions = Field(default_factory=IndexOptions)


class IndexLinkRequest(BaseModel):
    job_id: str
    resource_id: int

    resource_type: str = "link"
    domain: Optional[str] = None
    tags: List[str] = Field(default_factory=list)
    status: str = "todo"
    is_pinned: bool = False
    created_at: Optional[str] = None

    link: Dict[str, Any]
    options: IndexOptions = Field(default_factory=IndexOptions)


# -----------------------------
# Helpers
# -----------------------------
def delete_by_resource_id(resource_id: int) -> None:
    es = get_es()
    try:
        es.delete_by_query(
            index=INDEX_NAME,
            body={"query": {"term": {"resource_id": str(resource_id)}}},
            refresh=True,
            conflicts="proceed",
        )
    except Exception as e:
        # 인덱스가 없으면 무시 (첫 인덱싱 시 정상 상황)
        if "index_not_found_exception" in str(e):
            return
        raise


def build_chunk_docs(
    *,
    resource_id: int,
    resource_type: str,
    domain: Optional[str],
    tags: List[str],
    status: str,
    is_pinned: bool,
    created_at: Optional[str],
    source_kind: str,
    segments: List[Tuple[Optional[int], str]],
    chunking: ChunkingOptions,
) -> Tuple[List[dict], List[str]]:
    """
    segments: [(page_index, text), ...]
      - page_index: PDF/PPTX 페이지/슬라이드 인덱스. 없는 경우 None.
    """
    warnings: List[str] = []
    docs: List[dict] = []
    pos = 0

    for page_index, text in segments:
        chunks = chunk_text_by_chars(
            text,
            max_chars=chunking.max_chars,
            min_chars=chunking.min_chars,
            overlap_chars=chunking.overlap_chars,
            page_index=page_index,
            start_position=pos,
        )

        for c in chunks:
            docs.append(
                {
                    "resource_id": str(resource_id),
                    "resource_type": resource_type,
                    "domain": domain,
                    "tags": tags,
                    "status": status,
                    "is_pinned": is_pinned,
                    "created_at": created_at,
                    "source_kind": source_kind,
                    "page_index": c.page_index,
                    "position": c.position,
                    "chunk_text": c.text,
                    # embedding은 다음 단계에서 추가 (dims=384)
                }
            )
        pos += len(chunks)

    return docs, warnings


# -----------------------------
# Routes
# -----------------------------
@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/index/document")
def index_document(req: IndexDocumentRequest):
    fp = req.document.file_path
    mime = req.document.mime_type or detect_mime_from_ext(fp) or ""

    warnings: List[str] = []
    errors: List[Dict[str, str]] = []

    # 1) Extract text segments
    try:
        segments: List[Tuple[Optional[int], str]] = []

        if mime == "application/pdf":
            pages, w = extract_pdf_pages(fp)
            warnings.extend(w)
            segments = [(i, t) for i, t in enumerate(pages)]
            source_kind = "document_text"

        elif "wordprocessingml.document" in mime:  # DOCX
            text, w = extract_docx(fp)
            warnings.extend(w)
            segments = [(None, text)]
            source_kind = "document_text"

        elif "presentationml.presentation" in mime:  # PPTX
            slides, w = extract_pptx_slides(fp)
            warnings.extend(w)
            segments = [(i, t) for i, t in enumerate(slides)]
            source_kind = "document_text"

        elif mime in ("text/plain", "text/markdown") or fp.lower().endswith((".txt", ".md")):
            text, w = extract_txt(fp)
            warnings.extend(w)
            segments = [(None, text)]
            source_kind = "document_text"

        else:
            return {
                "job_id": req.job_id,
                "resource_id": req.resource_id,
                "status": "failed",
                "summary": {"chunk_count": 0},
                "warnings": [],
                "errors": [{"code": "UNSUPPORTED_FORMAT", "message": f"mime_type not supported: {mime}"}],
            }

    except Exception as e:
        return {
            "job_id": req.job_id,
            "resource_id": req.resource_id,
            "status": "failed",
            "summary": {"chunk_count": 0},
            "warnings": warnings,
            "errors": [{"code": "EXTRACT_ERROR", "message": str(e)}],
        }

    # 2) Chunking
    docs, _ = build_chunk_docs(
        resource_id=req.resource_id,
        resource_type=req.resource_type,
        domain=req.domain,
        tags=req.tags,
        status=req.status,
        is_pinned=req.is_pinned,
        created_at=req.created_at,
        source_kind=source_kind,
        segments=segments,
        chunking=req.options.chunking,
    )

    chars_extracted = sum(len(d.get("chunk_text", "")) for d in docs)

    # 3) Re-index in Elasticsearch (delete -> bulk index)
    try:
        delete_by_resource_id(req.resource_id)
        bulk_index(INDEX_NAME, docs)
    except Exception as e:
        return {
            "job_id": req.job_id,
            "resource_id": req.resource_id,
            "status": "failed",
            "summary": {"chunk_count": 0, "chars_extracted": chars_extracted},
            "warnings": warnings,
            "errors": [{"code": "ES_INDEX_ERROR", "message": str(e)}],
        }

    status = "completed" if not warnings else "completed_with_warnings"
    return {
        "job_id": req.job_id,
        "resource_id": req.resource_id,
        "status": status,
        "summary": {
            "source_kind": "document_text",
            "chunk_count": len(docs),
            "chars_extracted": chars_extracted,
        },
        "warnings": warnings,
        "errors": errors,
    }


@app.post("/index/link")
def index_link(req: IndexLinkRequest):
    warnings: List[str] = []
    errors: List[Dict[str, str]] = []

    link = req.link or {}
    title = (link.get("title") or "").strip()
    memo = (link.get("memo") or "").strip()
    link_tags = link.get("tags") or []
    if not isinstance(link_tags, list):
        link_tags = []

    # MVP: title + memo + tags를 하나의 chunk_text로
    chunk_text = normalize_text(" ".join([title, memo, " ".join(link_tags)]).strip())
    if not chunk_text:
        return {
            "job_id": req.job_id,
            "resource_id": req.resource_id,
            "status": "failed",
            "summary": {"chunk_count": 0},
            "warnings": [],
            "errors": [{"code": "EMPTY_TEXT", "message": "link meta is empty"}],
        }

    doc = {
        "resource_id": str(req.resource_id),
        "resource_type": req.resource_type,
        "domain": req.domain,
        "tags": req.tags,
        "status": req.status,
        "is_pinned": req.is_pinned,
        "created_at": req.created_at,
        "source_kind": "link_meta",
        "page_index": None,
        "position": 0,
        "chunk_text": chunk_text,
        # embedding은 다음 단계에서 추가
    }

    try:
        delete_by_resource_id(req.resource_id)
        bulk_index(INDEX_NAME, [doc])
    except Exception as e:
        return {
            "job_id": req.job_id,
            "resource_id": req.resource_id,
            "status": "failed",
            "summary": {"chunk_count": 0},
            "warnings": warnings,
            "errors": [{"code": "ES_INDEX_ERROR", "message": str(e)}],
        }

    return {
        "job_id": req.job_id,
        "resource_id": req.resource_id,
        "status": "completed",
        "summary": {"source_kind": "link_meta", "chunk_count": 1, "chars_extracted": len(chunk_text)},
        "warnings": warnings,
        "errors": errors,
    }
