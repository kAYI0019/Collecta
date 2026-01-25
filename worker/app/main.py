from __future__ import annotations

import json
import os
import threading
import time
import uuid
from typing import Any, Dict, List, Optional, Tuple

from fastapi import FastAPI
from pydantic import BaseModel, Field
from redis import Redis
from redis.exceptions import RedisError
from sentence_transformers import SentenceTransformer

from .chunking import chunk_text_by_chars, normalize_text
from .extractors import (
    detect_mime_from_ext,
    extract_docx,
    extract_pdf_pages,
    extract_pptx_slides,
    extract_txt,
)
from .es import bulk_index, get_es

INDEX_NAME = "collecta-chunks"

app = FastAPI(title="Collecta Worker", version="0.2.0")

REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")
OUTBOX_STREAM_KEY = os.environ.get("OUTBOX_STREAM_KEY", "collecta:outbox:resource")
OUTBOX_CONSUMER_GROUP = os.environ.get("OUTBOX_CONSUMER_GROUP", "collecta-worker")
EMBEDDING_MODEL_NAME = os.environ.get("EMBEDDING_MODEL", "dragonkue/BGE-m3-ko")
EMBEDDING_DIM = int(os.environ.get("EMBEDDING_DIM", "1024"))
EMBEDDING_ENABLED = os.environ.get("EMBEDDING_ENABLED", "true").lower() == "true"


# -----------------------------
# Request Models
# -----------------------------
class ChunkingOptions(BaseModel):
    max_chars: int = 2500
    min_chars: int = 300
    overlap_chars: int = 200


class EmbeddingOptions(BaseModel):
    enabled: bool = True
    model: Optional[str] = None
    dim: Optional[int] = None


class IndexOptions(BaseModel):
    chunking: ChunkingOptions = Field(default_factory=ChunkingOptions)
    embedding: EmbeddingOptions = Field(default_factory=lambda: EmbeddingOptions(enabled=EMBEDDING_ENABLED))


class EmbedRequest(BaseModel):
    texts: Optional[List[str]] = None
    text: Optional[str] = None
    model: Optional[str] = None
    dim: Optional[int] = None


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


def get_redis() -> Redis:
    return Redis.from_url(REDIS_URL, decode_responses=True)


def ensure_outbox_group(redis_client: Redis) -> None:
    try:
        redis_client.xgroup_create(
            name=OUTBOX_STREAM_KEY,
            groupname=OUTBOX_CONSUMER_GROUP,
            id="0",
            mkstream=True,
        )
    except RedisError as e:
        if "BUSYGROUP" not in str(e):
            raise


_embedding_lock = threading.Lock()
_embedding_model: Optional[SentenceTransformer] = None
_embedding_model_name: Optional[str] = None


def get_embedding_model(model_name: Optional[str]) -> SentenceTransformer:
    global _embedding_model, _embedding_model_name
    use_name = model_name or EMBEDDING_MODEL_NAME
    with _embedding_lock:
        if _embedding_model is None or _embedding_model_name != use_name:
            _embedding_model = SentenceTransformer(use_name)
            _embedding_model_name = use_name
    return _embedding_model


def embed_texts(texts: List[str], model_name: Optional[str], expected_dim: Optional[int]) -> List[List[float]]:
    if not texts:
        return []
    model = get_embedding_model(model_name)
    vectors = model.encode(texts, convert_to_numpy=True, show_progress_bar=False)
    out = [v.tolist() for v in vectors]
    if expected_dim is not None and out and len(out[0]) != expected_dim:
        raise ValueError(f"embedding dim mismatch: expected {expected_dim}, got {len(out[0])}")
    return out


def consume_outbox_events() -> None:
    redis_client = get_redis()
    ensure_outbox_group(redis_client)

    consumer_name = os.environ.get(
        "OUTBOX_CONSUMER_NAME",
        f"worker-{uuid.uuid4().hex[:8]}",
    )

    while True:
        try:
            responses = redis_client.xreadgroup(
                groupname=OUTBOX_CONSUMER_GROUP,
                consumername=consumer_name,
                streams={OUTBOX_STREAM_KEY: ">"},
                count=10,
                block=5000,
            )
            if not responses:
                continue

            for _, messages in responses:
                for message_id, fields in messages:
                    event_type = fields.get("event_type")
                    payload_raw = fields.get("payload", "{}")

                    if event_type == "RESOURCE_DELETED":
                        payload = json.loads(payload_raw)
                        resource_id = payload.get("resource_id")
                        if resource_id is not None:
                            delete_by_resource_id(int(resource_id))

                    redis_client.xack(OUTBOX_STREAM_KEY, OUTBOX_CONSUMER_GROUP, message_id)
        except RedisError as e:
            print(f"[outbox] redis error: {e}")
            time.sleep(1)
        except Exception as e:
            print(f"[outbox] consumer error: {e}")
            time.sleep(1)


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
                    # embedding은 다음 단계에서 추가 (dims=1024)
                }
            )
        pos += len(chunks)

    return docs, warnings


# -----------------------------
# Routes
# -----------------------------
@app.on_event("startup")
def start_outbox_consumer() -> None:
    thread = threading.Thread(target=consume_outbox_events, daemon=True)
    thread.start()


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

    if req.options.embedding.enabled and docs:
        try:
            vectors = embed_texts(
                [d.get("chunk_text", "") for d in docs],
                req.options.embedding.model,
                req.options.embedding.dim or EMBEDDING_DIM,
            )
            for d, v in zip(docs, vectors):
                d["embedding"] = v
        except Exception as e:
            return {
                "job_id": req.job_id,
                "resource_id": req.resource_id,
                "status": "failed",
                "summary": {"chunk_count": 0, "chars_extracted": chars_extracted},
                "warnings": warnings,
                "errors": [{"code": "EMBEDDING_ERROR", "message": str(e)}],
            }

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


@app.post("/embed")
def embed(req: EmbedRequest):
    texts = req.texts or []
    if not texts and req.text:
        texts = [req.text]
    if not texts:
        return {"embeddings": []}

    vectors = embed_texts(
        texts,
        req.model,
        req.dim or EMBEDDING_DIM,
    )
    return {
        "embeddings": vectors,
        "model": req.model or EMBEDDING_MODEL_NAME,
        "dim": req.dim or EMBEDDING_DIM,
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
        # embedding은 다음 단계에서 추가 (dims=1024)
    }

    if req.options.embedding.enabled:
        try:
            vectors = embed_texts(
                [chunk_text],
                req.options.embedding.model,
                req.options.embedding.dim or EMBEDDING_DIM,
            )
            if vectors:
                doc["embedding"] = vectors[0]
        except Exception as e:
            return {
                "job_id": req.job_id,
                "resource_id": req.resource_id,
                "status": "failed",
                "summary": {"chunk_count": 0},
                "warnings": warnings,
                "errors": [{"code": "EMBEDDING_ERROR", "message": str(e)}],
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
